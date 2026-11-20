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
 * set-up slowed by the gathering, given up on this network otherwise ([afterCall]). A call that comes in from a
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

        /**
         * Tried, and the sound still went one way, the calls were set up too slowly or failed in
         * their set-up, or [TRIAL_CALLS] calls told nothing: not tried again on this network for
         * [REFUSED_MS].
         */
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
     * Whether ICE took part in a call: one out did where it offered candidates ([offered]: its
     * account's NAT policy had ICE as it started); one that came in only where the other side
     * offered ICE as well ([iceState]: the SIP core's IceState of its sound).
     */
    fun iceTookPart(outgoing: Boolean, offered: Boolean, iceState: String): Boolean =
        if (outgoing) offered else iceState.isNotEmpty() && iceState != NOT_ACTIVATED

    /**
     * Whether a call out that failed with SIP status [code] before its sound ran may be the doing
     * of ICE: its INVITE, larger with the candidates, got no answer (408) or was too large for the
     * other side (513), or the other side, or an SBC on the way, could not take the offer (400,
     * 415, 488, 606).
     */
    fun isSetupFailure(code: Int): Boolean = code in SETUP_FAILURES

    /** Ends of a call out that are the other side's or the user's choice (busy, cancelled, declined): they tell nothing about ICE, nor count. */
    private val CHOSEN = setOf(486, 487, 600, 603)

    /**
     * Calls with ICE on trial that told nothing (too short for a verdict, or failed in a way ICE
     * does not cause, such as 480 or 503), after which ICE is given up on the line.
     */
    const val TRIAL_CALLS = 5

    /** How long ICE given up stays given up on a network: then a call whose sound goes one way may try it again. */
    const val REFUSED_MS = 7 * LineAdapt.DAY_MS

    /**
     * The record of a line after a call ICE [tookPart] in, while ICE is on trial there. Sound both
     * ways ([flow]) with a set-up no slower than [SLOW_SETUP_MS] ([setupMs]: from the start of a
     * call out to its INVITE; null for one that came in) keeps it: in use until it is given up to
     * see whether the line still needs it ([iceWithdrawn]), later each time the line needed it again.
     * Sound one way, a slow set-up, or a call out that failed before its sound ran in a way ICE
     * can cause ([failedWith], [isSetupFailure]) gives it up on this network for [REFUSED_MS].
     * Busy, cancelled or declined tells nothing, nor counts; [TRIAL_CALLS] calls that tell
     * nothing otherwise (too short for a verdict, or failed in another way) give it up as well.
     */
    fun afterCall(record: LineAdapt.Record, flow: MediaFlow?, tookPart: Boolean, setupMs: Long?, failedWith: Int?, now: Long): LineAdapt.Record {
        if (record.ice != Ice.TRIAL || !tookPart || failedWith in CHOSEN) return record
        val ice = when {
            failedWith != null -> Ice.REFUSED.takeIf { isSetupFailure(failedWith) }
            flow == null -> null
            flow == MediaFlow.BOTH && (setupMs == null || setupMs <= SLOW_SETUP_MS) -> Ice.ON
            else -> Ice.REFUSED
        }
        val calls = record.iceCalls + 1
        if (ice == null && calls < TRIAL_CALLS) return record.copy(iceCalls = calls)
        val verdict = ice ?: Ice.REFUSED
        val until = if (verdict == Ice.ON) now + LineAdapt.retestDelay(record.iceRetests) else now + REFUSED_MS
        return record.copy(ice = verdict, iceCalls = 0, iceRetestAt = until)
    }

    /**
     * ICE in use is given up when its time came ([LineAdapt.Record.iceRetestAt]), between calls: the
     * line tries it again when its sound goes one way again, and keeps it longer then. ICE given up
     * may be tried again once [REFUSED_MS] are over.
     */
    fun iceWithdrawn(record: LineAdapt.Record, now: Long, calls: Int): LineAdapt.Record = when {
        record.ice == Ice.ON && now >= record.iceRetestAt && calls == 0 -> record.copy(ice = Ice.NONE, iceRetestAt = 0, iceRetests = record.iceRetests + 1)
        record.ice == Ice.REFUSED && now >= record.iceRetestAt -> record.copy(ice = Ice.NONE, iceRetestAt = 0)
        else -> record
    }

    /** The SIP core's name of a call that goes without ICE (org.linphone.core.IceState). */
    private const val NOT_ACTIVATED = "NotActivated"

    /** Bad Request, Request Timeout, Unsupported Media Type, Not Acceptable Here, Message Too Large, Not Acceptable. */
    private val SETUP_FAILURES = setOf(400, 408, 415, 488, 513, 606)

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
     * network: no public address, neither the server the line connects to ([server]: the name it
     * is set up with and the addresses that stands for) nor in a network of this phone
     * ([local]: the phone's networks, `network/prefix` with their real prefix lengths, link-local
     * ones among them). The requests sent there go nowhere: the ACK of a call out (the PBX then
     * ends the call after half a minute) and the BYE. Without the phone's networks there is no
     * verdict.
     */
    fun unreachableHop(hop: String?, server: Collection<String>, local: Collection<String>): Boolean {
        val host = hop?.removePrefix("[")?.removeSuffix("]") ?: return false
        if (local.isEmpty()) return false
        val kind = NetAddress.kind(host)
        if (kind == NetAddress.Kind.PUBLIC || kind == NetAddress.Kind.INVALID) return false
        val bytes = NetAddress.parse(host) ?: return false
        if (server.any { NetAddress.parse(it)?.contentEquals(bytes) == true }) return false
        return local.none { NetAddress.inSubnet(host, it) }
    }
}
