package com.sky.siplink.core.logic

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The head of a WAV file as the SIP core writes a call recording: RIFF, a "fmt " chunk of
 * linear PCM, and the sound in a "data" chunk. The core writes the sizes into the head when it
 * closes the file; a file left by a crash has them wrong, so the sound is taken to run to the
 * end of the file.
 *
 * @param dataOffset where the sound starts in the file
 * @param dataBytes how many bytes of sound the file holds
 */
data class WavInfo(val sampleRate: Int, val channels: Int, val bitsPerSample: Int, val dataOffset: Int, val dataBytes: Long) {
    /** How long the sound lasts. */
    val durationMs: Long
        get() {
            val bytesPerSecond = sampleRate.toLong() * channels * (bitsPerSample / 8)
            return if (bytesPerSecond <= 0) 0 else dataBytes * 1000 / bytesPerSecond
        }
}

object WavFormat {
    /** How much of the start of a file [parse] needs at most. */
    const val HEAD_BYTES = 4096

    /**
     * Reads the head of a WAV file.
     *
     * @param head the first bytes of the file, up to [HEAD_BYTES]
     * @param fileLength the length of the whole file
     * @return null when it is no linear PCM WAV file
     */
    fun parse(head: ByteArray, fileLength: Long): WavInfo? {
        val buffer = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
        if (head.size < 12 || tag(head, 0) != "RIFF" || tag(head, 8) != "WAVE") return null
        var position = 12
        var format: Triple<Int, Int, Int>? = null
        while (position + 8 <= head.size) {
            val id = tag(head, position)
            val size = buffer.getInt(position + 4).toLong() and 0xffffffffL
            val body = position + 8
            when (id) {
                "fmt " -> {
                    if (body + 16 > head.size) return null
                    val encoding = buffer.getShort(body).toInt()
                    if (encoding != PCM) return null
                    format = Triple(buffer.getInt(body + 4), buffer.getShort(body + 2).toInt(), buffer.getShort(body + 14).toInt())
                }
                "data" -> {
                    val (rate, channels, bits) = format ?: return null
                    if (rate <= 0 || channels <= 0 || bits <= 0) return null
                    val available = (fileLength - body).coerceAtLeast(0)
                    // A size that does not fit the file was never written: the sound runs to its end.
                    val bytes = if (size in 1..available) size else available
                    return WavInfo(rate, channels, bits, body, bytes)
                }
            }
            if (size > head.size) break
            position = body + size.toInt() + (size.toInt() and 1)
        }
        return null
    }

    /** The two sizes of the head of [info]'s file as they have to be: of the RIFF chunk and of the data chunk. */
    fun sizes(info: WavInfo): Pair<Int, Int> = (info.dataOffset - 8 + info.dataBytes).toInt() to info.dataBytes.toInt()

    private fun tag(bytes: ByteArray, at: Int): String = String(bytes, at, 4, Charsets.US_ASCII)

    private const val PCM = 1
}
