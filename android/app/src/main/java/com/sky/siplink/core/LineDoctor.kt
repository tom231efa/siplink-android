package com.sky.siplink.core

import android.os.Handler
import android.os.SystemClock
import android.util.Log
import com.sky.siplink.core.logic.LineAdapt
import com.sky.siplink.core.logic.MediaAdapt
import com.sky.siplink.core.logic.NetworkKey
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
import org.linphone.core.RegistrationState
import org.linphone.core.TransportType
import java.util.concurrent.Executors

/**
 * Adapts the lines (an account on one network) to what the network lets through, by the rules
 * of [LineAdapt]: another transport where UDP does not get through, a shorter registration
 * interval where the NAT forgets the line between registrations. After the calls it gives an
 * account whose PBX announces an address of its own network the way by the server and symmetric
 * RTP ([MediaAdapt]). It also has the SIP core look at the network again where it would not
 * notice a change by itself, and holds back while the network wants a login first.
 *
 * What it changes it changes in the core only: the account's own settings are kept aside
 * ([own]) and shown and edited as they are. Main thread only.
 *
 * @param find the account with an id, [idOf] the id of an account
 * @param onChanged called after an account was changed, so that it is shown anew
 */
internal class LineDoctor(
    private val core: Core,
    private val handler: Handler,
    private val find: (String) -> Account?,
    private val idOf: (Account) -> String,
    private val onChanged: (Account) -> Unit,
) {
    /** The settings of an account that the doctor replaced in the core. */
    class Own(val transport: SipTransport, val expires: Int, val outbound: Boolean)

    /** What is going on with one account on the current network. */
    private class State {
        /** Looks again whether a registration that failed for want of an answer still fails ([LineAdapt.CONFIRM_MS]). */
        var confirm: Runnable? = null

        /** The transport the line was just moved to, until a registration shows whether it works. */
        var trial: SipTransport? = null

        /** The line went back to its own transport for a test; this is the one to return to if that fails. */
        var revertTo: SipTransport? = null
        var probing = false
        var probedAt = 0L

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
            if (!value) {
                core.config.cleanSection(SECTION)
                core.config.cleanSection(PBX)
            }
        }

    private val adapted = MutableStateFlow<Map<String, SipTransport>>(emptyMap())

    /** The accounts whose line runs on another transport than their own, with that transport. */
    val transports: StateFlow<Map<String, SipTransport>> = adapted.asStateFlow()

    private val states = HashMap<String, State>()
    private val workers = Executors.newSingleThreadExecutor()
    private val probes = HashMap<String, ArrayList<(LineAdapt.Probe) -> Unit>>()
    private var facts: NetworkFacts? = NetworkWatch.facts
    private var network: String? = facts?.let(NetworkKey::of)
    private var networkChangedAt = SystemClock.elapsedRealtime()
    private val lookAgain = Runnable {
        // As the SDK does itself when the kind of network changes: connections anew, registrations anew.
        Log.i(TAG, "Another network of the same kind: the SIP core looks again")
        core.isNetworkReachable = false
        core.isNetworkReachable = true
    }

    private val calls = object : CoreListenerStub() {
        override fun onCallStateChanged(core: Core, call: Call, state: Call.State?, message: String) {
            // A call out that got no answer while the registration works: maybe its request was too large.
            if (state == Call.State.Error && call.dir == Call.Dir.Outgoing && call.errorInfo.protocolCode == TIMEOUT) {
                call.params.account?.let(::callTimedOut)
            }
        }
    }

    init {
        // Both live as long as the core, which is as long as the process.
        NetworkWatch.listen(::onNetwork)
        core.addListener(calls)
        prune()
        publish()
    }

    // ---------------------------------------------------------------- for the account manager

    /** The account's own settings where the doctor replaced some in the core; null where it did not. */
    fun own(id: String): Own? {
        val fields = core.config.getString(APPLIED, id, null)?.split(SEPARATOR) ?: return null
        if (fields.size != 3) return null
        val transport = SipTransport.entries.firstOrNull { it.name == fields[0] } ?: return null
        return Own(transport, fields[1].toIntOrNull() ?: return null, fields[2] == "1")
    }

    private fun storeOwn(id: String, own: Own?) {
        if (own == null) {
            core.config.cleanEntry(APPLIED, id)
        } else {
            core.config.setString(APPLIED, id, listOf(own.transport.name, own.expires, if (own.outbound) "1" else "0").joinToString(SEPARATOR))
        }
    }

    /** Whether registrations that fail are to be taken as they come: the network wants a login first. */
    fun holds(): Boolean = enabled && facts?.captive == true

    /**
     * Before the user's change of account [id] goes into [params]: puts its own settings back in
     * there, and with [forget] (its servers or transport changed) drops what was learned for it.
     * [reapply] follows once the change is in the core.
     */
    fun release(id: String, params: AccountParams, forget: Boolean) {
        own(id)?.let { own ->
            params.serverAddress?.clone()?.let { server ->
                server.transport = typeOf(own.transport)
                params.serverAddress = server
            }
            params.expires = own.expires
            params.isOutboundProxyEnabled = own.outbound
            storeOwn(id, null)
        }
        if (forget) {
            forgetRecords(id)
            core.config.cleanEntry(PBX, id)
        }
        states.remove(id)?.let(::cancelConfirm)
    }

    /** Puts the lines on what is remembered for them once the user's change is in the core. */
    fun reapply() = applyAll()

    /** Account [id] is gone, or the user wants it on its own settings everywhere ("restore defaults"). */
    fun forget(id: String) {
        forgetRecords(id)
        core.config.cleanEntry(PBX, id)
        states.remove(id)?.let(::cancelConfirm)
        applyAll()
        storeOwn(id, null)
    }

    /**
     * What the line of an account runs on instead of its own settings, for the line checks.
     *
     * @param transport null: its own; [expires] likewise
     * @param viaServer its calls go by way of the server with symmetric RTP: the PBX announces an address of its own network
     */
    data class Adaptation(val transport: SipTransport?, val expires: Int?, val viaServer: Boolean)

    fun adaptation(id: String): Adaptation? {
        val params = find(id)?.params ?: return null
        val own = own(id)
        val viaServer = enabled && pbxCount(id) != null
        if (own == null && !viaServer) return null
        return Adaptation(
            transport = transportOf(params.serverAddress).takeIf { own != null && it != own.transport },
            expires = params.expires.takeIf { own != null && it != own.expires },
            viaServer = viaServer,
        )
    }

    /** The core looks at the network anew (its connections may be new ones): where the server sees the lines starts over. */
    fun onReachable() {
        states.values.forEach { it.contact = null }
    }

    // ---------------------------------------------------------------- following the registrations

    fun onRegistration(account: Account) {
        if (!enabled) return
        val id = idOf(account)
        val state = states.getOrPut(id, ::State)
        when (account.state) {
            RegistrationState.Ok -> registered(account, id, state)
            RegistrationState.Failed -> failed(account, id, state)
            else -> Unit
        }
    }

    private fun registered(account: Account, id: String, state: State) {
        cancelConfirm(state)
        state.trial = null
        val now = System.currentTimeMillis()
        val stored = record(id)
        var record = stored ?: LineAdapt.Record()
        if (state.revertTo != null) {
            // Back on its own transport, the line holds: what was learned here no longer applies.
            state.revertTo = null
            record = record.copy(failed = emptySet(), reverts = 0)
        }
        record = LineAdapt.bindingSteady(watchBinding(account, id, state, record, now), now)
        if (!record.isPlain && now - record.usedAt > USED_AT_STEP_MS) record = record.copy(usedAt = now)
        if (record != (stored ?: LineAdapt.Record())) store(id, record)
        // Not from inside this notification: the core is in the middle of the registration.
        if (record.expires != stored?.expires) handler.post(::applyAll)
        val ownExpires = own(id)?.expires ?: account.params.expires
        val newLoss = record.lost.lastOrNull() != stored?.lost?.lastOrNull()
        if (newLoss && record.lost.isNotEmpty() && LineAdapt.bindingHopeless(record, ownExpires)) {
            leaveForgetfulUdp(account, id, state)
        } else {
            retestIfDue(account, id, state, record, now)
        }
    }

    /** The NAT forgets the line however often it registers: over TCP the connection keeps it. */
    private fun leaveForgetfulUdp(account: Account, id: String, state: State) =
        // Over TCP the line is not watched for losses: the interval of its own is enough there.
        leaveUdp(account, id, state, { true }) { it.copy(expires = null, lost = emptyList()) }

    /**
     * Moves a line on UDP that works, but not well enough, to another transport the probe finds
     * open, when [why] holds for what the probe found; [change] adds to the record what else changes.
     */
    private fun leaveUdp(
        account: Account,
        id: String,
        state: State,
        why: (LineAdapt.Probe) -> Boolean,
        change: (LineAdapt.Record) -> LineAdapt.Record = { it },
    ) {
        val own = own(id)?.transport ?: transportOf(account.params.serverAddress)
        if (own != SipTransport.UDP || transportOf(account.params.serverAddress) != SipTransport.UDP) return
        probe(account, id, state) { probe ->
            if (!why(probe)) return@probe
            val record = record(id) ?: LineAdapt.Record()
            val next = LineAdapt.nextTransport(own, SipTransport.UDP, record.failed, probe, udpUnfit = true) ?: return@probe
            val now = System.currentTimeMillis()
            state.trial = next
            store(id, change(record).copy(transport = next, retestAt = now + LineAdapt.retestDelay(record.reverts), usedAt = now))
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
        return LineAdapt.bindingLost(record, own(id)?.expires ?: params.expires, now)
    }

    /**
     * A registration failed. One that got no answer is looked at again after a while
     * ([LineAdapt.CONFIRM_MS]); [stillFailing] follows if it has not recovered by then.
     */
    private fun failed(account: Account, id: String, state: State) {
        if (holds() || !LineAdapt.isTransportFailure(account.errorInfo.protocolCode)) {
            cancelConfirm(state)
            return
        }
        if (state.confirm != null) return
        val task = Runnable {
            state.confirm = null
            val current = find(id)
            // Dropped states (another network, another setting) have nothing to confirm.
            if (current != null && enabled && states[id] === state && current.state == RegistrationState.Failed && !holds()) {
                stillFailing(current, id, state)
            }
        }
        state.confirm = task
        handler.postDelayed(task, LineAdapt.CONFIRM_MS)
    }

    private fun cancelConfirm(state: State) {
        state.confirm?.let(handler::removeCallbacks)
        state.confirm = null
    }

    /** Starts over with every account: another network, or the adaptation switched. */
    private fun dropStates() {
        states.values.forEach(::cancelConfirm)
        states.clear()
    }

    private fun stillFailing(account: Account, id: String, state: State) {
        val now = System.currentTimeMillis()
        val revertTo = state.revertTo
        if (revertTo != null) {
            // Back on its own transport for a test, the line did not hold: back to what worked, and a longer wait.
            state.revertTo = null
            val record = record(id) ?: LineAdapt.Record()
            val reverts = record.reverts + 1
            store(id, record.copy(transport = revertTo, reverts = reverts, retestAt = now + LineAdapt.retestDelay(reverts), usedAt = now))
            applyAll()
            return
        }
        val own = own(id)?.transport ?: transportOf(account.params.serverAddress)
        if (own != SipTransport.UDP) return
        // A line found on another transport (as after a start) is on trial as well.
        val trial = state.trial ?: transportOf(account.params.serverAddress).takeIf { it != own }
        probe(account, id, state) { probe ->
            Log.i(TAG, "Line $id fails on ${account.params.serverAddress?.asStringUriOnly()}: $probe")
            val current = find(id)?.params?.serverAddress ?: return@probe
            val record = record(id) ?: LineAdapt.Record()
            // A transport that is open but did not register is not tried here again; one that is
            // closed now, like everything else, says nothing about it: the server is down.
            val stillOpen = when (trial) {
                SipTransport.TCP -> probe.tcp
                SipTransport.TLS -> probe.tls == true
                else -> false
            }
            if (!probe.udp && !probe.tcp && probe.tls != true) return@probe
            val failed = if (trial != null && stillOpen) record.failed + trial else record.failed
            val next = LineAdapt.nextTransport(own, transportOf(current), failed, probe, udpUnfit = probe.udp && probe.udpLarge == false)
            state.trial = next
            when {
                next != null -> store(id, record.copy(transport = next, failed = failed, retestAt = now + LineAdapt.retestDelay(record.reverts), usedAt = now))
                // Nothing left to try: its own transport again.
                trial != null -> store(id, record.copy(transport = null, failed = failed, usedAt = now))
                else -> return@probe
            }
            applyAll()
        }
    }

    /**
     * A call out on [account] got no answer: where small requests get through over UDP and large
     * ones do not, the line moves to another transport (RFC 3261 §18.1.1 wants TCP for them).
     */
    private fun callTimedOut(account: Account) {
        if (!enabled || holds()) return
        val id = idOf(account)
        leaveUdp(account, id, states.getOrPut(id, ::State), { it.udp && it.udpLarge == false })
    }

    /** A line on another transport looks again, when it is time, whether its own one gets through now. */
    private fun retestIfDue(account: Account, id: String, state: State, record: LineAdapt.Record, now: Long) {
        val adapted = record.transport ?: return
        if (now < record.retestAt || core.callsNb > 0) return
        probe(account, id, state) { probe ->
            val current = record(id) ?: return@probe
            if (current.transport != adapted) return@probe
            if (LineAdapt.udpBack(probe) && core.callsNb == 0) {
                state.revertTo = adapted
                store(id, current.copy(transport = null))
                applyAll()
            } else {
                store(id, current.copy(retestAt = now + LineAdapt.retestDelay(current.reverts)))
            }
        }
    }

    /**
     * Asks which transports get through to the server of [account], on a worker thread, once for
     * all accounts on that server; not more often than every [MIN_PROBE_GAP_MS] for one account.
     */
    private fun probe(account: Account, id: String, state: State, then: (LineAdapt.Probe) -> Unit) {
        val server = account.params.serverAddress ?: return
        val now = SystemClock.elapsedRealtime()
        if (state.probing || (state.probedAt > 0 && now - state.probedAt < MIN_PROBE_GAP_MS)) return
        state.probing = true
        state.probedAt = now
        val host = connectHostOf(server)
        val port = server.port.takeIf { it > 0 }
        val target = hostPortOf(server)
        val from = fromOf(account.params.identityAddress)
        val userAgent = core.userAgent
        val key = "$host|$port"
        val answer: (LineAdapt.Probe) -> Unit = { probe ->
            state.probing = false
            if (enabled && states[id] === state) then(probe)
        }
        probes[key]?.let {
            it += answer
            return
        }
        probes[key] = arrayListOf(answer)
        workers.execute {
            val probe = LineNetwork.transports(host, port, target, from, userAgent)
            handler.post { probes.remove(key)?.forEach { it(probe) } }
        }
    }

    // ---------------------------------------------------------------- the network

    private fun onNetwork(next: NetworkFacts?) {
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
            prune()
            applyAll()
        }
        // Logged in at last: the registrations need not wait for their next try.
        if (before?.captive == true && !next.captive) core.refreshRegisters()
        if (NetworkKey.unnoticedChange(before, next)) {
            handler.removeCallbacks(lookAgain)
            handler.postDelayed(lookAgain, SETTLE_MS)
        }
    }

    // ---------------------------------------------------------------- what is remembered

    private fun record(id: String): LineAdapt.Record? {
        val net = network ?: return null
        return LineAdapt.decode(core.config.getString(SECTION, LineAdapt.key(id, net), null))
    }

    private fun store(id: String, record: LineAdapt.Record) {
        val net = network ?: return
        val key = LineAdapt.key(id, net)
        if (record.isPlain) core.config.cleanEntry(SECTION, key) else core.config.setString(SECTION, key, LineAdapt.encode(record))
    }

    private fun keys(): List<String> = core.config.getKeysNamesList(SECTION).toList()

    private fun forgetRecords(id: String) {
        keys().filter { LineAdapt.accountOf(it) == id }.forEach { core.config.cleanEntry(SECTION, it) }
    }

    /** Forgets the networks not seen for long, and those beyond the most recent ones. */
    private fun prune() {
        val records = keys().associateWith { LineAdapt.decode(core.config.getString(SECTION, it, null)) ?: LineAdapt.Record() }
        LineAdapt.stale(records, System.currentTimeMillis()).forEach { core.config.cleanEntry(SECTION, it) }
    }

    /**
     * Puts every line on what is remembered for it on this network, or back on its own settings,
     * and the SIP core's symmetric RTP on what the lines need.
     */
    private fun applyAll() {
        // Before the network is known nothing is known of what it needs: the lines stay as they are.
        if (enabled && network == null) return
        val accounts = core.accountList
        accounts.forEach(::apply)
        applySymmetricRtp(accounts)
        publish()
    }

    /** Puts the line of [account] on what is remembered for it, or back on its own settings. */
    private fun apply(account: Account) {
        val id = idOf(account)
        val params = account.params
        val server = params.serverAddress ?: return
        val own = own(id) ?: Own(transportOf(server), params.expires, params.isOutboundProxyEnabled)
        val record = if (enabled) record(id) else null
        val transport = record?.transport?.takeIf { own.transport == SipTransport.UDP } ?: own.transport
        val expires = record?.expires?.takeIf { it < own.expires } ?: own.expires
        // Calls take the way of the registration, which the domain of the account may not lead
        // to, and the way of the server past a PBX that announces an address nothing reaches.
        val outbound = transport != own.transport || (enabled && pbxCount(id) != null) || own.outbound
        if (transport != transportOf(server) || expires != params.expires || outbound != params.isOutboundProxyEnabled) {
            val next = params.clone()
            if (transport != transportOf(server)) next.serverAddress = server.clone().also { it.transport = typeOf(transport) }
            next.expires = expires
            next.isOutboundProxyEnabled = outbound
            account.params = next
            states[id]?.contact = null
            onChanged(account)
            Log.i(TAG, "Line $id on $network: $transport, ${expires}s, outbound $outbound (own: ${own.transport}, ${own.expires}s, ${own.outbound})")
        }
        val adapted = transport != own.transport || expires != own.expires || outbound != own.outbound
        storeOwn(id, if (adapted) own else null)
    }

    // ---------------------------------------------------------------- the sound of the calls

    /**
     * A call whose sound ran ended on [account], with [contact] the host of the other side's
     * Contact: an account whose PBX announces an address of its own network goes by way of the
     * server from the next call on ([MediaAdapt]).
     */
    fun onCallFinished(account: Account, contact: String?) {
        if (!enabled) return
        val id = idOf(account)
        val host = account.params.serverAddress?.let(::connectHostOf) ?: return
        val local = facts?.addresses.orEmpty()
        workers.execute {
            val behind = MediaAdapt.pbxBehindNat(contact, LineNetwork.lookup(host) + host, local)
            handler.post { learn(id, behind) }
        }
    }

    private fun learn(id: String, pbxBehindNat: Boolean) {
        if (!enabled) return
        val before = pbxCount(id)
        val after = MediaAdapt.pbxAfter(before, pbxBehindNat)
        if (after == before) return
        if (after == null) core.config.cleanEntry(PBX, id) else core.config.setString(PBX, id, after.toString())
        if ((after == null) != (before == null)) {
            Log.i(TAG, "Line $id: the PBX ${if (after != null) "announces an address of its own network" else "is reached again"}")
            applyAll()
        }
    }

    /** The calls in a row since the PBX of account [id] last announced an address of its own network; null: it needs no remedy. */
    private fun pbxCount(id: String): Int? = core.config.getString(PBX, id, null)?.toIntOrNull()

    /**
     * Symmetric RTP (the sound goes back to where the other side's comes from, not to the
     * address it announced) while an account's PBX announces an address of its own network.
     * The SIP core reads it when a call's streams are set up, for every call: an incoming call
     * is set up before the app knows its account.
     */
    private fun applySymmetricRtp(accounts: Array<Account>) {
        val wanted = enabled && accounts.any { pbxCount(idOf(it)) != null }
        if (wanted == (core.config.getInt(RTP, SYMMETRIC, 0) != 0)) return
        if (wanted) core.config.setInt(RTP, SYMMETRIC, 1) else core.config.cleanEntry(RTP, SYMMETRIC)
        Log.i(TAG, "Symmetric RTP ${if (wanted) "on" else "off"}")
    }

    private fun publish() {
        adapted.value = core.accountList.mapNotNull { account ->
            val id = idOf(account)
            val own = own(id) ?: return@mapNotNull null
            val transport = transportOf(account.params.serverAddress)
            if (transport == own.transport) null else id to transport
        }.toMap()
    }

    private companion object {
        /** Section of the core configuration holding the [LineAdapt.Record]s, keyed by `account|network`. */
        const val SECTION = "siplink_line_adapt"

        /** Section holding the [Own] settings of the accounts the doctor changed, keyed by account id. */
        const val APPLIED = "siplink_line_applied"

        /** Section holding, by account id, the calls in a row since its PBX last announced an address of its own network ([MediaAdapt.pbxAfter]). */
        const val PBX = "siplink_line_pbx"

        /** The SIP core's key for symmetric RTP (linphone_core_symmetric_rtp_enabled, misc.c). */
        const val RTP = "rtp"
        const val SYMMETRIC = "symmetric"
        const val SEPARATOR = "|"

        /**
         * A server is asked again for one account after this long at the earliest: PBXs block who
         * asks too often. Shorter than [LineAdapt.CONFIRM_MS], so that a confirmed failure is
         * always looked into.
         */
        const val MIN_PROBE_GAP_MS = 30_000L

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
