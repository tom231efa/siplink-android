package com.sky.siplink.core.logic

import com.sky.siplink.core.model.NetType
import com.sky.siplink.core.model.NetworkFacts
import java.security.MessageDigest

/**
 * Tells networks apart without their names (the name of a Wi-Fi network needs the location
 * permission): by the kind of connection, the subnets of the phone's addresses, the gateways and
 * the name servers. Two homes that both use 192.168.1.0/24 behind 192.168.1.1 with the same
 * name servers look alike; what was learned on one is then tried on the other, and undone there
 * if it does not hold.
 */
object NetworkKey {
    /** Stays the same for one network across connections. */
    fun of(facts: NetworkFacts): String {
        val subnets = facts.addresses.mapNotNull { NetAddress.subnet(it) }.sorted()
        // A mobile network hands out another address now and then: its name servers say more.
        val parts = listOf(facts.type.name, if (facts.vpn) "vpn" else "") +
            (if (facts.type == NetType.CELLULAR) emptyList() else subnets + facts.gateways.sorted()) +
            facts.dns.sorted()
        val digest = MessageDigest.getInstance("SHA-256").digest(parts.joinToString("|").toByteArray())
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    /**
     * Changes whenever the phone's addresses on the network change. IPv6 counts by its prefix:
     * the address within it changes by itself every few hours.
     */
    fun addresses(facts: NetworkFacts): String {
        val four = facts.addresses.filter { NetAddress.parse(it)?.size == 4 }.sorted()
        val six = facts.addresses.filter { NetAddress.parse(it)?.size == 16 }.mapNotNull { NetAddress.subnet(it) }.sorted()
        return (listOf(facts.type.name, facts.vpn.toString()) + four + six.distinct()).joinToString("|")
    }

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

    /** The phone's own address to show: IPv4 first. */
    fun localAddress(facts: NetworkFacts): String? =
        facts.addresses.firstOrNull { NetAddress.parse(it)?.size == 4 } ?: facts.addresses.firstOrNull()
}
