package com.sky.siplink.core

import android.os.Handler
import android.os.SystemClock
import com.sky.siplink.core.logic.LineAdapt
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
 * interval where the NAT forgets the line between registrations. It also has the SIP core look
 * at the network again where it would not notice a change by itself, and holds back while the
 * network wants a login first.
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
        /** Registrations in a row that failed for want of an answer. */
        var failures = 0

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
            states.clear()
            if (!value) {
                core.accountList.forEach(::apply)
                core.config.cleanSection(SECTION)
            }
            publish()
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
        if (forget) forgetRecords(id)
        states.remove(id)
    }

    fun reapply(id: String) {
        find(id)?.let(::apply)
        publish()
    }

    /** Account [id] is gone, or the user wants it on its own settings everywhere ("restore defaults"). */
    fun forget(id: String) {
        forgetRecords(id)
        states.remove(id)
        find(id)?.let(::apply)
        storeOwn(id, null)
        publish()
    }

    /**
     * What the line of an account runs on instead of its own settings, for the line checks.
     *
     * @param transport null: its own; [expires] likewise
     */
    data class Adaptation(val transport: SipTransport?, val expires: Int?)

    fun adaptation(id: String): Adaptation? {
        val params = find(id)?.params ?: return null
        val own = own(id) ?: return null
        val transport = transportOf(params.serverAddress)
        return Adaptation(transport.takeIf { it != own.transport }, params.expires.takeIf { it != own.expires })
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
        state.failures = 0
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
        if (record.expires != stored?.expires) handler.post { reapply(id) }
        retestIfDue(account, id, state, record, now)
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

    private fun failed(account: Account, id: String, state: State) {
        if (holds()) return
        if (!LineAdapt.isTransportFailure(account.errorInfo.protocolCode)) {
            state.failures = 0
            return
        }
        if (++state.failures < LineAdapt.FAILURES_BEFORE_PROBE) return
        val now = System.currentTimeMillis()
        val revertTo = state.revertTo
        if (revertTo != null) {
            // Back on its own transport for a test, the line did not hold: back to what worked, and a longer wait.
            state.revertTo = null
            state.failures = 0
            val record = record(id) ?: LineAdapt.Record()
            val reverts = record.reverts + 1
            store(id, record.copy(transport = revertTo, reverts = reverts, retestAt = now + LineAdapt.retestDelay(reverts), usedAt = now))
            handler.post { reapply(id) }
            return
        }
        val own = own(id)?.transport ?: transportOf(account.params.serverAddress)
        if (own != SipTransport.UDP) return
        // A line found on another transport (as after a start) is on trial as well.
        val trial = state.trial ?: transportOf(account.params.serverAddress).takeIf { it != own }
        probe(account, id, state) { probe ->
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
            val next = LineAdapt.nextTransport(own, transportOf(current), failed, probe, largeLost = probe.udp && probe.udpLarge == false)
            state.failures = 0
            state.trial = next
            when {
                next != null -> store(id, record.copy(transport = next, failed = failed, retestAt = now + LineAdapt.retestDelay(record.reverts), usedAt = now))
                // Nothing left to try: its own transport again.
                trial != null -> store(id, record.copy(transport = null, failed = failed, usedAt = now))
                else -> return@probe
            }
            reapply(id)
        }
    }

    /**
     * A call out on [account] got no answer: where small requests get through over UDP and large
     * ones do not, the line moves to another transport (RFC 3261 §18.1.1 wants TCP for them).
     */
    private fun callTimedOut(account: Account) {
        if (!enabled || holds()) return
        val id = idOf(account)
        val state = states.getOrPut(id, ::State)
        val own = own(id)?.transport ?: transportOf(account.params.serverAddress)
        if (own != SipTransport.UDP || transportOf(account.params.serverAddress) != SipTransport.UDP) return
        probe(account, id, state) { probe ->
            if (!probe.udp || probe.udpLarge != false) return@probe
            val record = record(id) ?: LineAdapt.Record()
            val next = LineAdapt.nextTransport(own, SipTransport.UDP, record.failed, probe, largeLost = true) ?: return@probe
            val now = System.currentTimeMillis()
            state.trial = next
            store(id, record.copy(transport = next, retestAt = now + LineAdapt.retestDelay(record.reverts), usedAt = now))
            reapply(id)
        }
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
                reapply(id)
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
            states.clear()
        }
        if (!enabled) return
        if (moved) {
            prune()
            core.accountList.forEach(::apply)
            publish()
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

    /** Puts the line of [account] on what is remembered for it on this network, or back on its own settings. */
    private fun apply(account: Account) {
        val id = idOf(account)
        val params = account.params
        val server = params.serverAddress ?: return
        val own = own(id) ?: Own(transportOf(server), params.expires, params.isOutboundProxyEnabled)
        val record = if (enabled) record(id) else null
        val transport = record?.transport?.takeIf { own.transport == SipTransport.UDP } ?: own.transport
        val expires = record?.expires?.takeIf { it < own.expires } ?: own.expires
        // Calls take the way of the registration, which the domain of the account may not lead to.
        val outbound = if (transport != own.transport) true else own.outbound
        if (transport != transportOf(server) || expires != params.expires || outbound != params.isOutboundProxyEnabled) {
            val next = params.clone()
            if (transport != transportOf(server)) next.serverAddress = server.clone().also { it.transport = typeOf(transport) }
            next.expires = expires
            next.isOutboundProxyEnabled = outbound
            account.params = next
            states[id]?.contact = null
            onChanged(account)
        }
        storeOwn(id, if (transport == own.transport && expires == own.expires) null else own)
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
        const val SEPARATOR = "|"

        /** A server is asked again for one account after this long at the earliest: PBXs block who asks too often. */
        const val MIN_PROBE_GAP_MS = 60_000L

        /** A change of address settles for this long before the core looks at the network again. */
        const val SETTLE_MS = 2_000L

        /** When a line was last used is written down at most this often. */
        const val USED_AT_STEP_MS = 60 * 60_000L

        /** What the SIP core reports for a request nobody answered. */
        const val TIMEOUT = 408

        fun typeOf(transport: SipTransport): TransportType = when (transport) {
            SipTransport.UDP -> TransportType.Udp
            SipTransport.TCP -> TransportType.Tcp
            SipTransport.TLS -> TransportType.Tls
        }
    }
}
