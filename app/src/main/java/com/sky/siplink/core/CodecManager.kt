package com.sky.siplink.core

import com.sky.siplink.core.model.Codec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.linphone.core.Core
import org.linphone.core.PayloadType

/**
 * The audio and the video codecs of the SIP core: which ones are offered and in which order of
 * preference. The core keeps both in its configuration file. Use from the main thread.
 */
class CodecManager(private val core: Core) {
    /** One kind of codecs: how the core lists and stores them, and where their defaults are remembered. */
    private inner class Kind(
        private val get: () -> Array<PayloadType>,
        private val set: (Array<PayloadType>) -> Unit,
        private val defaultsKey: String,
        private val isVideo: Boolean,
    ) {
        val state = MutableStateFlow(read())

        init {
            // What the SDK ships with, remembered once so that "restore defaults" has something to restore.
            if (core.config.getString(SettingsStore.SECTION, defaultsKey, null).isNullOrEmpty()) {
                core.config.setString(SettingsStore.SECTION, defaultsKey, state.value.joinToString(";") { "${it.key}=${if (it.enabled) 1 else 0}" })
            }
        }

        fun has(key: String): Boolean = state.value.any { it.key == key }

        fun setEnabled(key: String, enabled: Boolean): Boolean {
            val types = get()
            val type = types.firstOrNull { keyOf(it) == key } ?: return false
            if (!enabled && type.enabled() && types.count { it.enabled() } <= 1) return false
            type.enable(enabled)
            commit(types)
            return true
        }

        fun move(key: String, offset: Int) {
            val types = get().toMutableList()
            val from = types.indexOfFirst { keyOf(it) == key }
            val to = from + offset
            if (from < 0 || to !in types.indices) return
            types.add(to, types.removeAt(from))
            commit(types.toTypedArray())
        }

        fun restoreDefaults() {
            val defaults = core.config.getString(SettingsStore.SECTION, defaultsKey, null).orEmpty()
                .split(';').filter { '=' in it }.map { it.substringBeforeLast('=') to it.endsWith("=1") }
            if (defaults.isEmpty()) return
            val types = get()
            val order = defaults.withIndex().associate { it.value.first to it.index }
            for ((key, enabled) in defaults) types.firstOrNull { keyOf(it) == key }?.enable(enabled)
            commit(types.sortedBy { order[keyOf(it)] ?: Int.MAX_VALUE }.toTypedArray())
        }

        private fun commit(types: Array<PayloadType>) {
            set(types)
            core.config.sync()
            state.value = read()
        }

        private fun read(): List<Codec> = get().map { Codec(it.mimeType, it.clockRate, it.channels, it.enabled(), isVideo) }
    }

    private val audio = Kind({ core.audioPayloadTypes }, { core.setAudioPayloadTypes(it) }, KEY_AUDIO_DEFAULTS, isVideo = false)
    private val video = Kind({ core.videoPayloadTypes }, { core.setVideoPayloadTypes(it) }, KEY_VIDEO_DEFAULTS, isVideo = true)

    val codecs: StateFlow<List<Codec>> = audio.state.asStateFlow()

    /** The video codecs the SDK provides on this device. */
    val videoCodecs: StateFlow<List<Codec>> = video.state.asStateFlow()

    private fun kindOf(key: String): Kind = if (audio.has(key)) audio else video

    /** Turns a codec on or off. The last one of its kind that is on stays on: a call needs a codec. */
    fun setEnabled(key: String, enabled: Boolean): Boolean = kindOf(key).setEnabled(key, enabled)

    /** Moves a codec [offset] places in the order of preference of its kind (negative: more preferred). */
    fun move(key: String, offset: Int) = kindOf(key).move(key, offset)

    /** Back to the order and the selection the SDK came with, for audio and video. */
    fun restoreDefaults() {
        audio.restoreDefaults()
        video.restoreDefaults()
    }

    private fun keyOf(type: PayloadType): String = "${type.mimeType}/${type.clockRate}/${type.channels}"

    private companion object {
        /** Named before there were video codecs; kept so that the stored defaults stay valid. */
        const val KEY_AUDIO_DEFAULTS = "audio_codec_defaults"
        const val KEY_VIDEO_DEFAULTS = "video_codec_defaults"
    }
}
