package com.sky.siplink.core

import com.sky.siplink.core.model.AudioCodec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.linphone.core.Core
import org.linphone.core.PayloadType

/**
 * The audio codecs of the SIP core: which ones are offered and in which order of preference.
 * The core keeps both in its configuration file. Use from the main thread.
 */
class CodecManager(private val core: Core) {
    private val state = MutableStateFlow(read())
    val codecs: StateFlow<List<AudioCodec>> = state.asStateFlow()

    init {
        // What the SDK ships with, remembered once so that "restore defaults" has something to restore.
        if (core.config.getString(SettingsStore.SECTION, KEY_DEFAULTS, null).isNullOrEmpty()) {
            core.config.setString(SettingsStore.SECTION, KEY_DEFAULTS, state.value.joinToString(";") { "${it.key}=${if (it.enabled) 1 else 0}" })
        }
    }

    /** Turns a codec on or off. The last one that is on stays on: a call needs a codec. */
    fun setEnabled(key: String, enabled: Boolean): Boolean {
        val types = core.audioPayloadTypes
        val type = types.firstOrNull { keyOf(it) == key } ?: return false
        if (!enabled && type.enabled() && types.count { it.enabled() } <= 1) return false
        type.enable(enabled)
        commit(types)
        return true
    }

    /** Moves a codec [offset] places in the order of preference (negative: more preferred). */
    fun move(key: String, offset: Int) {
        val types = core.audioPayloadTypes.toMutableList()
        val from = types.indexOfFirst { keyOf(it) == key }
        val to = from + offset
        if (from < 0 || to !in types.indices) return
        types.add(to, types.removeAt(from))
        commit(types.toTypedArray())
    }

    /** Back to the order and the selection the SDK came with. */
    fun restoreDefaults() {
        val defaults = core.config.getString(SettingsStore.SECTION, KEY_DEFAULTS, null).orEmpty()
            .split(';').filter { '=' in it }.map { it.substringBeforeLast('=') to it.endsWith("=1") }
        if (defaults.isEmpty()) return
        val types = core.audioPayloadTypes
        val order = defaults.withIndex().associate { it.value.first to it.index }
        for ((key, enabled) in defaults) types.firstOrNull { keyOf(it) == key }?.enable(enabled)
        commit(types.sortedBy { order[keyOf(it)] ?: Int.MAX_VALUE }.toTypedArray())
    }

    private fun commit(types: Array<PayloadType>) {
        core.setAudioPayloadTypes(types)
        core.config.sync()
        state.value = read()
    }

    private fun read(): List<AudioCodec> =
        core.audioPayloadTypes.map { AudioCodec(it.mimeType, it.clockRate, it.channels, it.enabled()) }

    private fun keyOf(type: PayloadType): String = "${type.mimeType}/${type.clockRate}/${type.channels}"

    private companion object {
        const val KEY_DEFAULTS = "audio_codec_defaults"
    }
}
