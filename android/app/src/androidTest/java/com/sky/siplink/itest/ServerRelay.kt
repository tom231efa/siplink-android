package com.sky.siplink.itest

import java.io.Closeable
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * A second way to the PBX that the test can cut: a UDP relay on the device itself, at [front]. To
 * the app it is a SIP server of its own. It can pass everything, nothing ([silent]: packets are
 * dropped without a word, as on a network that does not reach an office PBX), lose a share
 * of the packets each way ([loss]), or hold each packet back a while each way ([delayMs]).
 */
class ServerRelay(private val target: InetSocketAddress, front: InetSocketAddress) : Closeable {
    private val socket = DatagramSocket(front)
    private val backs = ConcurrentHashMap<SocketAddress, DatagramSocket>()

    /** Nothing gets through, either way. */
    @Volatile
    var silent = false

    /** The share of packets lost each way, 0 to 1. */
    @Volatile
    var loss = 0.0

    /** How long each packet is held back each way. */
    @Volatile
    var delayMs = 0L

    private val later = Executors.newSingleThreadScheduledExecutor()

    init {
        pump(socket) { packet ->
            // One socket towards the PBX per sender, so that its answers find their way back.
            val back = backs.getOrPut(packet.socketAddress) { backFor(packet.socketAddress) }
            if (passes()) forward(back, DatagramPacket(packet.data.copyOf(packet.length), packet.length, target))
        }
    }

    private fun passes(): Boolean = !silent && (loss <= 0.0 || Random.nextDouble() >= loss)

    private fun backFor(client: SocketAddress): DatagramSocket {
        val back = DatagramSocket()
        pump(back) { packet -> if (passes()) forward(socket, DatagramPacket(packet.data.copyOf(packet.length), packet.length, client)) }
        return back
    }

    /** Sends [packet] on [socket], after [delayMs] when there is one. */
    private fun forward(socket: DatagramSocket, packet: DatagramPacket) {
        val delay = delayMs
        if (delay <= 0) {
            socket.send(packet)
        } else {
            later.schedule({ runCatching { socket.send(packet) } }, delay, TimeUnit.MILLISECONDS)
        }
    }

    private fun pump(socket: DatagramSocket, forward: (DatagramPacket) -> Unit) {
        thread(isDaemon = true) {
            val buffer = ByteArray(MAX_PACKET)
            try {
                while (true) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    forward(packet)
                }
            } catch (_: IOException) {
                // Closed: the relay is switched off.
            }
        }
    }

    override fun close() {
        later.shutdownNow()
        socket.close()
        backs.values.forEach { it.close() }
    }

    private companion object {
        const val MAX_PACKET = 65_535
    }
}
