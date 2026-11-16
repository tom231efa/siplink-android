package com.sky.siplink.core

import android.os.Handler
import android.os.Looper
import com.sky.siplink.core.logic.ManagedConfig
import com.sky.siplink.core.logic.VideoRules
import com.sky.siplink.core.model.FeatureCodes
import com.sky.siplink.core.model.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.linphone.core.Config

/** App preferences, persisted in their own section of the SIP core configuration file. */
class SettingsStore(private val config: Config, private val lock: ConfigLock) {
    private val state = MutableStateFlow(load())
    val settings: StateFlow<Settings> = state.asStateFlow()
    val current: Settings get() = state.value

    private val handler = Handler(Looper.getMainLooper())
    private var unsaved = false
    private val writeToDisk = Runnable { flush() }

    /**
     * Changes the settings. While the configuration is managed only the user's own settings
     * change ([ManagedConfig.localUpdate]).
     *
     * @return false when part of the change was refused for that reason
     */
    fun update(transform: (Settings) -> Settings): Boolean {
        // A frame rate that cannot be chosen is no rate of its own, here as when it is read back.
        val wanted = transform(state.value).let { it.copy(videoFrameRate = VideoRules.frameRate(it.videoFrameRate)) }
        val next = if (lock.refuses) ManagedConfig.localUpdate(state.value, wanted) else wanted
        if (next != state.value) {
            save(next)
            state.value = next
        }
        return next == wanted
    }

    private fun load(): Settings {
        val d = Settings()
        val f = d.features
        return Settings(
            keepAlive = bool(KEY_KEEP_ALIVE, d.keepAlive),
            intercomAutoAnswer = bool(KEY_INTERCOM_AUTO_ANSWER, d.intercomAutoAnswer),
            intercomSpeaker = bool(KEY_INTERCOM_SPEAKER, d.intercomSpeaker),
            themeMode = enumOf(KEY_THEME, d.themeMode),
            dynamicColor = bool(KEY_DYNAMIC_COLOR, d.dynamicColor),
            callBannerPosition = enumOf(KEY_CALL_BANNER, d.callBannerPosition),
            verifyTlsCertificate = bool(KEY_TLS_VERIFY_CERT, d.verifyTlsCertificate),
            verifyTlsHostname = bool(KEY_TLS_VERIFY_HOST, d.verifyTlsHostname),
            mediaSecurity = enumOf(KEY_MEDIA_SECURITY, d.mediaSecurity),
            doNotDisturb = bool(KEY_DND, d.doNotDisturb),
            dndMode = enumOf(KEY_DND_MODE, d.dndMode),
            callWaiting = bool(KEY_CALL_WAITING, d.callWaiting),
            rejectAnonymous = bool(KEY_REJECT_ANONYMOUS, d.rejectAnonymous),
            dtmfMode = enumOf(KEY_DTMF_MODE, d.dtmfMode),
            forwardAlways = text(KEY_FORWARD_ALWAYS, d.forwardAlways),
            forwardBusy = text(KEY_FORWARD_BUSY, d.forwardBusy),
            forwardNoAnswer = text(KEY_FORWARD_NO_ANSWER, d.forwardNoAnswer),
            forwardNoAnswerSeconds = config.getInt(SECTION, KEY_FORWARD_NO_ANSWER_SECONDS, d.forwardNoAnswerSeconds),
            features = FeatureCodes(
                park = text(KEY_CODE_PARK, f.park),
                listen = text(KEY_CODE_LISTEN, f.listen),
                whisper = text(KEY_CODE_WHISPER, f.whisper),
                barge = text(KEY_CODE_BARGE, f.barge),
                forceHangUp = text(KEY_CODE_FORCE_HANG_UP, f.forceHangUp),
                pickup = text(KEY_CODE_PICKUP, f.pickup),
                groupPickup = text(KEY_CODE_GROUP_PICKUP, f.groupPickup),
                voicemail = text(KEY_CODE_VOICEMAIL, f.voicemail),
                intercom = text(KEY_CODE_INTERCOM, f.intercom),
                dndOn = text(KEY_CODE_DND_ON, f.dndOn),
                dndOff = text(KEY_CODE_DND_OFF, f.dndOff),
            ),
            videoCalls = bool(KEY_VIDEO_CALLS, d.videoCalls),
            videoSize = enumOf(KEY_VIDEO_SIZE, d.videoSize),
            videoFrameRate = VideoRules.frameRate(config.getInt(SECTION, KEY_VIDEO_FRAME_RATE, d.videoFrameRate)),
            lockScreenPromptDeclined = bool(KEY_LOCK_SCREEN_PROMPT_DECLINED, d.lockScreenPromptDeclined),
            autoUpdateCheck = bool(KEY_AUTO_UPDATE_CHECK, d.autoUpdateCheck),
            userAgent = text(KEY_USER_AGENT, d.userAgent),
            adaptiveRateControl = bool(KEY_ADAPTIVE_RATE_CONTROL, d.adaptiveRateControl),
            echoCancellation = bool(KEY_ECHO_CANCELLATION, d.echoCancellation),
            ipv6 = bool(KEY_IPV6, d.ipv6),
        )
    }

    private fun save(value: Settings) {
        config.setBool(SECTION, KEY_KEEP_ALIVE, value.keepAlive)
        config.setBool(SECTION, KEY_INTERCOM_AUTO_ANSWER, value.intercomAutoAnswer)
        config.setBool(SECTION, KEY_INTERCOM_SPEAKER, value.intercomSpeaker)
        config.setString(SECTION, KEY_THEME, value.themeMode.name)
        config.setBool(SECTION, KEY_DYNAMIC_COLOR, value.dynamicColor)
        config.setString(SECTION, KEY_CALL_BANNER, value.callBannerPosition.name)
        config.setBool(SECTION, KEY_TLS_VERIFY_CERT, value.verifyTlsCertificate)
        config.setBool(SECTION, KEY_TLS_VERIFY_HOST, value.verifyTlsHostname)
        config.setString(SECTION, KEY_MEDIA_SECURITY, value.mediaSecurity.name)
        config.setBool(SECTION, KEY_DND, value.doNotDisturb)
        config.setString(SECTION, KEY_DND_MODE, value.dndMode.name)
        config.setBool(SECTION, KEY_CALL_WAITING, value.callWaiting)
        config.setBool(SECTION, KEY_REJECT_ANONYMOUS, value.rejectAnonymous)
        config.setString(SECTION, KEY_DTMF_MODE, value.dtmfMode.name)
        config.setString(SECTION, KEY_FORWARD_ALWAYS, value.forwardAlways)
        config.setString(SECTION, KEY_FORWARD_BUSY, value.forwardBusy)
        config.setString(SECTION, KEY_FORWARD_NO_ANSWER, value.forwardNoAnswer)
        config.setInt(SECTION, KEY_FORWARD_NO_ANSWER_SECONDS, value.forwardNoAnswerSeconds)
        val f = value.features
        config.setString(SECTION, KEY_CODE_PARK, f.park)
        config.setString(SECTION, KEY_CODE_LISTEN, f.listen)
        config.setString(SECTION, KEY_CODE_WHISPER, f.whisper)
        config.setString(SECTION, KEY_CODE_BARGE, f.barge)
        config.setString(SECTION, KEY_CODE_FORCE_HANG_UP, f.forceHangUp)
        config.setString(SECTION, KEY_CODE_PICKUP, f.pickup)
        config.setString(SECTION, KEY_CODE_GROUP_PICKUP, f.groupPickup)
        config.setString(SECTION, KEY_CODE_VOICEMAIL, f.voicemail)
        config.setString(SECTION, KEY_CODE_INTERCOM, f.intercom)
        config.setString(SECTION, KEY_CODE_DND_ON, f.dndOn)
        config.setString(SECTION, KEY_CODE_DND_OFF, f.dndOff)
        config.setBool(SECTION, KEY_VIDEO_CALLS, value.videoCalls)
        config.setString(SECTION, KEY_VIDEO_SIZE, value.videoSize.name)
        config.setInt(SECTION, KEY_VIDEO_FRAME_RATE, value.videoFrameRate)
        config.setBool(SECTION, KEY_LOCK_SCREEN_PROMPT_DECLINED, value.lockScreenPromptDeclined)
        config.setBool(SECTION, KEY_AUTO_UPDATE_CHECK, value.autoUpdateCheck)
        config.setString(SECTION, KEY_USER_AGENT, value.userAgent)
        config.setBool(SECTION, KEY_ADAPTIVE_RATE_CONTROL, value.adaptiveRateControl)
        config.setBool(SECTION, KEY_ECHO_CANCELLATION, value.echoCancellation)
        config.setBool(SECTION, KEY_IPV6, value.ipv6)
        // Writing the file means writing every account in it as well, and a text field changes
        // the settings with each key stroke: one write once the changes have stopped coming.
        unsaved = true
        handler.removeCallbacks(writeToDisk)
        handler.postDelayed(writeToDisk, WRITE_DELAY_MS)
    }

    /** Writes pending changes to disk now, e.g. when the app leaves the screen. */
    fun flush() {
        handler.removeCallbacks(writeToDisk)
        if (!unsaved) return
        unsaved = false
        config.sync()
    }

    private fun bool(key: String, default: Boolean): Boolean = config.getBool(SECTION, key, default)

    /** An explicitly stored empty value must stay empty, so presence is checked separately. */
    private fun text(key: String, default: String): String =
        if (config.hasEntry(SECTION, key) != 0) config.getString(SECTION, key, "").orEmpty() else default

    private inline fun <reified T : Enum<T>> enumOf(key: String, default: T): T {
        val stored = config.getString(SECTION, key, null) ?: return default
        return enumValues<T>().firstOrNull { it.name == stored } ?: default
    }

    companion object {
        /** Section of the core configuration file owned by the app. */
        const val SECTION = "siplink"

        private const val WRITE_DELAY_MS = 600L

        private const val KEY_KEEP_ALIVE = "keep_alive"
        private const val KEY_INTERCOM_AUTO_ANSWER = "intercom_auto_answer"
        private const val KEY_INTERCOM_SPEAKER = "intercom_speaker"
        private const val KEY_THEME = "theme"
        private const val KEY_DYNAMIC_COLOR = "dynamic_color"
        private const val KEY_CALL_BANNER = "call_banner_position"
        private const val KEY_TLS_VERIFY_CERT = "tls_verify_certificate"
        private const val KEY_TLS_VERIFY_HOST = "tls_verify_hostname"
        private const val KEY_MEDIA_SECURITY = "media_security"
        private const val KEY_DND = "do_not_disturb"
        private const val KEY_DND_MODE = "do_not_disturb_mode"
        private const val KEY_CALL_WAITING = "call_waiting"
        private const val KEY_REJECT_ANONYMOUS = "reject_anonymous"
        private const val KEY_DTMF_MODE = "dtmf_mode"
        private const val KEY_FORWARD_ALWAYS = "forward_always"
        private const val KEY_FORWARD_BUSY = "forward_busy"
        private const val KEY_FORWARD_NO_ANSWER = "forward_no_answer"
        private const val KEY_FORWARD_NO_ANSWER_SECONDS = "forward_no_answer_seconds"
        private const val KEY_CODE_PARK = "code_park"
        private const val KEY_CODE_LISTEN = "code_listen"
        private const val KEY_CODE_WHISPER = "code_whisper"
        private const val KEY_CODE_BARGE = "code_barge"
        private const val KEY_CODE_FORCE_HANG_UP = "code_force_hang_up"
        private const val KEY_CODE_PICKUP = "code_pickup"
        private const val KEY_CODE_GROUP_PICKUP = "code_group_pickup"
        private const val KEY_CODE_VOICEMAIL = "code_voicemail"
        private const val KEY_CODE_INTERCOM = "code_intercom"
        private const val KEY_CODE_DND_ON = "code_dnd_on"
        private const val KEY_CODE_DND_OFF = "code_dnd_off"
        private const val KEY_VIDEO_CALLS = "video_calls"
        private const val KEY_VIDEO_SIZE = "video_size"
        private const val KEY_VIDEO_FRAME_RATE = "video_frame_rate"
        private const val KEY_LOCK_SCREEN_PROMPT_DECLINED = "lock_screen_prompt_declined"
        private const val KEY_AUTO_UPDATE_CHECK = "auto_update_check"
        private const val KEY_USER_AGENT = "user_agent"
        private const val KEY_ADAPTIVE_RATE_CONTROL = "adaptive_rate_control"
        private const val KEY_ECHO_CANCELLATION = "echo_cancellation"
        private const val KEY_IPV6 = "ipv6"
    }
}
