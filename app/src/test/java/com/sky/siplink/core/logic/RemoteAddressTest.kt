package com.sky.siplink.core.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteAddressTest {
    /** 43 characters of base64url, as the platform makes its tokens and pins. */
    private val token = "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789-_AbCd"
    private val pin = "q1w2e3r4t5y6u7i8o9p0a1s2d3f4g5h6j7k8l9z0x1c"

    private fun ok(text: String, allowHttp: Boolean = false): RemoteAddress.Enrollment {
        val parsed = RemoteAddress.parse(text, allowHttp)
        assertTrue("Refused: $parsed", parsed is RemoteAddress.Parsed.Ok)
        return (parsed as RemoteAddress.Parsed.Ok).enrollment
    }

    private fun error(text: String, allowHttp: Boolean = false): RemoteAddress.Error? =
        (RemoteAddress.parse(text, allowHttp) as? RemoteAddress.Parsed.Invalid)?.error

    @Test
    fun theAddressOfTheQrCodeIsTakenApart() {
        val enrollment = ok("  https://platform.example.com:8443/p/$token?pin=$pin \n")
        assertEquals("https://platform.example.com:8443/p/$token", enrollment.enrollUrl)
        assertEquals("https://platform.example.com:8443", enrollment.origin)
        assertEquals("platform.example.com", enrollment.host)
        assertEquals(pin, enrollment.pin)
        assertEquals("https://platform.example.com:8443/api/device/v1/sync", enrollment.syncUrl)
    }

    @Test
    fun withoutPinTheSystemDecides() {
        assertNull(ok("https://platform.example.com/p/$token").pin)
    }

    @Test
    fun aPlatformUnderAPathKeepsItForSyncing() {
        val enrollment = ok("https://example.com/siplink/p/$token")
        assertEquals("https://example.com/siplink/api/device/v1/sync", enrollment.syncUrl)
    }

    @Test
    fun literalAddressesAreHostsWithoutBrackets() {
        assertEquals("192.0.2.10", ok("https://192.0.2.10:8443/p/$token").host)
        val v6 = ok("https://[2001:db8::10]:8443/p/$token")
        assertEquals("2001:db8::10", v6.host)
        assertEquals("https://[2001:db8::10]:8443/api/device/v1/sync", v6.syncUrl)
    }

    @Test
    fun plainHttpOnlyWhereAllowed() {
        assertEquals(RemoteAddress.Error.SCHEME, error("http://192.0.2.10:8080/p/$token"))
        assertEquals("http://192.0.2.10:8080/p/$token", ok("http://192.0.2.10:8080/p/$token", allowHttp = true).enrollUrl)
        assertEquals(RemoteAddress.Error.NOT_AN_ADDRESS, error("ftp://example.com/p/$token", allowHttp = true))
    }

    @Test
    fun whatIsNoEnrollmentAddressIsRefused() {
        assertEquals(RemoteAddress.Error.NOT_AN_ADDRESS, error("扫一扫"))
        assertEquals(RemoteAddress.Error.NOT_AN_ADDRESS, error("https:///p/$token"))
        assertEquals(RemoteAddress.Error.NOT_AN_ADDRESS, error("https://user:secret@example.com/p/$token"))
        assertEquals(RemoteAddress.Error.NOT_ENROLLMENT, error("https://example.com/"))
        assertEquals(RemoteAddress.Error.NOT_ENROLLMENT, error("https://example.com/p/"))
        assertEquals(RemoteAddress.Error.NOT_ENROLLMENT, error("https://example.com/p/abc/def"))
        assertEquals(RemoteAddress.Error.NOT_ENROLLMENT, error("https://example.com/p/a+b"))
    }

    @Test
    fun aPinHasToBeASha256() {
        assertEquals(RemoteAddress.Error.PIN_INVALID, error("https://example.com/p/$token?pin="))
        assertEquals(RemoteAddress.Error.PIN_INVALID, error("https://example.com/p/$token?pin=abc"))
        // Standard base64 with padding is not the form of the QR code.
        assertEquals(RemoteAddress.Error.PIN_INVALID, error("https://example.com/p/$token?pin=${pin.dropLast(1)}+="))
        assertTrue(RemoteAddress.isPin(pin))
        assertFalse(RemoteAddress.isPin(pin + "A"))
    }

    @Test
    fun leavingFollowsTheSyncAddress() {
        assertEquals("https://example.com:8443/api/device/v1/leave", RemoteAddress.leaveUrl("https://example.com:8443/api/device/v1/sync"))
        assertEquals("https://moved.example.com/x/api/device/v1/leave", RemoteAddress.leaveUrl("https://moved.example.com/x/api/device/v1/sync"))
        assertEquals("https://example.com/api/device/v1/leave", RemoteAddress.leaveUrl("https://example.com/elsewhere"))
    }

    @Test
    fun theSyncAddressOfADocumentHasToBeOfTheSameKind() {
        assertTrue(RemoteAddress.isPlatformUrl("https://example.com/api/device/v1/sync", allowHttp = false))
        assertFalse(RemoteAddress.isPlatformUrl("http://example.com/api/device/v1/sync", allowHttp = false))
        assertTrue(RemoteAddress.isPlatformUrl("http://127.0.0.1:8080/api/device/v1/sync", allowHttp = true))
        assertFalse(RemoteAddress.isPlatformUrl("", allowHttp = true))
        assertEquals("2001:db8::10", RemoteAddress.hostOf("https://[2001:db8::10]/api/device/v1/sync"))
    }
}
