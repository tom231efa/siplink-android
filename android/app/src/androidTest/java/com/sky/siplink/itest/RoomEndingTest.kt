package com.sky.siplink.itest

import android.app.Instrumentation
import android.app.Notification
import android.app.NotificationManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.sky.siplink.core.model.CallInfo
import com.sky.siplink.core.model.CallPhase
import com.sky.siplink.service.CallNotifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The user ends a conference room: the call screen says so at once, while the SDK still takes
 * the calls down (it leaves the sound card first, which takes it a second and more), and the
 * end stays on screen for its moment from the tap, or until the room is closed when that takes
 * longer. The service and its call notification stay until the room is closed. A call that
 * waits next to the room takes the screen once the room is closed.
 */
class RoomEndingTest : RoomTestBase("room-ending") {
    /**
     * What the published call did after the user's action, in ms from it: when the end showed,
     * when the room was closed (its readings went), when the call was gone; and every snapshot seen on the way.
     * Between the end shown and the room closed: whether a call stayed ongoing for the service all the
     * time, and whether its call notification was up when looked at [NOTICE_AFTER_MS] into it (null: not looked).
     */
    private class Ending(
        val shownMs: Long,
        val closedMs: Long,
        val clearedMs: Long,
        val seen: List<CallInfo>,
        val ongoingWhileClosing: Boolean,
        val noticeWhileClosing: Boolean?,
    )

    /** Runs the user's [action] and follows the published call, to the millisecond, until it is gone. */
    private fun follow(action: () -> Unit): Ending {
        await("the room's first readings") { engine.calls.roomReadings.value != null }
        val start = SystemClock.elapsedRealtime()
        action()
        var shown = -1L
        var closed = -1L
        val seen = ArrayList<CallInfo>()
        var ongoing = true
        var notice: Boolean? = null
        while (true) {
            val now = SystemClock.elapsedRealtime() - start
            assertTrue("The call was still published ${TIMEOUT_MS}ms after the room was ended", now < TIMEOUT_MS)
            // Read before the room: the room closes before the calls stop being ongoing.
            val ongoingNow = engine.calls.hasOngoingCall
            if (closed < 0 && engine.calls.roomReadings.value == null) closed = now
            if (shown >= 0 && closed < 0) {
                ongoing = ongoing && ongoingNow
                if (notice == null && now >= shown + NOTICE_AFTER_MS) notice = callNoticeUp()
            }
            val info = call() ?: return Ending(shown, closed, now, seen, ongoing, notice)
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
        assertTrue("$what: no call was ongoing for the service before the room closed", ending.ongoingWhileClosing)
        assertEquals("$what: the call notification before the room closed", true, ending.noticeWhileClosing)
        assertFalse("$what: a call is still ongoing for the service once the screen cleared", engine.calls.hasOngoingCall)
    }

    /** Whether the service shows its notification of a call that is up. */
    private fun callNoticeUp(): Boolean = context.getSystemService(NotificationManager::class.java).activeNotifications
        .any { it.id == CallNotifier.ID_SERVICE && it.notification.category == Notification.CATEGORY_CALL }

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
        // The end is on screen first, then the PBX calls: the call reaches the app while the room
        // goes down, or just after it closed. A call that waits before the room is ended is the test above.
        awaitCall("the end of the room") { it.phase == CallPhase.ENDED }
        phoneCalls(CUSTOMER, "$APP", "tone")
        // Nothing on screen between the end and the ringing call: it came in while the room went down.
        var cleared = false
        var next: CallInfo? = null
        await("the call to ring") {
            next = call()
            if (next == null) cleared = true
            next?.phase == CallPhase.INCOMING
        }
        report("the call rang ${if (cleared) "after the end of the room was cleared" else "straight after the end of the room"}")
        val ringing = next!!
        assertNull("The ringing call shows the room", ringing.room)
        assertTrue("Another call rings: ${ringing.remoteAddress}", ringing.remoteAddress.startsWith("$CUSTOMER@"))
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

        /** Past what the service would take to drop the notification, were it told to. */
        const val NOTICE_AFTER_MS = 300L
    }
}
