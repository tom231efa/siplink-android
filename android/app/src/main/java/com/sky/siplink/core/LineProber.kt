package com.sky.siplink.core

import android.os.Handler
import com.sky.siplink.core.logic.LineAdapt
import java.util.concurrent.Executors

/**
 * Asks the network which transports get through to a server ([LineNetwork.transports]) for the
 * adaptation of the lines, on a thread of its own: once for all lines that ask the same on the
 * same network at the same time. Core thread only; the answers come on it.
 */
internal class LineProber(private val handler: Handler) {
    private val worker = Executors.newSingleThreadExecutor()
    private val waiting = HashMap<String, ArrayList<(LineAdapt.Probe) -> Unit>>()

    /**
     * What a probe asks.
     *
     * @param host the server's name or address, [port] its port when the account names one
     * @param server the server as the account knows it, `host[:port]`
     * @param asker who asks over UDP; null: UDP is not asked
     * @param ipv6 the SIP core's IPv6 is on; [sixFirst] it goes over IPv6 first
     */
    data class Ask(val host: String, val port: Int?, val server: String, val asker: LineNetwork.Asker?, val ipv6: Boolean, val sixFirst: Boolean) {
        /** The same question on [network]: the same server, port and family, and UDP asked or not. */
        fun key(network: String?): String = listOf(network, host, port, asker != null, ipv6, sixFirst).joinToString("|")
    }

    /** Whether the probe [ask] on [network] is under way: [answer] then has its findings as well. */
    fun join(network: String?, ask: Ask, answer: (LineAdapt.Probe) -> Unit): Boolean {
        val answers = waiting[ask.key(network)] ?: return false
        answers += answer
        return true
    }

    /** Starts the probe [ask] on [network]; [answer] has its findings. */
    fun start(network: String?, ask: Ask, answer: (LineAdapt.Probe) -> Unit) {
        val key = ask.key(network)
        if (join(network, ask, answer)) return
        waiting[key] = arrayListOf(answer)
        run {
            val probe = runCatching { LineNetwork.transports(ask.host, ask.port, ask.server, ask.asker, ask.ipv6, ask.sixFirst) }
                .getOrDefault(LineNetwork.nothing(ask.asker))
            handler.post { waiting.remove(key)?.forEach { it(probe) } }
        }
    }

    /** Runs [task] on the probe thread. What goes wrong in it stays there: a question that failed has no answer. */
    fun run(task: () -> Unit) {
        worker.execute { runCatching(task) }
    }
}
