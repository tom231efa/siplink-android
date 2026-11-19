package com.sky.siplink.core.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.cert.CertificateException
import java.util.Base64

class CertificateToolsTest {
    // Self-signed certificates generated only for these tests (public parts, no private keys).
    private val caPem = """
        -----BEGIN CERTIFICATE-----
        MIIB6zCCAZGgAwIBAgIUeDboWUkvCJMyeNAAGecqn1GQTgAwCgYIKoZIzj0EAwIw
        QzELMAkGA1UEBhMCQ04xFTATBgNVBAoMDFNpcExpbmsgVGVzdDEdMBsGA1UEAwwU
        U2lwTGluayBUZXN0IFJvb3QgQ0EwHhcNMjYxMTE0MDQwMzU0WhcNMzYxMTExMDQw
        MzU0WjBDMQswCQYDVQQGEwJDTjEVMBMGA1UECgwMU2lwTGluayBUZXN0MR0wGwYD
        VQQDDBRTaXBMaW5rIFRlc3QgUm9vdCBDQTBZMBMGByqGSM49AgEGCCqGSM49AwEH
        A0IABNjkiGTUeDQIBYViuQfZOj3zv4DhO95M79sDrKZMSYBRH2jL3w4KI6NagnF2
        JgskJdc+a/8sQ8H9NwG5BSnJP5ajYzBhMB0GA1UdDgQWBBRkixWj5HIVgiyw/FfH
        7aO2/vHOxDAfBgNVHSMEGDAWgBRkixWj5HIVgiyw/FfH7aO2/vHOxDAPBgNVHRMB
        Af8EBTADAQH/MA4GA1UdDwEB/wQEAwIBBjAKBggqhkjOPQQDAgNIADBFAiASoBoV
        JWD2cjciQ4pndqXdfE1abNDkM8XHot8x15Rh0wIhALf/KSr51RYFd8uGPrdGsnZ+
        EuLQIdDUDuEy3W1y3+Ew
        -----END CERTIFICATE-----
    """.trimIndent()

    private val leafPem = """
        -----BEGIN CERTIFICATE-----
        MIIBtTCCAVygAwIBAgIUZyuOcQMu8BLz+7bOke4jDs70KpwwCgYIKoZIzj0EAwIw
        MjEVMBMGA1UECgwMU2lwTGluayBUZXN0MRkwFwYDVQQDDBBwYngudGVzdC5pbnZh
        bGlkMB4XDTI2MTExNDA0MDM1NFoXDTM2MTExMTA0MDM1NFowMjEVMBMGA1UECgwM
        U2lwTGluayBUZXN0MRkwFwYDVQQDDBBwYngudGVzdC5pbnZhbGlkMFkwEwYHKoZI
        zj0CAQYIKoZIzj0DAQcDQgAE+GkgrOSAWvXKhcjDlQ+8nEZzBxoXsX1Yg+dMR2dL
        luJsNxEo61y7dX9ZctA9GBPHdfGEhKw/dBofNho1iCGmSKNQME4wHQYDVR0OBBYE
        FB3+XECdPj+qGmVFwYIE+Yd6cs+7MB8GA1UdIwQYMBaAFB3+XECdPj+qGmVFwYIE
        +Yd6cs+7MAwGA1UdEwEB/wQCMAAwCgYIKoZIzj0EAwIDRwAwRAIgd4l77bbk6vzs
        EGC6u0UCo7/13PxXhpNxzVR52pX6mIQCIB7r5l0bj6k8XF7sObw03FuKEWGrBeIr
        zrgG9u6QxcbI
        -----END CERTIFICATE-----
    """.trimIndent()

    private val caFingerprint =
        "02:C7:3A:CD:55:F3:10:3E:D1:12:B0:7B:8E:02:5D:C2:33:A4:24:12:13:55:12:CE:D2:B2:D5:84:6C:3D:6E:43"

    @Test
    fun `parses a pem certificate and matches the openssl fingerprint`() {
        val certificate = CertificateTools.parse(caPem.toByteArray()).single()
        assertEquals(caFingerprint, CertificateTools.fingerprint(certificate))
    }

    @Test
    fun `describes subject issuer validity and ca flag`() {
        val ca = CertificateTools.parse(caPem.toByteArray()).single()
        val info = CertificateTools.describe(ca, nowMs = ca.notBefore.time + 1000)
        assertEquals("SipLink Test Root CA", info.subject)
        assertEquals("SipLink Test Root CA", info.issuer)
        assertTrue(info.isCa)
        assertFalse(info.expired)
        assertTrue(CertificateTools.describe(ca, nowMs = ca.notAfter.time + 1).expired)

        val leaf = CertificateTools.parse(leafPem.toByteArray()).single()
        assertFalse(CertificateTools.describe(leaf, nowMs = 0).isCa)
        assertEquals("pbx.test.invalid", CertificateTools.describe(leaf, nowMs = 0).subject)
    }

    @Test
    fun `parses several certificates from one file`() {
        val certificates = CertificateTools.parse("$caPem\n$leafPem\n".toByteArray())
        assertEquals(2, certificates.size)
    }

    @Test
    fun `parses der encoding`() {
        val der = Base64.getMimeDecoder().decode(caPem.lines().filterNot { it.startsWith("-----") }.joinToString(""))
        assertEquals(caFingerprint, CertificateTools.fingerprint(CertificateTools.parse(der).single()))
    }

    @Test
    fun `pem round trip is stable`() {
        val certificate = CertificateTools.parse(caPem.toByteArray()).single()
        val pem = CertificateTools.toPem(certificate)
        assertTrue(pem.startsWith("-----BEGIN CERTIFICATE-----\n"))
        assertTrue(pem.endsWith("-----END CERTIFICATE-----\n"))
        assertTrue(pem.lines().all { it.length <= 64 })
        assertEquals(certificate, CertificateTools.parse(pem.toByteArray()).single())
    }

    @Test
    fun `rejects data that is not a certificate`() {
        assertThrows(CertificateException::class.java) { CertificateTools.parse("hello world".toByteArray()) }
        assertThrows(CertificateException::class.java) { CertificateTools.parse(ByteArray(0)) }
    }

    @Test
    fun `merge ignores certificates that are already trusted`() {
        val ca = CertificateTools.parse(caPem.toByteArray())
        val both = CertificateTools.parse("$caPem\n$leafPem".toByteArray())
        val (merged, added) = CertificateTools.merge(ca, both)
        assertEquals(2, merged.size)
        assertEquals(1, added)
        assertEquals(0, CertificateTools.merge(merged, both).second)
    }

    @Test
    fun `bundle keeps the stock roots first and appends user certificates`() {
        val ca = CertificateTools.parse(caPem.toByteArray())
        val stock = "$leafPem\n"
        val bundle = CertificateTools.bundle(stock, ca)
        assertEquals(2, CertificateTools.parse(bundle.toByteArray()).size)
        assertTrue(bundle.startsWith(leafPem))
        // Without stock roots only the user certificates are trusted.
        assertEquals(1, CertificateTools.parse(CertificateTools.bundle(null, ca).toByteArray()).size)
    }

    @Test
    fun `the bundle is written anew only when what it is made of changed`() {
        val ca = CertificateTools.parse(caPem.toByteArray())
        val leaf = CertificateTools.parse(leafPem.toByteArray())
        val stock = "$leafPem\n"
        val written = CertificateTools.digest(CertificateTools.bundle(stock, ca))
        // The same stock roots copied anew (a newer file, the same text) and the same user CAs: nothing to write.
        assertFalse(CertificateTools.bundleStale(true, written, CertificateTools.digest(CertificateTools.bundle(stock, ca))))
        // Other stock roots, other or more user CAs, no stock roots any more: written anew.
        assertTrue(CertificateTools.bundleStale(true, written, CertificateTools.digest(CertificateTools.bundle("$caPem\n", ca))))
        assertTrue(CertificateTools.bundleStale(true, written, CertificateTools.digest(CertificateTools.bundle(stock, ca + leaf))))
        assertTrue(CertificateTools.bundleStale(true, written, CertificateTools.digest(CertificateTools.bundle(null, ca))))
        // No bundle, or none written with a digest (by an earlier version): written anew.
        assertTrue(CertificateTools.bundleStale(false, written, written))
        assertTrue(CertificateTools.bundleStale(true, null, written))
        assertEquals(64, written.length)
    }

    @Test
    fun `common name extraction`() {
        assertEquals("Example Root", CertificateTools.commonName("CN=Example Root,O=Example,C=CN"))
        assertEquals("Doe, John", CertificateTools.commonName("O=Example,CN=Doe\\, John"))
        assertEquals("O=No Common Name", CertificateTools.commonName("O=No Common Name"))
    }
}
