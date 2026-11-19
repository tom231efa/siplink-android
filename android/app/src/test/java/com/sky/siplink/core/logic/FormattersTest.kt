package com.sky.siplink.core.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FormattersTest {
    @Test
    fun `durations`() {
        assertEquals("0:00", Formatters.duration(0))
        assertEquals("0:07", Formatters.duration(7))
        assertEquals("12:34", Formatters.duration(754))
        assertEquals("1:02:03", Formatters.duration(3723))
        assertEquals("0:00", Formatters.duration(-5))
    }

    @Test
    fun `initials`() {
        assertEquals("AS", Formatters.initials("alice smith"))
        assertEquals("B", Formatters.initials("Bob"))
        assertEquals("三丰", Formatters.initials("张三丰"))
        assertEquals("01", Formatters.initials("1001"))
        assertEquals("#", Formatters.initials("  "))
    }

    @Test
    fun `addresses`() {
        assertEquals("1001@pbx.local", Formatters.shortAddress("sip:1001@pbx.local;transport=tls"))
        assertEquals("1001", Formatters.userPart("sip:1001@pbx.local"))
        assertEquals("1001", Formatters.userPart("1001"))
        assertEquals("alice", Formatters.userPart("alice@example.com:5060"))
        assertEquals("1002@pbx.local", Formatters.shortAddress("SIPS:1002@pbx.local"))
    }

    @Test
    fun `escapes of a user part are read`() {
        assertEquals("*911005", Formatters.unescaped("%2A911005"))
        assertEquals("*911005", Formatters.unescaped("%2a%39%311005"))
        assertEquals("张伟", Formatters.unescaped("%E5%BC%A0%E4%BC%9F"))
        assertEquals("1005", Formatters.unescaped("1005"))
        // A % that starts no escape stays.
        assertEquals("50%", Formatters.unescaped("50%"))
        assertEquals("%zz1", Formatters.unescaped("%zz1"))
        assertEquals("a%2", Formatters.unescaped("a%2"))
    }

    @Test
    fun `dtmf characters`() {
        "0123456789*#".forEach { assertTrue(Formatters.isDtmf(it)) }
        assertFalse(Formatters.isDtmf('A'))
        assertFalse(Formatters.isDtmf('+'))
    }
}
