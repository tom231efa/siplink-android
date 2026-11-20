package com.sky.siplink.core

import android.os.Handler
import android.os.SystemClock
import java.util.concurrent.ExecutorService

/**
 * Asks a question that takes time on a worker thread of [pool], once for everybody who asks the
 * same at about the same time: a hundred accounts on one server need one answer. Main thread
 * only; the answers come on [handler].
 *
 * @param keepMs how long an answer serves those who ask later
 * @param failed the answer when asking went wrong
 */
internal class SharedAsk<T>(
    private val handler: Handler,
    private val pool: ExecutorService,
    private val keepMs: Long,
    private val failed: T,
) {
    private class Answer<T>(val value: T, val at: Long)

    private class Asking<T> {
        val then = ArrayList<(T) -> Unit>()
        val onStart = ArrayList<() -> Unit>()
        var started = false
    }

    private val asking = HashMap<String, Asking<T>>()
    private val answers = HashMap<String, Answer<T>>()

    /**
     * Has [then] told the answer to [question], which it calls with a function to call as the
     * question really goes out (after a name was looked up, say): [onStart] hears of that.
     *
     * @param fresh an answer kept from before does not count, and this one is not kept
     */
    fun ask(key: String, question: (started: () -> Unit) -> T, onStart: (() -> Unit)? = null, fresh: Boolean = false, then: (T) -> Unit) {
        val known = answers[key]
        if (!fresh && known != null && SystemClock.elapsedRealtime() - known.at < keepMs) {
            onStart?.invoke()
            then(known.value)
            return
        }
        asking[key]?.let { running ->
            running.then += then
            onStart?.let { if (running.started) it() else running.onStart += it }
            return
        }
        val running = Asking<T>()
        running.then += then
        onStart?.let(running.onStart::add)
        asking[key] = running
        val started = {
            handler.post {
                running.started = true
                running.onStart.toList().forEach { it() }
                running.onStart.clear()
            }
            Unit
        }
        pool.execute {
            val value = runCatching { question(started) }.getOrDefault(failed)
            handler.post {
                // A fresh question is asked under a key of its own: its answer serves nobody later.
                val now = SystemClock.elapsedRealtime()
                // What is too old to serve anyone goes, with the answers of networks left behind.
                answers.values.removeAll { now - it.at >= keepMs }
                if (!fresh) answers[key] = Answer(value, now)
                if (asking[key] === running) asking.remove(key)
                // A question that went wrong before it went out: those waiting for its start hear of it now.
                if (!running.started) {
                    running.started = true
                    running.onStart.toList().forEach { it() }
                    running.onStart.clear()
                }
                running.then.forEach { it(value) }
            }
        }
    }
}
