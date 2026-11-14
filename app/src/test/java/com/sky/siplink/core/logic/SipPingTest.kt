package com.sky.siplink.core.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SipPingTest {
    private val request = SipPing.options("pbx.example.com:5080", "1001@example.com", "192.0.2.7:40000", "abc123")

    @Test
    fun `the request is a complete options request`() {
        val lines = request.split("\r\n")
        assertEquals("OPTIONS sip:pbx.example.com:5080 SIP/2.0", lines.first())
        assertTrue("Via: SIP/2.0/UDP 192.0.2.7:40000;branch=z9hG4bKabc123;rport" in lines)
        assertTrue("From: <sip:1001@example.com>;tag=abc123" in lines)
        assertTrue("To: <sip:pbx.example.com:5080>" in lines)
        assertTrue("Call-ID: abc123" in lines)
        assertTrue("CSeq: 1 OPTIONS" in lines)
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
}
