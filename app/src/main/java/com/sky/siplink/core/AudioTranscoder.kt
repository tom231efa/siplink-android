package com.sky.siplink.core

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import com.sky.siplink.core.logic.WavInfo
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Turns the WAV file of a call recording into AAC-LC in an MPEG-4 file (.m4a), mono, at the
 * sample rate of the call: about a twentieth of the size, and a format every player opens. Uses
 * the platform's encoder (Android requires an AAC-LC encoder on every device) and muxer. Runs on
 * the thread of the caller, which is never the main thread.
 */
internal object AudioTranscoder {
    /**
     * @throws IOException or any other exception when the file could not be converted; [out] is then removed
     */
    fun wavToM4a(wav: File, info: WavInfo, out: File) {
        if (info.bitsPerSample != BITS) throw IOException("Unsupported sample size ${info.bitsPerSample}")
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, info.sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, if (info.sampleRate >= WIDEBAND_RATE) BIT_RATE else NARROWBAND_BIT_RATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, CHUNK_FRAMES * 2)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        var muxer: MediaMuxer? = null
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            RandomAccessFile(wav, "r").use { input ->
                input.seek(info.dataOffset.toLong())
                encode(codec, muxer, input, info)
            }
            muxer.stop()
        } catch (e: Exception) {
            out.delete()
            throw e
        } finally {
            runCatching { codec.stop() }
            codec.release()
            runCatching { muxer?.release() }
        }
    }

    private fun encode(codec: MediaCodec, muxer: MediaMuxer, input: RandomAccessFile, info: WavInfo) {
        val frameBytes = info.channels * 2
        val raw = ByteArray(CHUNK_FRAMES * frameBytes)
        val mono = ByteBuffer.allocate(CHUNK_FRAMES * 2).order(ByteOrder.LITTLE_ENDIAN)
        var remaining = info.dataBytes - info.dataBytes % frameBytes
        var framesFed = 0L
        var inputDone = false
        var track = -1
        val done = MediaCodec.BufferInfo()
        while (true) {
            if (!inputDone) {
                val index = codec.dequeueInputBuffer(TIMEOUT_US)
                if (index >= 0) {
                    val wanted = minOf(raw.size.toLong(), remaining).toInt()
                    val read = if (wanted > 0) input.read(raw, 0, wanted) else -1
                    val time = framesFed * 1_000_000 / info.sampleRate
                    if (read <= 0) {
                        codec.queueInputBuffer(index, 0, 0, time, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        val frames = read / frameBytes
                        downmix(raw, frames, info.channels, mono)
                        val buffer = codec.getInputBuffer(index) ?: throw IOException("No input buffer")
                        buffer.clear()
                        buffer.put(mono.array(), 0, frames * 2)
                        codec.queueInputBuffer(index, 0, frames * 2, time, 0)
                        framesFed += frames
                        remaining -= read
                    }
                }
            }
            val index = codec.dequeueOutputBuffer(done, TIMEOUT_US)
            when {
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                }
                index >= 0 -> {
                    val buffer = codec.getOutputBuffer(index) ?: throw IOException("No output buffer")
                    val config = done.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (!config && done.size > 0 && track >= 0) muxer.writeSampleData(track, buffer, done)
                    codec.releaseOutputBuffer(index, false)
                    if (done.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        if (track < 0) throw IOException("The encoder put out nothing")
                        return
                    }
                }
            }
        }
    }

    /** The first [frames] frames of 16-bit little-endian PCM in [raw], as one channel: the average of all. */
    private fun downmix(raw: ByteArray, frames: Int, channels: Int, out: ByteBuffer) {
        out.clear()
        for (frame in 0 until frames) {
            var sum = 0
            for (channel in 0 until channels) {
                val at = (frame * channels + channel) * 2
                sum += (raw[at].toInt() and 0xff) or (raw[at + 1].toInt() shl 8)
            }
            out.putShort((sum / channels).toShort())
        }
    }

    private const val BITS = 16
    private const val CHUNK_FRAMES = 2048
    private const val TIMEOUT_US = 10_000L

    /** From this sample rate on the call is wideband, and gets the higher bit rate. */
    private const val WIDEBAND_RATE = 16_000
    private const val BIT_RATE = 32_000
    private const val NARROWBAND_BIT_RATE = 24_000
}
