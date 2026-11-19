package com.sky.siplink.core.logic

import com.sky.siplink.core.model.Mapped

/**
 * The one STUN exchange the line checks need (RFC 5389): a Binding request, and the address the
 * server saw it come from in the answer.
 */
object StunMessage {
    /** RFC 5389 §6. */
    private const val MAGIC_COOKIE = 0x2112A442
    private const val BINDING_REQUEST = 0x0001
    private const val BINDING_SUCCESS = 0x0101
    private const val HEADER = 20
    private const val TRANSACTION_ID = 12

    /** RFC 5389 §15.1 and §15.2. */
    private const val MAPPED_ADDRESS = 0x0001
    private const val XOR_MAPPED_ADDRESS = 0x0020
    private const val FAMILY_IPV4 = 0x01
    private const val FAMILY_IPV6 = 0x02

    /** A Binding request with [transactionId] (12 bytes) and no attributes. */
    fun request(transactionId: ByteArray): ByteArray {
        require(transactionId.size == TRANSACTION_ID)
        val out = ByteArray(HEADER)
        putShort(out, 0, BINDING_REQUEST)
        putShort(out, 2, 0)
        putInt(out, 4, MAGIC_COOKIE)
        transactionId.copyInto(out, 8)
        return out
    }

    /**
     * The address of a Binding success answer to [transactionId]: from XOR-MAPPED-ADDRESS, or
     * from MAPPED-ADDRESS where a server (of RFC 3489) sends only that. Null for anything else.
     */
    fun mapped(packet: ByteArray, length: Int, transactionId: ByteArray): Mapped? {
        if (length < HEADER || shortAt(packet, 0) != BINDING_SUCCESS || intAt(packet, 4) != MAGIC_COOKIE) return null
        if (!packet.copyOfRange(8, HEADER).contentEquals(transactionId)) return null
        val end = (HEADER + shortAt(packet, 2)).coerceAtMost(length)
        var plain: Mapped? = null
        var offset = HEADER
        while (offset + 4 <= end) {
            val type = shortAt(packet, offset)
            val size = shortAt(packet, offset + 2)
            val value = offset + 4
            if (value + size > end) break
            when (type) {
                XOR_MAPPED_ADDRESS -> address(packet, value, size, xor = true)?.let { return it }
                MAPPED_ADDRESS -> plain = address(packet, value, size, xor = false) ?: plain
            }
            // Attributes are padded to a multiple of four bytes.
            offset = value + (size + 3) / 4 * 4
        }
        return plain
    }

    private fun address(packet: ByteArray, at: Int, size: Int, xor: Boolean): Mapped? {
        if (size < 8) return null
        val family = packet[at + 1].toInt() and 0xFF
        val length = when (family) {
            FAMILY_IPV4 -> 4
            FAMILY_IPV6 -> 16
            else -> return null
        }
        if (size < 4 + length) return null
        var port = shortAt(packet, at + 2)
        val bytes = packet.copyOfRange(at + 4, at + 4 + length)
        if (xor) {
            port = port xor (MAGIC_COOKIE ushr 16)
            // The address is XORed with the magic cookie, and an IPv6 one also with the transaction id.
            val key = ByteArray(16)
            putInt(key, 0, MAGIC_COOKIE)
            packet.copyInto(key, 4, 8, HEADER)
            for (i in bytes.indices) bytes[i] = (bytes[i].toInt() xor key[i].toInt()).toByte()
        }
        return Mapped(NetAddress.format(bytes), port)
    }

    private fun shortAt(b: ByteArray, at: Int): Int = ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)

    private fun intAt(b: ByteArray, at: Int): Int = (shortAt(b, at) shl 16) or shortAt(b, at + 2)

    private fun putShort(b: ByteArray, at: Int, value: Int) {
        b[at] = (value shr 8).toByte()
        b[at + 1] = value.toByte()
    }

    private fun putInt(b: ByteArray, at: Int, value: Int) {
        putShort(b, at, value ushr 16)
        putShort(b, at + 2, value and 0xFFFF)
    }
}
