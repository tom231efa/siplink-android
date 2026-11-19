package com.sky.siplink.core.logic

import com.sky.siplink.core.model.NetType
import com.sky.siplink.core.model.NetworkFacts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkKeyTest {
    private val home = NetworkFacts(
        type = NetType.WIFI,
        vpn = false,
        addresses = listOf("192.168.10.55", "2001:db8:1:2::abcd"),
        gateways = listOf("192.168.10.1"),
        dns = listOf("192.168.10.1"),
        validated = true,
        captive = false,
    )

    @Test
    fun `a network keeps its key across addresses within it`() {
        val again = home.copy(addresses = listOf("192.168.10.80", "2001:db8:1:2::1234"))
        assertEquals(NetworkKey.of(home), NetworkKey.of(again))
        assertEquals(16, NetworkKey.of(home).length)
    }

    @Test
    fun `other networks get other keys`() {
        assertNotEquals(NetworkKey.of(home), NetworkKey.of(home.copy(gateways = listOf("192.168.10.20"))))
        assertNotEquals(NetworkKey.of(home), NetworkKey.of(home.copy(vpn = true)))
        assertNotEquals(NetworkKey.of(home), NetworkKey.of(home.copy(addresses = listOf("10.0.0.5"))))
    }

    @Test
    fun `a mobile network is known by its name servers, not by the address it hands out`() {
        val mobile = home.copy(type = NetType.CELLULAR, addresses = listOf("10.120.4.5"), gateways = emptyList(), dns = listOf("211.136.17.107"))
        assertEquals(NetworkKey.of(mobile), NetworkKey.of(mobile.copy(addresses = listOf("100.70.1.2"))))
    }

    @Test
    fun `the core misses a change of network of the same kind`() {
        val other = home.copy(addresses = listOf("192.168.20.40"), gateways = listOf("192.168.20.1"))
        assertTrue(NetworkKey.unnoticedChange(home, other))
        // The SIP core itself notices a change of the kind of connection.
        assertFalse(NetworkKey.unnoticedChange(home, other.copy(type = NetType.CELLULAR)))
        assertFalse(NetworkKey.unnoticedChange(home, home.copy(vpn = true)))
        assertFalse(NetworkKey.unnoticedChange(null, home))
    }

    @Test
    fun `a new private ipv6 address in the same prefix is no change`() {
        assertFalse(NetworkKey.unnoticedChange(home, home.copy(addresses = listOf("192.168.10.55", "2001:db8:1:2::ffff"))))
        assertTrue(NetworkKey.unnoticedChange(home, home.copy(addresses = listOf("192.168.10.56", "2001:db8:1:2::abcd"))))
    }

    @Test
    fun `the address shown is the ipv4 one`() {
        assertEquals("192.168.10.55", NetworkKey.localAddress(home.copy(addresses = listOf("2001:db8::1", "192.168.10.55"))))
        assertEquals("2001:db8::1", NetworkKey.localAddress(home.copy(addresses = listOf("2001:db8::1"))))
    }
}
