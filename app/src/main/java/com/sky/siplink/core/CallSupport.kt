package com.sky.siplink.core

import com.sky.siplink.core.logic.Formatters
import com.sky.siplink.core.model.AudioRoute
import com.sky.siplink.core.model.CallPhase
import com.sky.siplink.core.model.SideCall
import org.linphone.core.AudioDevice
import org.linphone.core.Call
import org.linphone.core.Core
import org.linphone.core.Factory
import org.linphone.core.Reason

/** Stateless helpers of [CallManager]: audio routing, call state mapping and wording. */
internal object CallSupport {
    private const val ASSERTED_IDENTITY = "P-Asserted-Identity"

    fun isRinging(call: Call): Boolean =
        call.state == Call.State.IncomingReceived || call.state == Call.State.IncomingEarlyMedia

    fun isEnded(call: Call): Boolean =
        call.state == Call.State.End || call.state == Call.State.Error || call.state == Call.State.Released

    fun phaseOf(call: Call): CallPhase = when (call.state) {
        Call.State.IncomingReceived, Call.State.IncomingEarlyMedia, Call.State.PushIncomingReceived -> CallPhase.INCOMING
        Call.State.Idle, Call.State.OutgoingInit, Call.State.OutgoingProgress,
        Call.State.OutgoingRinging, Call.State.OutgoingEarlyMedia,
        -> CallPhase.OUTGOING
        Call.State.Pausing, Call.State.Paused, Call.State.PausedByRemote -> CallPhase.HELD
        Call.State.End, Call.State.Error, Call.State.Released -> CallPhase.ENDED
        else -> CallPhase.CONNECTED
    }

    /**
     * Who is on the other side. Once the call is established the PBX may tell us that the party
     * changed (transfer, pickup, ...) through P-Asserted-Identity; that identity then wins.
     */
    fun party(call: Call): SideCall {
        val remote = call.remoteAddress
        var name = remote.displayName?.takeIf { it.isNotBlank() }
        var number = remote.username
        var address = Formatters.shortAddress(remote.asStringUriOnly())

        val asserted = call.remoteParams?.getCustomHeader(ASSERTED_IDENTITY)
            ?.takeIf { it.isNotBlank() && phaseOf(call) != CallPhase.INCOMING }
            ?.let { Factory.instance().createAddress(it) }
        if (asserted != null && !asserted.username.isNullOrEmpty()) {
            name = asserted.displayName?.takeIf { it.isNotBlank() }
            number = asserted.username
            address = Formatters.shortAddress(asserted.asStringUriOnly())
        }
        return SideCall(name = name ?: number ?: address, address = address)
    }

    fun routeOf(type: AudioDevice.Type?): AudioRoute? = when (type) {
        AudioDevice.Type.Earpiece -> AudioRoute.EARPIECE
        AudioDevice.Type.Speaker -> AudioRoute.SPEAKER
        AudioDevice.Type.Bluetooth, AudioDevice.Type.HearingAid -> AudioRoute.BLUETOOTH
        AudioDevice.Type.Headset, AudioDevice.Type.Headphones, AudioDevice.Type.GenericUsb -> AudioRoute.HEADSET
        else -> null
    }

    fun availableRoutes(core: Core): List<AudioRoute> {
        val routes = core.audioDevices
            .filter { it.hasCapability(AudioDevice.Capabilities.CapabilityPlay) }
            .mapNotNull { routeOf(it.type) }
            .distinct()
        return AudioRoute.entries.filter { it in routes }
    }

    /** Routes playback (and the matching microphone) of [call]; false when no such device exists. */
    fun applyRoute(core: Core, call: Call, route: AudioRoute): Boolean {
        val types = when (route) {
            AudioRoute.EARPIECE -> listOf(AudioDevice.Type.Earpiece)
            AudioRoute.SPEAKER -> listOf(AudioDevice.Type.Speaker)
            AudioRoute.BLUETOOTH -> listOf(AudioDevice.Type.Bluetooth, AudioDevice.Type.HearingAid)
            AudioRoute.HEADSET -> listOf(AudioDevice.Type.Headset, AudioDevice.Type.Headphones, AudioDevice.Type.GenericUsb)
        }
        val devices = core.audioDevices
        val output = devices.firstOrNull {
            it.type in types && it.hasCapability(AudioDevice.Capabilities.CapabilityPlay)
        } ?: return false
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
        return true
    }

    fun endReason(call: Call, callState: Call.State, wasConnected: Boolean, message: String): String {
        if (callState == Call.State.End) {
            return when {
                wasConnected -> "通话结束"
                call.dir == Call.Dir.Incoming -> "已挂断"
                else -> "已取消"
            }
        }
        val error = call.errorInfo
        return when (error.reason) {
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
            else -> error.phrase?.takeIf { it.isNotBlank() } ?: message.ifBlank { "呼叫失败" }
        }
    }
}
