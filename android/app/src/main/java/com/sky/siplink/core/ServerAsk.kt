package com.sky.siplink.core

import android.os.Handler
import com.sky.siplink.core.logic.AccountRoute
import com.sky.siplink.core.logic.ServerChoice
import com.sky.siplink.core.logic.ServerChoice.Answer
import com.sky.siplink.core.model.SipTransport
import org.linphone.core.Account
import org.linphone.core.Core
import java.util.concurrent.Executors

/**
 * Asks the servers of the accounts whether they answer ([LineNetwork.answers]), for the router.
 * Probes with the fast timeout run on threads of their own: they never wait behind probes of
 * servers that do not answer. Main thread only; the answers come on [handler].
 *
 * @param network the network the phone is on ([com.sky.siplink.core.logic.NetworkKey]); null: none
 */
internal class ServerAsk(private val core: Core, private val handler: Handler, private val network: () -> String?) {
    private val probes = SharedAsk(handler, Executors.newFixedThreadPool(WORKERS), KEEP_MS, failed = false)
    private val fastProbes = SharedAsk(handler, Executors.newFixedThreadPool(WORKERS), KEEP_MS, failed = false)
    private var freshAsks = 0

    /**
     * Asks whether [server] answers over [transport], for [account], with the fast timeout
     * [timeoutMs]; [then] has the answer, [onStart] hears when the first request goes out.
     *
     * @param fresh a question of its own: no answer of a moment ago, and no question under way, serves it
     */
    fun ask(
        account: Account,
        record: AccountRoute.Record,
        server: String,
        transport: SipTransport,
        timeoutMs: Int,
        fresh: Boolean = false,
        onStart: (() -> Unit)? = null,
        then: (Boolean) -> Unit,
    ) {
        val (host, port) = AccountRoute.split(server)
        val lookup = record.dns.isNotEmpty() && !AccountRoute.isAddress(host)
        val from = fromOf(account.params.identityAddress)
        val userAgent = core.userAgent
        val shared = if (timeoutMs > 0) fastProbes else probes
        val key = "${network()}|$server/$transport@${record.dns}|$timeoutMs" + if (fresh) "|${++freshAsks}" else ""
        shared.ask(key, { started ->
            val target = if (lookup) {
                val (dnsAddress, dnsPort) = AccountRoute.dnsServer(record.dns)
                LineNetwork.resolve(host, dnsAddress, dnsPort).firstOrNull()
            } else {
                host
            }
            target != null && LineNetwork.answers(target, port ?: AccountRoute.defaultPort(transport), transport, server, from, userAgent, timeoutMs, started)
        }, onStart, fresh, then)
    }

    /**
     * Asks [server] as [ask] does and judges the answer by the fast timeout [timeoutMs], as a
     * check of both servers does: [Answer.ANSWERED] within the time from the first request,
     * [Answer.SILENT] without an answer in that time, [Answer.FAILED] without one at all, or
     * when no request went out within [ServerChoice.PROBE_WAIT_MS] (a name that is not found).
     * Without a fast timeout only the probe's own time counts.
     */
    fun judge(account: Account, record: AccountRoute.Record, server: String, transport: SipTransport, timeoutMs: Int, fresh: Boolean, then: (Answer) -> Unit) {
        var judged = false
        fun report(answer: Answer) {
            if (judged) return
            judged = true
            then(answer)
        }
        val unasked = Runnable { report(Answer.FAILED) }
        handler.postDelayed(unasked, ServerChoice.PROBE_WAIT_MS)
        ask(account, record, server, transport, timeoutMs, fresh, onStart = {
            handler.removeCallbacks(unasked)
            if (timeoutMs > 0) handler.postDelayed({ report(Answer.SILENT) }, timeoutMs.toLong())
        }) { alive ->
            handler.removeCallbacks(unasked)
            report(if (alive) Answer.ANSWERED else Answer.FAILED)
        }
    }

    private companion object {
        /** Both servers of an account are asked at once, a few accounts at a time. */
        const val WORKERS = 8

        /** Accounts on the same server that ask at once share one answer; one that asks a moment later asks anew. */
        const val KEEP_MS = 1_000L
    }
}
