package com.sky.siplink.core.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

class CertificatePinTest {
    private val ca = TestCertificates.ca()
    private val pin = CertificatePin.pinOf(ca.certificate)
    private val now = System.currentTimeMillis()

    @Test
    fun thePinIsTheHashOfTheSubjectPublicKeyInfoInBase64UrlWithoutPadding() {
        val expected = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(ca.certificate.publicKey.encoded))
        assertEquals(expected, pin)
        assertEquals(43, pin.length)
        assertTrue(RemoteAddress.isPin(pin))
    }

    @Test
    fun aChainOfLeafAndPinnedCaIsTakenAndTheCaIsKept() {
        val leaf = TestCertificates.server(ca)
        assertSame(ca.certificate, CertificatePin.check(listOf(leaf.certificate, ca.certificate), pin, "platform.example.com", now))
        // Host names are not case sensitive, and a trailing dot changes nothing.
        CertificatePin.check(listOf(leaf.certificate, ca.certificate), pin, "Platform.Example.com.", now)
    }

    @Test
    fun aLiteralAddressHasToBeAnAddressOfTheCertificate() {
        val leaf = TestCertificates.server(ca, dnsNames = listOf("platform.example.com"), addresses = listOf("192.0.2.10", "2001:db8::10"))
        val chain = listOf(leaf.certificate, ca.certificate)
        CertificatePin.check(chain, pin, "192.0.2.10", now)
        CertificatePin.check(chain, pin, "2001:db8:0:0:0:0:0:10", now)
        assertThrows(CertificatePin.Mismatch::class.java) { CertificatePin.check(chain, pin, "192.0.2.11", now) }
        // An address written as a name is not a name of the certificate.
        val named = TestCertificates.server(ca, dnsNames = listOf("192.0.2.10"))
        assertThrows(CertificatePin.Mismatch::class.java) { CertificatePin.check(listOf(named.certificate, ca.certificate), pin, "192.0.2.10", now) }
    }

    @Test
    fun anotherHostIsRefused() {
        val leaf = TestCertificates.server(ca)
        assertThrows(CertificatePin.Mismatch::class.java) {
            CertificatePin.check(listOf(leaf.certificate, ca.certificate), pin, "other.example.com", now)
        }
    }

    @Test
    fun aWildcardStandsForOneLabel() {
        val leaf = TestCertificates.server(ca, dnsNames = listOf("*.example.com")).certificate
        assertTrue(CertificatePin.names(leaf, "platform.example.com"))
        assertFalse(CertificatePin.names(leaf, "a.platform.example.com"))
        assertFalse(CertificatePin.names(leaf, "example.com"))
    }

    @Test
    fun aChainWithoutThePinnedCertificateIsRefused() {
        val other = TestCertificates.ca("Other CA")
        val leaf = TestCertificates.server(other)
        assertThrows(CertificatePin.Mismatch::class.java) {
            CertificatePin.check(listOf(leaf.certificate, other.certificate), pin, "platform.example.com", now)
        }
    }

    @Test
    fun aLeafThatThePinnedCaDidNotSignIsRefused() {
        // Same name as the pinned CA, another key: the chain claims the CA, its signature does not.
        val impostor = TestCertificates.ca("SipLink Test CA")
        val leaf = TestCertificates.server(impostor)
        assertThrows(CertificatePin.Mismatch::class.java) {
            CertificatePin.check(listOf(leaf.certificate, ca.certificate), pin, "platform.example.com", now)
        }
    }

    @Test
    fun anExpiredServerCertificateIsRefused() {
        val day = 24 * 60 * 60 * 1000L
        val leaf = TestCertificates.server(ca, notBefore = now - 10 * day, notAfter = now - day)
        assertThrows(CertificatePin.Mismatch::class.java) {
            CertificatePin.check(listOf(leaf.certificate, ca.certificate), pin, "platform.example.com", now)
        }
    }

    @Test
    fun aCertificateForClientsOnlyIsRefused() {
        val leaf = TestCertificates.server(ca, serverAuth = false)
        assertThrows(CertificatePin.Mismatch::class.java) {
            CertificatePin.check(listOf(leaf.certificate, ca.certificate), pin, "platform.example.com", now)
        }
    }

    @Test
    fun aKeptCaAddedBehindALeafAloneLeadsToIt() {
        val leaf = TestCertificates.server(ca)
        assertSame(ca.certificate, CertificatePin.check(listOf(leaf.certificate, ca.certificate), pin, "platform.example.com", now))
        assertThrows(CertificatePin.Mismatch::class.java) { CertificatePin.check(listOf(leaf.certificate), pin, "platform.example.com", now) }
    }
}
