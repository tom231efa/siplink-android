package com.sky.siplink.core.logic

import com.sky.siplink.core.model.Mapped
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StunMessageTest {
    private val id = ByteArray(12) { (it + 1).toByte() }

    /** A Binding success answer to [id] with the given attributes (type, value). */
    private fun answer(vararg attributes: Pair<Int, ByteArray>, transaction: ByteArray = id): ByteArray {
        val body = attributes.flatMap { (type, value) ->
            val padded = value.copyOf((value.size + 3) / 4 * 4)
            listOf((type shr 8).toByte(), type.toByte(), (value.size shr 8).toByte(), value.size.toByte()) + padded.toList()
        }
        val header = byteArrayOf(0x01, 0x01, (body.size shr 8).toByte(), body.size.toByte(), 0x21, 0x12, 0xA4.toByte(), 0x42)
        return header + transaction + body.toByteArray()
    }

    @Test
    fun `the request is a bare binding request`() {
        val request = StunMessage.request(id)
        assertEquals(20, request.size)
        assertArrayEquals(byteArrayOf(0x00, 0x01, 0x00, 0x00, 0x21, 0x12, 0xA4.toByte(), 0x42), request.copyOf(8))
        assertArrayEquals(id, request.copyOfRange(8, 20))
    }

    @Test
    fun `the xor mapped address is read`() {
        // 203.0.113.7:51234, XORed with the magic cookie (RFC 5389 §15.2).
        val port = 51234 xor 0x2112
        val address = byteArrayOf((203 xor 0x21).toByte(), (0 xor 0x12).toByte(), (113 xor 0xA4).toByte(), (7 xor 0x42).toByte())
        val value = byteArrayOf(0, 0x01, (port shr 8).toByte(), port.toByte()) + address
        val packet = answer(0x0020 to value)
        assertEquals(Mapped("203.0.113.7", 51234), StunMessage.mapped(packet, packet.size, id))
    }

    @Test
    fun `a server of the old kind answers with the plain mapped address`() {
        val value = byteArrayOf(0, 0x01, (40000 shr 8).toByte(), 40000.toByte(), 198.toByte(), 51, 100, 9)
        val packet = answer(0x8022 to "x".toByteArray(), 0x0001 to value)
        assertEquals(Mapped("198.51.100.9", 40000), StunMessage.mapped(packet, packet.size, id))
    }

    @Test
    fun `answers to other requests and other messages are ignored`() {
        val value = byteArrayOf(0, 0x01, 0, 80, 1, 2, 3, 4)
        val other = answer(0x0001 to value, transaction = ByteArray(12))
        assertNull(StunMessage.mapped(other, other.size, id))
        assertNull(StunMessage.mapped(StunMessage.request(id), 20, id))
        assertNull(StunMessage.mapped(ByteArray(10), 10, id))
    }
}
