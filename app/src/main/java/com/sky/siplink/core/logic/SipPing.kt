package com.sky.siplink.core.logic

/**
 * The smallest SIP exchange there is: an OPTIONS request, which every server answers one way
 * or another. Used to find out whether a server is back before an account returns to it.
 */
object SipPing {
    /**
     * @param server host[:port] the request is for
     * @param from `user@domain` of the account that asks
     * @param local address and port the request is sent from, where the answer is expected
     * @param token something random, to tell this exchange from any other
     */
    fun options(server: String, from: String, local: String, token: String): String = listOf(
        "OPTIONS sip:$server SIP/2.0",
        "Via: SIP/2.0/UDP $local;branch=z9hG4bK$token;rport",
        "Max-Forwards: 70",
        "From: <sip:$from>;tag=$token",
        "To: <sip:$server>",
        "Call-ID: $token",
        "CSeq: 1 OPTIONS",
        "User-Agent: SipLink",
        "Content-Length: 0",
        "",
        "",
    ).joinToString("\r\n")

    /** Whether [packet] is a SIP answer to the exchange [token]: whatever it says, the server is there. */
    fun isAnswer(packet: String, token: String): Boolean =
        packet.startsWith("SIP/2.0 ") && packet.contains(token)
}
