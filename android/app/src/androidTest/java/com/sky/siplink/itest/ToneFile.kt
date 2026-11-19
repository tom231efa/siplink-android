package com.sky.siplink.itest

import android.content.Context
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/**
 * A 1 kHz tone of five minutes the tests have the app send instead of the microphone, so that an
 * echo, or a phone at the other side, has something to hear. Written once into the app's cache.
 */
object ToneFile {
    private const val NAME = "itest-tone.wav"
    private const val WAV_HEADER = 44
    private const val SECONDS = 300
    private const val RATE = 8000
    private const val HZ = 1000.0
    private const val AMPLITUDE = 12000

    fun of(context: Context): File {
        val file = File(context.cacheDir, NAME)
        if (file.length() > 0) return file
        // 16 bit mono PCM; one second of tone is computed and then repeated.
        val second = ByteBuffer.allocate(RATE * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until RATE) second.putShort((sin(2 * PI * HZ * i / RATE) * AMPLITUDE).toInt().toShort())
        val dataSize = SECONDS * RATE * 2
        val header = ByteBuffer.allocate(WAV_HEADER).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(WAV_HEADER - 8 + dataSize)
        header.put("WAVEfmt ".toByteArray(Charsets.US_ASCII)).putInt(16)
        header.putShort(1).putShort(1).putInt(RATE).putInt(RATE * 2).putShort(2).putShort(16)
        header.put("data".toByteArray(Charsets.US_ASCII)).putInt(dataSize)
        file.outputStream().buffered().use { out ->
            out.write(header.array())
            repeat(SECONDS) { out.write(second.array()) }
        }
        return file
    }
}
