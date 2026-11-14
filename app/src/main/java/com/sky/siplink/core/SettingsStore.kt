package com.sky.siplink.core

import com.sky.siplink.core.model.IntercomHeaderMode
import com.sky.siplink.core.model.MediaSecurity
import com.sky.siplink.core.model.Settings
import com.sky.siplink.core.model.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.linphone.core.Config

/** App preferences, persisted in their own section of the SIP core configuration file. */
class SettingsStore(private val config: Config) {
    private val state = MutableStateFlow(load())
    val settings: StateFlow<Settings> = state.asStateFlow()
    val current: Settings get() = state.value

    fun update(transform: (Settings) -> Settings) {
        val next = transform(state.value)
        if (next == state.value) return
        save(next)
        state.value = next
    }

    private fun load(): Settings {
        val defaults = Settings()
        return Settings(
            keepAlive = config.getBool(SECTION, KEY_KEEP_ALIVE, defaults.keepAlive),
            intercomAutoAnswer = config.getBool(SECTION, KEY_INTERCOM_AUTO_ANSWER, defaults.intercomAutoAnswer),
            intercomSpeaker = config.getBool(SECTION, KEY_INTERCOM_SPEAKER, defaults.intercomSpeaker),
            intercomHeaderMode = enumOf(KEY_INTERCOM_HEADER, defaults.intercomHeaderMode),
            themeMode = enumOf(KEY_THEME, defaults.themeMode),
            dynamicColor = config.getBool(SECTION, KEY_DYNAMIC_COLOR, defaults.dynamicColor),
            verifyTlsCertificate = config.getBool(SECTION, KEY_TLS_VERIFY_CERT, defaults.verifyTlsCertificate),
            verifyTlsHostname = config.getBool(SECTION, KEY_TLS_VERIFY_HOST, defaults.verifyTlsHostname),
            mediaSecurity = enumOf(KEY_MEDIA_SECURITY, defaults.mediaSecurity),
        )
    }

    private fun save(value: Settings) {
        config.setBool(SECTION, KEY_KEEP_ALIVE, value.keepAlive)
        config.setBool(SECTION, KEY_INTERCOM_AUTO_ANSWER, value.intercomAutoAnswer)
        config.setBool(SECTION, KEY_INTERCOM_SPEAKER, value.intercomSpeaker)
        config.setString(SECTION, KEY_INTERCOM_HEADER, value.intercomHeaderMode.name)
        config.setString(SECTION, KEY_THEME, value.themeMode.name)
        config.setBool(SECTION, KEY_DYNAMIC_COLOR, value.dynamicColor)
        config.setBool(SECTION, KEY_TLS_VERIFY_CERT, value.verifyTlsCertificate)
        config.setBool(SECTION, KEY_TLS_VERIFY_HOST, value.verifyTlsHostname)
        config.setString(SECTION, KEY_MEDIA_SECURITY, value.mediaSecurity.name)
        config.sync()
    }

    private inline fun <reified T : Enum<T>> enumOf(key: String, default: T): T {
        val stored = config.getString(SECTION, key, null) ?: return default
        return enumValues<T>().firstOrNull { it.name == stored } ?: default
    }

    companion object {
        /** Section of the core configuration file owned by the app. */
        const val SECTION = "siplink"

        private const val KEY_KEEP_ALIVE = "keep_alive"
        private const val KEY_INTERCOM_AUTO_ANSWER = "intercom_auto_answer"
        private const val KEY_INTERCOM_SPEAKER = "intercom_speaker"
        private const val KEY_INTERCOM_HEADER = "intercom_header"
        private const val KEY_THEME = "theme"
        private const val KEY_DYNAMIC_COLOR = "dynamic_color"
        private const val KEY_TLS_VERIFY_CERT = "tls_verify_certificate"
        private const val KEY_TLS_VERIFY_HOST = "tls_verify_hostname"
        private const val KEY_MEDIA_SECURITY = "media_security"
    }
}
