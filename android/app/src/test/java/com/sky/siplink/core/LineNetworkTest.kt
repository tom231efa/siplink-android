package com.sky.siplink.core

import com.sky.siplink.core.logic.LineAdapt
import com.sky.siplink.core.model.Mapped
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/** The probes of the line checks against small servers on this machine. */
class LineNetworkTest {
    private val loopback = InetAddress.getByName("127.0.0.1")
    private val closing = ArrayList<AutoCloseable>()

    @After
    fun closeAll() = closing.forEach { runCatching { it.close() } }

    /**
     * A SIP server that answers every request it gets with 200 and the top Via as the server
     * sees it ([rewrite] stands for what a router changes on the way); records the sizes received.
     */
    private fun sipServer(maxAnswered: Int = Int.MAX_VALUE, rewrite: (String) -> String = { it }): Pair<Int, List<Int>> {
        val socket = DatagramSocket(0, loopback).also(closing::add)
        val sizes = CopyOnWriteArrayList<Int>()
        thread(isDaemon = true) {
            val buffer = ByteArray(4096)
            while (!socket.isClosed) {
                val packet = DatagramPacket(buffer, buffer.size)
                runCatching { socket.receive(packet) }.onFailure { return@thread }
                sizes += packet.length
                if (packet.length > maxAnswered) continue
                val request = String(packet.data, 0, packet.length, Charsets.ISO_8859_1)
                val via = request.lines().first { it.startsWith("Via:") }.trim()
                    .replace(";rport", ";received=${packet.address.hostAddress};rport=${packet.port}")
                val answer = rewrite(
                    "SIP/2.0 200 OK\r\n$via\r\n" +
                        request.lines().filter { it.startsWith("Call-ID:") || it.startsWith("CSeq:") }.joinToString("") { "${it.trim()}\r\n" } +
                        "Content-Length: 0\r\n\r\n",
                ).toByteArray(Charsets.ISO_8859_1)
                runCatching { socket.send(DatagramPacket(answer, answer.size, packet.socketAddress)) }
            }
        }
        return socket.localPort to sizes
    }

    /** A STUN server that answers with the address it saw, as XOR-MAPPED-ADDRESS. */
    private fun stunServer(): InetSocketAddress {
        val socket = DatagramSocket(0, loopback).also(closing::add)
        thread(isDaemon = true) {
            val buffer = ByteArray(512)
            while (!socket.isClosed) {
                val packet = DatagramPacket(buffer, buffer.size)
                runCatching { socket.receive(packet) }.onFailure { return@thread }
                val id = packet.data.copyOfRange(8, 20)
                val port = packet.port xor 0x2112
                val address = packet.address.address.mapIndexed { i, b -> (b.toInt() xor COOKIE[i].toInt()).toByte() }
                val value = byteArrayOf(0, 0x01, (port shr 8).toByte(), port.toByte()) + address
                val answer = byteArrayOf(0x01, 0x01, 0, 12) + COOKIE + id + byteArrayOf(0, 0x20, 0, 8) + value
                runCatching { socket.send(DatagramPacket(answer, answer.size, packet.socketAddress)) }
            }
        }
        return InetSocketAddress(loopback, socket.localPort)
    }

    @Test
    fun `the answer tells where the server saw the probe`() {
        val (port, _) = sipServer()
        val probe = LineNetwork.probeUdp("127.0.0.1", port, "127.0.0.1:$port", "1001@example.com", "test")
        assertNotNull(probe.answer)
        val answer = probe.answer!!
        assertEquals(200, answer.status)
        assertEquals("127.0.0.1", answer.received)
        assertEquals(probe.from?.port, answer.rport)
        assertTrue(answer.sentByIntact)
        assertTrue(answer.lengthIntact)
    }

    @Test
    fun `a padded probe has the size asked for`() {
        val (port, sizes) = sipServer()
        LineNetwork.probeUdp("127.0.0.1", port, "127.0.0.1:$port", "1001@example.com", "test", size = LineAdapt.LARGE_PACKET)
        assertEquals(LineAdapt.LARGE_PACKET, sizes.first())
    }

    @Test
    fun `a server that drops large packets answers only the small probe`() {
        val (port, _) = sipServer(maxAnswered = 1200)
        assertNotNull(LineNetwork.probeUdp("127.0.0.1", port, "127.0.0.1:$port", "a@b", "test").answer)
        assertNull(LineNetwork.probeUdp("127.0.0.1", port, "127.0.0.1:$port", "a@b", "test", size = LineAdapt.LARGE_PACKET).answer)
    }

    @Test
    fun `a rewritten via is seen`() {
        val (port, _) = sipServer { it.replace("Via: SIP/2.0/UDP 127.0.0.1:", "Via: SIP/2.0/UDP 192.0.2.1:") }
        val answer = LineNetwork.probeUdp("127.0.0.1", port, "127.0.0.1:$port", "a@b", "test").answer
        assertFalse(answer!!.sentByIntact)
    }

    @Test
    fun `the stun server is asked from the same socket`() {
        val (port, _) = sipServer()
        val stun = stunServer()
        val probe = LineNetwork.probeUdp("127.0.0.1", port, "127.0.0.1:$port", "a@b", "test", stun = stun)
        assertNotNull(probe.answer)
        assertEquals(Mapped("127.0.0.1", probe.from!!.port), probe.stun)
    }

    @Test
    fun `nobody there means no answer`() {
        val silent = DatagramSocket(0, loopback).also(closing::add)
        val probe = LineNetwork.probeUdp("127.0.0.1", silent.localPort, "127.0.0.1", "a@b", "test")
        assertNull(probe.answer)
        assertNotNull(probe.from)
    }

    @Test
    fun `a tcp connection is timed, a refused one is null`() {
        val server = ServerSocket(0, 1, loopback).also(closing::add)
        assertNotNull(LineNetwork.connectMs("127.0.0.1", server.localPort))
        val port = server.localPort
        server.close()
        assertNull(LineNetwork.connectMs("127.0.0.1", port))
    }

    @Test
    fun `the transports of a server with a port of its own leave tls out`() {
        val (port, _) = sipServer(maxAnswered = 1200)
        val tcp = ServerSocket(port, 1, loopback).also(closing::add)
        val probe = LineNetwork.transports("127.0.0.1", tcp.localPort, "127.0.0.1:$port", "a@b", "test")
        assertEquals(LineAdapt.Probe(udp = true, udpLarge = false, tcp = true, tls = null), probe)
    }

    @Test
    fun `an address stays itself, a bracketed one too`() {
        assertEquals(listOf("127.0.0.1"), LineNetwork.lookup("127.0.0.1"))
        assertEquals(listOf("0:0:0:0:0:0:0:1"), LineNetwork.lookup("[::1]"))
        assertEquals("[::1]:5060", LineNetwork.hostPort(Mapped("::1", 5060)))
    }

    private companion object {
        val COOKIE = byteArrayOf(0x21, 0x12, 0xA4.toByte(), 0x42)
    }
}
