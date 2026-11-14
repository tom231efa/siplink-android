package com.sky.siplink.core

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.sky.siplink.core.logic.Formatters
import com.sky.siplink.core.logic.IntercomHeaders
import com.sky.siplink.core.model.AudioRoute
import com.sky.siplink.core.model.CallInfo
import com.sky.siplink.core.model.CallPhase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.linphone.core.AudioDevice
import org.linphone.core.Call
import org.linphone.core.Core
import org.linphone.core.Reason

/**
 * Single-call controller: places / answers calls, tracks the current call for the UI and
 * drives audio routing. Must be used from the thread that owns the core (the main thread).
 */
class CallManager(
    private val core: Core,
    private val settings: SettingsStore,
    private val accounts: AccountManager,
) {
    /** Hooks implemented by the app layer (notifications, call screen, foreground service). */
    interface Host {
        /** True when one of the app's activities is visible, i.e. the microphone may be used. */
        val isAppVisible: Boolean
        fun showIncomingCall(info: CallInfo)
        fun dismissIncomingCall()
        fun onCallActivityChanged()
    }

    var host: Host? = null

    private val state = MutableStateFlow<CallInfo?>(null)
    val call: StateFlow<CallInfo?> = state.asStateFlow()

    /** True while a call is being set up or is established (not merely ringing in). */
    val hasOngoingCall: Boolean
        get() = state.value?.phase.let {
            it == CallPhase.OUTGOING || it == CallPhase.CONNECTED || it == CallPhase.HELD
        }

    private val handler = Handler(Looper.getMainLooper())
    private var current: Call? = null
    private var intercom = false
    private var connectedAt: Long? = null
    private var autoAnswerPending = false
    private var outgoingIntercom = false
    private var generation = 0

    /** @return a user readable error, or null when the call was started */
    fun dial(target: String, accountId: String?, asIntercom: Boolean): String? {
        val cleaned = target.trim()
        if (cleaned.isEmpty()) return "请输入号码或 SIP 地址"
        if (core.callsNb > 0) return "已有通话正在进行"
        val account = accountId?.let(accounts::find) ?: core.defaultAccount ?: return "请先添加 SIP 账号"
        val address = account.normalizeSipUri(cleaned.filterNot { it.isWhitespace() })
            ?: return "号码或地址无效"
        val params = core.createCallParams(null) ?: return "无法创建呼叫"
        params.account = account
        params.isVideoEnabled = false
        if (asIntercom) {
            val domain = account.params.identityAddress?.domain.orEmpty()
            IntercomHeaders.outgoing(settings.current.intercomHeaderMode, domain).forEach {
                params.addCustomHeader(it.name, it.value)
            }
        }
        outgoingIntercom = asIntercom
        core.inviteAddressWithParams(address, params) ?: return "呼叫失败，请检查网络和账号状态"
        return null
    }

    fun answer() {
        val call = current ?: return
        autoAnswerPending = false
        if (call.state == Call.State.IncomingReceived || call.state == Call.State.IncomingEarlyMedia) {
            call.accept()
        }
    }

    fun decline() {
        autoAnswerPending = false
        current?.decline(Reason.Declined)
    }

    fun hangUp() {
        val call = current ?: return
        if (call.state == Call.State.IncomingReceived || call.state == Call.State.IncomingEarlyMedia) {
            call.decline(Reason.Declined)
        } else {
            call.terminate()
        }
    }

    fun toggleMute() {
        val call = current ?: return
        call.microphoneMuted = !call.microphoneMuted
        publish()
    }

    fun toggleHold() {
        val call = current ?: return
        when (call.state) {
            Call.State.Paused, Call.State.Pausing -> call.resume()
            Call.State.StreamsRunning -> call.pause()
            else -> Unit
        }
    }

    fun sendDtmf(digit: Char) {
        if (!Formatters.isDtmf(digit)) return
        current?.sendDtmf(digit)
    }

    fun setRoute(route: AudioRoute) {
        val call = current ?: return
        val types = typesOf(route)
        val devices = core.audioDevices
        val output = devices.firstOrNull {
            it.type in types && it.hasCapability(AudioDevice.Capabilities.CapabilityPlay)
        } ?: return
        call.outputAudioDevice = output
        // Bluetooth and wired headsets bring their own microphone, otherwise use the built-in one.
        val inputTypes = if (route == AudioRoute.BLUETOOTH || route == AudioRoute.HEADSET) {
            types
        } else {
            listOf(AudioDevice.Type.Microphone)
        }
        devices.firstOrNull {
            it.type in inputTypes && it.hasCapability(AudioDevice.Capabilities.CapabilityRecord)
        }?.let { call.inputAudioDevice = it }
        publish()
    }

    /** Called when the call screen becomes visible: completes a pending intercom auto answer. */
    fun onCallUiVisible() {
        if (autoAnswerPending) answer()
    }

    fun onAudioChanged() {
        if (current != null) publish()
    }

    fun onCallStateChanged(call: Call, callState: Call.State?, message: String) {
        when (callState) {
            Call.State.IncomingReceived, Call.State.IncomingEarlyMedia -> {
                if (current == null) begin(call, isIntercom = detectIntercom(call))
                if (call != current) {
                    // Single-call client: anything arriving on top of the current call is busy.
                    call.decline(Reason.Busy)
                    return
                }
                val info = publish(CallPhase.INCOMING) ?: return
                if (callState == Call.State.IncomingReceived) onIncoming(info)
            }

            Call.State.OutgoingInit, Call.State.OutgoingProgress,
            Call.State.OutgoingRinging, Call.State.OutgoingEarlyMedia,
            -> {
                if (current == null) begin(call, isIntercom = outgoingIntercom)
                if (call == current) publish(CallPhase.OUTGOING)
            }

            Call.State.Connected, Call.State.StreamsRunning, Call.State.Resuming,
            Call.State.Updating, Call.State.UpdatedByRemote,
            -> {
                if (call != current) return
                val first = connectedAt == null
                if (first) connectedAt = SystemClock.elapsedRealtime()
                host?.dismissIncomingCall()
                if (callState == Call.State.StreamsRunning && intercom && settings.current.intercomSpeaker &&
                    !speakerApplied
                ) {
                    speakerApplied = true
                    setRoute(AudioRoute.SPEAKER)
                }
                publish(CallPhase.CONNECTED)
            }

            Call.State.Pausing, Call.State.Paused, Call.State.PausedByRemote -> {
                if (call == current) publish(CallPhase.HELD)
            }

            Call.State.Error, Call.State.End -> {
                if (call != current) return
                host?.dismissIncomingCall()
                publish(CallPhase.ENDED, describeEnd(call, callState, message))
            }

            Call.State.Released -> {
                if (call != current) return
                host?.dismissIncomingCall()
                val ended = state.value?.takeIf { it.phase == CallPhase.ENDED }
                    ?: publish(CallPhase.ENDED, "通话结束")
                current = null
                state.value = ended
                host?.onCallActivityChanged()
                // Leave the "call ended" state on screen for a moment before clearing it.
                val token = ++generation
                handler.postDelayed({ if (token == generation && current == null) state.value = null }, END_LINGER_MS)
            }

            else -> Unit
        }
    }

    private var speakerApplied = false

    private fun begin(call: Call, isIntercom: Boolean) {
        generation++
        current = call
        intercom = isIntercom
        connectedAt = null
        speakerApplied = false
        autoAnswerPending = false
        outgoingIntercom = false
    }

    private fun onIncoming(info: CallInfo) {
        val autoAnswer = intercom && settings.current.intercomAutoAnswer
        val visible = host?.isAppVisible == true
        if (autoAnswer && visible) {
            // The microphone is only usable while the app is visible, so answer right away.
            handler.postDelayed({ answer() }, AUTO_ANSWER_DELAY_MS)
            host?.showIncomingCall(info)
        } else {
            // Not visible: Android would mute the microphone. Bring up the call screen first;
            // the answer happens as soon as that screen is shown (see onCallUiVisible).
            autoAnswerPending = autoAnswer
            host?.showIncomingCall(info)
        }
    }

    private fun detectIntercom(call: Call): Boolean {
        if (call.askedToAutoanswer()) return true
        val remote = call.remoteParams ?: return false
        return IntercomHeaders.requestsAutoAnswer(
            callInfo = remote.getCustomHeader(IntercomHeaders.CALL_INFO),
            alertInfo = remote.getCustomHeader(IntercomHeaders.ALERT_INFO),
            answerMode = remote.getCustomHeader(IntercomHeaders.ANSWER_MODE),
        )
    }

    private fun publish(phase: CallPhase? = null, endReason: String? = null): CallInfo? {
        val call = current ?: return null
        val previous = state.value
        val effectivePhase = phase ?: previous?.phase ?: return null
        val remote = call.remoteAddress
        val number = remote.username ?: Formatters.shortAddress(remote.asStringUriOnly())
        val account = call.params.account?.params?.identityAddress
        val info = CallInfo(
            phase = effectivePhase,
            remoteName = remote.displayName?.takeIf { it.isNotBlank() } ?: number,
            remoteAddress = Formatters.shortAddress(remote.asStringUriOnly()),
            accountLabel = account?.let { "${it.username.orEmpty()}@${it.domain.orEmpty()}" }.orEmpty(),
            incoming = call.dir == Call.Dir.Incoming,
            intercom = intercom,
            muted = call.microphoneMuted,
            route = routeOf(call.outputAudioDevice?.type) ?: previous?.route ?: AudioRoute.EARPIECE,
            availableRoutes = availableRoutes(),
            connectedAt = connectedAt,
            endReason = endReason ?: previous?.endReason,
        )
        val activityChanged = previous?.phase != info.phase
        state.value = info
        if (activityChanged) host?.onCallActivityChanged()
        return info
    }

    private fun availableRoutes(): List<AudioRoute> {
        val routes = core.audioDevices
            .filter { it.hasCapability(AudioDevice.Capabilities.CapabilityPlay) }
            .mapNotNull { routeOf(it.type) }
            .distinct()
        return AudioRoute.entries.filter { it in routes }
    }

    private fun routeOf(type: AudioDevice.Type?): AudioRoute? = when (type) {
        AudioDevice.Type.Earpiece -> AudioRoute.EARPIECE
        AudioDevice.Type.Speaker -> AudioRoute.SPEAKER
        AudioDevice.Type.Bluetooth, AudioDevice.Type.HearingAid -> AudioRoute.BLUETOOTH
        AudioDevice.Type.Headset, AudioDevice.Type.Headphones, AudioDevice.Type.GenericUsb -> AudioRoute.HEADSET
        else -> null
    }

    private fun typesOf(route: AudioRoute): List<AudioDevice.Type> = when (route) {
        AudioRoute.EARPIECE -> listOf(AudioDevice.Type.Earpiece)
        AudioRoute.SPEAKER -> listOf(AudioDevice.Type.Speaker)
        AudioRoute.BLUETOOTH -> listOf(AudioDevice.Type.Bluetooth, AudioDevice.Type.HearingAid)
        AudioRoute.HEADSET -> listOf(AudioDevice.Type.Headset, AudioDevice.Type.Headphones, AudioDevice.Type.GenericUsb)
    }

    private fun describeEnd(call: Call, callState: Call.State, message: String): String {
        if (callState == Call.State.End) {
            return if (connectedAt != null) "通话结束" else if (call.dir == Call.Dir.Incoming) "已挂断" else "已取消"
        }
        return when (call.errorInfo.reason) {
            Reason.Busy -> "对方正忙"
            Reason.Declined -> "对方已拒绝"
            Reason.NotFound -> "号码不存在"
            Reason.NotAnswered -> "无人接听"
            Reason.NoResponse -> "对方无响应"
            Reason.DoNotDisturb -> "对方已开启免打扰"
            Reason.TemporarilyUnavailable -> "对方暂时无法接通"
            Reason.Forbidden, Reason.Unauthorized -> "认证失败，无权呼叫"
            Reason.IOError -> "网络连接失败"
            Reason.NotAcceptable, Reason.UnsupportedContent -> "媒体协商失败"
            else -> call.errorInfo.phrase?.takeIf { it.isNotBlank() } ?: message.ifBlank { "呼叫失败" }
        }
    }

    private companion object {
        const val END_LINGER_MS = 1400L
        const val AUTO_ANSWER_DELAY_MS = 400L
    }
}
