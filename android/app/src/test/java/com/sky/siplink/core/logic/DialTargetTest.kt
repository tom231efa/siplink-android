package com.sky.siplink.core.logic

import org.junit.Assert.assertEquals
import org.junit.Test

class DialTargetTest {
    @Test
    fun `a bare number is left to the stack`() {
        assertEquals("1002", DialTarget.of("1002"))
        assertEquals("+8613800138000", DialTarget.of(" +86 138 0013 8000 "))
        assertEquals("#9", DialTarget.of("#9"))
        assertEquals("*80 1005".replace(" ", ""), DialTarget.of("*80 1005"))
    }

    @Test
    fun `an address of the call log gets a scheme`() {
        assertEquals("sip:1002@pbx.example.com", DialTarget.of("1002@pbx.example.com"))
        assertEquals("sip:+8613800138000@192.0.2.10:5080", DialTarget.of("+8613800138000@192.0.2.10:5080"))
        assertEquals("sip:*801005@[2001:db8::1]", DialTarget.of("*801005@[2001:db8::1]"))
    }

    @Test
    fun `what a uri cannot hold in its user part is escaped`() {
        assertEquals("sip:%239@pbx.example.com", DialTarget.of("#9@pbx.example.com"))
        assertEquals("sip:100%25@pbx.example.com", DialTarget.of("100%@pbx.example.com"))
        assertEquals("sip:%E5%89%8D%E5%8F%B0@pbx.example.com", DialTarget.of("前台@pbx.example.com"))
        // Only the last @ parts the user from the host.
        assertEquals("sip:a%40b@pbx.example.com", DialTarget.of("a@b@pbx.example.com"))
    }

    @Test
    fun `a full uri is taken as written`() {
        assertEquals("sip:%239@pbx.example.com;transport=tcp", DialTarget.of("sip:%239@pbx.example.com;transport=tcp"))
        assertEquals("SIPS:1002@pbx.example.com", DialTarget.of("SIPS:1002@pbx.example.com"))
    }

    @Test
    fun `nothing stays nothing`() {
        assertEquals("", DialTarget.of("  "))
        assertEquals("@pbx", DialTarget.of("@pbx"))
    }
}
