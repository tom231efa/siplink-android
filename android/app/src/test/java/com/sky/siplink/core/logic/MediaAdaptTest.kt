package com.sky.siplink.core.logic

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
    fun `a pbx announcing an address of its own network is told from one the phone reaches`() {
        val local = listOf("192.168.1.20", "fe80::1")
        // Behind the NAT of its office, reached by its public name and address.
        assertTrue(MediaAdapt.pbxBehindNat("10.0.0.5", listOf("pbx.example.com", "203.0.113.5"), local))
        assertTrue(MediaAdapt.pbxBehindNat("[fd00::5]", listOf("203.0.113.5"), local))
        // The very address the line connects to, or one in the phone's own network: reachable.
        assertFalse(MediaAdapt.pbxBehindNat("10.0.0.5", listOf("pbx.example.com", "10.0.0.5"), local))
        assertFalse(MediaAdapt.pbxBehindNat("192.168.1.10", listOf("203.0.113.5"), local))
        // Public addresses and names say nothing.
        assertFalse(MediaAdapt.pbxBehindNat("203.0.113.9", listOf("203.0.113.5"), local))
        assertFalse(MediaAdapt.pbxBehindNat("pbx.example.com", listOf("203.0.113.5"), local))
        assertFalse(MediaAdapt.pbxBehindNat(null, listOf("203.0.113.5"), local))
    }

    @Test
    fun `the remedy for a pbx behind a nat is lifted after calls in a row without the need`() {
        assertEquals(0, MediaAdapt.pbxAfter(null, behind = true))
        assertNull(MediaAdapt.pbxAfter(null, behind = false))
        var count: Int? = MediaAdapt.pbxAfter(null, behind = true)
        repeat(MediaAdapt.CALLS_TO_LIFT - 1) {
            count = MediaAdapt.pbxAfter(count, behind = false)
            assertTrue(count != null)
        }
        // One more call where it is needed starts the count again.
        assertEquals(0, MediaAdapt.pbxAfter(count, behind = true))
        assertNull(MediaAdapt.pbxAfter(count, behind = false))
    }
}
