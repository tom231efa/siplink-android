package com.sky.siplink.core.logic

import com.sky.siplink.core.model.NetType
import com.sky.siplink.core.model.NetworkFacts
import java.security.MessageDigest

/**
 * Tells networks apart without their names (the name of a Wi-Fi network needs the location
 * permission): by the kind of connection, the IPv4 subnets of the phone, the gateways and the
 * name servers. Two homes that both use 192.168.1.0/24 behind 192.168.1.1 with the same name
 * servers look alike; what was learned on one is then tried on the other, and undone there if
 * it does not hold.
 *
 * IPv6 counts only where the network has no IPv4: its global prefixes change by themselves
 * (a provider hands out another one now and then), and it often comes a moment after IPv4. A
 * mobile network and a VPN hand out another address at every connection, often alone in a /31 or
 * /32: they are known by their name servers only, and such single addresses count nowhere.
 */
object NetworkKey {
    /** Stays the same for one network across connections. */
    fun of(facts: NetworkFacts): String {
        val subnets = facts.subnets.filter { isIpv4(it.substringBefore('/')) && !isSingle(it) }.distinct().sorted()
        val gateways = facts.gateways.filter(::isIpv4)
            .ifEmpty { facts.gateways.filter { NetAddress.kind(it) == NetAddress.Kind.LINK_LOCAL } }
        val dnsOnly = facts.type == NetType.CELLULAR || facts.vpn
        // The name servers of a provider or a VPN stay; a home router's own global IPv6 address goes with its prefix.
        val dns = facts.dns.filter(::isIpv4).ifEmpty { facts.dns.filter { dnsOnly || NetAddress.kind(it) != NetAddress.Kind.PUBLIC } }
        val parts = listOf(facts.type.name, if (facts.vpn) "vpn" else "") +
            (if (dnsOnly) emptyList() else subnets + gateways.distinct().sorted()) +
            dns.distinct().sorted()
        val digest = MessageDigest.getInstance("SHA-256").digest(parts.joinToString("|").toByteArray())
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    private fun isIpv4(address: String): Boolean = NetAddress.parse(address)?.size == 4

    /** A subnet of one or two IPv4 addresses (/31, /32): a point-to-point link, no network. */
    private fun isSingle(subnet: String): Boolean = (subnet.substringAfterLast('/', "").toIntOrNull() ?: 0) >= POINT_TO_POINT

    private const val POINT_TO_POINT = 31

    /**
     * Changes whenever the phone's addresses on the network change. IPv6 counts by its prefix:
     * the address within it changes by itself every few hours.
     */
    fun addresses(facts: NetworkFacts): String = (listOf(facts.type.name, facts.vpn.toString()) + ipv4(facts) + ipv6Prefixes(facts)).joinToString("|")

    private fun ipv4(facts: NetworkFacts): List<String> = facts.addresses.filter(::isIpv4).sorted()

    private fun ipv6Prefixes(facts: NetworkFacts): List<String> =
        facts.addresses.filter { NetAddress.parse(it)?.size == 16 }.mapNotNull { NetAddress.subnet(it) }.distinct().sorted()

    /**
     * Whether going from [before] to [after] is a change of network that the SIP core does not
     * notice by itself: it does when the kind of connection changes, not when the phone moves to
     * another network of the same kind or gets another address on the same one.
     */
    fun unnoticedChange(before: NetworkFacts?, after: NetworkFacts?): Boolean {
        if (before == null || after == null) return false
        if (before.type != after.type || before.vpn != after.vpn) return false
        return addresses(before) != addresses(after)
    }

    /** Whether only the IPv6 prefixes changed between [before] and [after]: the IPv4 addresses stayed. */
    fun onlyIpv6Changed(before: NetworkFacts, after: NetworkFacts): Boolean =
        ipv4(before) == ipv4(after) && ipv6Prefixes(before) != ipv6Prefixes(after)

    /** The phone's own address to show: IPv4 first. */
    fun localAddress(facts: NetworkFacts): String? =
        facts.addresses.firstOrNull(::isIpv4) ?: facts.addresses.firstOrNull()
}
