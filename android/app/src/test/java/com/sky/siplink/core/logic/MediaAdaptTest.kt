package com.sky.siplink.core.logic

import com.sky.siplink.core.logic.MediaAdapt.Ice
import com.sky.siplink.core.model.MediaFlow
import com.sky.siplink.core.model.NatKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaAdaptTest {
    @Test
    fun `a stun server is a host with a port or the usual one`() {
        assertEquals("stun.example.com" to 3478, MediaAdapt.stunServer("stun.example.com"))
        assertEquals("192.0.2.7" to 19302, MediaAdapt.stunServer(" 192.0.2.7:19302 "))
        assertEquals("2001:db8::1" to 3478, MediaAdapt.stunServer("[2001:db8::1]"))
        assertEquals("2001:db8::1" to 3479, MediaAdapt.stunServer("[2001:db8::1]:3479"))
        assertNull(MediaAdapt.stunServer(""))
        assertNull(MediaAdapt.stunServer("stun:stun.example.com"))
        assertNull(MediaAdapt.stunServer("stun.example.com:0"))
        assertNull(MediaAdapt.stunServer("two words"))
        // The setting itself, as the platform checks it: no blanks around it.
        assertTrue(MediaAdapt.isValidStunServer(""))
        assertTrue(MediaAdapt.isValidStunServer("stun.example.com:3478"))
        assertFalse(MediaAdapt.isValidStunServer(" stun.example.com"))
        assertFalse(MediaAdapt.isValidStunServer("stun.example.com:70000"))
    }

    @Test
    fun `ice is tried where the sound went one way behind a nat stun sees through`() {
        assertTrue(MediaAdapt.tryIce(MediaFlow.SEND_ONLY, NatKind.PRESENT, stunSet = true, ice = Ice.NONE))
        assertTrue(MediaAdapt.tryIce(MediaFlow.RECEIVE_ONLY, NatKind.CARRIER, stunSet = true, ice = Ice.NONE))
        // Sound both ways, none at all, or no verdict: nothing to mend.
        assertFalse(MediaAdapt.tryIce(MediaFlow.BOTH, NatKind.PRESENT, stunSet = true, ice = Ice.NONE))
        assertFalse(MediaAdapt.tryIce(MediaFlow.NONE, NatKind.PRESENT, stunSet = true, ice = Ice.NONE))
        assertFalse(MediaAdapt.tryIce(null, NatKind.PRESENT, stunSet = true, ice = Ice.NONE))
        // No NAT, one that maps each destination anew, or none seen: no.
        assertFalse(MediaAdapt.tryIce(MediaFlow.SEND_ONLY, NatKind.NONE, stunSet = true, ice = Ice.NONE))
        assertFalse(MediaAdapt.tryIce(MediaFlow.SEND_ONLY, NatKind.SYMMETRIC, stunSet = true, ice = Ice.NONE))
        assertFalse(MediaAdapt.tryIce(MediaFlow.SEND_ONLY, NatKind.UNKNOWN, stunSet = true, ice = Ice.NONE))
        assertFalse(MediaAdapt.tryIce(MediaFlow.SEND_ONLY, NatKind.PRESENT, stunSet = false, ice = Ice.NONE))
        // Once tried here, not again.
        assertFalse(MediaAdapt.tryIce(MediaFlow.SEND_ONLY, NatKind.PRESENT, stunSet = true, ice = Ice.REFUSED))
        assertFalse(MediaAdapt.tryIce(MediaFlow.SEND_ONLY, NatKind.PRESENT, stunSet = true, ice = Ice.ON))
    }

    private val day = LineAdapt.DAY_MS
    private val now = 100 * day
    private val trial = LineAdapt.Record(ice = Ice.TRIAL)

    private fun after(flow: MediaFlow?, tookPart: Boolean = true, setupMs: Long? = 300, failedWith: Int? = null, record: LineAdapt.Record = trial) =
        MediaAdapt.afterCall(record, flow, tookPart, setupMs, failedWith, now)

    @Test
    fun `ice on trial is kept after sound both ways in time and given up otherwise`() {
        assertEquals(Ice.ON, after(MediaFlow.BOTH).ice)
        assertEquals(now + day, after(MediaFlow.BOTH).iceRetestAt)
        assertEquals(Ice.ON, after(MediaFlow.BOTH, setupMs = null).ice)
        assertEquals(Ice.REFUSED, after(MediaFlow.BOTH, setupMs = MediaAdapt.SLOW_SETUP_MS + 1).ice)
        assertEquals(Ice.REFUSED, after(MediaFlow.SEND_ONLY).ice)
        assertEquals(Ice.REFUSED, after(MediaFlow.RECEIVE_ONLY).ice)
        assertEquals(now + MediaAdapt.REFUSED_MS, after(MediaFlow.SEND_ONLY).iceRetestAt)
        // A call ICE took no part in tells nothing, nor counts.
        assertEquals(trial, after(MediaFlow.SEND_ONLY, tookPart = false, setupMs = null))
        // One bad call does not undo what worked.
        val on = LineAdapt.Record(ice = Ice.ON)
        assertEquals(on, after(MediaFlow.SEND_ONLY, record = on))
    }

    @Test
    fun `a call out with ice on trial that fails in a way ice can cause gives ice up`() {
        // Its INVITE got no answer or was too large (larger with the candidates), or the other
        // side or an SBC could not take the offer.
        for (code in listOf(400, 408, 415, 488, 513, 606)) assertEquals("$code", Ice.REFUSED, after(null, failedWith = code).ice)
        // Busy, cancelled or declined: the other side's or the user's choice, not counted.
        for (code in listOf(486, 487, 600, 603)) assertEquals("$code", trial, after(null, failedWith = code))
        var busy = trial
        repeat(MediaAdapt.TRIAL_CALLS + 1) { busy = after(null, failedWith = 486, record = busy) }
        assertEquals(trial, busy)
    }

    @Test
    fun `other failures count towards the end of the trial`() {
        // Unavailable, unknown, a server error: not ICE's doing, but a trial does not last for ever.
        for (code in listOf(404, 480, 500, 503)) assertEquals("$code", trial.copy(iceCalls = 1), after(null, failedWith = code))
        var record = trial
        repeat(MediaAdapt.TRIAL_CALLS - 1) {
            record = after(null, failedWith = 480, record = record)
            assertEquals(Ice.TRIAL, record.ice)
        }
        record = after(null, failedWith = 503, record = record)
        assertEquals(Ice.REFUSED, record.ice)
        assertEquals(5, MediaAdapt.TRIAL_CALLS)
    }

    @Test
    fun `a trial whose calls tell nothing ends after a few of them`() {
        var record = trial
        repeat(MediaAdapt.TRIAL_CALLS - 1) {
            record = after(null, record = record)
            assertEquals(Ice.TRIAL, record.ice)
        }
        record = after(null, record = record)
        assertEquals(Ice.REFUSED, record.ice)
        assertEquals(now + MediaAdapt.REFUSED_MS, record.iceRetestAt)
        assertEquals(0, record.iceCalls)
        // One with a verdict starts the count anew.
        assertEquals(0, after(MediaFlow.BOTH, record = trial.copy(iceCalls = 2)).iceCalls)
    }

    @Test
    fun `ice in use is given up from time to time, later each time the line needed it again`() {
        val on = after(MediaFlow.BOTH)
        assertEquals(on, MediaAdapt.iceWithdrawn(on, now + day - 1, calls = 0))
        assertEquals(on, MediaAdapt.iceWithdrawn(on, now + day, calls = 1))
        val withdrawn = MediaAdapt.iceWithdrawn(on, now + day, calls = 0)
        assertEquals(Ice.NONE, withdrawn.ice)
        assertEquals(1, withdrawn.iceRetests)
        // Needed again: kept for two days, then four, then a week at most.
        val delays = (1..4).map { retests ->
            MediaAdapt.afterCall(LineAdapt.Record(ice = Ice.TRIAL, iceRetests = retests), MediaFlow.BOTH, true, 300, null, now).iceRetestAt - now
        }
        assertEquals(listOf(2 * day, 4 * day, 7 * day, 7 * day), delays)
    }

    @Test
    fun `ice given up may be tried again after a week`() {
        val refused = after(MediaFlow.SEND_ONLY)
        assertEquals(refused, MediaAdapt.iceWithdrawn(refused, now + MediaAdapt.REFUSED_MS - 1, calls = 0))
        val again = MediaAdapt.iceWithdrawn(refused, now + MediaAdapt.REFUSED_MS, calls = 1)
        assertEquals(Ice.NONE, again.ice)
        assertEquals(0L, again.iceRetestAt)
        assertEquals(0, again.iceRetests)
    }

    @Test
    fun `ice takes part in calls out that offered it and in calls in that offered it`() {
        assertTrue(MediaAdapt.iceTookPart(outgoing = true, offered = true, iceState = "NotActivated"))
        // A call out that started before ICE was in place for its line tells nothing.
        assertFalse(MediaAdapt.iceTookPart(outgoing = true, offered = false, iceState = ""))
        assertTrue(MediaAdapt.iceTookPart(outgoing = false, offered = false, iceState = "ReflexiveConnection"))
        assertFalse(MediaAdapt.iceTookPart(outgoing = false, offered = false, iceState = "NotActivated"))
        assertFalse(MediaAdapt.iceTookPart(outgoing = false, offered = false, iceState = ""))
    }

    @Test
    fun `a pbx naming a hop of its own network is told from one the phone reaches`() {
        val local = listOf("192.168.1.0/24", "fe80::/64")
        // Behind the NAT of its office, reached by its public name and address.
        assertTrue(MediaAdapt.unreachableHop("10.0.0.5", listOf("pbx.example.com", "203.0.113.5"), local))
        assertTrue(MediaAdapt.unreachableHop("[fd00::5]", listOf("203.0.113.5"), local))
        // The very address the line connects to, or one in the phone's own network: reachable.
        assertFalse(MediaAdapt.unreachableHop("10.0.0.5", listOf("pbx.example.com", "10.0.0.5"), local))
        assertFalse(MediaAdapt.unreachableHop("192.168.1.10", listOf("203.0.113.5"), local))
        // Public addresses and names say nothing.
        assertFalse(MediaAdapt.unreachableHop("203.0.113.9", listOf("203.0.113.5"), local))
        assertFalse(MediaAdapt.unreachableHop("pbx.example.com", listOf("203.0.113.5"), local))
        assertFalse(MediaAdapt.unreachableHop(null, listOf("203.0.113.5"), local))
    }

    @Test
    fun `the phone's networks count with their real prefixes`() {
        // A /16 office network: a hop outside the /24 of the phone is still in it.
        assertFalse(MediaAdapt.unreachableHop("172.20.9.5", listOf("203.0.113.5"), listOf("172.20.1.0/16")))
        // A /28 one: the neighbour /24 is not.
        assertTrue(MediaAdapt.unreachableHop("192.168.30.99", listOf("203.0.113.5"), listOf("192.168.30.0/28")))
        // A link-local hop is on the phone's link where the phone has that network.
        assertFalse(MediaAdapt.unreachableHop("fe80::5", listOf("203.0.113.5"), listOf("fe80::/64")))
        assertTrue(MediaAdapt.unreachableHop("169.254.3.3", listOf("203.0.113.5"), listOf("192.168.1.0/24")))
        // Without the phone's networks: no verdict.
        assertFalse(MediaAdapt.unreachableHop("10.0.0.5", listOf("203.0.113.5"), emptyList()))
    }

    @Test
    fun `the next hop of a call is the record route next to the phone, else the contact`() {
        assertEquals("10.0.0.5", MediaAdapt.nextHop("10.0.0.5", null, outgoing = true))
        assertEquals("fd00::5", MediaAdapt.nextHop("[fd00::5]", "", outgoing = false))
        val routes = "<sip:edge.example.com;lr>, <sip:10.0.0.9:5060;transport=udp;lr>"
        // A call out takes the route set in reverse: the last entry is next to the phone.
        assertEquals("10.0.0.9", MediaAdapt.nextHop("10.0.0.5", routes, outgoing = true))
        assertEquals("edge.example.com", MediaAdapt.nextHop("10.0.0.5", routes, outgoing = false))
        assertNull(MediaAdapt.nextHop(null, null, outgoing = true))
    }

    @Test
    fun `record route values give the hosts of their uris`() {
        assertEquals(
            listOf("p1.example.com", "[2001:db8::1]", "192.0.2.4", "203.0.113.8"),
            MediaAdapt.routeHosts(
                "<sip:p1.example.com;lr>,<sips:[2001:db8::1]:5061;lr> , <sip:user@192.0.2.4:5060;lr;x=\"a,b\">, \"Edge, west\" <sip:203.0.113.8>",
            ),
        )
        assertEquals(listOf("192.0.2.4"), MediaAdapt.routeHosts("sip:192.0.2.4;lr"))
        assertEquals(emptyList<String>(), MediaAdapt.routeHosts("<tel:+4930123>"))
        assertEquals(emptyList<String>(), MediaAdapt.routeHosts(null))
    }
}
