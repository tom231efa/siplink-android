package com.sky.siplink.itest

import java.io.Closeable
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Minimal Asterisk Manager Interface client. The tests use it to look at calls from the PBX
 * side and to make the scripted desk phones place calls.
 */
class Ami(private val host: String, user: String, secret: String, port: Int = 5038) : Closeable {
    private val socket = Socket()
    private val lock = Object()

    /** Every packet received so far, responses and events alike. Guarded by [lock]. */
    private val packets = ArrayList<Map<String, String>>()
    private var nextId = 0

    init {
        socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        thread(isDaemon = true, name = "ami-$host") { readLoop() }
        val response = send("Login", "Username" to user, "Secret" to secret)
        check(response["Response"] == "Success") { "AMI login to $host failed: ${response["Message"]}" }
    }

    /** Position in the packet stream; pass it to [awaitEvent] to ignore everything older. */
    fun mark(): Int = synchronized(lock) { packets.size }

    /** Sends an action and returns its response packet. */
    fun send(action: String, vararg fields: Pair<String, String>): Map<String, String> {
        val start = mark()
        val id = write(action, fields)
        return awaitPacket(start, ACTION_TIMEOUT_MS, "the response to $action") {
            it["ActionID"] == id && it.containsKey("Response")
        }
    }

    /** Runs a list action and returns its item events. */
    fun list(action: String, vararg fields: Pair<String, String>): List<Map<String, String>> {
        val start = mark()
        val id = write(action, fields)
        awaitPacket(start, ACTION_TIMEOUT_MS, "the end of the $action list") {
            it["ActionID"] == id && it["EventList"] == "Complete"
        }
        return synchronized(lock) {
            packets.drop(start).filter { it["ActionID"] == id && it.containsKey("Event") && it["EventList"] == null }
        }
    }

    /** Runs an Asterisk CLI command and returns its output. */
    fun command(cli: String): String = send("Command", "Command" to cli)["Output"].orEmpty()

    fun awaitEvent(
        name: String,
        from: Int,
        timeoutMs: Long = EVENT_TIMEOUT_MS,
        match: (Map<String, String>) -> Boolean = { true },
    ): Map<String, String> = awaitPacket(from, timeoutMs, "the AMI event $name") { it["Event"] == name && match(it) }

    /** Every event [name] that arrived since [from] and matches, in order. */
    fun events(name: String, from: Int, match: (Map<String, String>) -> Boolean = { true }): List<Map<String, String>> =
        synchronized(lock) { packets.drop(from).filter { it["Event"] == name && match(it) } }

    /** True if a matching event arrives within [windowMs]; for asserting that something does not happen. */
    fun sees(name: String, from: Int, windowMs: Long, match: (Map<String, String>) -> Boolean = { true }): Boolean =
        try {
            awaitEvent(name, from, windowMs, match)
            true
        } catch (e: AssertionError) {
            false
        }

    override fun close() {
        runCatching { socket.close() }
    }

    private fun write(action: String, fields: Array<out Pair<String, String>>): String {
        val id: String
        val text = StringBuilder()
        synchronized(lock) { id = "siplink-${++nextId}" }
        text.append("Action: ").append(action).append("\r\n")
        text.append("ActionID: ").append(id).append("\r\n")
        fields.forEach { (key, value) -> text.append(key).append(": ").append(value).append("\r\n") }
        text.append("\r\n")
        synchronized(socket) {
            socket.getOutputStream().apply {
                write(text.toString().toByteArray(Charsets.UTF_8))
                flush()
            }
        }
        return id
    }

    private fun awaitPacket(
        from: Int,
        timeoutMs: Long,
        what: String,
        match: (Map<String, String>) -> Boolean,
    ): Map<String, String> {
        val deadline = System.currentTimeMillis() + timeoutMs
        var index = from
        synchronized(lock) {
            while (true) {
                while (index < packets.size) {
                    val packet = packets[index++]
                    if (match(packet)) return packet
                }
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) throw AssertionError("Timed out after ${timeoutMs}ms waiting for $what from $host")
                lock.wait(remaining)
            }
        }
    }

    private fun readLoop() {
        try {
            val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
            reader.readLine() // banner: "Asterisk Call Manager/x.y.z"
            var packet = LinkedHashMap<String, String>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) {
                    if (packet.isNotEmpty()) {
                        synchronized(lock) {
                            packets.add(packet)
                            lock.notifyAll()
                        }
                        packet = LinkedHashMap()
                    }
                    continue
                }
                val separator = line.indexOf(": ")
                if (separator <= 0) continue
                val key = line.substring(0, separator)
                val value = line.substring(separator + 2)
                // Repeated headers (the "Output" lines of a CLI command) are kept as lines.
                packet[key] = packet[key]?.let { "$it\n$value" } ?: value
            }
        } catch (e: Exception) {
            // Socket closed by close(): nothing left to read.
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
        const val ACTION_TIMEOUT_MS = 20_000L
        const val EVENT_TIMEOUT_MS = 45_000L
    }
}
