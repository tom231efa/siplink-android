package com.sky.siplink.itest

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * Plays the management platform for the tests: a small HTTP server on 127.0.0.1, inside the test
 * process, that answers each request as the test says ([answer]) and remembers it ([requests]).
 * Plain HTTP, which debug builds of the app accept.
 */
class PlatformDouble : AutoCloseable {
    /** A request as it came: the method, the path with its query, the headers (names in lower case) and the body. */
    class Request(val method: String, val path: String, val headers: Map<String, String>, val body: String)

    class Response(val status: Int, val body: String = "")

    private val server = ServerSocket(0, 16, InetAddress.getByName(HOST))
    val requests = CopyOnWriteArrayList<Request>()

    /** How the platform answers; the test changes it as the platform would change. */
    @Volatile
    var answer: (Request) -> Response = { Response(404, """{"error":"invalid_token"}""") }

    /** Where it is reached, as the QR code would say: `http://127.0.0.1:{port}`. */
    val origin: String = "http://$HOST:${server.localPort}"

    private val acceptor = thread(name = "platform-double", isDaemon = true) {
        while (!server.isClosed) {
            val socket = try {
                server.accept()
            } catch (e: SocketException) {
                break
            }
            thread(isDaemon = true) { socket.use(::serve) }
        }
    }

    private fun serve(socket: Socket) {
        val input = BufferedInputStream(socket.getInputStream())
        val requestLine = input.line() ?: return
        val headers = HashMap<String, String>()
        while (true) {
            val line = input.line() ?: return
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val count = input.read(body, read, length - read)
            if (count < 0) break
            read += count
        }
        val parts = requestLine.split(' ')
        val request = Request(parts.getOrElse(0) { "" }, parts.getOrElse(1) { "" }, headers, String(body, 0, read, Charsets.UTF_8))
        requests += request
        val response = answer(request)
        val content = response.body.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 ${response.status} ${reason(response.status)}\r\n")
            if (response.status != 204) {
                append("Content-Type: application/json; charset=utf-8\r\n")
                append("Content-Length: ${content.size}\r\n")
            }
            append("Connection: close\r\n\r\n")
        }
        socket.getOutputStream().apply {
            write(head.toByteArray(Charsets.ISO_8859_1))
            if (response.status != 204) write(content)
            flush()
        }
    }

    private fun reason(status: Int): String = when (status) {
        200 -> "OK"
        204 -> "No Content"
        401 -> "Unauthorized"
        404 -> "Not Found"
        410 -> "Gone"
        else -> "Status"
    }

    /** One line of the head of a request, without its line end; null at the end of the stream. */
    private fun InputStream.line(): String? {
        val out = ByteArrayOutputStream()
        while (true) {
            val byte = read()
            if (byte < 0) return if (out.size() == 0) null else out.toString(Charsets.ISO_8859_1.name())
            if (byte == '\n'.code) return out.toString(Charsets.ISO_8859_1.name()).trimEnd('\r')
            out.write(byte)
        }
    }

    override fun close() {
        server.close()
        acceptor.join(1_000)
    }

    private companion object {
        const val HOST = "127.0.0.1"
    }
}
