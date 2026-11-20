package com.sky.siplink.core.logic

import com.sky.siplink.core.model.SipTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountRouteTest {
    @Test
    fun `a record survives being stored`() {
        val record = AccountRoute.Record("pbx1.example.com:5080", "pbx2.example.com", "192.0.2.53:5300", waiting = true)
        assertEquals(record, AccountRoute.decode(AccountRoute.encode(record)))
        val bare = AccountRoute.Record("", "pbx2.example.com", "")
        assertEquals(bare, AccountRoute.decode(AccountRoute.encode(bare)))
        assertNull(AccountRoute.decode(null))
        assertNull(AccountRoute.decode("pbx.example.com"))
        // The fast timeout is kept apart: an older version reads the four fields it knows.
        val fast = AccountRoute.Record("pbx.example.com", "sbc.example.com", "", timeoutMs = 500)
        assertEquals("pbx.example.com|sbc.example.com||0", AccountRoute.encode(fast))
        assertEquals(fast.copy(timeoutMs = 0), AccountRoute.decode(AccountRoute.encode(fast)))
        assertTrue(AccountRoute.Record("pbx.example.com", "", "").isPlain)
        assertFalse(bare.isPlain)
    }

    @Test
    fun `the fast timeout kept apart is read within its bounds`() {
        assertEquals(500, AccountRoute.decodeTimeout("500"))
        assertEquals(0, AccountRoute.decodeTimeout(null))
        assertEquals(0, AccountRoute.decodeTimeout("99"))
        assertEquals(0, AccountRoute.decodeTimeout("soon"))
        assertEquals(AccountRoute.Record("a", "b", "c"), AccountRoute.decode("a|b|c|0|500"))
    }

    @Test
    fun `host and port are told apart`() {
        assertEquals("pbx.example.com" to null, AccountRoute.split("pbx.example.com"))
        assertEquals("pbx.example.com" to 5080, AccountRoute.split("pbx.example.com:5080"))
        assertEquals("[2001:db8::1]" to null, AccountRoute.split("[2001:db8::1]"))
        assertEquals("[2001:db8::1]" to 5061, AccountRoute.split("[2001:db8::1]:5061"))
    }

    @Test
    fun `an address needs no lookup`() {
        assertTrue(AccountRoute.isAddress("192.0.2.10"))
        assertTrue(AccountRoute.isAddress("[2001:db8::1]"))
        assertFalse(AccountRoute.isAddress("pbx.example.com"))
        assertFalse(AccountRoute.isAddress("192.0.2"))
        assertFalse(AccountRoute.isAddress("192.0.2.256"))
        assertFalse(AccountRoute.isAddress("1.2.3.example"))
    }

    @Test
    fun `a dns server is an ipv4 address with or without a port`() {
        assertTrue(AccountRoute.isValidDnsServer("192.0.2.53"))
        assertTrue(AccountRoute.isValidDnsServer("192.0.2.53:5300"))
        assertFalse(AccountRoute.isValidDnsServer("dns.example.com"))
        assertFalse(AccountRoute.isValidDnsServer("[2001:db8::53]"))
        assertFalse(AccountRoute.isValidDnsServer("192.0.2.53:0"))
        assertFalse(AccountRoute.isValidDnsServer("192.0.2.53:port"))
        assertFalse(AccountRoute.isValidDnsServer(""))
        assertEquals("192.0.2.53" to 53, AccountRoute.dnsServer("192.0.2.53"))
        assertEquals("192.0.2.53" to 5300, AccountRoute.dnsServer("192.0.2.53:5300"))
    }

    @Test
    fun `the server uri carries the transport and the address it was found at`() {
        assertEquals("sip:pbx.example.com;transport=udp", AccountRoute.uri("pbx.example.com", SipTransport.UDP))
        assertEquals(
            "sip:pbx.example.com:5061;transport=tls;maddr=192.0.2.10",
            AccountRoute.uri("pbx.example.com:5061", SipTransport.TLS, pin = "192.0.2.10"),
        )
        assertEquals(5060, AccountRoute.defaultPort(SipTransport.UDP))
        assertEquals(5060, AccountRoute.defaultPort(SipTransport.TCP))
        assertEquals(5061, AccountRoute.defaultPort(SipTransport.TLS))
    }

    @Test
    fun `only a server that is out of reach or out of order calls for the backup`() {
        for (code in listOf(0, -1, 408, 500, 502, 503, 504)) assertTrue("$code", AccountRoute.isServerDown(code))
        for (code in listOf(401, 403, 404, 407, 423, 480, 486, 603)) assertFalse("$code", AccountRoute.isServerDown(code))
    }

    @Test
    fun `each server gets its turn before the account flips again`() {
        assertEquals(listOf(true, false, true, false), (1..4).map(AccountRoute::switchesNow))
    }

    @Test
    fun `the first server is asked less often when returning to it keeps failing`() {
        assertEquals(60_000L, AccountRoute.failbackDelay(0))
        assertEquals(120_000L, AccountRoute.failbackDelay(1))
        assertEquals(480_000L, AccountRoute.failbackDelay(3))
        assertEquals(1_800_000L, AccountRoute.failbackDelay(5))
        assertEquals(1_800_000L, AccountRoute.failbackDelay(500))
    }
}
