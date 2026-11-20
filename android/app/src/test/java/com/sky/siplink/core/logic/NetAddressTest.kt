package com.sky.siplink.core.logic

import com.sky.siplink.core.logic.NetAddress.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetAddressTest {
    @Test
    fun `ipv4 addresses are sorted by the networks they belong to`() {
        assertEquals(Kind.PRIVATE, NetAddress.kind("10.1.2.3"))
        assertEquals(Kind.PRIVATE, NetAddress.kind("172.16.0.1"))
        assertEquals(Kind.PRIVATE, NetAddress.kind("172.31.255.254"))
        assertEquals(Kind.PUBLIC, NetAddress.kind("172.32.0.1"))
        assertEquals(Kind.PRIVATE, NetAddress.kind("192.168.10.55"))
        assertEquals(Kind.SHARED, NetAddress.kind("100.64.0.1"))
        assertEquals(Kind.SHARED, NetAddress.kind("100.127.255.255"))
        assertEquals(Kind.PUBLIC, NetAddress.kind("100.128.0.1"))
        assertEquals(Kind.LOOPBACK, NetAddress.kind("127.0.0.1"))
        assertEquals(Kind.LINK_LOCAL, NetAddress.kind("169.254.10.1"))
        assertEquals(Kind.PUBLIC, NetAddress.kind("203.0.113.7"))
        assertEquals(Kind.INVALID, NetAddress.kind("224.0.0.1"))
    }

    @Test
    fun `ipv6 addresses are sorted by the networks they belong to`() {
        assertEquals(Kind.LOOPBACK, NetAddress.kind("::1"))
        assertEquals(Kind.LINK_LOCAL, NetAddress.kind("fe80::1%wlan0"))
        assertEquals(Kind.UNIQUE_LOCAL, NetAddress.kind("fd00:1234::5"))
        assertEquals(Kind.PUBLIC, NetAddress.kind("2001:db8::1"))
        assertEquals(Kind.PUBLIC, NetAddress.kind("[2001:db8::1]"))
        assertEquals(Kind.PRIVATE, NetAddress.kind("::ffff:192.168.1.1"))
    }

    @Test
    fun `names and broken literals are no addresses`() {
        assertEquals(Kind.INVALID, NetAddress.kind("pbx.example.com"))
        assertNull(NetAddress.parse("1.2.3"))
        assertNull(NetAddress.parse("1.2.3.256"))
        assertNull(NetAddress.parse("1::2::3"))
        assertNull(NetAddress.parse("1:2:3:4:5:6:7"))
        assertNull(NetAddress.parse("12345::1"))
        assertNull(NetAddress.parse(""))
    }

    @Test
    fun `subnets are written with their prefix`() {
        assertEquals("192.168.10.0/24", NetAddress.subnet("192.168.10.55"))
        assertEquals("10.0.0.0/8", NetAddress.subnet("10.9.8.7", prefix4 = 8))
        assertEquals("2001:db8:1:2::/64", NetAddress.subnet("2001:db8:1:2:aaaa:bbbb:cccc:dddd"))
        assertNull(NetAddress.subnet("pbx.example.com"))
    }

    @Test
    fun `ipv6 is written in its short form`() {
        assertEquals("2001:db8::1", NetAddress.format(NetAddress.parse("2001:0db8:0000:0000:0000:0000:0000:0001")!!))
        assertEquals("::", NetAddress.format(ByteArray(16)))
        assertEquals("1:0:2::3", NetAddress.format(NetAddress.parse("1:0:2:0:0:0:0:3")!!))
    }

    @Test
    fun `an address lies in a network by its prefix`() {
        assertTrue(NetAddress.inSubnet("192.168.10.55", "192.168.10.0/24"))
        assertTrue(NetAddress.inSubnet("172.20.9.5", "172.20.1.0/16"))
        assertFalse(NetAddress.inSubnet("192.168.10.99", "192.168.10.0/28"))
        assertTrue(NetAddress.inSubnet("fe80::1234", "fe80::/64"))
        assertTrue(NetAddress.inSubnet("[2001:db8:1:2::9]", "2001:db8:1:2::/64"))
        // Families do not mix; what is no network says no.
        assertFalse(NetAddress.inSubnet("192.168.10.55", "2001:db8::/32"))
        assertFalse(NetAddress.inSubnet("192.168.10.55", "192.168.10.0"))
        assertFalse(NetAddress.inSubnet("192.168.10.55", "192.168.10.0/33"))
        assertFalse(NetAddress.inSubnet("pbx.example.com", "192.168.10.0/24"))
    }

    @Test
    fun `the same address is told however it is written`() {
        assertTrue(NetAddress.same("2001:db8::1", "2001:0DB8:0:0::1"))
        assertTrue(NetAddress.same("[2001:db8::1]", "2001:db8::1"))
        assertTrue(NetAddress.same("192.168.10.1", "192.168.10.1"))
        assertFalse(NetAddress.same("192.168.10.1", "192.168.10.2"))
        assertFalse(NetAddress.same("pbx.example.com", "pbx.example.com"))
    }

    @Test
    fun `the servers are asked at the address the sip core goes to`() {
        val both = listOf("192.0.2.5", "2001:db8::5", "192.0.2.6")
        assertEquals(listOf("192.0.2.5", "192.0.2.6"), NetAddress.coreOrder(both, ipv6 = false, sixFirst = true))
        assertEquals(listOf("2001:db8::5", "192.0.2.5", "192.0.2.6"), NetAddress.coreOrder(both, ipv6 = true, sixFirst = true))
        assertEquals(listOf("192.0.2.5", "192.0.2.6", "2001:db8::5"), NetAddress.coreOrder(both, ipv6 = true, sixFirst = false))
        // As the registration went, else by whether the phone has IPv6 beyond its link.
        assertTrue(NetAddress.sixFirst("2001:db8::77", listOf("192.168.10.55")))
        assertFalse(NetAddress.sixFirst("203.0.113.7", listOf("2001:db8:1::5")))
        assertTrue(NetAddress.sixFirst(null, listOf("192.168.10.55", "2001:db8:1::5")))
        assertFalse(NetAddress.sixFirst(null, listOf("192.168.10.55")))
        assertFalse(NetAddress.sixFirst("pbx.example.com", listOf("192.168.10.55")))
    }
}
