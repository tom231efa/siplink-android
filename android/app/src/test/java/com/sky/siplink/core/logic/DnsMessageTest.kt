package com.sky.siplink.core.logic

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsMessageTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    /** The question "pbx.example.com A IN", as it stands in a query and is repeated in the answer. */
    private val question = bytes(3) + "pbx".toByteArray() + bytes(7) + "example".toByteArray() + bytes(3) + "com".toByteArray() +
        bytes(0, 0, 1, 0, 1)

    private fun header(id: Int, flags: Int, answers: Int) =
        bytes(id shr 8, id and 0xFF, flags shr 8, flags and 0xFF, 0, 1, 0, answers, 0, 0, 0, 0)

    /** A record whose name is a pointer to the name of the question. */
    private fun record(type: Int, data: ByteArray) =
        bytes(0xC0, 12, 0, type, 0, 1, 0, 0, 0, 60, 0, data.size) + data

    @Test
    fun `a query asks one question with recursion`() {
        assertArrayEquals(header(0x1234, 0x0100, 0) + question, DnsMessage.query(0x1234, "pbx.example.com"))
        assertArrayEquals(
            DnsMessage.query(7, "pbx.example.com"),
            DnsMessage.query(7, "pbx.example.com."),
        )
    }

    @Test
    fun `what cannot be a host name is not asked for`() {
        assertNull(DnsMessage.query(1, ""))
        assertNull(DnsMessage.query(1, "pbx..example.com"))
        assertNull(DnsMessage.query(1, "总机.example.com"))
        assertNull(DnsMessage.query(1, "a".repeat(64) + ".com"))
    }

    @Test
    fun `the addresses of the answer are read`() {
        val answer = header(0x1234, 0x8180, 2) + question +
            record(1, bytes(192, 0, 2, 10)) + record(1, bytes(192, 0, 2, 11))
        assertEquals(listOf("192.0.2.10", "192.0.2.11"), DnsMessage.addresses(answer, 0x1234))
    }

    @Test
    fun `an alias in front of the address is passed over`() {
        val alias = bytes(4) + "host".toByteArray() + bytes(0xC0, 16) // host.example.com
        val answer = header(5, 0x8180, 2) + question + record(5, alias) +
            // The address belongs to the alias: its name points into the record before.
            bytes(0xC0, 45, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4, 198, 51, 100, 7)
        assertEquals(listOf("198.51.100.7"), DnsMessage.addresses(answer, 5))
    }

    @Test
    fun `an answer to another query an error or a query is nothing`() {
        val record = record(1, bytes(192, 0, 2, 10))
        assertTrue(DnsMessage.addresses(header(2, 0x8180, 1) + question + record, 1).isEmpty())
        // NXDOMAIN
        assertTrue(DnsMessage.addresses(header(1, 0x8183, 0) + question, 1).isEmpty())
        // Not a response
        assertTrue(DnsMessage.addresses(header(1, 0x0100, 1) + question + record, 1).isEmpty())
    }

    @Test
    fun `a damaged packet is nothing and does not throw`() {
        val answer = header(1, 0x8180, 3) + question + record(1, bytes(192, 0, 2, 10))
        for (length in 0 until answer.size) {
            // Must not throw for any length; what it finds in a truncated packet does not matter.
            DnsMessage.addresses(answer.copyOf(length), 1)
        }
        // It announces three records and holds one: the one it holds is not trusted either.
        assertTrue(DnsMessage.addresses(answer, 1).isEmpty())
        assertTrue(DnsMessage.addresses(ByteArray(12), 0).isEmpty())
    }
}
