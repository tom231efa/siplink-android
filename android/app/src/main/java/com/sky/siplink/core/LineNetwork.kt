package com.sky.siplink.core

import com.sky.siplink.core.logic.DnsMessage
import com.sky.siplink.core.logic.SipPing
import com.sky.siplink.core.model.SipTransport
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.random.Random

/**
 * The questions the routing of an account asks the network itself, with plain sockets. They
 * wait for their answer: never call them on the main thread.
 */
internal object LineNetwork {
    private const val TIMEOUT_MS = 2000
    private const val ATTEMPTS = 2
    private const val MAX_PACKET = 1500

    /**
     * The IPv4 addresses the DNS server at [server]:[port] has for [host]; none when it knows
     * none or does not answer.
     */
    fun resolve(host: String, server: String, port: Int): List<String> {
        val id = Random.nextInt(0x10000)
        val query = DnsMessage.query(id, host) ?: return emptyList()
        return try {
            DatagramSocket().use { socket ->
                socket.soTimeout = TIMEOUT_MS
                // Connected: only what this server sends is received.
                socket.connect(InetSocketAddress(server, port))
                repeat(ATTEMPTS) {
                    socket.send(DatagramPacket(query, query.size))
                    val packet = DatagramPacket(ByteArray(MAX_PACKET), MAX_PACKET)
                    try {
                        socket.receive(packet)
                        // An answer without an address stays one however often it is asked for.
                        return DnsMessage.addresses(packet.data.copyOf(packet.length), id)
                    } catch (_: SocketTimeoutException) {
                        // Ask once more.
                    }
                }
                emptyList()
            }
        } catch (_: IOException) {
            emptyList()
        } catch (_: IllegalArgumentException) {
            emptyList()
        }
    }

    /**
     * Whether a SIP server is there at [host]:[port]. Over UDP it is sent an OPTIONS request,
     * which any server answers; for TCP and TLS it is enough that it takes the connection.
     *
     * @param server the server as the account knows it, `host[:port]`
     * @param from `user@domain` of the account that asks
     * @param userAgent what the app calls itself in its other requests
     */
    fun answers(host: String, port: Int, transport: SipTransport, server: String, from: String, userAgent: String): Boolean = try {
        if (transport == SipTransport.UDP) {
            answersOptions(host, port, server, from, userAgent)
        } else {
            Socket().use { it.connect(InetSocketAddress(host, port), TIMEOUT_MS) }
            true
        }
    } catch (_: IOException) {
        false
    } catch (_: IllegalArgumentException) {
        false
    }

    private fun answersOptions(host: String, port: Int, server: String, from: String, userAgent: String): Boolean = DatagramSocket().use { socket ->
        socket.soTimeout = TIMEOUT_MS
        socket.connect(InetSocketAddress(host, port))
        val token = Random.nextLong().toULong().toString(16)
        val local = "${socket.localAddress.hostAddress}:${socket.localPort}"
        val request = SipPing.options(server, from, local, token, userAgent).toByteArray(Charsets.US_ASCII)
        repeat(ATTEMPTS) {
            socket.send(DatagramPacket(request, request.size))
            val packet = DatagramPacket(ByteArray(MAX_PACKET), MAX_PACKET)
            try {
                socket.receive(packet)
                if (SipPing.isAnswer(String(packet.data, 0, packet.length, Charsets.ISO_8859_1), token)) return true
            } catch (_: SocketTimeoutException) {
                // Ask once more.
            }
        }
        false
    }
}
