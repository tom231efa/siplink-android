package com.sky.siplink.core.logic

/**
 * IP address literals, read without asking any name server: what kind of network an address
 * belongs to, and which subnet. A name (or anything else that is no literal) is [Kind.INVALID].
 */
object NetAddress {
    enum class Kind {
        LOOPBACK,
        LINK_LOCAL,

        /** RFC 1918 (10/8, 172.16/12, 192.168/16). */
        PRIVATE,

        /** RFC 6598 shared address space (100.64/10), handed out behind carrier-grade NAT. */
        SHARED,

        /** RFC 4193 unique local IPv6 (fc00::/7). */
        UNIQUE_LOCAL,
        PUBLIC,
        INVALID,
    }

    fun kind(address: String): Kind {
        val bytes = parse(address) ?: return Kind.INVALID
        return if (bytes.size == 4) kindOf4(bytes) else kindOf6(bytes)
    }

    /** The 4 or 16 bytes of an IPv4 or IPv6 literal (an IPv6 one may stand in brackets); null for anything else. */
    fun parse(address: String): ByteArray? {
        val text = address.trim().removePrefix("[").removeSuffix("]").substringBefore('%')
        if (text.isEmpty()) return null
        return if (':' in text) parse6(text) else parse4(text)
    }

    /**
     * The network [address] belongs to, written as `network/prefix`: [prefix4] bits of an IPv4
     * address, [prefix6] of an IPv6 one. Null for what is no literal.
     */
    fun subnet(address: String, prefix4: Int = 24, prefix6: Int = 64): String? {
        val bytes = parse(address) ?: return null
        val prefix = if (bytes.size == 4) prefix4 else prefix6
        val masked = bytes.copyOf()
        for (i in masked.indices) {
            val keep = (prefix - i * 8).coerceIn(0, 8)
            masked[i] = (masked[i].toInt() and (0xFF shl (8 - keep)) and 0xFF).toByte()
        }
        return "${format(masked)}/$prefix"
    }

    /** Whether [address] lies in [network], `network/prefix` as [subnet] writes it. False for anything that is no such pair. */
    fun inSubnet(address: String, network: String): Boolean {
        val prefix = network.substringAfterLast('/', "").toIntOrNull() ?: return false
        val base = parse(network.substringBeforeLast('/')) ?: return false
        val bytes = parse(address) ?: return false
        if (bytes.size != base.size || prefix !in 0..bytes.size * BITS) return false
        return subnet(address, prefix, prefix) == subnet(format(base), prefix, prefix)
    }

    /** Whether [a] and [b] are the same address, however each is written. False when either is no address. */
    fun same(a: String, b: String): Boolean {
        val left = parse(a) ?: return false
        return parse(b)?.contentEquals(left) == true
    }

    private const val BITS = 8

    /**
     * The addresses of a server in the order the SIP core tries them: IPv4 alone while its IPv6
     * is off ([ipv6]); with it on, IPv6 first where [sixFirst], else IPv4 first. (belle-sip
     * resolves both and puts the AAAA answers first, its family preference being IPv6:
     * sipstack.c, belle_sip_resolver.c dual_resolver_context_notify.)
     */
    fun coreOrder(addresses: List<String>, ipv6: Boolean, sixFirst: Boolean): List<String> {
        val (six, four) = addresses.partition { parse(it)?.size == IPV6_BYTES }
        return when {
            !ipv6 -> four
            sixFirst -> six + four
            else -> four + six
        }
    }

    /**
     * Whether the SIP core goes to the servers over IPv6 first, for [coreOrder]: as the server saw
     * the registration ([contactHost], the host of its contact; null while it has none), else
     * where the phone has an IPv6 address that reaches beyond its link ([local], its addresses).
     */
    fun sixFirst(contactHost: String?, local: Collection<String>): Boolean {
        contactHost?.let(::parse)?.let { return it.size == IPV6_BYTES }
        return local.any { parse(it)?.size == IPV6_BYTES && kind(it).let { k -> k == Kind.PUBLIC || k == Kind.UNIQUE_LOCAL } }
    }

    private const val IPV6_BYTES = 16

    /** The canonical text of an address: dotted IPv4, or IPv6 in groups with the longest run of zeros shortened. */
    fun format(bytes: ByteArray): String {
        if (bytes.size == 4) return bytes.joinToString(".") { (it.toInt() and 0xFF).toString() }
        val groups = IntArray(8) { ((bytes[it * 2].toInt() and 0xFF) shl 8) or (bytes[it * 2 + 1].toInt() and 0xFF) }
        var bestStart = -1
        var bestLength = 0
        var i = 0
        while (i < 8) {
            if (groups[i] == 0) {
                val start = i
                while (i < 8 && groups[i] == 0) i++
                if (i - start > bestLength && i - start > 1) {
                    bestStart = start
                    bestLength = i - start
                }
            } else {
                i++
            }
        }
        if (bestStart < 0) return groups.joinToString(":") { Integer.toHexString(it) }
        val head = groups.take(bestStart).joinToString(":") { Integer.toHexString(it) }
        val tail = groups.drop(bestStart + bestLength).joinToString(":") { Integer.toHexString(it) }
        return "$head::$tail"
    }

    private fun kindOf4(b: ByteArray): Kind {
        val first = b[0].toInt() and 0xFF
        val second = b[1].toInt() and 0xFF
        return when {
            first == 127 -> Kind.LOOPBACK
            first == 169 && second == 254 -> Kind.LINK_LOCAL
            first == 10 -> Kind.PRIVATE
            first == 172 && second in 16..31 -> Kind.PRIVATE
            first == 192 && second == 168 -> Kind.PRIVATE
            first == 100 && second in 64..127 -> Kind.SHARED
            first == 0 || first >= 224 -> Kind.INVALID
            else -> Kind.PUBLIC
        }
    }

    private fun kindOf6(b: ByteArray): Kind {
        val first = b[0].toInt() and 0xFF
        val second = b[1].toInt() and 0xFF
        val allZeroButLast = (0 until 15).all { b[it].toInt() == 0 }
        val mapped4 = (0 until 10).all { b[it].toInt() == 0 } && b[10] == 0xFF.toByte() && b[11] == 0xFF.toByte()
        return when {
            allZeroButLast && b[15].toInt() == 1 -> Kind.LOOPBACK
            allZeroButLast && b[15].toInt() == 0 -> Kind.INVALID
            mapped4 -> kindOf4(b.copyOfRange(12, 16))
            first == 0xFE && (second and 0xC0) == 0x80 -> Kind.LINK_LOCAL
            (first and 0xFE) == 0xFC -> Kind.UNIQUE_LOCAL
            first == 0xFF -> Kind.INVALID
            else -> Kind.PUBLIC
        }
    }

    private fun parse4(text: String): ByteArray? {
        val parts = text.split('.')
        if (parts.size != 4) return null
        val bytes = ByteArray(4)
        for ((i, part) in parts.withIndex()) {
            if (part.isEmpty() || part.length > 3 || !part.all(Char::isDigit)) return null
            val value = part.toInt()
            if (value > 255) return null
            bytes[i] = value.toByte()
        }
        return bytes
    }

    private fun parse6(text: String): ByteArray? {
        if (text.count { it == ':' } < 2) return null
        val halves = text.split("::")
        if (halves.size > 2) return null
        fun groupsOf(part: String): List<String>? = if (part.isEmpty()) emptyList() else part.split(':').takeIf { it.none(String::isEmpty) }
        val head = groupsOf(halves[0]) ?: return null
        val tail = if (halves.size == 2) groupsOf(halves[1]) ?: return null else emptyList()
        // An IPv4 address may end the literal (::ffff:192.0.2.1): it counts as two groups.
        val values = ArrayList<Int>()
        val all = head + listOf("") + tail
        var gap = -1
        for ((index, group) in all.withIndex()) {
            if (index == head.size) {
                gap = values.size
                continue
            }
            if ('.' in group) {
                if (index != all.lastIndex) return null
                val four = parse4(group) ?: return null
                values += ((four[0].toInt() and 0xFF) shl 8) or (four[1].toInt() and 0xFF)
                values += ((four[2].toInt() and 0xFF) shl 8) or (four[3].toInt() and 0xFF)
            } else {
                if (group.length > 4 || !group.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
                values += group.toInt(16)
            }
        }
        val missing = 8 - values.size
        if (halves.size == 1 && missing != 0) return null
        if (halves.size == 2 && missing < 1) return null
        val groups = values.subList(0, gap) + List(missing.coerceAtLeast(0)) { 0 } + values.subList(gap, values.size)
        val bytes = ByteArray(16)
        groups.forEachIndexed { i, g ->
            bytes[i * 2] = (g shr 8).toByte()
            bytes[i * 2 + 1] = g.toByte()
        }
        return bytes
    }
}
