package com.sky.siplink.core.logic

import com.sky.siplink.core.model.OptionsAnswer

/**
 * The smallest SIP exchange there is: an OPTIONS request, which every server answers one way
 * or another. Used to find out whether a server is back before an account returns to it, and by
 * the line checks: the answer says where the server saw the request come from (RFC 3581), and
 * whether something on the way changed it.
 */
object SipPing {
    /**
     * @param server host[:port] the request is for
     * @param from `user@domain` of the account that asks
     * @param local address and port the request is sent from, where the answer is expected
     * @param token something random, to tell this exchange from any other
     * @param userAgent what the app calls itself in its other requests
     * @param size when larger than the request, it is padded to this many bytes: to find out
     *   whether large UDP packets get through
     */
    fun options(server: String, from: String, local: String, token: String, userAgent: String, size: Int = 0): String {
        val headers = listOf(
            "OPTIONS sip:$server SIP/2.0",
            "Via: SIP/2.0/UDP $local;branch=z9hG4bK$token;rport",
            "Max-Forwards: 70",
            "From: <sip:$from>;tag=$token",
            "To: <sip:$server>",
            "Call-ID: $token",
            "CSeq: 1 OPTIONS",
            "User-Agent: $userAgent",
        )
        val tail = listOf("Content-Length: 0", "", "")
        val plain = (headers + tail).joinToString("\r\n")
        val missing = size - plain.length - "$PAD_HEADER: \r\n".length
        if (missing <= 0) return plain
        // An extension header, which a server that does not know it ignores (RFC 3261 §7.3.1).
        return (headers + "$PAD_HEADER: ${"x".repeat(missing)}" + tail).joinToString("\r\n")
    }

    private const val PAD_HEADER = "X-SipLink-Pad"

    /** Whether [packet] is a SIP answer to the exchange [token]: whatever it says, the server is there. */
    fun isAnswer(packet: String, token: String): Boolean =
        packet.startsWith("SIP/2.0 ") && packet.contains(token)

    /**
     * What the answer [packet] to the request with [token], sent from [local], tells; null when
     * it is no answer to it.
     */
    fun answer(packet: String, token: String, local: String, rttMs: Long): OptionsAnswer? {
        if (!isAnswer(packet, token)) return null
        val status = packet.substringAfter(' ').take(3).toIntOrNull() ?: return null
        val via = topVia(packet)
        return OptionsAnswer(
            status = status,
            rttMs = rttMs,
            received = via?.params?.get("received")?.takeIf { it.isNotEmpty() },
            rport = via?.params?.get("rport")?.toIntOrNull(),
            sentByIntact = via?.sentBy.equals(local, ignoreCase = true),
            lengthIntact = lengthIntact(packet),
        )
    }

    /** The first Via of a message: its sent-by and its parameters (a parameter without value maps to ""). */
    class Via(val sentBy: String, val params: Map<String, String>)

    fun topVia(packet: String): Via? {
        val value = headers(packet).firstOrNull { (name, _) -> name.equals("Via", true) || name.equals("v", true) }?.second
            ?: return null
        // A header may hold several values separated by commas: the first one is the top one.
        val afterProtocol = value.substringBefore(',').trim().substringAfter(' ', "").trim()
        if (afterProtocol.isEmpty()) return null
        val parts = afterProtocol.split(';')
        val params = parts.drop(1).associate { part ->
            part.substringBefore('=').trim().lowercase() to (if ('=' in part) part.substringAfter('=').trim() else "")
        }
        return Via(parts.first().trim(), params)
    }

    /** Whether the Content-Length of [packet] matches its body; true when it has none. */
    fun lengthIntact(packet: String): Boolean {
        val declared = headers(packet).firstOrNull { (name, _) -> name.equals("Content-Length", true) || name.equals("l", true) }
            ?.second?.toIntOrNull() ?: return true
        val start = packet.indexOf("\r\n\r\n")
        if (start < 0) return false
        // The body is counted in bytes; the packet is read as ISO-8859-1, one character per byte.
        return packet.length - (start + 4) == declared
    }

    private fun headers(packet: String): List<Pair<String, String>> {
        val end = packet.indexOf("\r\n\r\n").let { if (it < 0) packet.length else it }
        return packet.substring(0, end).split("\r\n").drop(1).mapNotNull { line ->
            val colon = line.indexOf(':')
            if (colon <= 0) null else line.substring(0, colon).trim() to line.substring(colon + 1).trim()
        }
    }
}
