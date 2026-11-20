package com.sky.siplink.core.logic

import com.sky.siplink.core.model.MediaFlow
import com.sky.siplink.core.model.NatKind

/**
 * Rules of the adaptation of a line's sound, learned from the calls: ICE, on one line (an account
 * on one network), after a call whose sound went one way only, behind a NAT that STUN can see
 * through, with a STUN server set. The SIP core then offers its candidates with the address the
 * STUN server sees as the default one (IceService chooseDefaultCandidates: relay, then server
 * reflexive, then host), which a PBX without ICE takes from the offer's c= and m= lines; a PBX
 * with ICE checks the candidates. Kept once a call ICE took part in had sound both ways without a
 * set-up slowed by the gathering, given up on this network otherwise. A call that comes in from a
 * PBX without ICE goes without it (IceService::updateFromRemoteMediaDescription deletes the
 * session) and tells nothing.
 *
 * A PBX that names an address of its own network as the next hop of its calls (Contact, or a
 * Record-Route) stands behind a NAT it does not know of. Nothing on the phone repairs that: the
 * requests within a call (ACK, BYE) go to that hop whatever the account's route (op.cpp
 * addInitialRouteSet leaves a route out that names the host of the request URI; dialog.c
 * belle_sip_dialog_establish takes the route set of a call from the Record-Route of its answer
 * alone, RFC 3261 12.1.2). The line checks show it ([unreachableHop]).
 *
 * STUN alone (a NAT policy with STUN and without ICE) is not among them: with the SIP core of the
 * app (liblinphone 5.5.39) it asks the STUN server for calls that come in only, not while IPv6 is
 * on, and its answer then names the STUN port with the phone's own address at the stream
 * (MS2Stream::fillLocalMediaDescription writes the local address after the STUN client wrote the
 * one it found), so the other side still sends to the phone's own address.
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

    /** Where ICE stands on a line. */
    enum class Ice {
        /** Not tried. */
        NONE,

        /** In use, until a call ICE takes part in shows whether it helps. */
        TRIAL,

        /** In use: a call had sound both ways with it. */
        ON,

        /** Tried, and the sound still went one way, or the calls were set up too slowly: not tried again on this network. */
        REFUSED,
    }

    /**
     * How much longer than without ICE a call out may take to be set up (the SIP core sends its
     * INVITE once the candidates are gathered) before ICE costs more than it brings.
     */
    const val SLOW_SETUP_MS = 2_000L

    /**
     * Whether a line whose last call went [flow] tries ICE: the sound went one way only, a NAT
     * is in the way that STUN can see through ([nat]; not a symmetric one, and not one nobody
     * could tell), a STUN server is set, and the line did not try it before.
     */
    fun tryIce(flow: MediaFlow?, nat: NatKind, stunSet: Boolean, ice: Ice): Boolean =
        (flow == MediaFlow.SEND_ONLY || flow == MediaFlow.RECEIVE_ONLY) &&
            (nat == NatKind.PRESENT || nat == NatKind.CARRIER) && stunSet && ice == Ice.NONE

    /**
     * Whether ICE took part in a call: one out offered its candidates; one that came in did only
     * where the other side offered ICE as well ([iceState]: the SIP core's IceState of its sound).
     */
    fun iceTookPart(outgoing: Boolean, iceState: String): Boolean = outgoing || (iceState.isNotEmpty() && iceState != NOT_ACTIVATED)

    /**
     * Where ICE stands after a call of [flow] on a line where it stood at [ice]. Only a call ICE
     * [tookPart] in, with a verdict on its sound, tells: sound both ways and a set-up no slower
     * than [SLOW_SETUP_MS] ([setupMs]: from the start of a call out to its INVITE; null for one
     * that came in) keep it; anything else gives it up.
     */
    fun iceAfter(ice: Ice, flow: MediaFlow?, tookPart: Boolean, setupMs: Long?): Ice = when {
        ice != Ice.TRIAL || flow == null || !tookPart -> ice
        flow == MediaFlow.BOTH && (setupMs == null || setupMs <= SLOW_SETUP_MS) -> Ice.ON
        else -> Ice.REFUSED
    }

    /** The SIP core's name of a call that goes without ICE (org.linphone.core.IceState). */
    private const val NOT_ACTIVATED = "NotActivated"

    /**
     * The host the requests within a call go to: the Record-Route entry next to this phone
     * ([recordRoute], the header of the other side's last message; the last entry for a call
     * out, the first for one that came in, RFC 3261 12.1.1/12.1.2), else the host of the other
     * side's [contact]. Null when neither names one.
     */
    fun nextHop(contact: String?, recordRoute: String?, outgoing: Boolean): String? {
        val routes = routeHosts(recordRoute)
        val hop = if (routes.isEmpty()) contact else if (outgoing) routes.last() else routes.first()
        return hop?.removePrefix("[")?.removeSuffix("]")?.takeIf { it.isNotEmpty() }
    }

    /** The hosts of the URIs in a Record-Route value such as `<sip:p1.example.com;lr>, <sip:[fd00::1]:5060;lr>`. */
    fun routeHosts(value: String?): List<String> {
        if (value.isNullOrBlank()) return emptyList()
        // Entries are split at the commas outside <> and quotes (RFC 3261 25.1: rec-route).
        val entries = ArrayList<String>()
        var depth = 0
        var quoted = false
        var from = 0
        value.forEachIndexed { i, c ->
            when {
                c == '"' && (i == 0 || value[i - 1] != '\\') -> quoted = !quoted
                quoted -> Unit
                c == '<' -> depth++
                c == '>' -> depth--
                c == ',' && depth == 0 -> {
                    entries += value.substring(from, i)
                    from = i + 1
                }
            }
        }
        entries += value.substring(from)
        return entries.mapNotNull(::uriHost)
    }

    private fun uriHost(entry: String): String? {
        val uri = entry.substringAfter('<', entry).substringBefore('>').trim()
        val scheme = uri.substringBefore(':', "").lowercase()
        if (scheme != "sip" && scheme != "sips") return null
        val hostPort = uri.substringAfter(':').substringAfter('@').substringBefore(';').substringBefore('?')
        val host = if (hostPort.startsWith("[")) hostPort.substringBefore(']') + "]" else hostPort.substringBefore(':')
        return host.takeIf { it.isNotEmpty() && it != "]" }
    }

    /**
     * Whether the next hop of a call ([hop], see [nextHop]) is an address of the PBX's own
     * network: no public address, and neither the server the line connects to ([server]: the
     * name it is set up with and the addresses that stands for) nor in a network of this phone
     * ([local], its own addresses). The requests sent there go nowhere: the ACK of a call out
     * (the PBX then ends the call after half a minute) and the BYE.
     */
    fun unreachableHop(hop: String?, server: Collection<String>, local: Collection<String>): Boolean {
        val host = hop?.removePrefix("[")?.removeSuffix("]") ?: return false
        val kind = NetAddress.kind(host)
        if (kind == NetAddress.Kind.PUBLIC || kind == NetAddress.Kind.INVALID) return false
        val bytes = NetAddress.parse(host) ?: return false
        if (server.any { NetAddress.parse(it)?.contentEquals(bytes) == true }) return false
        val subnet = NetAddress.subnet(host)
        return local.none { NetAddress.subnet(it) == subnet }
    }
}
