package com.sky.siplink.core

import android.os.Handler
import android.os.SystemClock
import android.util.Log
import com.sky.siplink.core.logic.AccountRoute
import com.sky.siplink.core.logic.LineAdapt
import com.sky.siplink.core.logic.NetworkKey
import com.sky.siplink.core.logic.ServerChoice
import com.sky.siplink.core.logic.ServerChoice.Answer
import com.sky.siplink.core.logic.ServerChoice.Move
import com.sky.siplink.core.model.AccountDraft
import com.sky.siplink.core.model.NetworkFacts
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
 * - An account with a backup server has both its servers asked at the same time ([check]) when
 *   it starts and after a change of network, and with the fast timeout ([ServerChoice]) also
 *   whenever its registration starts or is refreshed; it goes where [ServerChoice.next] says. On
 *   a network it knows it goes at once to the server it registered on there last, while both are
 *   asked. Between those times it moves to the backup after its registration failed for want of
 *   a server, and back once the first one answers again and no call is up.
 * - An account with a DNS server of its own has the address of its server looked up there and
 *   handed to the core, which would only ask the system.
 *
 * Main thread only. The questions that take time are asked on worker threads.
 *
 * @param find the account with an id, [idOf] the id of an account
 * @param onChanged called after the router changed an account, so that it is shown anew
 * @param holds whether failed registrations are to be taken as they come: the network wants a
 *   login first, and no server would be reached
 * @param ownTransport the transport of an account as the user set it up, where the line doctor
 *   runs it on another one; null: the one it runs on
 * @param afterMove called after an account was moved to its other server
 */
internal class AccountRouter(
    private val core: Core,
    private val handler: Handler,
    private val find: (String) -> Account?,
    private val idOf: (Account) -> String,
    private val onChanged: (Account) -> Unit,
    private val holds: () -> Boolean = { false },
    private val ownTransport: (String) -> SipTransport? = { null },
    private val afterMove: (String) -> Unit = {},
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

        /** Both servers are being asked; null while they are not. */
        var check: Check? = null

        /** When the servers were last asked, and when the account last moved ([SystemClock.elapsedRealtime]). */
        var checkedAt = 0L
        var movedAt = 0L

        /** A move to the other server is on its way: the failure that led to it was the old server's. */
        var moving = false

        /** What the last check found, for the line checks. */
        var seen: Seen? = null
    }

    /** One asking of both servers; [waiters] are told whether the account moved. */
    private class Check(val timeoutMs: Int) {
        var primary = Answer.PENDING
        var backup = Answer.PENDING
        var deadline: Runnable? = null
        val waiters = ArrayList<(Boolean) -> Unit>()
    }

    /**
     * What the last asking of both servers found.
     *
     * @param primary the first server answered; null: it gave no answer within the fast timeout,
     *   and the backup was taken before its probe was over
     * @param at when (ms since the epoch)
     */
    class Seen(val primary: Boolean?, val backup: Boolean, val at: Long)

    /** How an account is routed, for showing it. */
    class View(val record: AccountRoute.Record, val onBackup: Boolean, val dnsFailed: Boolean, val seen: Seen?)

    private val states = HashMap<String, State>()
    private var generations = 0
    private val workers = Executors.newFixedThreadPool(WORKERS)
    private val lookups = Shared<List<String>>(LOOKUP_KEEP_MS, failed = emptyList())
    private val probes = Shared(PROBE_KEEP_MS, failed = false)

    /** The network the phone is on ([NetworkKey]); null while it has none. */
    private var network: String? = null
    private val settle = Runnable { checkAll() }

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
        val state = states[id]
        return View(record, onBackup(account, record), state?.dnsFailed == true, state?.seen)
    }

    /** Whether account [id] is on its backup server. */
    fun isOnBackup(id: String): Boolean {
        val account = find(id) ?: return false
        val record = record(id) ?: return false
        return onBackup(account, record)
    }

    /**
     * Whether the router is deciding where account [id] goes: its servers are being asked, or
     * it is about to move. A failed registration then says nothing of the line's transport.
     */
    fun isRouting(id: String): Boolean = states[id]?.let { it.check != null || it.moving } == true

    /** The server account [id] registered on last on [net]. */
    private fun remembered(id: String, net: String): ServerChoice.Remembered? =
        ServerChoice.decode(core.config.getString(MEMORY, LineAdapt.key(id, net), null))

    private fun remember(id: String, backup: Boolean) {
        val net = network ?: return
        val next = ServerChoice.remember(remembered(id, net), backup, System.currentTimeMillis()) ?: return
        core.config.setString(MEMORY, LineAdapt.key(id, net), ServerChoice.encode(next))
    }

    private fun forgetMemory(id: String) {
        core.config.getKeysNamesList(MEMORY).filter { LineAdapt.accountOf(it) == id }.forEach { core.config.cleanEntry(MEMORY, it) }
    }

    /** Forgets the networks not seen for long, and those beyond the most recent ones ([LineAdapt.staleKeys]). */
    private fun pruneMemory() {
        val used = core.config.getKeysNamesList(MEMORY).associateWith { ServerChoice.decode(core.config.getString(MEMORY, it, null))?.usedAt ?: 0L }
        LineAdapt.staleKeys(used, System.currentTimeMillis()).forEach { core.config.cleanEntry(MEMORY, it) }
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
        val record = AccountRoute.Record(draft.server, draft.backupServer, draft.dnsServer, timeoutMs = draft.serverTimeoutMs)
        val wait = draft.enabled && needsLookup(draft.server.ifEmpty { draft.address }, record)
        val before = record(id)
        store(id, record.copy(waiting = wait))
        // Other servers: where the account registered on each network does not hold for them.
        if (before?.primary != record.primary || before.backup != record.backup) forgetMemory(id)
        states.remove(id)?.let(::cancel)
        return draft.enabled && !wait
    }

    /** Account [id] keeps its servers and gets the fast timeout [timeoutMs]: the next asking of its servers goes by it. */
    fun setTimeout(id: String, timeoutMs: Int) {
        val record = record(id) ?: return
        if (record.timeoutMs != timeoutMs) store(id, record.copy(timeoutMs = timeoutMs))
    }

    fun begin(id: String) {
        if (record(id)?.waiting == true) route(id, useBackup = false)
        // Its servers are asked at once, as on arrival at a network.
        handler.post { arrive(id) }
    }

    /** Like [configure], for an account that is only being switched on again. */
    fun enable(id: String): Boolean {
        val account = find(id) ?: return true
        val record = record(id) ?: return true
        handler.post { arrive(id) }
        val backup = onBackup(account, record)
        if (!needsLookup(serverOf(account, record, backup), record)) return true
        store(id, record.copy(waiting = true))
        route(id, backup)
        return false
    }

    fun disable(id: String) {
        record(id)?.takeIf { it.waiting }?.let { store(id, it.copy(waiting = false)) }
        states.remove(id)?.let(::cancel)
    }

    fun forget(id: String) {
        states.remove(id)?.let(::cancel)
        store(id, null)
        forgetMemory(id)
    }

    /**
     * After a start or when the network is back: accounts still waiting for the address of their
     * server get it, and the addresses found earlier are looked up again.
     */
    fun refresh() {
        for (account in core.accountList) {
            val id = idOf(account)
            val record = record(id) ?: continue
            if (record.waiting || (record.dns.isNotEmpty() && account.params.isRegisterEnabled)) route(id, onBackup(account, record))
        }
    }

    // ---------------------------------------------------------------- the network

    /**
     * The phone is on [facts] now ([NetworkWatch]). On another network, or on the same one again
     * after none, every account with a backup server goes to the server it registered on there
     * last, and both its servers are asked at once, and once more a moment later
     * ([ServerChoice.SETTLE_MS]).
     */
    fun onNetwork(facts: NetworkFacts?) {
        val next = facts?.let(NetworkKey::of)
        val before = network
        network = next
        if (next == null || next == before) return
        pruneMemory()
        handler.removeCallbacks(settle)
        for (account in core.accountList) {
            val id = idOf(account)
            // What was being asked was asked on the network before.
            states[id]?.let(::dropCheck)
            arrive(id)
        }
        handler.postDelayed(settle, ServerChoice.SETTLE_MS)
    }

    /** Account [id] is on a network: to the server it registered on there last, and both servers asked. */
    private fun arrive(id: String) {
        val account = find(id) ?: return
        val record = record(id) ?: return
        if (record.backup.isEmpty() || !account.params.isRegisterEnabled) return
        val known = network?.let { remembered(id, it) }
        if (known != null && known.backup != onBackup(account, record) && core.callsNb == 0 && !holds()) {
            Log.i(TAG, "Line $id: to its ${if (known.backup) "backup" else "first"} server, as last time on this network")
            move(id, known.backup)
        }
        check(id)
    }

    private fun checkAll() {
        for (account in core.accountList) check(idOf(account))
    }

    // ---------------------------------------------------------------- asking both servers

    /**
     * Asks both servers of account [id] at the same time ([LineNetwork.answers]), and moves it
     * where [ServerChoice.next] says once that is decided. [then] is told whether the account
     * left the server it was on; false at once when nothing is asked.
     */
    private fun check(id: String, then: ((Boolean) -> Unit)? = null) {
        val account = find(id)
        val record = record(id)
        if (account == null || record == null || record.backup.isEmpty() || record.waiting || !account.params.isRegisterEnabled || holds()) {
            then?.invoke(false)
            return
        }
        val state = states.getOrPut(id, ::State)
        state.check?.let { running ->
            then?.let(running.waiters::add)
            return
        }
        val check = Check(record.timeoutMs)
        then?.let(check.waiters::add)
        state.check = check
        state.checkedAt = SystemClock.elapsedRealtime()
        val onBackup = onBackup(account, record)
        val current = transportOf(account.params.serverAddress)
        // The server the account is on is asked as its line runs now; the other one as the account is set up.
        val other = ownTransport(id) ?: current
        ask(account, record, serverOf(account, record, backup = false), if (onBackup) other else current, record.timeoutMs) { alive ->
            check.primary = if (alive) Answer.ANSWERED else Answer.FAILED
            decide(id, check)
        }
        ask(account, record, record.backup, if (onBackup) current else other, timeoutMs = 0) { alive ->
            check.backup = if (alive) Answer.ANSWERED else Answer.FAILED
            decide(id, check)
        }
        if (record.timeoutMs > 0) {
            val deadline = Runnable {
                check.deadline = null
                if (check.primary == Answer.PENDING) check.primary = Answer.SILENT
                decide(id, check)
            }
            check.deadline = deadline
            handler.postDelayed(deadline, record.timeoutMs.toLong())
        }
    }

    /** Asks whether [server] answers over [transport], for [account]; [then] on the main thread. */
    private fun ask(account: Account, record: AccountRoute.Record, server: String, transport: SipTransport, timeoutMs: Int, then: (Boolean) -> Unit) {
        val (host, port) = AccountRoute.split(server)
        val lookup = needsLookup(server, record)
        val from = fromOf(account.params.identityAddress)
        val userAgent = core.userAgent
        probes.ask("$network|$server/$transport@${record.dns}|$timeoutMs", {
            val target = if (lookup) {
                val (dnsAddress, dnsPort) = AccountRoute.dnsServer(record.dns)
                LineNetwork.resolve(host, dnsAddress, dnsPort).firstOrNull()
            } else {
                host
            }
            target != null && LineNetwork.answers(target, port ?: AccountRoute.defaultPort(transport), transport, server, from, userAgent, timeoutMs)
        }, then)
    }

    private fun decide(id: String, check: Check) {
        val state = states[id]
        val account = find(id)
        val record = record(id)
        if (state == null || state.check !== check || account == null || record == null) return
        val onBackup = onBackup(account, record)
        val move = ServerChoice.next(onBackup, check.primary, check.backup, callUp = core.callsNb > 0)
        if (move == Move.WAIT) return
        dropCheck(state)
        state.seen = Seen(
            primary = check.primary.takeIf { it != Answer.SILENT }?.let { it == Answer.ANSWERED },
            backup = check.backup == Answer.ANSWERED,
            at = System.currentTimeMillis(),
        )
        val toBackup = move == Move.BACKUP
        val moves = move != Move.STAY && toBackup != onBackup
        Log.i(TAG, "Line $id: first server ${check.primary}, backup ${check.backup}${if (check.timeoutMs > 0) " (fast, ${check.timeoutMs} ms)" else ""}: $move")
        if (moves) {
            if (!toBackup) state.returning = true
            move(id, toBackup)
        } else if (onBackup && account.state == RegistrationState.Ok) {
            // It stays on the backup: the first server is asked again later.
            scheduleProbe(id, state)
        }
        check.waiters.forEach { it(moves) }
    }

    /** Stops the asking of an account: what it finds is dropped, and those who wait for it hear nothing. */
    private fun dropCheck(state: State) {
        val check = state.check ?: return
        state.check = null
        check.deadline?.let(handler::removeCallbacks)
        check.deadline = null
    }

    /** Puts account [id] on its other server: the backup with [toBackup], else its first one. */
    private fun move(id: String, toBackup: Boolean) {
        val state = states.getOrPut(id, ::State)
        state.failures = 0
        state.movedAt = SystemClock.elapsedRealtime()
        route(id, toBackup)
        afterMove(id)
    }

    /**
     * A call out on account [id] that went through [server] has had no answer: [then] is told on
     * the main thread whether the account is now on its other server, because [server] is
     * silent and the other one answers ([ServerChoice.next] with a call up).
     */
    fun leaveSilentServer(id: String, server: String, then: (Boolean) -> Unit) {
        val account = find(id)
        val record = record(id)
        if (account == null || record == null || record.backup.isEmpty()) {
            then(false)
            return
        }
        if (!hostPortOf(account.params.serverAddress).equals(server, ignoreCase = true)) {
            // The account moved while the call was being set up: the other server answered then.
            then(true)
            return
        }
        check(id, then)
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
                if (record.backup.isNotEmpty()) remember(id, backup)
                if (backup) {
                    scheduleProbe(id, state)
                } else {
                    state.returning = false
                    state.failedReturns = 0
                }
            }
            RegistrationState.Progress, RegistrationState.Refreshing -> {
                // With the fast timeout the servers are asked as the registration goes out: a dead one is left in a moment.
                val now = SystemClock.elapsedRealtime()
                val due = now - state.checkedAt > ServerChoice.MIN_CHECK_GAP_MS && now - state.movedAt > ServerChoice.MIN_CHECK_GAP_MS
                if (record.timeoutMs > 0 && record.backup.isNotEmpty() && due && state.check == null) handler.post { check(id) }
            }
            RegistrationState.Failed -> {
                if (!AccountRoute.isServerDown(account.errorInfo.protocolCode) || holds()) return
                state.failures++
                val switch = record.backup.isNotEmpty() && AccountRoute.switchesNow(state.failures)
                if (switch && !backup && state.returning) state.failedReturns++
                if (switch) state.returning = false
                val stale = record.dns.isNotEmpty() && SystemClock.elapsedRealtime() - state.lookedUpAt > RELOOKUP_MS
                if (switch) state.moving = true
                // Not from inside this notification: the core is in the middle of the registration.
                if (switch || stale) {
                    handler.post {
                        state.moving = false
                        if (switch) move(id, !backup) else route(id, backup)
                    }
                }
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

    /** Stops whatever is pending for an account whose state is dropped. */
    private fun cancel(state: State) {
        cancelProbe(state)
        dropCheck(state)
    }

    /** Asks the first server of an account on its backup whether it is there again, and moves the account back if so. */
    private fun probe(id: String) {
        val account = find(id) ?: return
        val record = record(id) ?: return
        val state = states[id] ?: return
        // Anything else than registered on the backup takes its course in onRegistration.
        if (!onBackup(account, record) || account.state != RegistrationState.Ok) return
        val generation = state.generation
        val transport = ownTransport(id) ?: transportOf(account.params.serverAddress)
        ask(account, record, serverOf(account, record, backup = false), transport, timeoutMs = 0) { alive ->
            val current = states[id]
            if (current == null || current.generation != generation) return@ask
            // A call in progress stays where it is; the next round finds the line free.
            if (alive && core.callsNb == 0) {
                current.returning = true
                move(id, toBackup = false)
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

        /** Section holding, by `account|network`, the server the account registered on there last ([ServerChoice.Remembered]). */
        const val MEMORY = "siplink_account_servers"

        /** Both servers of an account are asked at once, a few accounts at a time. */
        const val WORKERS = 8
        const val LOOKUP_KEEP_MS = 10_000L

        /** Accounts on the same server that ask at once share one answer; one that asks a moment later asks anew. */
        const val PROBE_KEEP_MS = 1_000L

        /** A registration that keeps failing has the address of its server looked up again this often. */
        const val RELOOKUP_MS = 30_000L

        const val TAG = "SipLink.Route"
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

/** The host the core connects to for the server [address]: the address the router pinned (maddr), else its name. */
internal fun connectHostOf(address: Address): String = address.getUriParam("maddr")?.ifEmpty { null } ?: address.domain.orEmpty()

/** `user@domain` of the account with [identity], for the From of the app's own requests. */
internal fun fromOf(identity: Address?): String = identity?.let { "${it.username}@${hostPortOf(it)}" }.orEmpty()

internal fun transportOf(address: Address?): SipTransport = when (address?.transport) {
    TransportType.Tcp -> SipTransport.TCP
    TransportType.Tls -> SipTransport.TLS
    else -> SipTransport.UDP
}
