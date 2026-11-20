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
        subnets = listOf("192.168.10.0/24", "2001:db8:1:2::/64", "fe80::/64"),
        gateways = listOf("192.168.10.1", "fe80::1"),
        dns = listOf("192.168.10.1", "2001:db8:1:2::1"),
        validated = true,
        captive = false,
    )

    /** The same network before its IPv6 came: IPv4 alone. */
    private val homeIpv4 = home.copy(
        addresses = listOf("192.168.10.55"),
        subnets = listOf("192.168.10.0/24"),
        gateways = listOf("192.168.10.1"),
        dns = listOf("192.168.10.1"),
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
        assertNotEquals(NetworkKey.of(home), NetworkKey.of(home.copy(subnets = listOf("10.0.0.0/24"))))
        assertNotEquals(NetworkKey.of(home), NetworkKey.of(home.copy(dns = listOf("192.168.10.2"))))
    }

    @Test
    fun `several addresses in one network count once`() {
        val two = home.copy(
            addresses = listOf("192.168.10.55", "192.168.10.56", "2001:db8:1:2::abcd", "2001:db8:1:2::beef"),
            subnets = listOf("192.168.10.0/24", "192.168.10.0/24", "2001:db8:1:2::/64", "2001:db8:1:2::/64"),
        )
        assertEquals(NetworkKey.of(home), NetworkKey.of(two))
    }

    @Test
    fun `a new ipv6 prefix from the provider is the same network`() {
        val rotated = home.copy(
            addresses = listOf("192.168.10.55", "2001:db8:9:9::abcd"),
            subnets = listOf("192.168.10.0/24", "2001:db8:9:9::/64", "fe80::/64"),
            dns = listOf("192.168.10.1", "2001:db8:9:9::1"),
        )
        assertEquals(NetworkKey.of(home), NetworkKey.of(rotated))
    }

    @Test
    fun `ipv6 that comes a moment after ipv4 is the same network`() {
        assertEquals(NetworkKey.of(homeIpv4), NetworkKey.of(home))
    }

    @Test
    fun `a network with ipv6 alone is known by its link-local gateway and its own name servers`() {
        val six = home.copy(
            addresses = listOf("2001:db8:1:2::abcd"),
            subnets = listOf("2001:db8:1:2::/64", "fe80::/64"),
            gateways = listOf("fe80::1"),
            dns = listOf("fd00::53"),
        )
        assertEquals(NetworkKey.of(six), NetworkKey.of(six.copy(addresses = listOf("2001:db8:7:7::1"), subnets = listOf("2001:db8:7:7::/64"))))
        assertNotEquals(NetworkKey.of(six), NetworkKey.of(six.copy(gateways = listOf("fe80::2"))))
    }

    @Test
    fun `the real prefix tells networks apart that a 24 bit one would not`() {
        val small = homeIpv4.copy(subnets = listOf("192.168.10.0/28"))
        val wide = homeIpv4.copy(subnets = listOf("192.168.0.0/16"))
        assertNotEquals(NetworkKey.of(small), NetworkKey.of(wide))
    }

    @Test
    fun `a mobile network is known by its name servers, not by the address it hands out`() {
        val mobile = home.copy(
            type = NetType.CELLULAR,
            addresses = listOf("10.120.4.5"),
            subnets = listOf("10.120.4.5/32"),
            gateways = emptyList(),
            dns = listOf("211.136.17.107"),
        )
        assertEquals(NetworkKey.of(mobile), NetworkKey.of(mobile.copy(addresses = listOf("100.70.1.2"), subnets = listOf("100.70.1.0/30"))))
        // One with IPv6 alone: by its provider's name servers.
        val six = mobile.copy(dns = listOf("2001:db8:53::1"))
        assertNotEquals(NetworkKey.of(six), NetworkKey.of(six.copy(dns = listOf("2001:db8:53::2"))))
    }

    @Test
    fun `a vpn is known by its name servers, and single addresses count nowhere`() {
        val vpn = home.copy(
            vpn = true,
            addresses = listOf("10.8.0.6"),
            subnets = listOf("10.8.0.6/32"),
            gateways = listOf("10.8.0.5"),
            dns = listOf("10.8.0.1"),
        )
        // Another address at every connection, alone in a /32 or a /31.
        val again = vpn.copy(addresses = listOf("10.8.0.10"), subnets = listOf("10.8.0.10/31"), gateways = listOf("10.8.0.9"))
        assertEquals(NetworkKey.of(vpn), NetworkKey.of(again))
        assertNotEquals(NetworkKey.of(vpn), NetworkKey.of(vpn.copy(dns = listOf("10.9.0.1"))))
        // A point-to-point address beside the network's own counts nowhere either.
        assertEquals(NetworkKey.of(homeIpv4), NetworkKey.of(homeIpv4.copy(subnets = listOf("192.168.10.0/24", "10.64.0.2/32"))))
        assertEquals(NetworkKey.of(homeIpv4), NetworkKey.of(homeIpv4.copy(subnets = listOf("192.168.10.0/24", "10.64.0.2/31"))))
        assertNotEquals(NetworkKey.of(homeIpv4), NetworkKey.of(homeIpv4.copy(subnets = listOf("192.168.10.0/24", "10.64.0.0/30"))))
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
    fun `a change of the ipv6 prefix alone is told from one of the ipv4 address`() {
        val rotated = home.copy(addresses = listOf("192.168.10.55", "2001:db8:9:9::abcd"))
        assertTrue(NetworkKey.unnoticedChange(home, rotated))
        assertTrue(NetworkKey.onlyIpv6Changed(home, rotated))
        assertTrue(NetworkKey.onlyIpv6Changed(homeIpv4, home))
        assertFalse(NetworkKey.onlyIpv6Changed(home, home.copy(addresses = listOf("192.168.10.56", "2001:db8:9:9::abcd"))))
        assertFalse(NetworkKey.onlyIpv6Changed(home, home))
    }

    @Test
    fun `the address shown is the ipv4 one`() {
        assertEquals("192.168.10.55", NetworkKey.localAddress(home.copy(addresses = listOf("2001:db8::1", "192.168.10.55"))))
        assertEquals("2001:db8::1", NetworkKey.localAddress(home.copy(addresses = listOf("2001:db8::1"))))
    }
}
