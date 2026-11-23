package com.sky.siplink.itest

import android.app.Instrumentation
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.sky.siplink.core.logic.AccountRules
import com.sky.siplink.core.model.CallPhase
import com.sky.siplink.core.model.RegState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.linphone.core.Factory
import org.linphone.core.LogLevel

/**
 * How long the main thread at most did not get to run a task posted to it while the SIP core
 * was busy: as the user ends a conference room with members in it, as a call rings in and is
 * answered, and as a hundred accounts register at once. The numbers go to logcat (tag [TAG])
 * and to the output of the instrumentation.
 *
 * With the core on the main thread, ending a room held it for 1.5 to 1.7 s on the test phone;
 * on its own thread for some 40 ms: the end of a room is held to [ROOM_END_MS]. The others are
 * reported only: answering a call stalled the main thread for 160 to 270 ms before and 50 to 130 ms
 * after (the call's notification and the screen are drawn there), registering 40 to 60 ms before
 * and about 12 after, and adding the accounts waits for the core as it did.
 */
class MainThreadStallTest : RoomTestBase("stall") {
    /**
     * Posts a task to the main thread every [TICK_MS] and keeps the longest time one of them
     * ran after it was due. Written on the main thread, read after [stop].
     */
    private class MainTicker {
        private val handler = Handler(Looper.getMainLooper())

        @Volatile
        private var running = false
        private var due = 0L

        @Volatile
        var longest = 0L
            private set

        private val tick = object : Runnable {
            override fun run() {
                val now = SystemClock.uptimeMillis()
                longest = maxOf(longest, now - due)
                if (!running) return
                due = now + TICK_MS
                handler.postDelayed(this, TICK_MS)
            }
        }

        fun start() {
            running = true
            handler.post {
                due = SystemClock.uptimeMillis()
                tick.run()
            }
        }

        fun stop(): Long {
            running = false
            InstrumentationRegistry.getInstrumentation().runOnMainSync { handler.removeCallbacks(tick) }
            return longest
        }
    }

    /** Runs [action] and returns the longest stall of the main thread during it, reported as [what]. */
    private fun longestStall(what: String, action: () -> Unit): Long {
        val ticker = MainTicker()
        ticker.start()
        val stall = try {
            action()
            ticker.stop()
        } catch (e: Throwable) {
            ticker.stop()
            throw e
        }
        report("$what: the main thread stalled for $stall ms at most")
        return stall
    }

    private fun report(line: String) {
        Log.i(TAG, line)
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString(Instrumentation.REPORT_KEY_STREAMRESULT, "$TAG $line\n") })
    }

    @Test
    fun endingARoomWithMembers() {
        openRoom("2501", "2502")
        val stall = longestStall("ending a room of two") {
            onMain { engine.calls.hangUp() }
            awaitNoCall()
        }
        assertTrue("Ending the room held the main thread for $stall ms", stall < ROOM_END_MS)
    }

    @Test
    fun aCallRingsAndIsAnswered() {
        register()
        longestStall("a call ringing in and answered") {
            ringing("\"Stall\" <$CALLER>")
            onMain { engine.calls.answer() }
            awaitCall("the call to be answered") { it.phase == CallPhase.CONNECTED }
            // The sound starts a moment after the answer.
            Thread.sleep(SETTLE_MS)
            onMain { engine.calls.hangUp() }
            awaitNoCall()
        }
    }

    @Test
    fun aHundredAccountsRegister() {
        // As RegistrationTest: the debug build's log of every SIP message is turned down for the burst.
        val logging = Factory.instance().loggingService
        onCore { logging.setLogLevel(LogLevel.Warning) }
        try {
            val drafts = (FIRST until FIRST + AccountRules.MAX_ACCOUNTS).map { draft(it) }
            longestStall("adding ${drafts.size} accounts") {
                assertEquals(drafts.size, onMain { engine.accounts.addAll(drafts) })
            }
            longestStall("${drafts.size} accounts registering") {
                await("all ${drafts.size} accounts to register", LONG_TIMEOUT_MS) {
                    accounts().count { it.state == RegState.OK } == drafts.size
                }
            }
        } finally {
            onCore { logging.setLogLevel(LogLevel.Message) }
        }
    }

    private companion object {
        const val TAG = "SipLinkStall"
        const val TICK_MS = 5L
        const val ROOM_END_MS = 500L
        const val SETTLE_MS = 2_000L
        const val CALLER = 2000
        const val FIRST = 1001
    }
}
