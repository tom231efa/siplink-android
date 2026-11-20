package com.sky.siplink.core.logic

import com.sky.siplink.core.logic.ServerChoice.Answer.ANSWERED
import com.sky.siplink.core.logic.ServerChoice.Answer.FAILED
import com.sky.siplink.core.logic.ServerChoice.Answer.PENDING
import com.sky.siplink.core.logic.ServerChoice.Answer.SILENT
import com.sky.siplink.core.logic.ServerChoice.Move
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerChoiceTest {
    @Test
    fun `the fast timeout is off or within its bounds`() {
        assertTrue(ServerChoice.isValidTimeout(0))
        assertTrue(ServerChoice.isValidTimeout(200))
        assertTrue(ServerChoice.isValidTimeout(500))
        assertTrue(ServerChoice.isValidTimeout(10_000))
        assertFalse(ServerChoice.isValidTimeout(199))
        assertFalse(ServerChoice.isValidTimeout(10_001))
        assertFalse(ServerChoice.isValidTimeout(-1))
    }

    @Test
    fun `a remote fast timeout is held within the bounds and nothing is off`() {
        assertEquals(0, ServerChoice.fromRemote(0))
        assertEquals(0, ServerChoice.fromRemote(-5))
        assertEquals(200, ServerChoice.fromRemote(1))
        assertEquals(500, ServerChoice.fromRemote(500))
        assertEquals(10_000, ServerChoice.fromRemote(60_000))
    }

    @Test
    fun `udp is asked three times within the fast timeout and every two seconds after`() {
        assertEquals(listOf(0L, 166L, 333L, 2_000L), ServerChoice.sendTimes(500, reliable = false))
        assertEquals(4_000L, ServerChoice.waitMs(500))
        // Off: as the probes always asked, twice in four seconds.
        assertEquals(listOf(0L, 2_000L), ServerChoice.sendTimes(0, reliable = false))
        assertEquals(4_000L, ServerChoice.waitMs(0))
        // A long timeout is waited for in full.
        assertEquals(listOf(0L, 2_000L, 4_000L), ServerChoice.sendTimes(6_000, reliable = false))
        assertEquals(10_000L, ServerChoice.waitMs(10_000))
    }

    @Test
    fun `tcp and tls start a second connection halfway through the fast timeout`() {
        assertEquals(listOf(0L, 250L), ServerChoice.sendTimes(500, reliable = true))
        assertEquals(listOf(0L), ServerChoice.sendTimes(0, reliable = true))
    }

    @Test
    fun `the first server is taken when it answers`() {
        assertEquals(Move.PRIMARY, ServerChoice.next(onBackup = false, ANSWERED, PENDING, callUp = false))
        assertEquals(Move.PRIMARY, ServerChoice.next(onBackup = true, ANSWERED, ANSWERED, callUp = false))
        assertEquals(Move.PRIMARY, ServerChoice.next(onBackup = true, ANSWERED, FAILED, callUp = false))
    }

    @Test
    fun `the backup is taken once the first server is silent and the backup answers`() {
        assertEquals(Move.WAIT, ServerChoice.next(onBackup = false, PENDING, ANSWERED, callUp = false))
        assertEquals(Move.BACKUP, ServerChoice.next(onBackup = false, SILENT, ANSWERED, callUp = false))
        assertEquals(Move.BACKUP, ServerChoice.next(onBackup = false, FAILED, ANSWERED, callUp = false))
        // Its answer may take longer than the fast timeout.
        assertEquals(Move.WAIT, ServerChoice.next(onBackup = false, SILENT, PENDING, callUp = false))
    }

    @Test
    fun `a late answer of the first server still counts while the account has not moved`() {
        assertEquals(Move.WAIT, ServerChoice.next(onBackup = false, SILENT, FAILED, callUp = false))
        assertEquals(Move.PRIMARY, ServerChoice.next(onBackup = false, ANSWERED, PENDING, callUp = false))
    }

    @Test
    fun `with both silent the account stays`() {
        assertEquals(Move.STAY, ServerChoice.next(onBackup = false, FAILED, FAILED, callUp = false))
        assertEquals(Move.STAY, ServerChoice.next(onBackup = true, FAILED, FAILED, callUp = true))
    }

    @Test
    fun `a call keeps the account where it is unless that server failed`() {
        // Back to the first server only once the call is over.
        assertEquals(Move.STAY, ServerChoice.next(onBackup = true, ANSWERED, ANSWERED, callUp = true))
        assertEquals(Move.WAIT, ServerChoice.next(onBackup = true, ANSWERED, PENDING, callUp = true))
        assertEquals(Move.PRIMARY, ServerChoice.next(onBackup = true, ANSWERED, FAILED, callUp = true))
        // Away from a first server that is gone, call or not; with a call, silence past the fast timeout is not enough.
        assertEquals(Move.WAIT, ServerChoice.next(onBackup = false, SILENT, ANSWERED, callUp = true))
        assertEquals(Move.BACKUP, ServerChoice.next(onBackup = false, FAILED, ANSWERED, callUp = true))
        assertEquals(Move.WAIT, ServerChoice.next(onBackup = true, ANSWERED, SILENT, callUp = true))
        // A call out without an answer leaves on silence.
        assertEquals(Move.BACKUP, ServerChoice.next(onBackup = false, SILENT, ANSWERED, callUp = true, silentLeaves = true))
        // Staying where it is needs no exception.
        assertEquals(Move.PRIMARY, ServerChoice.next(onBackup = false, ANSWERED, FAILED, callUp = true))
    }

    @Test
    fun `the server of a network survives being stored`() {
        val remembered = ServerChoice.Remembered(backup = true, usedAt = 1_700_000_000_000)
        assertEquals(remembered, ServerChoice.decode(ServerChoice.encode(remembered)))
        val first = ServerChoice.Remembered(backup = false, usedAt = 5)
        assertEquals(first, ServerChoice.decode(ServerChoice.encode(first)))
        assertNull(ServerChoice.decode(null))
        assertNull(ServerChoice.decode("x|5"))
        assertNull(ServerChoice.decode("b|soon"))
        assertNull(ServerChoice.decode("b"))
    }

    @Test
    fun `the server of a network is written down when it changes and now and then while it stays`() {
        val now = 10 * ServerChoice.USED_AT_STEP_MS
        assertEquals(ServerChoice.Remembered(true, now), ServerChoice.remember(null, backup = true, primaryDown = true, now))
        assertEquals(ServerChoice.Remembered(false, now), ServerChoice.remember(ServerChoice.Remembered(true, now - 1), backup = false, primaryDown = false, now))
        assertNull(ServerChoice.remember(ServerChoice.Remembered(true, now - 1), backup = true, primaryDown = true, now))
        val old = now - ServerChoice.USED_AT_STEP_MS
        assertEquals(ServerChoice.Remembered(true, now), ServerChoice.remember(ServerChoice.Remembered(true, old), backup = true, primaryDown = true, now))
    }

    @Test
    fun `the backup is remembered only where a probe found the first server silent`() {
        assertNull(ServerChoice.remember(null, backup = true, primaryDown = false, now = 5))
        assertNull(ServerChoice.remember(ServerChoice.Remembered(false, 1), backup = true, primaryDown = false, now = 5))
        assertEquals(ServerChoice.Remembered(false, 5), ServerChoice.remember(null, backup = false, primaryDown = true, now = 5))
    }

    @Test
    fun `a server the account registered on here is left after silence only the second time`() {
        assertFalse(ServerChoice.leavesNow(SILENT, registeredHere = true, strikes = 0))
        assertTrue(ServerChoice.leavesNow(SILENT, registeredHere = true, strikes = 1))
        // Right after a change of network, or after a failure for all the probe's time, once is enough.
        assertTrue(ServerChoice.leavesNow(SILENT, registeredHere = false, strikes = 0))
        assertTrue(ServerChoice.leavesNow(FAILED, registeredHere = true, strikes = 0))
    }

    @Test
    fun `a failed registration switches blindly only outside a check and the quiet time after a move`() {
        val quiet = ServerChoice.MOVED_QUIET_MS
        assertTrue(ServerChoice.switchesBlindly(failures = 1, checking = false, sinceMoveMs = quiet))
        assertFalse(ServerChoice.switchesBlindly(failures = 2, checking = false, sinceMoveMs = quiet))
        assertTrue(ServerChoice.switchesBlindly(failures = 3, checking = false, sinceMoveMs = quiet))
        assertFalse(ServerChoice.switchesBlindly(failures = 1, checking = true, sinceMoveMs = quiet))
        assertFalse(ServerChoice.switchesBlindly(failures = 1, checking = false, sinceMoveMs = quiet - 1))
    }

    @Test
    fun `after returns that did not hold the first server waits out its back-off`() {
        assertTrue(ServerChoice.mayReturn(failedReturns = 0, sinceFailedReturnMs = 0))
        assertFalse(ServerChoice.mayReturn(failedReturns = 1, sinceFailedReturnMs = AccountRoute.failbackDelay(1) - 1))
        assertTrue(ServerChoice.mayReturn(failedReturns = 1, sinceFailedReturnMs = AccountRoute.failbackDelay(1)))
        assertFalse(ServerChoice.mayReturn(failedReturns = 3, sinceFailedReturnMs = AccountRoute.failbackDelay(2)))
    }

    @Test
    fun `a move counts for a call out only when a check made it for the silence of the call's server`() {
        assertTrue(ServerChoice.movedForSilence("pbx.example.com:5060", "PBX.example.com:5060", sinceMs = 1_000))
        assertFalse(ServerChoice.movedForSilence("pbx.example.com:5060", null, sinceMs = 1_000))
        assertFalse(ServerChoice.movedForSilence("pbx.example.com:5060", "sbc.example.com", sinceMs = 1_000))
        assertFalse(ServerChoice.movedForSilence("pbx.example.com:5060", "pbx.example.com:5060", sinceMs = ServerChoice.LEFT_SILENT_KEEP_MS))
    }

    @Test
    fun `the servers of networks are forgotten like what the lines learned`() {
        val day = LineAdapt.DAY_MS
        val now = 100 * day
        val used = HashMap<String, Long>()
        used[LineAdapt.key("a", "old")] = now - 31 * day
        repeat(LineAdapt.MAX_NETWORKS + 1) { i -> used[LineAdapt.key("a", "net$i")] = now - i * 1000L }
        val stale = LineAdapt.staleKeys(used, now)
        assertEquals(setOf(LineAdapt.key("a", "old"), LineAdapt.key("a", "net${LineAdapt.MAX_NETWORKS}")), stale)
    }

    @Test
    fun `a call out is looked at after the fast timeout and two seconds at least`() {
        assertNull(ServerChoice.retryAfter(0))
        assertEquals(2_000L, ServerChoice.retryAfter(500))
        assertEquals(2_000L, ServerChoice.retryAfter(200))
        assertEquals(5_000L, ServerChoice.retryAfter(5_000))
    }

    @Test
    fun `only a call of its own without an answer is placed again, once`() {
        assertTrue(ServerChoice.mayRetry(inRoom = false, consultation = false, retried = false, waiting = true))
        assertFalse(ServerChoice.mayRetry(inRoom = true, consultation = false, retried = false, waiting = true))
        assertFalse(ServerChoice.mayRetry(inRoom = false, consultation = true, retried = false, waiting = true))
        assertFalse(ServerChoice.mayRetry(inRoom = false, consultation = false, retried = true, waiting = true))
        assertFalse(ServerChoice.mayRetry(inRoom = false, consultation = false, retried = false, waiting = false))
    }
}
