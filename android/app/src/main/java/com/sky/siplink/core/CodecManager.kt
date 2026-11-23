package com.sky.siplink.core

import com.sky.siplink.core.logic.CodecChoice
import com.sky.siplink.core.logic.RemotePlan
import com.sky.siplink.core.model.Codec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.linphone.core.Core
import org.linphone.core.PayloadType

/**
 * The audio and the video codecs of the SIP core: which ones are offered and in which order of
 * preference. The core keeps both in its configuration file. Use from the main thread.
 *
 * While the configuration is managed ([ConfigLock]) neither can be changed here.
 */
class CodecManager(private val core: Core, private val lock: ConfigLock) {
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

        fun move(key: String, offset: Int): Boolean {
            val types = get().toMutableList()
            val from = types.indexOfFirst { keyOf(it) == key }
            val to = from + offset
            if (from < 0 || to !in types.indices) return false
            types.add(to, types.removeAt(from))
            commit(types.toTypedArray())
            return true
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

        /** Puts the codecs in the order of [RemotePlan.codecOrder] and switches each as it says. */
        fun arrange(wanted: List<CodecChoice>) {
            val types = get()
            val order = RemotePlan.codecOrder(types.map(::keyOf), wanted)
            val position = order.withIndex().associate { it.value.key to it.index }
            val on = order.associate { it.key to it.enabled }
            val arranged = types.sortedBy { position.getValue(keyOf(it)) }
            if (arranged.map(::keyOf) == types.map(::keyOf) && types.all { it.enabled() == on.getValue(keyOf(it)) }) return
            arranged.forEach { it.enable(on.getValue(keyOf(it))) }
            commit(arranged.toTypedArray())
        }

        private fun commit(types: Array<PayloadType>) {
            CoreThread.check("The codecs")
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

    /**
     * Turns a codec on or off. The last one of its kind that is on stays on: a call needs a codec.
     *
     * @return whether it was done: not for that last codec, nor while the configuration is managed
     */
    fun setEnabled(key: String, enabled: Boolean): Boolean = !lock.refuses && kindOf(key).setEnabled(key, enabled)

    /**
     * Moves a codec [offset] places in the order of preference of its kind (negative: more preferred).
     *
     * @return whether it moved: not past either end of the list, nor while the configuration is managed
     */
    fun move(key: String, offset: Int): Boolean = !lock.refuses && kindOf(key).move(key, offset)

    /**
     * Back to the order and the selection the SDK came with, for audio and video.
     *
     * @return false when the configuration is managed: nothing was changed
     */
    fun restoreDefaults(): Boolean {
        if (lock.refuses) return false
        audio.restoreDefaults()
        video.restoreDefaults()
        return true
    }

    /**
     * Orders and switches the codecs as the remote side wants them ([RemotePlan.codecOrder]).
     * Where that would leave no audio codec on, the audio codecs go back to the SDK's defaults
     * instead: a call needs one.
     *
     * @return false when that happened, or when the configuration is managed and this is not the remote side
     */
    fun apply(audioCodecs: List<CodecChoice>, videoCodecs: List<CodecChoice>): Boolean {
        if (lock.refuses) return false
        video.arrange(videoCodecs)
        val audioKeys = audio.state.value.map { it.key }
        if (RemotePlan.codecOrder(audioKeys, audioCodecs).none { it.enabled }) {
            audio.restoreDefaults()
            return false
        }
        audio.arrange(audioCodecs)
        return true
    }

    private fun keyOf(type: PayloadType): String = "${type.mimeType}/${type.clockRate}/${type.channels}"

    private companion object {
        /** Named before there were video codecs; kept so that the stored defaults stay valid. */
        const val KEY_AUDIO_DEFAULTS = "audio_codec_defaults"
        const val KEY_VIDEO_DEFAULTS = "video_codec_defaults"
    }
}
