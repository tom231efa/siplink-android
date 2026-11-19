package com.sky.siplink.core.logic


/**
 * Rules of the adaptation of a line's sound, learned from the calls: for an account whose PBX
 * announces an address of its own network (it stands behind a NAT that it does not know of),
 * the requests of its calls by way of the server, and symmetric RTP (the sound goes back to
 * where the other side's sound comes from).
 *
 * STUN for the sound is not among them: with the SIP core of the app (liblinphone 5.5.39) a NAT
 * policy with STUN and without ICE asks the STUN server for calls that come in only, not while
 * IPv6 is on, and its answer then names the STUN port with the phone's own address at the stream
 * (MS2Stream::fillLocalMediaDescription writes the local address after the STUN client wrote the
 * one it found), so the other side still sends to the phone's own address. The STUN server only
 * serves the line checks.
 */
object MediaAdapt {
    /** RFC 5389 §9. */
    const val STUN_PORT = 3478

    /**
     * Whether [value] can be the STUN server setting: empty for none, else `host[:port]` as an
     * account's server address ([AccountRules.isValidHostPort]). The platform checks the same
     * (platform/internal/rules/settings.go IsValidStunServer).
     */
    fun isValidStunServer(value: String): Boolean = value.isEmpty() || AccountRules.isValidHostPort(value)

    /** Host and port of the STUN server setting `host[:port]` (trimmed); null for none, or one that cannot be used. */
    fun stunServer(value: String): Pair<String, Int>? {
        val text = value.trim()
        if (!AccountRules.isValidHostPort(text)) return null
        val (host, port) = AccountRoute.split(text)
        return host.removePrefix("[").removeSuffix("]") to (port ?: STUN_PORT)
    }
    /**
     * Whether a PBX whose Contact names [contact] (its host) announces an address of its own
     * network: one that is no public address, and neither the server the line connects to
     * ([server]: the name it is set up with and the addresses that stands for) nor in a network
     * of this phone ([local], its own addresses). Requests sent there go nowhere: the ACK of a
     * call out (the PBX then ends the call after half a minute) and the BYE.
     */
    fun pbxBehindNat(contact: String?, server: Collection<String>, local: Collection<String>): Boolean {
        val host = contact?.removePrefix("[")?.removeSuffix("]") ?: return false
        val kind = NetAddress.kind(host)
        if (kind == NetAddress.Kind.PUBLIC || kind == NetAddress.Kind.INVALID) return false
        val bytes = NetAddress.parse(host) ?: return false
        if (server.any { NetAddress.parse(it)?.contentEquals(bytes) == true }) return false
        val subnet = NetAddress.subnet(host)
        return local.none { NetAddress.subnet(it) == subnet }
    }

    /** Calls in a row with a PBX that announces a reachable address before its remedy is lifted. */
    const val CALLS_TO_LIFT = 3

    /**
     * The calls in a row since the PBX of an account last announced an address of its own
     * network, after a call where it did so or not ([behind]); null: the account has no remedy
     * (none was needed, or it was lifted after [CALLS_TO_LIFT] calls).
     */
    fun pbxAfter(count: Int?, behind: Boolean): Int? = when {
        behind -> 0
        count == null || count + 1 >= CALLS_TO_LIFT -> null
        else -> count + 1
    }
}
