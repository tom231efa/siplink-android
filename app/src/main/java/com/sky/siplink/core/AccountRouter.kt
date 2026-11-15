package com.sky.siplink.core

import android.os.Handler
import android.os.SystemClock
import com.sky.siplink.core.logic.AccountRoute
import com.sky.siplink.core.model.AccountDraft
import com.sky.siplink.core.model.SipTransport
import org.linphone.core.Account
import org.linphone.core.Address
import org.linphone.core.Core
import org.linphone.core.Factory
import org.linphone.core.RegistrationState
import org.linphone.core.TransportType
import java.util.concurrent.Executors

/**
 * Keeps every account on the right server, which the SIP core cannot do by itself:
 *
 * - An account with a backup server moves there when its server is out of reach, and back once
 *   that one answers again and no call is up.
 * - An account with a DNS server of its own has the address of its server looked up there and
 *   handed to the core, which would only ask the system.
 *
 * Main thread only. The questions that take time are asked on worker threads.
 *
 * @param find the account with an id, [idOf] the id of an account
 * @param onChanged called after the router changed an account, so that it is shown anew
 */
internal class AccountRouter(
    private val core: Core,
    private val handler: Handler,
    private val find: (String) -> Account?,
    private val idOf: (Account) -> String,
    private val onChanged: (Account) -> Unit,
) {
    /** What is going on with one account. */
    private class State {
        /** Registrations in a row that failed because the server was out of reach. */
        var failures = 0

        /** Changes whenever the route is decided anew: the answer to an older question is dropped. */
        var generation = 0

        /** The account just went back to its first server; [failedReturns] counts the times that did not hold. */
        var returning = false
        var failedReturns = 0
        var probe: Runnable? = null
        var lookedUpAt = 0L
        var dnsFailed = false
    }

    /** How an account is routed, for showing it. */
    class View(val record: AccountRoute.Record, val onBackup: Boolean, val dnsFailed: Boolean)

    private val states = HashMap<String, State>()
    private var generations = 0
    private val workers = Executors.newFixedThreadPool(WORKERS)
    private val lookups = Shared<List<String>>(LOOKUP_KEEP_MS, failed = emptyList())
    private val probes = Shared(PROBE_KEEP_MS, failed = false)

    // ---------------------------------------------------------------- what the app remembers

    fun record(id: String): AccountRoute.Record? = AccountRoute.decode(core.config.getString(SECTION, id, null))

    private fun store(id: String, record: AccountRoute.Record?) {
        if (record == null || record.isPlain) {
            core.config.cleanEntry(SECTION, id)
        } else {
            core.config.setString(SECTION, id, AccountRoute.encode(record))
        }
    }

    fun view(id: String, account: Account): View? {
        val record = record(id) ?: return null
        return View(record, onBackup(account, record), states[id]?.dnsFailed == true)
    }

    // ---------------------------------------------------------------- set up by the account manager

    /**
     * Remembers the servers of account [id] as [draft] has them, starting over on the first
     * one. Call [begin] once the account is in the core with these servers.
     *
     * @return whether the account registers right away; when not, the address of its server
     *   has to be looked up first and the router switches the registration on after that
     */
    fun configure(id: String, draft: AccountDraft): Boolean {
        val record = AccountRoute.Record(draft.server, draft.backupServer, draft.dnsServer)
        val wait = draft.enabled && needsLookup(draft.server.ifEmpty { draft.address }, record)
        store(id, record.copy(waiting = wait))
        states.remove(id)?.let(::cancelProbe)
        return draft.enabled && !wait
    }

    fun begin(id: String) {
        if (record(id)?.waiting == true) route(id, useBackup = false)
    }

    /** Like [configure], for an account that is only being switched on again. */
    fun enable(id: String): Boolean {
        val account = find(id) ?: return true
        val record = record(id) ?: return true
        val backup = onBackup(account, record)
        if (!needsLookup(serverOf(account, record, backup), record)) return true
        store(id, record.copy(waiting = true))
        route(id, backup)
        return false
    }

    fun disable(id: String) {
        record(id)?.takeIf { it.waiting }?.let { store(id, it.copy(waiting = false)) }
        states.remove(id)?.let(::cancelProbe)
    }

    fun forget(id: String) {
        states.remove(id)?.let(::cancelProbe)
        store(id, null)
    }

    /**
     * After a start or a change of network: accounts still waiting for the address of their
     * server get it, and the addresses found earlier are looked up again.
     */
    fun refresh() {
        for (account in core.accountList) {
            val id = idOf(account)
            val record = record(id) ?: continue
            if (record.waiting || (record.dns.isNotEmpty() && account.params.isRegisterEnabled)) route(id, onBackup(account, record))
        }
    }

    // ---------------------------------------------------------------- following the registration

    fun onRegistration(account: Account) {
        val id = idOf(account)
        val record = record(id) ?: return
        val state = states.getOrPut(id, ::State)
        val backup = onBackup(account, record)
        when (account.state) {
            RegistrationState.Ok -> {
                state.failures = 0
                if (backup) {
                    scheduleProbe(id, state)
                } else {
                    state.returning = false
                    state.failedReturns = 0
                }
            }
            RegistrationState.Failed -> {
                if (!AccountRoute.isServerDown(account.errorInfo.protocolCode)) return
                state.failures++
                val switch = record.backup.isNotEmpty() && AccountRoute.switchesNow(state.failures)
                if (switch && !backup && state.returning) state.failedReturns++
                if (switch) state.returning = false
                val stale = record.dns.isNotEmpty() && SystemClock.elapsedRealtime() - state.lookedUpAt > RELOOKUP_MS
                // Not from inside this notification: the core is in the middle of the registration.
                if (switch || stale) handler.post { route(id, useBackup = if (switch) !backup else backup) }
            }
            else -> Unit
        }
    }

    /** Puts account [id] on its first or its backup server, looking its address up when the account has a DNS server. */
    private fun route(id: String, useBackup: Boolean) {
        val account = find(id) ?: return
        val record = record(id) ?: return
        val state = states.getOrPut(id, ::State)
        val generation = ++generations
        state.generation = generation
        cancelProbe(state)
        val server = serverOf(account, record, useBackup)
        if (!needsLookup(server, record)) {
            apply(id, server, pin = null)
            return
        }
        state.lookedUpAt = SystemClock.elapsedRealtime()
        val host = AccountRoute.split(server).first
        val (dnsAddress, dnsPort) = AccountRoute.dnsServer(record.dns)
        lookups.ask("$host@${record.dns}", { LineNetwork.resolve(host, dnsAddress, dnsPort) }) { addresses ->
            val current = states[id]
            if (current == null || current.generation != generation) return@ask
            current.dnsFailed = addresses.isEmpty()
            // Without an answer the address found last time is the best guess; without one, the system is asked.
            apply(id, server, addresses.firstOrNull() ?: find(id)?.let { pinOf(it, server) })
        }
    }

    private fun apply(id: String, server: String, pin: String?) {
        val account = find(id) ?: return
        val record = record(id) ?: return
        val params = account.params.clone()
        val address = Factory.instance().createAddress(AccountRoute.uri(server, transportOf(params.serverAddress), pin)) ?: return
        val same = params.serverAddress?.asStringUriOnly() == address.asStringUriOnly()
        if (same && !record.waiting) {
            // Nothing changes, so no registration follows that would set up the next look at the first server.
            if (onBackup(account, record) && account.state == RegistrationState.Ok) states[id]?.let { scheduleProbe(id, it) }
            return
        }
        params.serverAddress = address
        // Calls have to take the same way as the registration.
        params.isOutboundProxyEnabled = true
        if (record.waiting) {
            params.isRegisterEnabled = true
            store(id, record.copy(waiting = false))
        }
        account.params = params
        onChanged(account)
    }

    // ---------------------------------------------------------------- back to the first server

    private fun scheduleProbe(id: String, state: State) {
        if (state.probe != null) return
        val task = Runnable {
            state.probe = null
            probe(id)
        }
        state.probe = task
        handler.postDelayed(task, AccountRoute.failbackDelay(state.failedReturns))
    }

    private fun cancelProbe(state: State) {
        state.probe?.let(handler::removeCallbacks)
        state.probe = null
    }

    /** Asks the first server of an account on its backup whether it is there again, and moves the account back if so. */
    private fun probe(id: String) {
        val account = find(id) ?: return
        val record = record(id) ?: return
        val state = states[id] ?: return
        // Anything else than registered on the backup takes its course in onRegistration.
        if (!onBackup(account, record) || account.state != RegistrationState.Ok) return
        val generation = state.generation
        val transport = transportOf(account.params.serverAddress)
        val server = serverOf(account, record, backup = false)
        val (host, port) = AccountRoute.split(server)
        val lookup = needsLookup(server, record)
        val from = account.params.identityAddress?.let { "${it.username}@${hostPortOf(it)}" }.orEmpty()
        val userAgent = core.userAgent
        probes.ask("$server/$transport@${record.dns}", {
            val target = if (lookup) {
                val (dnsAddress, dnsPort) = AccountRoute.dnsServer(record.dns)
                LineNetwork.resolve(host, dnsAddress, dnsPort).firstOrNull()
            } else {
                host
            }
            target != null && LineNetwork.answers(target, port ?: AccountRoute.defaultPort(transport), transport, server, from, userAgent)
        }) { alive ->
            val current = states[id]
            if (current == null || current.generation != generation) return@ask
            // A call in progress stays where it is; the next round finds the line free.
            if (alive && core.callsNb == 0) {
                current.returning = true
                route(id, useBackup = false)
            } else {
                scheduleProbe(id, current)
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun needsLookup(server: String, record: AccountRoute.Record): Boolean =
        record.dns.isNotEmpty() && !AccountRoute.isAddress(AccountRoute.split(server).first)

    private fun serverOf(account: Account, record: AccountRoute.Record, backup: Boolean): String =
        if (backup) record.backup else record.primary.ifEmpty { hostPortOf(account.params.identityAddress) }

    private fun onBackup(account: Account, record: AccountRoute.Record): Boolean =
        record.backup.isNotEmpty() && hostPortOf(account.params.serverAddress).equals(record.backup, ignoreCase = true)

    /** The address the account connects to [server] at, when that is its server now and an address was found for it. */
    private fun pinOf(account: Account, server: String): String? {
        val current = account.params.serverAddress ?: return null
        return if (hostPortOf(current).equals(server, ignoreCase = true)) current.getUriParam("maddr")?.ifEmpty { null } else null
    }

    /**
     * Asks a question that takes time on a worker thread, once for everybody who asks the same
     * at about the same time: a hundred accounts on one server need one answer.
     *
     * @param keepMs how long an answer serves those who ask later
     * @param failed the answer when asking went wrong
     */
    private inner class Shared<T>(private val keepMs: Long, private val failed: T) {
        private inner class Answer(val value: T, val at: Long)

        private val asking = HashMap<String, ArrayList<(T) -> Unit>>()
        private val answers = HashMap<String, Answer>()

        fun ask(key: String, question: () -> T, then: (T) -> Unit) {
            val known = answers[key]
            if (known != null && SystemClock.elapsedRealtime() - known.at < keepMs) {
                then(known.value)
                return
            }
            asking[key]?.let {
                it += then
                return
            }
            asking[key] = arrayListOf(then)
            workers.execute {
                val value = runCatching(question).getOrDefault(failed)
                handler.post {
                    answers[key] = Answer(value, SystemClock.elapsedRealtime())
                    asking.remove(key)?.forEach { it(value) }
                }
            }
        }
    }

    private companion object {
        /** Section of the core configuration file holding the [AccountRoute.Record]s, keyed by account id. */
        const val SECTION = "siplink_account_routes"
        const val WORKERS = 4
        const val LOOKUP_KEEP_MS = 10_000L
        const val PROBE_KEEP_MS = 5_000L

        /** A registration that keeps failing has the address of its server looked up again this often. */
        const val RELOOKUP_MS = 30_000L
    }
}

/** `host[:port]` of [address], an IPv6 address in brackets; empty without an address. */
internal fun hostPortOf(address: Address?): String {
    val host = address?.domain.orEmpty()
    if (host.isEmpty()) return ""
    val literal = if (':' in host && !host.startsWith("[")) "[$host]" else host
    val port = address?.port ?: 0
    return if (port > 0) "$literal:$port" else literal
}

internal fun transportOf(address: Address?): SipTransport = when (address?.transport) {
    TransportType.Tcp -> SipTransport.TCP
    TransportType.Tls -> SipTransport.TLS
    else -> SipTransport.UDP
}
