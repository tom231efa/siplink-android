package com.sky.siplink.core

import com.sky.siplink.R
import com.sky.siplink.core.logic.FeatureDial
import com.sky.siplink.core.logic.IntercomHeaders
import com.sky.siplink.core.model.AudioRoute
import com.sky.siplink.core.model.CallPhase
import com.sky.siplink.core.model.FeatureCodes
import com.sky.siplink.core.model.MemberState
import com.sky.siplink.core.model.Phrase
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
     * Whom the message last received on [call] asserts to be on the other side (P-Asserted-Identity):
     * the PBX tells us so, and that the party changed (transfer, pickup, ...). Null when it asserts
     * no one with a number. [CallDescriber] keeps it beyond that message.
     */
    fun asserted(call: Call): SideCall? = call.remoteParams?.getCustomHeader(ASSERTED_IDENTITY)
        ?.takeIf { it.isNotBlank() }
        ?.let { Factory.instance().createAddress(it) }
        ?.takeUnless { it.username.isNullOrEmpty() }
        ?.let(::side)

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

    /** Why a call ended that the SIP core gave up because no sound arrived any more ([CallMediaWatch.mediaLost]). */
    val NO_SOUND: Phrase = Phrase.of(R.string.call_no_sound)

    /** Why a call out ended that its server gave no answer, and that could not go over the other server. */
    val SERVER_SILENT: Phrase = Phrase.of(R.string.call_server_silent)

    /** Why a call ended; a reason the app has no words for is the server's own, as it sent it. */
    fun endReason(call: Call, callState: Call.State, wasConnected: Boolean, message: String): Phrase {
        if (wasConnected && CallMediaWatch.mediaLost(call)) return NO_SOUND
        if (callState == Call.State.End) {
            return Phrase.of(
                when {
                    wasConnected -> R.string.call_ended
                    call.dir == Call.Dir.Incoming -> R.string.call_hung_up
                    else -> R.string.call_cancelled
                },
            )
        }
        val error = call.errorInfo
        val known = when (error.reason) {
            Reason.Busy -> R.string.call_end_busy
            Reason.Declined -> R.string.call_end_declined
            Reason.NotFound -> R.string.call_end_not_found
            Reason.NotAnswered -> R.string.call_end_not_answered
            Reason.NoResponse -> R.string.call_end_no_response
            Reason.DoNotDisturb -> R.string.call_end_dnd
            Reason.TemporarilyUnavailable -> R.string.call_end_unavailable
            Reason.Forbidden, Reason.Unauthorized -> R.string.call_end_forbidden
            Reason.IOError -> R.string.call_end_network
            Reason.NotAcceptable, Reason.UnsupportedContent -> R.string.call_end_media
            else -> null
        }
        if (known != null) return Phrase.of(known)
        val said = error.phrase?.takeIf { it.isNotBlank() } ?: message.takeIf { it.isNotBlank() }
        return said?.let(Phrase::plain) ?: Phrase.of(R.string.call_failed)
    }
}
