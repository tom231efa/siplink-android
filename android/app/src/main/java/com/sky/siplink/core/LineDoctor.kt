package com.sky.siplink.core

import android.os.Handler
import android.os.SystemClock
import android.util.Log
import com.sky.siplink.BuildConfig
import com.sky.siplink.core.logic.AccountRoute
import com.sky.siplink.core.logic.LineAdapt
import com.sky.siplink.core.logic.LineAdapt.Leave
import com.sky.siplink.core.logic.LineSteps
import com.sky.siplink.core.logic.LineVerdict
import com.sky.siplink.core.logic.MediaAdapt
import com.sky.siplink.core.logic.NetAddress
import com.sky.siplink.core.logic.NetworkKey
import com.sky.siplink.core.model.MediaFlow
import com.sky.siplink.core.model.NatKind
import com.sky.siplink.core.model.NetworkFacts
import com.sky.siplink.core.model.SipTransport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.linphone.core.Account
import org.linphone.core.AccountParams
import org.linphone.core.Call
import org.linphone.core.Core
import org.linphone.core.CoreListenerStub
import org.linphone.core.NatPolicy
import org.linphone.core.RegistrationState
import org.linphone.core.TransportType

/**
 * Adapts the lines (an account on one network) to what the network lets through, by the rules
 * of [LineAdapt] and the steps of [LineSteps]: another transport where UDP does not get through,
 * a shorter registration interval where the NAT forgets the line between registrations. After the
 * calls it gives a line whose sound went one way ICE ([MediaAdapt]), and notes an account whose PBX
 * names an address of its own network as the next hop of its calls, which nothing on the phone
 * repairs. It also has the SIP core look at the network again where it would not notice a change
 * by itself, and holds back while the network wants a login first.
 *
 * What it changes it changes in the core only: the account's own settings are kept aside
 * ([own]) and shown and edited as they are. An account set to TLS sends nothing in clear: no
 * OPTIONS request with its identity. Release builds log no addresses. Main thread only.
 *
 * The choice of the server comes first ([AccountRouter]), the adaptation second, on the server
 * chosen: what is learned is kept for each server apart ([LineRecords]), and a failure while the
 * router decides, or one of a server the line just left, does not count.
 *
 * @param find the account with an id, [idOf] the id of an account
 * @param onChanged called after an account was changed, so that it is shown anew
 * @param onBackup whether an account is on its backup server now
 * @param routing whether the router is deciding where an account goes
 */
internal class LineDoctor(
    private val core: Core,
    private val handler: Handler,
    private val find: (String) -> Account?,
    private val idOf: (Account) -> String,
    private val onChanged: (Account) -> Unit,
    onBackup: (String) -> Boolean = { false },
    private val routing: (String) -> Boolean = { false },
) {
    /** What is going on with one account on the current network. */
    private class State {
        /** Looks again whether a registration that failed for want of an answer still fails ([LineAdapt.CONFIRM_MS]). */
        var confirm: Runnable? = null

        /** The transport the line was just moved to, until a registration shows whether it works. */
        var trial: SipTransport? = null

        /** The line went back to its own transport for a test; this is the one to return to if that fails. */
        var revertTo: SipTransport? = null

        /** When the server was last asked for this account ([SystemClock.elapsedRealtime]); null: not on this network. */
        var probedAt: Long? = null

        /** Probes asked for too soon after the last one: they run once [LineSteps.MIN_PROBE_GAP_MS] is over. */
        val later = ArrayList<Runnable>()

        /** Looks again at a line that still fails once what failed may be tried again ([LineAdapt.Record.failedUntil]). */
        var recheck: Runnable? = null

        /** Where the server saw the line at its last registration, and over which server and when. */
        var contact: String? = null
        var contactServer: String? = null
        var contactAt = 0L
    }

    /** Whether the lines adapt; off, every line goes back to its own settings and nothing learned is kept. */
    var enabled = true
        set(value) {
            if (field == value) return
            field = value
            dropStates()
            applyAll()
            if (!value) records.forgetAll()
        }

    /** The STUN server the lines' ICE asks, `host[:port]`; empty: none, and no ICE. A value that cannot be one is not taken. */
    var stunServer = ""
        set(value) {
            val next = value.trim()
            if (field == next || !MediaAdapt.isValidStunServer(next)) return
            field = next
            applyAll()
        }

    private val records = LineRecords(core.config, onBackup)
    private val prober = LineProber(handler)
    private val adapted = MutableStateFlow<Map<String, SipTransport>>(emptyMap())

    /** The accounts whose line runs on another transport than their own, with that transport. */
    val transports: StateFlow<Map<String, SipTransport>> = adapted.asStateFlow()

    private val states = HashMap<String, State>()
    private var facts: NetworkFacts? = NetworkWatch.facts
    private var network: String? = facts?.let(NetworkKey::of)
    private var networkChangedAt = SystemClock.elapsedRealtime()

    /** Only an IPv6 prefix changed during a call: the SIP core looks at the network once the calls are over. */
    private var lookAgainAfterCalls = false
    private val lookAgain = Runnable {
        // As the SDK does itself when the kind of network changes: connections anew, registrations anew.
        Log.i(TAG, "Another network of the same kind: the SIP core looks again")
        core.isNetworkReachable = false
        core.isNetworkReachable = true
    }

    /** Calls placed again over the other server of their account ([supersede]). */
    private val superseded = HashSet<Long>()

    /** [call] is being placed again over the other server ([CallRetry]): its end is no failure of the line. */
    fun supersede(call: Call) {
        superseded += call.nativePointer
    }

    private val calls = object : CoreListenerStub() {
        override fun onCallStateChanged(core: Core, call: Call, state: Call.State?, message: String) {
            // A call placed again over the other server ([CallRetry]) counts for nothing here.
            if (call.nativePointer in superseded) {
                if (state == Call.State.Released) superseded -= call.nativePointer
                return
            }
            // A call out that got no answer while the registration works: maybe its request was too large.
            if (state == Call.State.Error && call.dir == Call.Dir.Outgoing && call.errorInfo.protocolCode == TIMEOUT) {
                call.params.account?.let(::callTimedOut)
            }
            if (state == Call.State.Released && lookAgainAfterCalls) handler.post(::lookAgainIfIdle)
        }
    }

    init {
        // It lives as long as the core, which is as long as the process. The network comes from the account manager ([onNetwork]).
        core.addListener(calls)
        records.prune(System.currentTimeMillis())
        publish()
    }

    // ---------------------------------------------------------------- for the account manager

    /** The account's own settings where the doctor replaced some in the core; null where it did not. */
    fun own(id: String): LineSteps.Own? = records.own(id)

    /** Whether registrations that fail are to be taken as they come: the network wants a login first. */
    fun holds(): Boolean = enabled && facts?.captive == true

    /**
     * Before the user's change of account [id] goes into [params]: puts its own settings back in
     * there, and with [forget] (its servers or transport changed) drops what was learned for it.
     * With [credentials] (its user, server or password changed) the transports that did not
     * register may register now, and its login has to register again before a challenge counts
     * as a refusal of a transport ([LineSteps.failure]). [reapply] follows once the change is in
     * the core.
     */
    fun release(id: String, params: AccountParams, forget: Boolean, credentials: Boolean) {
        records.own(id)?.let { own ->
            putOwn(params, own)
            records.storeOwn(id, null)
        }
        if (forget) {
            records.forget(id)
            records.storePbxHop(id, null)
        } else if (credentials) {
            records.changeAll(id, LineSteps::credentialsChanged)
        }
        if (credentials) records.storeProven(id, false)
        states.remove(id)?.let(::cancel)
    }

    /** Puts the lines on what is remembered for them once the user's change is in the core. */
    fun reapply() = applyAll()

    /** Account [id] is gone: what was learned for it goes. Its parameters are left alone: it unregisters with them. */
    fun drop(id: String) {
        records.forget(id)
        records.storePbxHop(id, null)
        records.storeOwn(id, null)
        records.storeProven(id, false)
        states.remove(id)?.let(::cancel)
        publish()
    }

    /** The user wants account [id] on its own settings everywhere ("restore defaults"). */
    fun forget(id: String) {
        records.forget(id)
        records.storePbxHop(id, null)
        states.remove(id)?.let(::cancel)
        // Back on its own settings here and now: before the network is known the lines are not
        // touched otherwise. Only an account the core still has (find also has one that a batch
        // of changes is removing).
        val account = core.accountList.firstOrNull { idOf(it) == id }
        val own = records.own(id)
        if (account != null && own != null) {
            account.params = account.params.clone().also { putOwn(it, own) }
            onChanged(account)
        }
        records.storeOwn(id, null)
        applyAll()
        publish()
    }

    private fun putOwn(params: AccountParams, own: LineSteps.Own) {
        params.serverAddress?.clone()?.let { server ->
            server.transport = typeOf(own.transport)
            params.serverAddress = server
        }
        params.expires = own.expires
        params.isOutboundProxyEnabled = own.outbound
        params.natPolicy = null
    }

    /**
     * What the line of an account runs on instead of its own settings, for the line checks.
     *
     * @param transport null: its own; [expires] likewise
     * @param ice its calls offer ICE candidates
     */
    data class Adaptation(val transport: SipTransport?, val expires: Int?, val ice: Boolean)

    fun adaptation(id: String): Adaptation? {
        val params = find(id)?.params ?: return null
        val own = records.own(id) ?: return null
        return Adaptation(
            transport = transportOf(params.serverAddress).takeIf { it != own.transport },
            expires = params.expires.takeIf { it != own.expires },
            ice = params.natPolicy?.isIceEnabled == true,
        )
    }

    /** Whether the PBX of account [id] named an address of its own network as the next hop of its last call ([MediaAdapt.unreachableHop]). */
    fun pbxUnreachable(id: String): Boolean = records.pbxHop(id) != null

    /**
     * The transport the line of account [id] runs on towards its backup server ([onBackup]) or
     * its first one, by what is remembered for it on this network; null: before the network is
     * known, where nothing is remembered yet. The router moves the account onto it in one change.
     */
    fun transportFor(id: String, onBackup: Boolean): SipTransport? {
        if (network == null) return null
        val params = find(id)?.params ?: return null
        val server = params.serverAddress ?: return null
        val own = records.own(id) ?: LineSteps.Own(transportOf(server), params.expires, params.isOutboundProxyEnabled)
        return LineSteps.target(own, if (enabled) records.record(id, network, onBackup) else null, stunServer).transport
    }

    /**
     * The router moved account [id] to its other server: what was pending for the line was about
     * the server it left, and the line runs on what was learned for the new one.
     */
    fun onServerMoved(id: String) {
        states.remove(id)?.let(::cancel)
        applyAll()
    }

    /** The core looks at the network anew (its connections may be new ones): where the server sees the lines starts over. */
    fun onReachable() {
        states.values.forEach { it.contact = null }
    }

    // ---------------------------------------------------------------- following the registrations

    fun onRegistration(account: Account) {
        val id = idOf(account)
        // Whether the account's login works shows wherever it registers, adapting or not.
        if (account.state == RegistrationState.Ok && !records.proven(id)) records.storeProven(id, true)
        if (!enabled) return
        val state = states.getOrPut(id, ::State)
        when (account.state) {
            RegistrationState.Ok -> registered(account, id, state)
            RegistrationState.Failed -> failed(account, id, state)
            else -> Unit
        }
    }

    private fun registered(account: Account, id: String, state: State) {
        cancelConfirm(state)
        cancelRecheck(state)
        state.trial = null
        val now = System.currentTimeMillis()
        val stored = records.record(id, network)
        var record = stored ?: LineAdapt.Record()
        if (state.revertTo != null) {
            // Back on its own transport for a test, the line holds.
            state.revertTo = null
            record = LineSteps.returned(record)
        }
        record = LineSteps.registeredOn(record, transportOf(account.params.serverAddress))
        record = LineAdapt.bindingSteady(watchBinding(account, id, state, record, now), now)
        record = LineSteps.held(record, now)
        if (!record.isPlain && now - record.usedAt > USED_AT_STEP_MS) record = record.copy(usedAt = now)
        record = MediaAdapt.iceWithdrawn(record, now, core.callsNb)
        if (record != (stored ?: LineAdapt.Record())) records.store(id, network, record)
        // Not from inside this notification: the core is in the middle of the registration.
        if (record.expires != stored?.expires || record.ice != (stored?.ice ?: MediaAdapt.Ice.NONE)) handler.post(::applyAll)
        val ownExpires = records.own(id)?.expires ?: account.params.expires
        val newLoss = record.lost.lastOrNull() != stored?.lost?.lastOrNull()
        if (newLoss && record.lost.isNotEmpty() && LineAdapt.bindingHopeless(record, ownExpires)) {
            // The NAT forgets the line however often it registers: over TCP the connection keeps it.
            leaveUdp(account, id, state, Leave.NAT_FORGETS) { true }
        } else {
            retestIfDue(account, id, state, record, now)
        }
    }

    /** Moves a line on UDP that works, but not well enough ([why]), to another transport, when [unfit] holds for what the probe found. */
    private fun leaveUdp(account: Account, id: String, state: State, why: Leave, unfit: (LineAdapt.Probe) -> Boolean) {
        val own = ownTransport(id, account)
        if (own != SipTransport.UDP || transportOf(account.params.serverAddress) != SipTransport.UDP) return
        probe(account, id, state) { probe ->
            if (!stillOn(id, SipTransport.UDP) || !unfit(probe)) return@probe
            val change = LineSteps.leaveUdp(records.record(id, network) ?: LineAdapt.Record(), own, probe, why, System.currentTimeMillis())
                ?: return@probe
            Log.i(TAG, "Line $id leaves UDP ($why) for ${change.trial}")
            state.trial = change.trial
            records.store(id, network, change.record)
            applyAll()
        }
    }

    /**
     * The record after looking at where the server saw the line: a NAT that forgot it maps it
     * anew, and the server sees it on another port, while the phone stayed on the same network.
     */
    private fun watchBinding(account: Account, id: String, state: State, record: LineAdapt.Record, now: Long): LineAdapt.Record {
        val params = account.params
        val server = params.serverAddress?.asStringUriOnly()
        val contact = account.contactAddress?.let(::hostPortOf)
        if (contact.isNullOrEmpty() || transportOf(params.serverAddress) != SipTransport.UDP) {
            state.contact = null
            return record
        }
        val before = state.contact
        val sameWay = before != null && state.contactServer == server && state.contactAt > networkChangedAt
        state.contact = contact
        state.contactServer = server
        state.contactAt = SystemClock.elapsedRealtime()
        if (!sameWay || before == contact) return record
        return LineAdapt.bindingLost(record, records.own(id)?.expires ?: params.expires, now)
    }

    /**
     * A registration failed ([LineSteps.failure]). One that got no answer is looked at again after
     * a while ([LineAdapt.CONFIRM_MS]); one the server refused on a transport on test ends that
     * test at once. Either way a line on another transport looks whether its own one gets through
     * again, when it is time.
     */
    private fun failed(account: Account, id: String, state: State) {
        // Which server the line goes to is being decided, or it just moved: the failure may be the way
        // to the server it leaves, or a way not up yet. It is looked at again later, nothing more.
        if (routing(id)) {
            if (LineAdapt.isTransportFailure(account.errorInfo.protocolCode) && !holds()) confirmLater(id, state) else cancelConfirm(state)
            return
        }
        val current = transportOf(account.params.serverAddress)
        val onTrial = LineSteps.onTrial(state.trial, state.revertTo, current, ownTransport(id, account), records.record(id, network))
        when (LineSteps.failure(account.errorInfo.protocolCode, holds(), onTrial, records.proven(id))) {
            LineSteps.Failure.NOTHING -> cancelConfirm(state)
            LineSteps.Failure.END_TRIAL -> {
                cancelConfirm(state)
                stillFailing(account, id, state, refused = true)
                // The end of the test decides where the line goes: no retest besides.
                return
            }
            LineSteps.Failure.CONFIRM -> confirmLater(id, state)
        }
        if (!holds()) records.record(id, network)?.let { retestIfDue(account, id, state, it, System.currentTimeMillis()) }
    }

    private fun confirmLater(id: String, state: State) {
        if (state.confirm != null) return
        val task = Runnable {
            state.confirm = null
            val current = find(id)
            // Dropped states (another network, another setting) have nothing to confirm.
            if (current != null && enabled && states[id] === state && current.state == RegistrationState.Failed && !holds()) {
                // The router is still at it: looked at once more later.
                if (routing(id)) confirmLater(id, state) else stillFailing(current, id, state, refused = false)
            }
        }
        state.confirm = task
        handler.postDelayed(task, LineAdapt.CONFIRM_MS)
    }

    private fun cancelConfirm(state: State) {
        state.confirm?.let(handler::removeCallbacks)
        state.confirm = null
    }

    /** Stops whatever is pending for a line whose state is dropped. */
    private fun cancel(state: State) {
        cancelConfirm(state)
        cancelRecheck(state)
        state.later.forEach(handler::removeCallbacks)
        state.later.clear()
    }

    /** Starts over with every account: another network, or the adaptation switched. */
    private fun dropStates() {
        states.values.forEach(::cancel)
        states.clear()
    }

    /**
     * The registration still fails ([refused]: the server refused the line on the transport on
     * test): back to what worked after a test of its own transport, else the next transport the
     * probe finds open, or its own one ([LineSteps.afterFailure]).
     */
    private fun stillFailing(account: Account, id: String, state: State, refused: Boolean) {
        val revertTo = state.revertTo
        if (revertTo != null) {
            // Back on its own transport for a test, the line did not hold: back to what worked, and a longer wait.
            state.revertTo = null
            records.store(id, network, LineSteps.revertFailed(records.record(id, network) ?: LineAdapt.Record(), revertTo, System.currentTimeMillis()))
            applyAll()
            return
        }
        val own = ownTransport(id, account)
        if (own != SipTransport.UDP) return
        val asked = transportOf(account.params.serverAddress)
        probe(account, id, state) { probe ->
            // Another answer to the same probe may have moved the line already.
            if (!stillOn(id, asked)) return@probe
            Log.i(TAG, "Line $id fails on $asked${if (refused) ", refused" else ""}: $probe")
            val record = records.record(id, network) ?: LineAdapt.Record()
            // A line found on another transport (as after a start) is on test as well.
            val trial = state.trial ?: asked.takeIf { it != own }
            val change = LineSteps.afterFailure(record, own, asked, trial, probe, refused, System.currentTimeMillis())
            if (change != null) {
                state.trial = change.trial
                records.store(id, network, change.record)
                applyAll()
            }
            recheckAt(id, state, (change?.record ?: record).failedUntil)
        }
    }

    /**
     * Looks again at [at] (ms since the epoch) whether the line of account [id] still fails, when
     * what failed may be tried again: the SIP core reports a failed registration once, and the
     * REGISTERs it keeps sending after it fail without a notice (over blocked UDP in the NAT lab,
     * one every 34 seconds).
     * Nothing when that time is over already.
     */
    private fun recheckAt(id: String, state: State, at: Long) {
        cancelRecheck(state)
        val wait = at - System.currentTimeMillis()
        if (wait <= 0) return
        val task = Runnable {
            state.recheck = null
            val current = find(id)
            if (current != null && enabled && states[id] === state && current.state == RegistrationState.Failed && !holds()) {
                stillFailing(current, id, state, refused = false)
            }
        }
        state.recheck = task
        handler.postDelayed(task, wait)
    }

    private fun cancelRecheck(state: State) {
        state.recheck?.let(handler::removeCallbacks)
        state.recheck = null
    }

    /**
     * A call out on [account] got no answer: where small requests get through over UDP and large
     * ones do not, the line moves to another transport (RFC 3261 §18.1.1 wants TCP for them).
     */
    private fun callTimedOut(account: Account) {
        if (!enabled || holds()) return
        val id = idOf(account)
        leaveUdp(account, id, states.getOrPut(id, ::State), Leave.LARGE_LOST) { it.udp == true && it.udpLarge == false }
    }

    /** A line on another transport looks again, when it is time, whether its own one gets through now ([LineSteps.retestDue]). */
    private fun retestIfDue(account: Account, id: String, state: State, record: LineAdapt.Record, now: Long) {
        if (!LineSteps.retestDue(record, now, core.callsNb)) return
        val adapted = record.transport ?: return
        probe(account, id, state) { probe ->
            val current = records.record(id, network) ?: return@probe
            if (current.transport != adapted || !stillOn(id, adapted)) return@probe
            val (next, back) = LineSteps.afterRetest(current, probe, core.callsNb, System.currentTimeMillis())
            if (back) state.revertTo = adapted
            records.store(id, network, next)
            if (back) applyAll()
        }
    }

    /**
     * Asks which transports get through to the server of [account] ([LineProber]), once for all
     * accounts on that server; not more often than every [LineSteps.MIN_PROBE_GAP_MS] for one
     * account: one asked for sooner runs once that is over, as long as the line's state stands.
     */
    private fun probe(account: Account, id: String, state: State, then: (LineAdapt.Probe) -> Unit) {
        val params = account.params
        val server = params.serverAddress ?: return
        val ask = LineProber.Ask(
            host = connectHostOf(server),
            port = server.port.takeIf { it > 0 },
            server = hostPortOf(server),
            asker = askerOf(id, account),
            ipv6 = core.isIpv6Enabled,
            sixFirst = NetAddress.sixFirst(account.contactAddress?.domain, facts?.addresses.orEmpty()),
        )
        val answer: (LineAdapt.Probe) -> Unit = { probe -> if (enabled && states[id] === state) then(probe) }
        if (prober.join(network, ask, answer)) return
        val now = SystemClock.elapsedRealtime()
        val wait = LineSteps.probeWait(state.probedAt, now)
        if (wait > 0) {
            val retry = object : Runnable {
                override fun run() {
                    state.later.remove(this)
                    if (enabled && states[id] === state) find(id)?.let { probe(it, id, state, then) }
                }
            }
            state.later += retry
            handler.postDelayed(retry, wait)
            return
        }
        state.probedAt = now
        prober.start(network, ask, answer)
    }

    /**
     * Who the OPTIONS requests of account [id] come from; null for an account set to TLS, which
     * sends nothing in clear. One set to UDP that went to TLS registers in clear once it is back
     * on UDP: its probes may ask over UDP.
     */
    private fun askerOf(id: String, account: Account): LineNetwork.Asker? =
        if (ownTransport(id, account) == SipTransport.TLS) null else LineNetwork.Asker(fromOf(account.params.identityAddress), core.userAgent)

    /** Whether the line of account [id] still runs on [transport]. */
    private fun stillOn(id: String, transport: SipTransport): Boolean = find(id)?.params?.serverAddress?.let(::transportOf) == transport

    private fun ownTransport(id: String, account: Account): SipTransport = records.own(id)?.transport ?: transportOf(account.params.serverAddress)

    // ---------------------------------------------------------------- the network

    /**
     * The phone is on [next] now ([NetworkWatch]); before the router hears of it. With [applyNow]
     * false the lines are put on what is remembered for the new network only by [reapply],
     * after the router chose their servers: one change of each account, not two.
     */
    fun onNetwork(next: NetworkFacts?, applyNow: Boolean = true) {
        val before = facts
        facts = next
        if (next == null) return
        val key = NetworkKey.of(next)
        val moved = key != network
        network = key
        if (moved) {
            networkChangedAt = SystemClock.elapsedRealtime()
            dropStates()
        }
        if (!enabled) return
        if (moved) {
            records.prune(System.currentTimeMillis())
            if (applyNow) applyAll()
        }
        // Logged in at last: the registrations need not wait for their next try.
        if (before?.captive == true && !next.captive) core.refreshRegisters()
        if (before != null && NetworkKey.unnoticedChange(before, next)) {
            handler.removeCallbacks(lookAgain)
            // A new IPv6 prefix alone does not cut a call short: the core looks once the calls are over.
            lookAgainAfterCalls = inCall() && NetworkKey.onlyIpv6Changed(before, next)
            if (!lookAgainAfterCalls) handler.postDelayed(lookAgain, SETTLE_MS)
        }
    }

    private fun lookAgainIfIdle() {
        if (!lookAgainAfterCalls || inCall()) return
        lookAgainAfterCalls = false
        lookAgain.run()
    }

    /** Whether a call is under way: one that ended may still be in the core's list a moment. */
    private fun inCall(): Boolean = core.calls.any { it.state != Call.State.End && it.state != Call.State.Error && it.state != Call.State.Released }

    // ---------------------------------------------------------------- what the lines run on

    /** Puts every line on what is remembered for it on this network, or back on its own settings. */
    private fun applyAll() {
        // Before the network is known nothing is known of what it needs: the lines stay as they are.
        if (enabled && network == null) return
        core.accountList.forEach(::apply)
        publish()
    }

    /** Puts the line of [account] on what is remembered for it ([LineSteps.target]), or back on its own settings. */
    private fun apply(account: Account) {
        val id = idOf(account)
        val params = account.params
        val server = params.serverAddress ?: return
        val own = records.own(id) ?: LineSteps.Own(transportOf(server), params.expires, params.isOutboundProxyEnabled)
        val target = LineSteps.target(own, if (enabled) records.record(id, network) else null, stunServer)
        val iceNow = params.natPolicy?.takeIf { it.isIceEnabled }?.stunServer
        if (target.transport != transportOf(server) || target.expires != params.expires || target.outbound != params.isOutboundProxyEnabled || target.ice != iceNow) {
            val next = params.clone()
            if (target.transport != transportOf(server)) next.serverAddress = server.clone().also { it.transport = typeOf(target.transport) }
            next.expires = target.expires
            next.isOutboundProxyEnabled = target.outbound
            next.natPolicy = target.ice?.let(::icePolicy)
            account.params = next
            states[id]?.contact = null
            onChanged(account)
            Log.i(TAG, "Line $id on $network: ${target.transport}, ${target.expires}s, outbound ${target.outbound}, ICE ${target.ice != null} (own: ${own.transport}, ${own.expires}s, ${own.outbound})")
        }
        records.storeOwn(id, own.takeIf { target.adapted(it) })
    }

    /** A NAT policy that gathers ICE candidates, with the address the STUN server at [server] sees among them. */
    private fun icePolicy(server: String): NatPolicy = core.createNatPolicy().also {
        it.stunServer = server
        it.isStunEnabled = true
        it.isIceEnabled = true
    }

    private fun publish() {
        adapted.value = core.accountList.mapNotNull { account ->
            val id = idOf(account)
            val own = records.own(id) ?: return@mapNotNull null
            val transport = transportOf(account.params.serverAddress)
            if (transport == own.transport) null else id to transport
        }.toMap()
    }

    // ---------------------------------------------------------------- the sound of the calls

    /**
     * A call ended on [account]: where ICE stands on its line ([MediaAdapt.afterCall]) is in place
     * for the next call. After one whose sound ran, a line whose sound went one way may try ICE
     * ([learn]), and whether its PBX names a next hop nothing reaches is noted (with the
     * adaptation switched off as well: it changes nothing).
     */
    fun onCallFinished(account: Account, call: CallMediaWatch.Finished) {
        val id = idOf(account)
        val net = network
        // An intercom or paging call may go one way by design: it teaches nothing.
        val flow = call.summary?.flow?.takeIf { call.judged }
        val tookPart = MediaAdapt.iceTookPart(call.outgoing, call.iceOffered, call.summary?.ice.orEmpty())
        Log.i(TAG, "Line $id: a call ${if (call.outgoing) "out, set up in ${call.setupMs} ms" else "in"}, sound ${call.summary?.flow}${if (call.judged) "" else " (not judged)"}, ICE ${call.summary?.ice}, failed ${call.failedWith}")
        var ice = MediaAdapt.Ice.NONE
        // A call not judged that ran tells nothing of ICE either, nor counts.
        if (enabled && (call.judged || call.failedWith != null)) {
            val record = records.record(id, net) ?: LineAdapt.Record()
            val next = MediaAdapt.afterCall(record, flow, tookPart, call.setupMs, call.failedWith, System.currentTimeMillis())
            ice = next.ice
            if (next != record) {
                if (next.ice != record.ice) Log.i(TAG, "Line $id on $net: ICE ${next.ice}")
                records.store(id, net, next)
                if (next.ice != record.ice) applyAll()
            }
        }
        // A call that never ran says nothing of the way of its sound, nor of its PBX's next hop.
        if (call.failedWith != null) return
        val hop = MediaAdapt.nextHop(call.contact, call.recordRoute, call.outgoing)
        if (BuildConfig.DEBUG) Log.d(TAG, "Line $id: next hop $hop")
        val stun = MediaAdapt.stunServer(stunServer)
        val askNat = enabled && stun != null && ice == MediaAdapt.Ice.NONE && (flow == MediaFlow.SEND_ONLY || flow == MediaFlow.RECEIVE_ONLY)
        val params = account.params
        val server = params.serverAddress ?: return
        val host = connectHostOf(server)
        val port = server.port.takeIf { it > 0 && transportOf(server) != SipTransport.TLS } ?: AccountRoute.defaultPort(SipTransport.UDP)
        val target = hostPortOf(server)
        // An account set to TLS sends nothing in clear: its NAT is told by the STUN server alone.
        val asker = askerOf(id, account)
        val local = facts?.addresses.orEmpty()
        val subnets = facts?.subnets.orEmpty()
        val ipv6 = core.isIpv6Enabled
        val sixFirst = NetAddress.sixFirst(account.contactAddress?.domain, local)
        prober.run {
            val addresses = LineNetwork.lookup(host)
            val address = NetAddress.coreOrder(addresses, ipv6, sixFirst).firstOrNull()
            val nat = if (askNat) natOf(address, port, target, asker, stun, local) else NatKind.UNKNOWN
            val unreachable = MediaAdapt.unreachableHop(hop, addresses + host, subnets)
            handler.post { learn(id, net, flow, nat, hop.takeIf { unreachable }) }
        }
    }

    /** What lies between the phone and the server at [address], as the SIP server (unless [asker] is null) and the STUN server see one socket. Worker thread. */
    private fun natOf(address: String?, port: Int, target: String, asker: LineNetwork.Asker?, stun: Pair<String, Int>?, local: List<String>): NatKind {
        if (address == null || stun == null) return NatKind.UNKNOWN
        val stunAddress = LineNetwork.socketAddress(stun.first, stun.second) ?: return NatKind.UNKNOWN
        val probe = LineNetwork.probeUdp(address, port, target, asker, stun = stunAddress)
        return LineVerdict.nat(local, probe.from?.let { LineVerdict.seen(probe.answer, it) }, probe.stun)
    }

    /** After a call of line [id] on network [net]: ICE on trial where it may help; [unreachableHop]: the next hop its PBX named, which nothing reaches. */
    private fun learn(id: String, net: String?, flow: MediaFlow?, nat: NatKind, unreachableHop: String?) {
        if ((unreachableHop != null) != pbxUnreachable(id)) {
            records.storePbxHop(id, unreachableHop)
            if (unreachableHop == null) {
                Log.i(TAG, "Line $id: the PBX names a next hop this phone reaches again")
            } else {
                Log.i(TAG, "Line $id: the PBX names an address of its own network as the next hop of its calls: their ACK and BYE get lost")
                if (BuildConfig.DEBUG) Log.d(TAG, "Line $id: unreachable next hop $unreachableHop")
            }
            find(id)?.let(onChanged)
        }
        if (!enabled) return
        val record = records.record(id, net) ?: LineAdapt.Record()
        if (net == network && MediaAdapt.tryIce(flow, nat, MediaAdapt.stunServer(stunServer) != null, record.ice)) {
            Log.i(TAG, "Line $id on $net: ICE on trial ($flow, $nat)")
            records.store(id, net, record.copy(ice = MediaAdapt.Ice.TRIAL, iceCalls = 0, usedAt = System.currentTimeMillis()))
            applyAll()
        }
    }

    private companion object {
        /** A change of address settles for this long before the core looks at the network again. */
        const val SETTLE_MS = 2_000L

        /** When a line was last used is written down at most this often. */
        const val USED_AT_STEP_MS = 60 * 60_000L

        /** What the SIP core reports for a request nobody answered. */
        const val TIMEOUT = 408

        const val TAG = "SipLink.Line"

        fun typeOf(transport: SipTransport): TransportType = when (transport) {
            SipTransport.UDP -> TransportType.Udp
            SipTransport.TCP -> TransportType.Tcp
            SipTransport.TLS -> TransportType.Tls
        }
    }
}
