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
        assertFalse(NetAddress.isLiteral("example"))
        assertTrue(NetAddress.isLiteral("::"))
    }

    @Test
    fun `subnets are written with their prefix`() {
        assertEquals("192.168.10.0/24", NetAddress.subnet("192.168.10.55"))
        assertEquals("10.0.0.0/8", NetAddress.subnet("10.9.8.7", prefix4 = 8))
        assertEquals("2001:db8:1:2::/64", NetAddress.subnet("2001:db8:1:2:aaaa:bbbb:cccc:dddd"))
        assertTrue(NetAddress.sameSubnet("192.168.10.55", "192.168.10.1"))
        assertFalse(NetAddress.sameSubnet("192.168.10.55", "192.168.20.19"))
        assertFalse(NetAddress.sameSubnet("192.168.10.55", "pbx.example.com"))
    }

    @Test
    fun `ipv6 is written in its short form`() {
        assertEquals("2001:db8::1", NetAddress.format(NetAddress.parse("2001:0db8:0000:0000:0000:0000:0000:0001")!!))
        assertEquals("::", NetAddress.format(ByteArray(16)))
        assertEquals("1:0:2::3", NetAddress.format(NetAddress.parse("1:0:2:0:0:0:0:3")!!))
    }
}
