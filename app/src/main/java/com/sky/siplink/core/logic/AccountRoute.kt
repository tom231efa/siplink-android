package com.sky.siplink.core.logic

import com.sky.siplink.core.model.SipTransport

/**
 * Which server an account talks to. The SIP core knows one server per account; that an account
 * has a second one to fall back on, and a DNS server of its own to find them with, is kept and
 * acted on by the app.
 */
object AccountRoute {
    /**
     * What the app remembers about the servers of an account, next to what the SIP core holds.
     *
     * @param primary the server as the user gave it, `host[:port]`; empty: the domain of the account
     * @param backup the server to use while the first one is down; empty: none
     * @param dns the DNS server to ask for the address of either, `address[:port]`; empty: the system's
     * @param waiting the account is to register as soon as the address of its server is known
     */
    data class Record(val primary: String, val backup: String, val dns: String, val waiting: Boolean = false) {
        /** Nothing here that the SIP core does not know by itself. */
        val isPlain: Boolean get() = backup.isEmpty() && dns.isEmpty()
    }

    private const val SEPARATOR = '|'
    private const val DNS_PORT = 53

    fun encode(record: Record): String =
        listOf(record.primary, record.backup, record.dns, if (record.waiting) "1" else "0").joinToString(SEPARATOR.toString())

    fun decode(text: String?): Record? {
        val fields = text?.split(SEPARATOR) ?: return null
        if (fields.size != 4) return null
        return Record(fields[0], fields[1], fields[2], waiting = fields[3] == "1")
    }

    /** Host and port of `host[:port]`; an IPv6 address stays in its brackets. */
    fun split(hostPort: String): Pair<String, Int?> {
        val colon = hostPort.lastIndexOf(':')
        // The colons inside the brackets of an IPv6 address are not the one in front of a port.
        if (colon < 0 || colon < hostPort.lastIndexOf(']')) return hostPort to null
        return hostPort.substring(0, colon) to hostPort.substring(colon + 1).toIntOrNull()
    }

    /** Whether [host] is an address already, so that there is nothing to look up. */
    fun isAddress(host: String): Boolean {
        if (host.startsWith("[")) return true
        val parts = host.split('.')
        return parts.size == 4 && parts.all { part -> part.length in 1..3 && part.all(Char::isDigit) && part.toInt() <= 255 }
    }

    /** A DNS server is given by its IPv4 address, with a port when it is not the usual one. */
    fun isValidDnsServer(value: String): Boolean {
        val (host, port) = split(value)
        return AccountRules.isValidHostPort(value) && !host.startsWith("[") && isAddress(host) && (port == null || port in 1..65_535)
    }

    /** Address and port of a DNS server that [isValidDnsServer]. */
    fun dnsServer(value: String): Pair<String, Int> = split(value).let { (host, port) -> host to (port ?: DNS_PORT) }

    /**
     * The server URI for the SIP core.
     *
     * @param pin the address [server] was found to have: the stack then connects there without
     *   asking the system, while it still knows the server by its name (for its TLS certificate)
     */
    fun uri(server: String, transport: SipTransport, pin: String? = null): String =
        "sip:$server;transport=${AccountRules.transportParam(transport)}" + (pin?.let { ";maddr=$it" } ?: "")

    fun defaultPort(transport: SipTransport): Int = if (transport == SipTransport.TLS) 5061 else 5060

    /**
     * Whether a registration that failed with SIP status [code] says the server is out of
     * reach or out of order, which is what a backup server is for. A server that answers and
     * refuses (wrong password, unknown user) would be no different on the backup.
     */
    fun isServerDown(code: Int): Boolean = code <= 0 || code == 408 || code in 500..599

    /**
     * Whether to change servers right after failure number [failures] in a row. Every other
     * time: both servers get their turn, and when both are down the account does not flip
     * between them faster than the stack retries.
     */
    fun switchesNow(failures: Int): Boolean = failures % 2 == 1

    private const val FAILBACK_MS = 60_000L
    private const val MAX_FAILBACK_MS = 30 * 60_000L

    /**
     * How long to wait before asking the first server whether it is back, when the last
     * [failedReturns] returns to it did not hold: longer each time, up to half an hour.
     */
    fun failbackDelay(failedReturns: Int): Long =
        (FAILBACK_MS shl failedReturns.coerceIn(0, 10)).coerceAtMost(MAX_FAILBACK_MS)
}
