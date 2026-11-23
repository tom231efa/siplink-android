package com.sky.siplink.core

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.sky.siplink.BuildConfig
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask

/**
 * The thread of the SIP core. The core, every manager built on it and every state they keep
 * belong to it: the SDK reports on it, its timers run on it ([handler]), and work that leaves
 * for another thread comes back to it.
 *
 * The screens reach the managers through their functions, which take the hop themselves: one
 * that returns nothing is handed to this thread ([post]) and returns at once, one that returns a
 * value runs here while its caller waits ([wait]). Both run in place on this thread, and the
 * calls of one thread reach it in the order they were made. What the core has to tell the
 * screens it posts to the main thread ([toMain]); it never waits for it, for the main thread
 * may be waiting for the core.
 *
 * Debug builds check that the core is used from here ([check]); release builds do not.
 */
object CoreThread {
    /** The looper of the core. */
    val looper: Looper = Looper.getMainLooper()

    private val queue = Handler(looper)
    private val main = Handler(Looper.getMainLooper())

    /** Whether the calling thread is the core's. */
    val isCurrent: Boolean get() = Looper.myLooper() === looper

    /** A handler of its own on the core's looper, for timers: removing its tasks touches no other. */
    fun handler(): Handler = Handler(looper)

    /** Debug builds: fails at once when [what] is used from another thread than the core's. */
    fun check(what: String) {
        if (BuildConfig.DEBUG && !isCurrent) {
            error("$what belongs to the thread of the SIP core and was used from the thread \"${Thread.currentThread().name}\"")
        }
    }

    /** Runs [block] on the core thread: in place when called there, else later, in the order of the calls. */
    fun post(block: () -> Unit) {
        if (isCurrent) block() else queue.post(block)
    }

    /**
     * Runs [block] on the core thread and returns what it returned; what it threw is thrown
     * here. The caller waits, unless it is the core thread itself. Debug builds log a wait of the
     * main thread longer than [SLOW_WAIT_MS], under [name].
     */
    fun <T> wait(name: String, block: () -> T): T {
        if (isCurrent) return block()
        val task = FutureTask(block)
        val started = SystemClock.uptimeMillis()
        queue.post(task)
        try {
            return task.get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        } finally {
            val waited = SystemClock.uptimeMillis() - started
            if (BuildConfig.DEBUG && waited > SLOW_WAIT_MS && Looper.myLooper() === Looper.getMainLooper()) {
                Log.w(TAG, "The main thread waited $waited ms for $name")
            }
        }
    }

    /** Has the main thread run [block] once it gets to it; the core never waits for that. */
    fun toMain(block: () -> Unit) {
        main.post(block)
    }

    private const val TAG = "SipLinkCoreWait"
    private const val SLOW_WAIT_MS = 50L
}

/**
 * Made on the core thread on first use, like [lazy]; a caller on another thread waits for it.
 * No lock is held while waiting: the core thread may need the value at the same time.
 */
internal fun <T : Any> coreLazy(name: String, make: () -> T): Lazy<T> = object : Lazy<T> {
    @Volatile
    private var made: T? = null

    override val value: T get() = made ?: CoreThread.wait(name) { made ?: make().also { made = it } }

    override fun isInitialized(): Boolean = made != null
}
