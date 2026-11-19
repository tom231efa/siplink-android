package com.sky.siplink.core.model

/** The kind of connection the phone reaches the internet over. */
enum class NetType { WIFI, CELLULAR, ETHERNET, OTHER }

/**
 * What the phone knows about the network it is on, read from the system (no permission beyond
 * the network state is needed: the name of a Wi-Fi network is not part of it).
 *
 * @param addresses the phone's own addresses on it, link-local ones left out
 * @param gateways the next hops of its default routes
 * @param validated the system reached the internet through it
 * @param captive the system found a login page in the way
 */
data class NetworkFacts(
    val type: NetType,
    val vpn: Boolean,
    val addresses: List<String>,
    val gateways: List<String>,
    val dns: List<String>,
    val validated: Boolean,
    val captive: Boolean,
)

/** What lies between the phone and a server, as far as the probes could tell. */
enum class NatKind {
    /** The server sees the phone's own address. */
    NONE,

    /** The server sees another address: the phone is behind a NAT. */
    PRESENT,

    /** As [PRESENT], and the phone's own address is one of a carrier's shared space (RFC 6598). */
    CARRIER,

    /** Two servers see the same socket on different ports: STUN is no help behind it. */
    SYMMETRIC,

    /** Nothing answered that could tell. */
    UNKNOWN,
}

/**
 * The answer of a SIP server to an OPTIONS request of the app's own.
 *
 * @param status the status code of the answer
 * @param rttMs time from the request to the answer
 * @param received the address the server saw the request come from (Via `received`)
 * @param rport the port it saw (Via `rport`)
 * @param sentByIntact the Via came back as it was sent
 * @param lengthIntact the Content-Length of the answer matches its body
 */
data class OptionsAnswer(
    val status: Int,
    val rttMs: Long,
    val received: String?,
    val rport: Int?,
    val sentByIntact: Boolean,
    val lengthIntact: Boolean,
)

/** An address and port seen from outside: by a STUN server, or by a SIP server in its Via. */
data class Mapped(val address: String, val port: Int)

/** How the sound of a call went, from the counters of the SIP core. */
enum class MediaFlow { BOTH, SEND_ONLY, RECEIVE_ONLY, NONE }

/** The last call of an account, as the line checks show it. */
data class CallMediaSummary(
    val flow: MediaFlow,
    /** Of what this phone received, in percent. */
    val lossDown: Float,
    /** Of what this phone sent, as the other side reported it, in percent. */
    val lossUp: Float,
    val jitterMs: Float,
    val roundTripMs: Float,
    /** The SIP core's name of the ICE state (org.linphone.core.IceState). */
    val ice: String,
    /** The call ended because no sound arrived any more (SIP core: 503 "Media lost"). */
    val mediaLost: Boolean,
)

/**
 * What the line checks found for one account. A null value is a check that did not run or had
 * nothing to look at.
 *
 * @param publicAddress where the servers see the phone: the STUN server, else the SIP server
 * @param server the server the account is on now, `host[:port]`
 * @param serverAddresses what the server's name was found to stand for
 * @param udp the SIP server's answer to an OPTIONS request over UDP; null without one
 * @param udpLarge the same, padded to [com.sky.siplink.core.logic.LineAdapt.LARGE_PACKET]
 *   bytes, got an answer; null: not asked (no answer to the small one)
 * @param tcpMs time to a TCP connection to the server's SIP port; null: none was taken
 * @param tlsMs the same to its TLS port
 * @param transport the transport the line runs on now
 * @param adaptedTransport the transport the line adapted to; null: its own ([transport])
 * @param adaptedExpires the registration interval it adapted to; null: its own
 * @param adaptedIce its calls offer ICE candidates
 * @param viaServer its calls go by way of the server, with symmetric RTP: its PBX announces an
 *   address of its own network
 */
data class LineReport(
    val network: NetworkFacts?,
    val localAddress: String?,
    val publicAddress: Mapped?,
    val nat: NatKind,
    val batteryUnrestricted: Boolean?,
    val server: String,
    val serverAddresses: List<String>,
    val udp: OptionsAnswer?,
    val udpLarge: Boolean?,
    val tcpMs: Long?,
    val tlsMs: Long?,
    val algSuspected: Boolean?,
    val registration: RegState,
    val error: String?,
    val transport: SipTransport,
    val contact: String?,
    val expires: Int,
    val adaptedTransport: SipTransport?,
    val adaptedExpires: Int?,
    val adaptedIce: Boolean,
    val viaServer: Boolean,
    val lastCall: CallMediaSummary?,
)
