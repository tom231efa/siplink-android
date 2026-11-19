package com.sky.siplink.core.logic

import com.sky.siplink.core.logic.RoomPacing.Failure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomPacingTest {
    @Test
    fun `a few set-ups at a time`() {
        assertEquals(RoomPacing.AT_ONCE, RoomPacing.free(0))
        assertEquals(RoomPacing.AT_ONCE - 1, RoomPacing.free(1))
        assertEquals(0, RoomPacing.free(RoomPacing.AT_ONCE))
        assertEquals(0, RoomPacing.free(RoomPacing.AT_ONCE + 2))
    }

    @Test
    fun `a set-up without an answer stops holding back the next one after a while`() {
        assertTrue(RoomPacing.holdsBack(placedAt = 1_000, now = 1_000))
        assertTrue(RoomPacing.holdsBack(placedAt = 1_000, now = 1_000 + RoomPacing.SETUP_TIMEOUT_MS - 1))
        assertFalse(RoomPacing.holdsBack(placedAt = 1_000, now = 1_000 + RoomPacing.SETUP_TIMEOUT_MS))
    }

    @Test
    fun `the answers that say something about the PBX`() {
        assertEquals(Failure.LINE_FULL, RoomPacing.failureOf(503))
        assertEquals(Failure.REFUSED, RoomPacing.failureOf(403))
        assertEquals(Failure.BUSY, RoomPacing.failureOf(486))
        assertEquals(Failure.BUSY, RoomPacing.failureOf(600))
        assertEquals(Failure.UNAVAILABLE, RoomPacing.failureOf(480))
        assertEquals(Failure.MEDIA, RoomPacing.failureOf(488))
        // A wrong number, a member who declines, no answer at all: nothing to hold back for.
        for (status in listOf(0, 404, 487, 603, 408)) assertNull("$status", RoomPacing.failureOf(status))
    }

    @Test
    fun `the labels are short`() {
        assertEquals(listOf("线路已满", "PBX 拒绝", "忙", "暂时无法接通", "媒体协商失败"), Failure.entries.map { it.label })
    }

    @Test
    fun `a full line stops the calling at once`() {
        assertEquals(RoomPacing.Verdict(stop = true, label = "线路已满"), RoomPacing.afterFailure(Failure.LINE_FULL, run = 1, connected = false))
        assertEquals(RoomPacing.Verdict(stop = true, label = "线路已满"), RoomPacing.afterFailure(Failure.LINE_FULL, run = 1, connected = true))
    }

    @Test
    fun `other failures stop it once they come in a row`() {
        for (failure in listOf(Failure.REFUSED, Failure.BUSY, Failure.UNAVAILABLE, Failure.MEDIA)) {
            for (run in 1 until RoomPacing.FAILURES_TO_STOP) {
                assertFalse("$failure $run", RoomPacing.afterFailure(failure, run, connected = false).stop)
            }
            assertEquals(RoomPacing.Verdict(stop = true, label = failure.label), RoomPacing.afterFailure(failure, RoomPacing.FAILURES_TO_STOP, connected = false))
        }
    }

    @Test
    fun `busy or refused in a row while members talk is the line's limit of calls`() {
        val run = RoomPacing.FAILURES_TO_STOP
        assertEquals("线路已满", RoomPacing.afterFailure(Failure.BUSY, run, connected = true).label)
        assertEquals("线路已满", RoomPacing.afterFailure(Failure.REFUSED, run, connected = true).label)
        // Not reachable or no common codec says nothing about how many calls the line has.
        assertEquals("暂时无法接通", RoomPacing.afterFailure(Failure.UNAVAILABLE, run, connected = true).label)
        assertEquals("媒体协商失败", RoomPacing.afterFailure(Failure.MEDIA, run, connected = true).label)
    }

    @Test
    fun `retry after is respected and its absence lets the user call at once`() {
        assertEquals(0L, RoomPacing.retryAt(now = 5_000, seconds = 0))
        assertEquals(0L, RoomPacing.retryAt(now = 5_000, seconds = -1))
        assertEquals(35_000L, RoomPacing.retryAt(now = 5_000, seconds = 30))
    }

    @Test
    fun `a member that hangs up right after answering while others talk was the PBX saying the line is full`() {
        assertTrue(RoomPacing.endedLikeALimit(connectedForMs = 4_000, endedByUser = false, othersTalk = true))
        assertFalse("Talked for a while", RoomPacing.endedLikeALimit(connectedForMs = RoomPacing.SHORT_CALL_MS, endedByUser = false, othersTalk = true))
        assertFalse("The user hung up", RoomPacing.endedLikeALimit(connectedForMs = 4_000, endedByUser = true, othersTalk = true))
        assertFalse("Nobody else on the line", RoomPacing.endedLikeALimit(connectedForMs = 4_000, endedByUser = false, othersTalk = false))
    }
}
