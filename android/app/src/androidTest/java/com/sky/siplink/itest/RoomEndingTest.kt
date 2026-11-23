package com.sky.siplink.itest

import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.sky.siplink.core.model.CallInfo
import com.sky.siplink.core.model.CallPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The user ends a conference room: the call screen says so at once, while the SDK still takes
 * the calls down (it leaves the sound card first, which takes it a second and more), and the
 * end stays on screen for its moment from the tap, or until the room is closed when that takes
 * longer. A call that waits next to the room takes the screen once the room is closed.
 */
class RoomEndingTest : RoomTestBase("room-ending") {
    /**
     * What the published call did after the user's action, in ms from it: when the end showed,
     * when the room was closed (its readings went), when the call was gone; and every snapshot seen on the way.
     */
    private class Ending(val shownMs: Long, val closedMs: Long, val clearedMs: Long, val seen: List<CallInfo>)

    /** Runs the user's [action] and follows the published call, to the millisecond, until it is gone. */
    private fun follow(action: () -> Unit): Ending {
        await("the room's first readings") { engine.calls.roomReadings.value != null }
        val start = SystemClock.elapsedRealtime()
        action()
        var shown = -1L
        var closed = -1L
        val seen = ArrayList<CallInfo>()
        while (true) {
            val now = SystemClock.elapsedRealtime() - start
            assertTrue("The call was still published ${TIMEOUT_MS}ms after the room was ended", now < TIMEOUT_MS)
            if (closed < 0 && engine.calls.roomReadings.value == null) closed = now
            val info = call() ?: return Ending(shown, closed, now, seen)
            if (seen.lastOrNull() !== info) seen += info
            if (shown < 0 && info.phase == CallPhase.ENDED) shown = now
            Thread.sleep(1)
        }
    }

    /** The end showed within [SHOWN_MS], stayed as it was until it went, and went when it should. */
    private fun assertEndedAtOnce(what: String, ending: Ending) {
        report("$what: the end showed after ${ending.shownMs} ms, the room closed after ${ending.closedMs} ms, the screen cleared after ${ending.clearedMs} ms")
        assertTrue("$what: the end showed only after ${ending.shownMs} ms", ending.shownMs in 0 until SHOWN_MS)
        val ended = ending.seen.dropWhile { it.phase != CallPhase.ENDED }
        assertNull("$what: the end shows the room", ended.first().room)
        assertEquals("$what: the room came back, or the end changed what it says: $ended", 1, ended.distinctBy { it.phase to it.endReason }.size)
        val due = maxOf(ending.shownMs + END_LINGER_MS, ending.closedMs)
        assertTrue("$what: the screen cleared after ${ending.clearedMs} ms, due after $due ms", ending.clearedMs in (due - SLACK_MS)..(due + SLACK_MS))
    }

    private fun report(line: String) {
        Log.i(TAG, line)
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString(Instrumentation.REPORT_KEY_STREAMRESULT, "$TAG $line\n") })
    }

    @Test
    fun endingTheRoomShowsItsEndAtOnce() {
        openRoom("2501", "2502")
        assertEndedAtOnce("ending a room of two", follow { onMain { engine.calls.hangUp() } })
    }

    @Test
    fun hangingUpTheLastMemberShowsTheEndAtOnce() {
        openRoom("2501")
        val member = room()!!.members.single()
        assertEndedAtOnce("hanging up the last member", follow { onMain { engine.calls.hangUpMember(member.id) } })
    }

    @Test
    fun aWaitingCallTakesTheScreenOnceTheRoomIsClosed() {
        openRoom("2501", "2502")
        phoneCalls(CUSTOMER, "$APP", "tone")
        awaitCall("the call to wait") { it.waitingCall != null }
        onMain { engine.calls.hangUp() }
        val next = awaitCall("the waiting call to ring on its own") { it.room == null && it.phase != CallPhase.ENDED }
        assertEquals(CallPhase.INCOMING, next.phase)
        assertTrue("Another call took the screen: ${next.remoteAddress}", next.remoteAddress.startsWith("$CUSTOMER@"))
        onMain { engine.calls.decline() }
        awaitNoCall()
    }

    @Test
    fun aCallThatComesInAsTheRoomGoesDownRings() {
        openRoom("2501", "2502")
        onMain { engine.calls.hangUp() }
        // The PBX calls at once: the call reaches the app while it leaves the sound card, or just after.
        phoneCalls(CUSTOMER, "$APP", "tone")
        awaitCall("the end of the room") { it.phase == CallPhase.ENDED }
        val next = awaitCall("the call to ring") { it.phase == CallPhase.INCOMING }
        assertNull("The ringing call shows the room", next.room)
        assertTrue("Another call rings: ${next.remoteAddress}", next.remoteAddress.startsWith("$CUSTOMER@"))
        onMain { engine.calls.decline() }
        awaitNoCall()
    }

    private companion object {
        const val TAG = "SipLinkRoomEnd"

        /** The SDK takes a second and more to leave the sound card; the end shows long before. */
        const val SHOWN_MS = 500L

        /** How long an ended call stays on screen (CallFollower.END_LINGER_MS). */
        const val END_LINGER_MS = 1400L

        /** What the clock of the screen and the polling here may be off by. */
        const val SLACK_MS = 300L
    }
}
