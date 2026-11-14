package com.sky.siplink.core.logic

import java.io.ByteArrayOutputStream

/**
 * The little of the DNS protocol the app speaks itself, to ask one given server for the IPv4
 * addresses of a host: an account can name its own DNS server, and the SIP stack only knows
 * the servers of the system.
 */
object DnsMessage {
    private const val TYPE_A = 1
    private const val CLASS_IN = 1
    private const val HEADER_SIZE = 12
    private const val FLAG_RESPONSE = 0x8000
    private const val FLAG_RECURSION_DESIRED = 0x0100
    private const val RCODE_MASK = 0x000F
    private const val POINTER = 0xC0
    private const val MAX_LABEL = 63
    private const val MAX_NAME = 253
    private const val A_SIZE = 4

    /** A query for the addresses of [host]; null when [host] cannot be a host name. */
    fun query(id: Int, host: String): ByteArray? {
        val name = host.trimEnd('.')
        if (name.isEmpty() || name.length > MAX_NAME) return null
        val labels = name.split('.')
        if (labels.any { label -> label.isEmpty() || label.length > MAX_LABEL || label.any { it.code > 0x7F } }) return null

        val out = ByteArrayOutputStream()
        fun u16(value: Int) {
            out.write(value shr 8 and 0xFF)
            out.write(value and 0xFF)
        }
        u16(id)
        u16(FLAG_RECURSION_DESIRED)
        u16(1) // one question, nothing else
        repeat(3) { u16(0) }
        for (label in labels) {
            out.write(label.length)
            out.write(label.toByteArray(Charsets.US_ASCII))
        }
        out.write(0)
        u16(TYPE_A)
        u16(CLASS_IN)
        return out.toByteArray()
    }

    /**
     * The addresses in the answer to query [id], as dotted numbers. Empty when [packet] answers
     * another query, reports an error, holds no address or is damaged.
     */
    fun addresses(packet: ByteArray, id: Int): List<String> = try {
        parse(packet, id)
    } catch (_: IndexOutOfBoundsException) {
        emptyList()
    }

    private fun parse(packet: ByteArray, id: Int): List<String> {
        fun u8(at: Int) = packet[at].toInt() and 0xFF
        fun u16(at: Int) = (u8(at) shl 8) or u8(at + 1)

        if (packet.size < HEADER_SIZE || u16(0) != (id and 0xFFFF)) return emptyList()
        val flags = u16(2)
        if (flags and FLAG_RESPONSE == 0 || flags and RCODE_MASK != 0) return emptyList()

        var at = HEADER_SIZE

        /** Moves past a name: labels up to an empty one, or up to a pointer to where it goes on. */
        fun skipName() {
            while (true) {
                val length = u8(at)
                when {
                    length == 0 -> {
                        at += 1
                        return
                    }
                    length and POINTER == POINTER -> {
                        at += 2
                        return
                    }
                    else -> at += 1 + length
                }
            }
        }

        repeat(u16(4)) {
            skipName()
            at += 4 // type and class of the question
        }
        val found = ArrayList<String>()
        repeat(u16(6)) {
            skipName()
            val type = u16(at)
            val length = u16(at + 8)
            val data = at + 10
            // Anything else, such as the alias the name stands for, is passed over.
            if (type == TYPE_A && length == A_SIZE) found += (0 until A_SIZE).joinToString(".") { u8(data + it).toString() }
            at = data + length
        }
        return found
    }
}
