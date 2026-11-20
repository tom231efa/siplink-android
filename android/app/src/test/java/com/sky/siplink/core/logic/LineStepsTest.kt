package com.sky.siplink.core.logic

import com.sky.siplink.core.logic.LineAdapt.Leave
import com.sky.siplink.core.logic.LineAdapt.Probe
import com.sky.siplink.core.logic.LineAdapt.Record
import com.sky.siplink.core.logic.LineSteps.Failure
import com.sky.siplink.core.logic.LineSteps.Own
import com.sky.siplink.core.logic.MediaAdapt.Ice
import com.sky.siplink.core.model.SipTransport.TCP
import com.sky.siplink.core.model.SipTransport.TLS
import com.sky.siplink.core.model.SipTransport.UDP
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LineStepsTest {
    private val day = LineAdapt.DAY_MS
    private val now = 100 * day
    private val blocked = Probe(udp = false, udpLarge = null, tcp = true, tls = true)
    private val open = Probe(udp = true, udpLarge = true, tcp = true, tls = true)
    private val down = Probe(udp = false, udpLarge = null, tcp = false, tls = false)

    @Test
    fun `own settings survive being stored`() {
        val own = Own(UDP, 3600, outbound = true)
        assertEquals(own, LineSteps.decodeOwn(LineSteps.encodeOwn(own)))
        assertNull(LineSteps.decodeOwn("UDP|x|1"))
        assertNull(LineSteps.decodeOwn(null))
    }

    @Test
    fun `no answer is confirmed first, a refusal ends a test at once`() {
        assertEquals(Failure.CONFIRM, LineSteps.failure(408, holds = false, onTrial = false, proven = true))
        assertEquals(Failure.CONFIRM, LineSteps.failure(503, holds = false, onTrial = true, proven = true))
        assertEquals(Failure.END_TRIAL, LineSteps.failure(403, holds = false, onTrial = true, proven = true))
        assertEquals(Failure.END_TRIAL, LineSteps.failure(488, holds = false, onTrial = true, proven = true))
        // On its own transport a refusal is the account's own business: wrong password, unknown user.
        assertEquals(Failure.NOTHING, LineSteps.failure(401, holds = false, onTrial = false, proven = true))
        // Behind a login page nothing counts.
        assertEquals(Failure.NOTHING, LineSteps.failure(408, holds = true, onTrial = true, proven = true))
        assertEquals(Failure.NOTHING, LineSteps.failure(403, holds = true, onTrial = true, proven = true))
    }

    @Test
    fun `a challenge on test refuses the transport only for a login that registered before`() {
        // The login registered somewhere since it last changed: the transport is refused (an Asterisk ACL answers 401).
        assertEquals(Failure.END_TRIAL, LineSteps.failure(401, holds = false, onTrial = true, proven = true))
        assertEquals(Failure.END_TRIAL, LineSteps.failure(407, holds = false, onTrial = true, proven = true))
        // A new account, or a changed password: the login is wrong, the line stays and shows it.
        assertEquals(Failure.NOTHING, LineSteps.failure(401, holds = false, onTrial = true, proven = false))
        assertEquals(Failure.NOTHING, LineSteps.failure(407, holds = false, onTrial = true, proven = false))
        // Other refusals end the test whatever the login.
        assertEquals(Failure.END_TRIAL, LineSteps.failure(403, holds = false, onTrial = true, proven = false))
        assertEquals(Failure.CONFIRM, LineSteps.failure(408, holds = false, onTrial = true, proven = false))
    }

    @Test
    fun `a line whose udp gets no answer moves on to what the probe found open`() {
        val change = LineSteps.afterFailure(Record(), UDP, UDP, null, blocked, refused = false, now = now)!!
        assertEquals(TCP, change.trial)
        assertEquals(TCP, change.record.transport)
        assertEquals(Leave.NO_ANSWER, change.record.left)
        assertEquals(now + day, change.record.retestAt)
        val large = LineSteps.afterFailure(Record(), UDP, UDP, null, open.copy(udpLarge = false), refused = false, now = now)!!
        assertEquals(Leave.LARGE_LOST, large.record.left)
    }

    @Test
    fun `a server that is down changes nothing`() {
        assertNull(LineSteps.afterFailure(Record(), UDP, UDP, null, down, refused = false, now = now))
        assertNull(LineSteps.afterFailure(Record(transport = TCP), UDP, TCP, TCP, down, refused = false, now = now))
        // An account set to TCP or TLS is never changed.
        assertNull(LineSteps.afterFailure(Record(), TCP, TCP, null, blocked, refused = false, now = now))
    }

    @Test
    fun `a transport on test that the server refused ends its test, on to the next or back to the own one`() {
        val onTcp = Record(transport = TCP, left = Leave.NO_ANSWER)
        // Refused over TCP while TLS is open: TLS is next.
        val next = LineSteps.afterFailure(onTcp, UDP, TCP, TCP, blocked, refused = true, now = now)!!
        assertEquals(TLS, next.trial)
        assertEquals(setOf(TCP), next.record.failed)
        assertEquals(Leave.NO_ANSWER, next.record.left)
        // Nothing left: back on its own transport, with TCP not tried here again.
        val back = LineSteps.afterFailure(onTcp, UDP, TCP, TCP, blocked.copy(tls = false), refused = true, now = now)!!
        assertNull(back.trial)
        assertNull(back.record.transport)
        assertNull(back.record.left)
        assertEquals(setOf(TCP), back.record.failed)
        assertEquals(now + day, back.record.failedUntil)
        // Refused, the transport counts as open even where the probe found nothing: the server answered on it.
        val refusedEverywhere = LineSteps.afterFailure(Record(transport = TLS, failed = setOf(TCP), failedUntil = now + day), UDP, TLS, TLS, down, refused = true, now = now)!!
        assertNull(refusedEverywhere.record.transport)
        assertEquals(setOf(TCP, TLS), refusedEverywhere.record.failed)
    }

    @Test
    fun `a transport on test that is closed, with udp not known, sends the line back to its own one`() {
        val unknown = Probe(udp = null, udpLarge = null, tcp = true, tls = false)
        val back = LineSteps.afterFailure(Record(transport = TLS, registered = true), UDP, TLS, TLS, unknown, refused = false, now = now)!!
        assertNull(back.trial)
        assertNull(back.record.transport)
        assertFalse(back.record.registered)
        assertEquals(emptySet<Any>(), back.record.failed)
    }

    @Test
    fun `a line settled on its transport keeps it while that is open, whatever the server answers`() {
        val settled = Record(transport = TCP, registered = true, retestAt = now + day)
        // 503 for longer than the confirmation wait, the TCP port open: the server's trouble.
        assertNull(LineSteps.afterFailure(settled, UDP, TCP, TCP, blocked, refused = false, now = now))
        // TCP closed: on to what is open, without marking TCP.
        val closed = LineSteps.afterFailure(settled, UDP, TCP, TCP, blocked.copy(tcp = false), refused = false, now = now)!!
        assertEquals(TLS, closed.trial)
        assertEquals(emptySet<Any>(), closed.record.failed)
        // Not settled there yet: an open transport that does not register is marked as before.
        val onTest = LineSteps.afterFailure(settled.copy(registered = false), UDP, TCP, TCP, blocked, refused = false, now = now)!!
        assertEquals(setOf(TCP), onTest.record.failed)
    }

    @Test
    fun `a transport refused again waits longer each time, up to a week`() {
        val tcpOnly = blocked.copy(tls = false)
        var record = Record(transport = TCP)
        var at = now
        val waits = (1..5).map {
            val back = LineSteps.afterFailure(record, UDP, TCP, TCP, tcpOnly, refused = true, now = at)!!
            assertNull(back.record.transport)
            val wait = back.record.failedUntil - at
            // Its wait over, UDP still gets no answer: TCP again, which refuses again.
            at = back.record.failedUntil
            record = LineSteps.afterFailure(back.record, UDP, UDP, null, tcpOnly, refused = false, now = at)!!.record
            assertEquals(TCP, record.transport)
            wait
        }
        assertEquals(listOf(day, 2 * day, 4 * day, 7 * day, 7 * day), waits)
        // A new login starts over.
        assertEquals(emptySet<Any>(), LineSteps.credentialsChanged(record).failed)
    }

    @Test
    fun `a transport that did not register is tried again once its time is over`() {
        val refused = LineSteps.afterFailure(Record(transport = TCP), UDP, TCP, TCP, blocked.copy(tls = false), refused = true, now = now)!!
        assertEquals(setOf(TCP), LineAdapt.failedAt(refused.record, now + day - 1))
        assertEquals(emptySet<Any>(), LineAdapt.failedAt(refused.record, now + day))
        // Before then UDP that gets no answer finds nothing to try; after it, TCP again.
        assertNull(LineSteps.afterFailure(refused.record, UDP, UDP, null, blocked.copy(tls = false), refused = false, now = now + 1))
        val later = LineSteps.afterFailure(refused.record, UDP, UDP, null, blocked.copy(tls = false), refused = false, now = now + day)!!
        assertEquals(TCP, later.trial)
        // Still known, past its wait: refused once more, it waits longer.
        assertEquals(setOf(TCP), later.record.failed)
        assertEquals(emptySet<Any>(), LineAdapt.failedAt(later.record, now + day))
        // A new password: what was refused may register now.
        val changed = LineSteps.credentialsChanged(refused.record)
        assertEquals(emptySet<Any>(), changed.failed)
        assertEquals(0L, changed.failedUntil)
    }

    @Test
    fun `a line is on test until it registered on its transport, then a refusal is the account's business`() {
        // Just moved, or back on its own transport for a test.
        assertTrue(LineSteps.onTrial(TCP, null, TCP, UDP, Record(transport = TCP)))
        assertTrue(LineSteps.onTrial(null, TCP, UDP, UDP, Record()))
        // Found on TCP at a start, never registered there.
        assertTrue(LineSteps.onTrial(null, null, TCP, UDP, Record(transport = TCP)))
        assertTrue(LineSteps.onTrial(null, null, TCP, UDP, null))
        // Registered there once: settled, a 401 after a password change on the PBX changes nothing.
        val settled = LineSteps.registeredOn(Record(transport = TCP), TCP)
        assertTrue(settled.registered)
        assertFalse(LineSteps.onTrial(null, null, TCP, UDP, settled))
        assertEquals(Failure.NOTHING, LineSteps.failure(401, holds = false, onTrial = false, proven = true))
        // On its own transport nothing is marked.
        assertFalse(LineSteps.registeredOn(Record(), UDP).registered)
        assertFalse(LineSteps.onTrial(null, null, UDP, UDP, null))
        // The mark goes with the transport: moved on (TCP closed now), the line is on test again.
        val moved = LineSteps.afterFailure(settled, UDP, TCP, TCP, blocked.copy(tcp = false), refused = false, now = now)!!
        assertEquals(TLS, moved.record.transport)
        assertFalse(moved.record.registered)
    }

    @Test
    fun `a transport on test that got no answer either is only marked where it is open`() {
        val closedTcp = LineSteps.afterFailure(Record(transport = TCP), UDP, TCP, TCP, open.copy(tcp = false, tls = false), refused = false, now = now)!!
        assertEquals(emptySet<Any>(), closedTcp.record.failed)
        assertNull(closedTcp.record.transport)
        val openTcp = LineSteps.afterFailure(Record(transport = TCP), UDP, TCP, TCP, blocked, refused = false, now = now)!!
        assertEquals(setOf(TCP), openTcp.record.failed)
        assertEquals(TLS, openTcp.trial)
    }

    @Test
    fun `a test of the own transport that fails goes back to what worked, with a longer wait`() {
        val back = LineSteps.revertFailed(Record(transport = null, reverts = 1, backAt = now - 10), TCP, now)
        assertEquals(TCP, back.transport)
        assertTrue(back.registered)
        assertEquals(2, back.reverts)
        assertEquals(now + 4 * day, back.retestAt)
        assertEquals(0L, back.backAt)
        // To TLS, where it went because nothing else got through, a week.
        assertEquals(now + 7 * day, LineSteps.revertFailed(Record(), TLS, now).retestAt)
    }

    @Test
    fun `the own transport is tested again when it is time, between calls, from tls after a week`() {
        val onTcp = Record(transport = TCP, retestAt = now)
        assertTrue(LineSteps.retestDue(onTcp, now, calls = 0))
        assertFalse(LineSteps.retestDue(onTcp, now - 1, calls = 0))
        assertFalse(LineSteps.retestDue(onTcp, now, calls = 1))
        assertFalse(LineSteps.retestDue(Record(), now, calls = 0))
        // An account set to UDP registers in clear anyway: its line on TLS asks over UDP as well.
        assertTrue(LineSteps.retestDue(Record(transport = TLS, retestAt = now), now, calls = 0))
        val toTls = LineSteps.afterFailure(Record(), UDP, UDP, null, blocked.copy(tcp = false), refused = false, now = now)!!
        assertEquals(TLS, toTls.record.transport)
        assertEquals(now + 7 * day, toTls.record.retestAt)
        assertEquals(now + 7 * day, LineSteps.afterRetest(toTls.record, blocked, calls = 0, now = now).first.retestAt)
    }

    @Test
    fun `a retest goes back where udp gets through, else waits again`() {
        val onTcp = Record(transport = TCP, reverts = 1, left = Leave.NO_ANSWER)
        val (back, goes) = LineSteps.afterRetest(onTcp, open, calls = 0, now = now)
        assertTrue(goes)
        assertNull(back.transport)
        assertEquals(0L, back.backAt)
        val (waits, stays) = LineSteps.afterRetest(onTcp, blocked, calls = 0, now = now)
        assertFalse(stays)
        assertEquals(TCP, waits.transport)
        assertEquals(now + 2 * day, waits.retestAt)
        assertFalse(LineSteps.afterRetest(onTcp, open, calls = 1, now = now).second)
        // Once back and registered, what was learned no longer applies.
        val held = LineSteps.returned(back.copy(failed = setOf(TLS)))
        assertEquals(Record(), held)
    }

    @Test
    fun `a line that left udp because the nat forgot it is judged by the day that follows its return`() {
        val left = LineSteps.leaveUdp(Record(expires = 300, lost = listOf(now - 10)), UDP, open, Leave.NAT_FORGETS, now)!!
        assertEquals(TCP, left.trial)
        assertEquals(Leave.NAT_FORGETS, left.record.left)
        assertNull(left.record.expires)
        assertTrue(left.record.lost.isEmpty())
        assertEquals(0, left.record.reverts)
        // A probe cannot tell whether the NAT still forgets: back on UDP once it answers, and watched.
        val due = now + day
        val (back, goes) = LineSteps.afterRetest(left.record, open, calls = 0, now = due)
        assertTrue(goes)
        assertEquals(due, back.backAt)
        // At the short interval at once: the NAT that forgot it within the long one is not given the chance.
        assertEquals(LineAdapt.SHORT_EXPIRES, back.expires)
        val registered = LineSteps.returned(back)
        assertEquals(Leave.NAT_FORGETS, registered.left)
        assertEquals(due, registered.backAt)
        // It forgets the line again within the day: off UDP once more, and a longer wait.
        val again = LineSteps.leaveUdp(registered.copy(lost = listOf(due + 1000, due + 2000)), UDP, open, Leave.NAT_FORGETS, due + 3000)!!
        assertEquals(1, again.record.reverts)
        assertEquals(0L, again.record.backAt)
        assertEquals(due + 3000 + 2 * day, again.record.retestAt)
        // It holds a day without a loss: forgotten.
        assertEquals(registered, LineSteps.held(registered, due + day - 1))
        val settled = LineSteps.held(registered, due + day)
        assertNull(settled.left)
        assertEquals(0L, settled.backAt)
        assertEquals(0, settled.reverts)
        // A loss since the return keeps it watched.
        val lossy = registered.copy(lost = listOf(due + 10))
        assertEquals(lossy, LineSteps.held(lossy, due + day))
    }

    @Test
    fun `large packets lost move a line on udp only where something else is open`() {
        val change = LineSteps.leaveUdp(Record(), UDP, open.copy(udpLarge = false), Leave.LARGE_LOST, now)!!
        assertEquals(TCP, change.record.transport)
        assertEquals(Leave.LARGE_LOST, change.record.left)
        assertNull(LineSteps.leaveUdp(Record(), UDP, open.copy(udpLarge = false, tcp = false, tls = false), Leave.LARGE_LOST, now))
        assertNull(LineSteps.leaveUdp(Record(), TCP, open, Leave.LARGE_LOST, now))
    }

    @Test
    fun `a server is asked at most every half minute for one line`() {
        assertEquals(0L, LineSteps.probeWait(null, 5))
        assertEquals(LineSteps.MIN_PROBE_GAP_MS - 1_000, LineSteps.probeWait(10_000, 11_000))
        assertEquals(0L, LineSteps.probeWait(10_000, 10_000 + LineSteps.MIN_PROBE_GAP_MS))
    }

    @Test
    fun `a line runs on what is remembered for it, and on its own settings without a record`() {
        val own = Own(UDP, 3600, outbound = false)
        val record = Record(transport = TCP, expires = 300, ice = Ice.TRIAL)
        val adapted = LineSteps.target(own, record, "stun.example.com")
        assertEquals(LineSteps.Target(TCP, 300, true, "stun.example.com"), adapted)
        assertTrue(adapted.adapted(own))
        // Switched off, or forgotten: the own settings.
        val plain = LineSteps.target(own, null, "stun.example.com")
        assertEquals(LineSteps.Target(UDP, 3600, false, null), plain)
        assertFalse(plain.adapted(own))
        // ICE needs a STUN server that can be one, and a trial or a keeper.
        assertNull(LineSteps.target(own, record, "").ice)
        assertNull(LineSteps.target(own, record, "stun example").ice)
        assertNull(LineSteps.target(own, record.copy(ice = Ice.REFUSED), "stun.example.com").ice)
        assertEquals("stun.example.com:3479", LineSteps.target(own, record.copy(ice = Ice.ON), " stun.example.com:3479 ").ice)
        // An account set to TCP keeps it; a longer interval than its own is not taken.
        assertEquals(TCP, LineSteps.target(own.copy(transport = TCP), record.copy(transport = TLS), "").transport)
        assertEquals(3600, LineSteps.target(own, Record(expires = 7200), "").expires)
    }

    @Test
    fun `a line blocked on udp walks to tcp, is refused there, and goes back to its own transport`() {
        // UDP gets no answer: confirmed, then on to TCP.
        assertEquals(Failure.CONFIRM, LineSteps.failure(408, holds = false, onTrial = false, proven = true))
        val toTcp = LineSteps.afterFailure(Record(), UDP, UDP, null, blocked.copy(tls = false), refused = false, now = now)!!
        assertEquals(TCP, toTcp.trial)
        // TCP refuses the registration (403): the test ends at once, and nothing else is open.
        assertEquals(Failure.END_TRIAL, LineSteps.failure(403, holds = false, onTrial = true, proven = true))
        val back = LineSteps.afterFailure(toTcp.record, UDP, TCP, toTcp.trial, blocked.copy(tls = false), refused = true, now = now + 1)!!
        assertNull(back.trial)
        assertNull(back.record.transport)
        // Still no answer over UDP: nothing more to try, nothing changes.
        assertNull(LineSteps.afterFailure(back.record, UDP, UDP, null, blocked.copy(tls = false), refused = false, now = now + 2))
        // Nothing stays in effect that would put it back on TCP.
        assertEquals(LineSteps.Target(UDP, 3600, false, null), LineSteps.target(Own(UDP, 3600, false), back.record, ""))
    }
}
