package com.sky.siplink.core

import com.sky.siplink.core.logic.AccountRoute
import com.sky.siplink.core.logic.DnsMessage
import com.sky.siplink.core.logic.LineAdapt
import com.sky.siplink.core.logic.NetAddress
import com.sky.siplink.core.logic.SipPing
import com.sky.siplink.core.logic.StunMessage
import com.sky.siplink.core.model.Mapped
import com.sky.siplink.core.model.OptionsAnswer
import com.sky.siplink.core.model.SipTransport
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.random.Random

/**
 * The questions the routing of an account, and the line checks, ask the network itself, with
 * plain sockets. They wait for their answer: never call them on the main thread.
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

    // ---------------------------------------------------------------- the line checks

    /**
     * What one UDP socket found out.
     *
     * @param from the address and port it was sent from; null when it could not be opened
     * @param answer the SIP server's answer to an OPTIONS request; null without one
     * @param stun where the STUN server saw the same socket; null without one, or when none was asked
     */
    class UdpProbe(val from: Mapped?, val answer: OptionsAnswer?, val stun: Mapped?)

    /**
     * Who asks in an OPTIONS request: `user@domain` of an account, and what the app calls itself
     * in its other requests.
     */
    class Asker(val from: String, val userAgent: String)

    /**
     * Sends an OPTIONS request to the SIP server at [host]:[port] and, with [stun], a STUN Binding
     * request from the same socket: where both see it says what kind of NAT is in the way.
     *
     * @param asker who asks; null: no OPTIONS request, the STUN server alone is asked (a line on
     *   TLS sends nothing in clear)
     * @param size the request is padded to this many bytes, to see whether large packets get through
     */
    fun probeUdp(
        host: String,
        port: Int,
        server: String,
        asker: Asker?,
        size: Int = 0,
        stun: InetSocketAddress? = null,
    ): UdpProbe = try {
        DatagramSocket().use { socket ->
            val target = InetSocketAddress(host, port)
            // Connected for a moment, the socket learns the address it sends from towards the server.
            socket.connect(target)
            val sentFrom = Mapped(socket.localAddress.hostAddress.orEmpty().substringBefore('%'), socket.localPort)
            socket.disconnect()
            val local = hostPort(sentFrom)
            val token = Random.nextLong().toULong().toString(16)
            val request = asker?.let { SipPing.options(server, it.from, local, token, it.userAgent, size).toByteArray(Charsets.US_ASCII) }
            val stunId = Random.nextBytes(STUN_ID)
            val stunRequest = stun?.let { StunMessage.request(stunId) }
            var answer: OptionsAnswer? = null
            var mapped: Mapped? = null
            for (attempt in 1..ATTEMPTS) {
                val sentAt = System.nanoTime()
                if (request != null && answer == null) socket.send(DatagramPacket(request, request.size, target))
                if (stunRequest != null && mapped == null) socket.send(DatagramPacket(stunRequest, stunRequest.size, stun))
                val deadline = sentAt + TIMEOUT_MS * NANOS_PER_MS
                while ((request != null && answer == null) || (stun != null && mapped == null)) {
                    val left = (deadline - System.nanoTime()) / NANOS_PER_MS
                    if (left <= 0) break
                    socket.soTimeout = left.toInt()
                    val packet = DatagramPacket(ByteArray(MAX_PACKET), MAX_PACKET)
                    try {
                        socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        break
                    }
                    if (packet.socketAddress == target) {
                        val rtt = (System.nanoTime() - sentAt) / NANOS_PER_MS
                        answer = answer ?: SipPing.answer(String(packet.data, 0, packet.length, Charsets.ISO_8859_1), token, local, rtt)
                    } else if (stun != null && packet.socketAddress == stun) {
                        mapped = mapped ?: StunMessage.mapped(packet.data, packet.length, stunId)
                    }
                }
                if ((request == null || answer != null) && (stun == null || mapped != null)) break
            }
            UdpProbe(sentFrom, answer, mapped)
        }
    } catch (_: IOException) {
        UdpProbe(null, null, null)
    } catch (_: IllegalArgumentException) {
        UdpProbe(null, null, null)
    } catch (_: SecurityException) {
        UdpProbe(null, null, null)
    }

    /** How long a TCP connection to [host]:[port] took to be taken; null when it was not. */
    fun connectMs(host: String, port: Int): Long? = try {
        val start = System.nanoTime()
        Socket().use { it.connect(InetSocketAddress(host, port), TIMEOUT_MS) }
        (System.nanoTime() - start) / NANOS_PER_MS
    } catch (_: IOException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: SecurityException) {
        null
    }

    /** The addresses the system finds for [host] (an address stays itself); none when it finds none. */
    fun lookup(host: String): List<String> = try {
        InetAddress.getAllByName(host.removePrefix("[").removeSuffix("]")).mapNotNull { it.hostAddress?.substringBefore('%') }
    } catch (_: UnknownHostException) {
        emptyList()
    } catch (_: SecurityException) {
        emptyList()
    }

    /** [host] (a name or an address) looked up, with [port]; null when the system finds no address for it. */
    fun socketAddress(host: String, port: Int): InetSocketAddress? = try {
        InetSocketAddress(host, port).takeUnless { it.isUnresolved }
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: SecurityException) {
        null
    }

    /** `address:port`, an IPv6 address in brackets. */
    fun hostPort(mapped: Mapped): String = if (':' in mapped.address) "[${mapped.address}]:${mapped.port}" else "${mapped.address}:${mapped.port}"

    /**
     * Which transports get through to a SIP server, for the adaptation of a line. The address
     * asked is the one the SIP core goes to ([NetAddress.coreOrder]).
     *
     * @param host the server's name or address, [port] its port when the account names one
     * @param server the server as the account knows it, `host[:port]`
     * @param asker who asks over UDP; null: UDP is not asked (a line on TLS sends nothing in clear)
     * @param ipv6 the SIP core's IPv6 is on; [sixFirst] it goes over IPv6 first ([NetAddress.sixFirst])
     */
    fun transports(host: String, port: Int?, server: String, asker: Asker?, ipv6: Boolean, sixFirst: Boolean): LineAdapt.Probe {
        val address = NetAddress.coreOrder(lookup(host), ipv6, sixFirst).firstOrNull() ?: return nothing(asker)
        val sipPort = port ?: AccountRoute.defaultPort(SipTransport.UDP)
        val udp = asker?.let { probeUdp(address, sipPort, server, it).answer != null }
        val large = if (udp == true) probeUdp(address, sipPort, server, asker, size = LineAdapt.INVITE_PACKET).answer != null else null
        // An account that names its port has TLS somewhere else, if at all: only its own port is tried.
        val tls = if (port == null) connectMs(address, AccountRoute.defaultPort(SipTransport.TLS)) != null else null
        return LineAdapt.Probe(udp = udp, udpLarge = large, tcp = connectMs(address, sipPort) != null, tls = tls)
    }

    /** What a probe that reached nothing found. */
    fun nothing(asker: Asker?): LineAdapt.Probe = LineAdapt.Probe(udp = if (asker == null) null else false, udpLarge = null, tcp = false, tls = null)

    private const val STUN_ID = 12
    private const val NANOS_PER_MS = 1_000_000L
}
