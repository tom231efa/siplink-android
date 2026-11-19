package com.sky.siplink.core.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SipPingTest {
    private val request = SipPing.options("pbx.example.com:5080", "1001@example.com", "192.0.2.7:40000", "abc123", "LinphoneSDK/5.5.39 SipLink/0.1.1")

    @Test
    fun `the request is a complete options request`() {
        val lines = request.split("\r\n")
        assertEquals("OPTIONS sip:pbx.example.com:5080 SIP/2.0", lines.first())
        assertTrue("Via: SIP/2.0/UDP 192.0.2.7:40000;branch=z9hG4bKabc123;rport" in lines)
        assertTrue("From: <sip:1001@example.com>;tag=abc123" in lines)
        assertTrue("To: <sip:pbx.example.com:5080>" in lines)
        assertTrue("Call-ID: abc123" in lines)
        assertTrue("CSeq: 1 OPTIONS" in lines)
        assertTrue("User-Agent: LinphoneSDK/5.5.39 SipLink/0.1.1" in lines)
        assertTrue("Content-Length: 0" in lines)
        // Headers end with an empty line.
        assertTrue(request.endsWith("\r\n\r\n"))
    }

    @Test
    fun `any answer to the request counts whatever its status`() {
        val headers = "\r\nVia: SIP/2.0/UDP 192.0.2.7:40000;branch=z9hG4bKabc123\r\nCall-ID: abc123\r\n\r\n"
        assertTrue(SipPing.isAnswer("SIP/2.0 200 OK$headers", "abc123"))
        assertTrue(SipPing.isAnswer("SIP/2.0 401 Unauthorized$headers", "abc123"))
        assertTrue(SipPing.isAnswer("SIP/2.0 404 Not Found$headers", "abc123"))
    }

    @Test
    fun `what is no answer to this request does not count`() {
        assertFalse(SipPing.isAnswer("SIP/2.0 200 OK\r\nCall-ID: other\r\n\r\n", "abc123"))
        assertFalse(SipPing.isAnswer(request, "abc123"))
        assertFalse(SipPing.isAnswer("", "abc123"))
    }

    @Test
    fun `a padded request has the size asked for and stays a request`() {
        val large = SipPing.options("pbx.example.com", "1001@example.com", "192.0.2.7:40000", "abc123", "UA", size = 1400)
        assertEquals(1400, large.length)
        assertTrue(large.startsWith("OPTIONS sip:pbx.example.com SIP/2.0\r\n"))
        assertTrue(large.endsWith("Content-Length: 0\r\n\r\n"))
        // Asked for less than the plain request, it stays as it is.
        assertEquals(request, SipPing.options("pbx.example.com:5080", "1001@example.com", "192.0.2.7:40000", "abc123", "LinphoneSDK/5.5.39 SipLink/0.1.1", size = 100))
    }

    @Test
    fun `the answer tells where the server saw the request`() {
        val packet = "SIP/2.0 200 OK\r\nVia: SIP/2.0/UDP 192.0.2.7:40000;branch=z9hG4bKabc123;received=203.0.113.7;rport=51234\r\n" +
            "Call-ID: abc123\r\nContent-Length: 0\r\n\r\n"
        val answer = SipPing.answer(packet, "abc123", "192.0.2.7:40000", 12)!!
        assertEquals(200, answer.status)
        assertEquals("203.0.113.7", answer.received)
        assertEquals(51234, answer.rport)
        assertTrue(answer.sentByIntact)
        assertTrue(answer.lengthIntact)
        assertEquals(12L, answer.rttMs)
    }

    @Test
    fun `a rewritten via and a wrong length are noticed`() {
        val packet = "SIP/2.0 404 Not Found\r\nv: SIP/2.0/UDP 203.0.113.7:51234;rport=51234;branch=z9hG4bKabc123\r\n" +
            "Call-ID: abc123\r\nl: 5\r\n\r\nab"
        val answer = SipPing.answer(packet, "abc123", "192.0.2.7:40000", 3)!!
        assertEquals(404, answer.status)
        assertEquals(null, answer.received)
        assertEquals(51234, answer.rport)
        assertFalse(answer.sentByIntact)
        assertFalse(answer.lengthIntact)
        assertEquals(null, SipPing.answer("SIP/2.0 200 OK\r\nCall-ID: other\r\n\r\n", "abc123", "x", 1))
    }

    @Test
    fun `the top via of several is read`() {
        val via = SipPing.topVia("SIP/2.0 200 OK\r\nVia: SIP/2.0/UDP a:1;rport=2, SIP/2.0/UDP b:3\r\nVia: SIP/2.0/UDP c:4\r\n\r\n")!!
        assertEquals("a:1", via.sentBy)
        assertEquals("2", via.params["rport"])
    }
}
