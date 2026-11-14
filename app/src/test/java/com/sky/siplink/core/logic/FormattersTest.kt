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
    }

    @Test
    fun `dtmf characters`() {
        "0123456789*#".forEach { assertTrue(Formatters.isDtmf(it)) }
        assertFalse(Formatters.isDtmf('A'))
        assertFalse(Formatters.isDtmf('+'))
    }
}
