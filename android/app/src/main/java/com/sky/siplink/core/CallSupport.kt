package com.sky.siplink.core

import com.sky.siplink.core.logic.FeatureDial
import com.sky.siplink.core.logic.IntercomHeaders
import com.sky.siplink.core.model.AudioRoute
import com.sky.siplink.core.model.CallPhase
import com.sky.siplink.core.model.FeatureCodes
import com.sky.siplink.core.model.MemberState
import com.sky.siplink.core.model.SideCall
import org.linphone.core.Account
import org.linphone.core.Address
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

    /** The line of a call as the call screen names it: `user@domain`, empty without one. */
    fun lineOf(account: Account?): String {
        val identity = account?.params?.identityAddress ?: return ""
        return "${identity.username.orEmpty()}@${identity.domain.orEmpty()}"
    }

    /** Where the member of a conference room whose call this is stands. */
    fun memberStateOf(call: Call): MemberState = when (call.state) {
        Call.State.OutgoingRinging, Call.State.OutgoingEarlyMedia -> MemberState.RINGING
        Call.State.Idle, Call.State.OutgoingInit, Call.State.OutgoingProgress, Call.State.IncomingReceived,
        Call.State.IncomingEarlyMedia, Call.State.PushIncomingReceived,
        -> MemberState.CALLING
        Call.State.PausedByRemote -> MemberState.HELD
        Call.State.End, Call.State.Error, Call.State.Released -> MemberState.LEFT
        else -> MemberState.CONNECTED
    }

    /** Whether the caller asks for [call] to be answered by itself: an intercom call. */
    fun isIntercom(call: Call): Boolean {
        if (call.askedToAutoanswer()) return true
        val remote = call.remoteParams ?: return false
        return IntercomHeaders.requestsAutoAnswer(
            callInfo = remote.getCustomHeader(IntercomHeaders.CALL_INFO),
            alertInfo = remote.getCustomHeader(IntercomHeaders.ALERT_INFO),
            answerMode = remote.getCustomHeader(IntercomHeaders.ANSWER_MODE),
        )
    }

    /**
     * Whether [call] is an intercom or paging call: one the caller asks to be answered by itself
     * ([isIntercom]), or one out to the intercom feature code of [codes] ("*801005").
     */
    fun isIntercom(call: Call, codes: FeatureCodes): Boolean =
        isIntercom(call) ||
            (call.dir == Call.Dir.Outgoing && FeatureDial.parse(call.remoteAddress.username.orEmpty(), codes)?.first == FeatureDial.Action.INTERCOM)

    /**
     * Who is on the other side. Once the call is established the PBX may tell us that the party
     * changed (transfer, pickup, ...) through P-Asserted-Identity; that identity then wins.
     */
    fun party(call: Call): SideCall {
        val asserted = call.remoteParams?.getCustomHeader(ASSERTED_IDENTITY)
            ?.takeIf { it.isNotBlank() && phaseOf(call) != CallPhase.INCOMING }
            ?.let { Factory.instance().createAddress(it) }
            ?.takeUnless { it.username.isNullOrEmpty() }
        return side(asserted ?: call.remoteAddress)
    }

    /** [address] as a party: by the name it carries, else by its number. */
    fun side(address: Address): SideCall {
        val plain = plain(address)
        return SideCall(name = address.displayName?.takeIf { it.isNotBlank() } ?: address.username ?: plain, address = plain)
    }

    /**
     * `user@host[:port]` the way a person writes it: without scheme and parameters, and with the
     * characters a URI has to escape (`#`, a space) as they are.
     */
    fun plain(address: Address): String {
        val domain = address.domain.orEmpty()
        val host = (if (':' in domain) "[$domain]" else domain) + (address.port.takeIf { it > 0 }?.let { ":$it" } ?: "")
        val user = address.username
        return if (user.isNullOrEmpty()) host else "$user@$host"
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
        val (output, input) = devicesFor(core, route) ?: return false
        call.outputAudioDevice = output
        input?.let { call.inputAudioDevice = it }
        return true
    }

    /**
     * The device that plays [route], and the microphone that goes with it (null where there is
     * none); null when the route has no device.
     */
    fun devicesFor(core: Core, route: AudioRoute): Pair<AudioDevice, AudioDevice?>? {
        val types = when (route) {
            AudioRoute.EARPIECE -> listOf(AudioDevice.Type.Earpiece)
            AudioRoute.SPEAKER -> listOf(AudioDevice.Type.Speaker)
            AudioRoute.BLUETOOTH -> listOf(AudioDevice.Type.Bluetooth, AudioDevice.Type.HearingAid)
            AudioRoute.HEADSET -> listOf(AudioDevice.Type.Headset, AudioDevice.Type.Headphones, AudioDevice.Type.GenericUsb)
        }
        val devices = core.audioDevices
        val output = devices.firstOrNull {
            it.type in types && it.hasCapability(AudioDevice.Capabilities.CapabilityPlay)
        } ?: return null
        // Bluetooth and wired headsets bring their own microphone, otherwise use the built-in one.
        val inputTypes = if (route == AudioRoute.BLUETOOTH || route == AudioRoute.HEADSET) {
            types
        } else {
            listOf(AudioDevice.Type.Microphone)
        }
        val input = devices.firstOrNull {
            it.type in inputTypes && it.hasCapability(AudioDevice.Capabilities.CapabilityRecord)
        }
        return output to input
    }

    /** What the call screen says while no sound comes in, and after the SIP core ended a call for it. */
    const val NO_SOUND = "未收到对方声音"

    fun endReason(call: Call, callState: Call.State, wasConnected: Boolean, message: String): String {
        if (wasConnected && CallMediaWatch.mediaLost(call)) return NO_SOUND
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
            Reason.Forbidden, Reason.Unauthorized -> "无权呼叫"
            Reason.IOError -> "网络连接失败"
            Reason.NotAcceptable, Reason.UnsupportedContent -> "媒体协商失败"
            else -> error.phrase?.takeIf { it.isNotBlank() } ?: message.ifBlank { "呼叫失败" }
        }
    }
}
