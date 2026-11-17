package com.sky.siplink.core.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavFormatTest {
    /** The head of a WAV file of 16-bit PCM as the SIP core writes it; [dataSize] as it stands in the file. */
    private fun head(rate: Int, channels: Int, dataSize: Int, encoding: Int = 1): ByteArray {
        val buffer = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(36 + dataSize).put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray()).putInt(16).putShort(encoding.toShort()).putShort(channels.toShort())
            .putInt(rate).putInt(rate * channels * 2).putShort((channels * 2).toShort()).putShort(16)
        buffer.put("data".toByteArray()).putInt(dataSize)
        return buffer.array()
    }

    @Test
    fun aClosedFileIsReadAsItsHeadSays() {
        val info = WavFormat.parse(head(48_000, 1, 96_000), 44L + 96_000)!!
        assertEquals(WavInfo(48_000, 1, 16, 44, 96_000), info)
        assertEquals(1000L, info.durationMs)
        assertEquals((36 + 96_000) to 96_000, WavFormat.sizes(info))
    }

    @Test
    fun aFileLeftByACrashRunsToItsEnd() {
        // The sizes are written when the file is closed: 0 until then.
        val info = WavFormat.parse(head(8_000, 2, 0), 44L + 32_000)!!
        assertEquals(32_000L, info.dataBytes)
        assertEquals(1000L, info.durationMs)
        // A size larger than the file was never written either.
        assertEquals(16_000L, WavFormat.parse(head(8_000, 1, 1_000_000), 44L + 16_000)!!.dataBytes)
    }

    @Test
    fun anythingElseIsNoRecording() {
        assertNull(WavFormat.parse(head(8_000, 1, 0, encoding = 3), 44))
        assertNull(WavFormat.parse(ByteArray(44), 44))
        assertNull(WavFormat.parse("RIFF".toByteArray(), 4))
        assertNull(WavFormat.parse(head(8_000, 1, 0).copyOf(30), 30))
    }
}
