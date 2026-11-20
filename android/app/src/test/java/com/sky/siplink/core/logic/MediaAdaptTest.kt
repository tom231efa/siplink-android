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

    @Test
    fun `ice on trial is kept after sound both ways in time and given up otherwise`() {
        assertEquals(Ice.ON, MediaAdapt.iceAfter(Ice.TRIAL, MediaFlow.BOTH, tookPart = true, setupMs = 300))
        assertEquals(Ice.ON, MediaAdapt.iceAfter(Ice.TRIAL, MediaFlow.BOTH, tookPart = true, setupMs = null))
        assertEquals(Ice.REFUSED, MediaAdapt.iceAfter(Ice.TRIAL, MediaFlow.BOTH, tookPart = true, setupMs = MediaAdapt.SLOW_SETUP_MS + 1))
        assertEquals(Ice.REFUSED, MediaAdapt.iceAfter(Ice.TRIAL, MediaFlow.SEND_ONLY, tookPart = true, setupMs = 300))
        assertEquals(Ice.REFUSED, MediaAdapt.iceAfter(Ice.TRIAL, MediaFlow.RECEIVE_ONLY, tookPart = true, setupMs = 300))
        // A call without a verdict, or one ICE took no part in, tells nothing.
        assertEquals(Ice.TRIAL, MediaAdapt.iceAfter(Ice.TRIAL, null, tookPart = true, setupMs = 300))
        assertEquals(Ice.TRIAL, MediaAdapt.iceAfter(Ice.TRIAL, MediaFlow.SEND_ONLY, tookPart = false, setupMs = null))
        // One bad call does not undo what worked.
        assertEquals(Ice.ON, MediaAdapt.iceAfter(Ice.ON, MediaFlow.SEND_ONLY, tookPart = true, setupMs = 300))
    }

    @Test
    fun `ice takes part in calls out and in calls in that offered it`() {
        assertTrue(MediaAdapt.iceTookPart(outgoing = true, iceState = "NotActivated"))
        assertTrue(MediaAdapt.iceTookPart(outgoing = false, iceState = "ReflexiveConnection"))
        assertFalse(MediaAdapt.iceTookPart(outgoing = false, iceState = "NotActivated"))
        assertFalse(MediaAdapt.iceTookPart(outgoing = false, iceState = ""))
    }

    @Test
    fun `a pbx naming a hop of its own network is told from one the phone reaches`() {
        val local = listOf("192.168.1.20", "fe80::1")
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
