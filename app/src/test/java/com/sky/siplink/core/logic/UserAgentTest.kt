package com.sky.siplink.core.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UserAgentTest {
    @Test
    fun printableAsciiUpToTheLimitIsTaken() {
        assertNull(UserAgent.error(""))
        assertNull(UserAgent.error("SipLink-Example/1.0"))
        assertNull(UserAgent.error("Yealink SIP-T46U 108.86.0.20 (x) ~!@#\$%^&*"))
        assertNull(UserAgent.error("a".repeat(UserAgent.MAX_LENGTH)))
        assertNull(UserAgent.error("a b"))
    }

    @Test
    fun whatTheRuleRefusesSaysWhy() {
        assertEquals(UserAgent.Error.TOO_LONG, UserAgent.error("a".repeat(UserAgent.MAX_LENGTH + 1)))
        assertEquals(UserAgent.Error.NOT_PRINTABLE_ASCII, UserAgent.error("话机/1.0"))
        assertEquals(UserAgent.Error.NOT_PRINTABLE_ASCII, UserAgent.error("a\tb"))
        assertEquals(UserAgent.Error.NOT_PRINTABLE_ASCII, UserAgent.error("a\u007Fb"))
        assertEquals(UserAgent.Error.SPACE_AT_ENDS, UserAgent.error(" SipLink"))
        assertEquals(UserAgent.Error.SPACE_AT_ENDS, UserAgent.error("SipLink "))
        assertEquals(UserAgent.Error.SPACE_AT_ENDS, UserAgent.error(" "))
        assertEquals("客户端名称（User-Agent）前后不能有空格", UserAgent.Error.SPACE_AT_ENDS.full)
    }

    /** What the SIP core makes of a name and a version: `name/version`, or the name alone without a version. */
    private fun onTheWire(parts: Pair<String, String?>): String = parts.second?.let { "${parts.first}/$it" } ?: parts.first

    @Test
    fun theDefaultNamesTheStackFirstAndTheApp() {
        assertEquals("LinphoneSDK/5.5.39 SipLink/0.1.2", UserAgent.default("5.5.39", "0.1.2"))
        assertEquals(UserAgent.default("5.5.39", "0.1.2"), onTheWire(UserAgent.parts("", "5.5.39", "0.1.2")))
    }

    @Test
    fun aNameOfTheUsersOwnGoesAsItIsTyped() {
        // Without a version the core adds no "/": with or without one in the name, it goes as typed.
        assertEquals("SipLink-Example/1.0" to null, UserAgent.parts("SipLink-Example/1.0", "5.5.39", "0.1.2"))
        assertEquals("Office Phone", onTheWire(UserAgent.parts("Office Phone", "5.5.39", "0.1.2")))
    }
}
