package com.sky.siplink.core

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.sky.siplink.core.CallSupport.isEnded
import com.sky.siplink.core.CallSupport.isRinging
import com.sky.siplink.core.CallSupport.phaseOf
import com.sky.siplink.core.logic.DialTarget
import com.sky.siplink.core.logic.FeatureDial
import com.sky.siplink.core.logic.Formatters
import com.sky.siplink.core.logic.IncomingPolicy
import com.sky.siplink.core.logic.IntercomHeaders
import com.sky.siplink.core.logic.NameLookup
import com.sky.siplink.core.logic.PartyName
import com.sky.siplink.core.logic.VideoRules
import com.sky.siplink.core.model.AudioRoute
import com.sky.siplink.core.model.CallInfo
import com.sky.siplink.core.model.CallPhase
import com.sky.siplink.core.model.SideCall
import com.sky.siplink.core.model.SupervisionMode
import com.sky.siplink.core.model.TransferStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.linphone.core.Account
import org.linphone.core.Call
import org.linphone.core.Conference
import org.linphone.core.Core
import org.linphone.core.Factory
import org.linphone.core.Reason

/**
 * Call controller. The UI follows one "current" call; a second call may sit next to it, either
 * on hold (consultation for an attended transfer, call swapped away) or ringing (call waiting).
 *
 * Besides the basics (dial, answer, hold, mute, DTMF, routing) it implements the PBX-style
 * features: blind and attended transfer, parking, three-way merge, do not disturb, forwarding,
 * and the bookkeeping needed when the PBX rearranges our calls (REFER, replaced or force-released
 * calls). Must be used from the thread that owns the core (the main thread).
 */
class CallManager(
    private val core: Core,
    private val settings: SettingsStore,
    private val accounts: AccountManager,
    private val names: NameResolver,
) {
    /** Hooks implemented by the app layer (notifications, call screen, foreground service). */
    interface Host {
        /** True when one of the app's activities is visible, i.e. the microphone may be used. */
        val isAppVisible: Boolean
        fun showIncomingCall(info: CallInfo)

        /** The call that is being announced got a better name (found in the directory). */
        fun updateIncomingCall(info: CallInfo)
        fun dismissIncomingCall()
        fun onCallActivityChanged()

        /** True when the app holds the camera permission. */
        val canUseCamera: Boolean
    }

    var host: Host? = null

    private val state = MutableStateFlow<CallInfo?>(null)
    val call: StateFlow<CallInfo?> = state.asStateFlow()

    init {
        names.addListener(::onNamesFound)
    }

    /** True while a call is being set up or is established (not merely ringing in). */
    val hasOngoingCall: Boolean
        get() = state.value?.phase.let {
            it == CallPhase.OUTGOING || it == CallPhase.CONNECTED || it == CallPhase.HELD
        }

    /** Facts about a call that the SIP core does not keep for us. */
    private class Meta(
        var intercom: Boolean = false,
        var supervision: SupervisionMode? = null,
        var connectedAt: Long? = null,
        var speakerApplied: Boolean = false,
        var endReason: String? = null,
        /** The call had video the last time it was looked at (sound moves to the speaker as video starts). */
        var hadVideo: Boolean = false,
    )

    /** What the next outgoing call started by [invite] is meant to be. */
    private class Pending(val supervision: SupervisionMode?, val consultation: Boolean)

    private val handler = Handler(Looper.getMainLooper())
    private val metas = HashMap<Long, Meta>()

    private var current: Call? = null
    private var other: Call? = null

    /** The call in which the PBX asked us (REFER) to call someone else; [current] is that new call. */
    private var referredFrom: Call? = null
    private var conference: Conference? = null
    private var pending: Pending? = null
    private var consulting = false
    private var attendedTransfer = false
    private var transferStatus: TransferStatus? = null
    private var parking = false
    private var autoAnswerPending = false
    private var generation = 0

    private val video = VideoController(core, settings, { host?.canUseCamera == true }) { publish() }
    private val videoAllowed: Boolean get() = settings.current.videoCalls

    // ---------------------------------------------------------------- placing calls

    /**
     * @param video a video call: the camera is sent if the app may use it, else the video is only received
     * @return a user readable error, or null when the call was started
     */
    fun dial(target: String, accountId: String?, supervision: SupervisionMode? = null, video: Boolean = false): String? {
        if (live(current) != null || core.calls.any { !isEnded(it) }) return "已有通话正在进行"
        val account = accountId?.let(accounts::find) ?: core.defaultAccount
        return invite(target, account, Pending(supervision, consultation = false), video && videoAllowed)
    }

    /** Puts the current call on hold and calls [target]: first step of an attended transfer. */
    fun startConsultation(target: String): String? {
        val active = live(current) ?: return "当前无通话"
        if (other != null) return "已有两路通话"
        val phase = phaseOf(active)
        if (phase != CallPhase.CONNECTED && phase != CallPhase.HELD) return "通话接通后方可发起咨询"
        return invite(target, active.params.account ?: core.defaultAccount, Pending(null, consultation = true))
    }

    private fun invite(target: String, account: Account?, kind: Pending, video: Boolean = false): String? {
        val cleaned = DialTarget.of(target)
        if (cleaned.isEmpty()) return "请输入号码"
        if (account == null) return "请先添加 SIP 账号"
        // A line that is switched off stays out of use, even where the PBX would take its calls.
        if (!accounts.isSwitchedOn(account)) return "线路已停用"
        val address = account.normalizeSipUri(cleaned) ?: return "号码或地址无效"
        val params = core.createCallParams(null) ?: return "无法创建呼叫"
        params.account = account
        this.video.prepare(params, video)
        // The core reports OutgoingInit from inside the invite call; `pending` tells it apart there.
        pending = kind
        val created = core.inviteAddressWithParams(address, params)
        pending = null
        return if (created == null) "呼叫失败" else null
    }

    // ---------------------------------------------------------------- answering / ending

    /**
     * Answers the ringing call: the waiting one if there is one, else the current one.
     *
     * @param video take the video the caller offers (the camera is sent if the app may use
     *   it); without, the call is answered audio-only
     */
    fun answer(video: Boolean = false) {
        autoAnswerPending = false
        val waiting = other?.takeIf(::isRinging)
        if (waiting != null) {
            // The core puts the running call on hold by itself when another one is accepted.
            other = current
            current = waiting
            accept(waiting, video)
            publish()
            return
        }
        current?.takeIf(::isRinging)?.let { accept(it, video) }
    }

    private fun accept(call: Call, video: Boolean) {
        val params = core.createCallParams(call)
        if (params == null) {
            call.accept()
            return
        }
        this.video.prepare(params, video && CallVideoSupport.offered(call))
        call.acceptWithParams(params)
    }

    /** Declines the ringing call: the waiting one if there is one, else the current one. */
    fun decline() {
        autoAnswerPending = false
        val waiting = other?.takeIf(::isRinging)
        if (waiting != null) {
            waiting.decline(Reason.Busy)
            return
        }
        current?.takeIf(::isRinging)?.decline(Reason.Declined)
    }

    fun hangUp() {
        if (conference != null) {
            core.terminateAllCalls()
            return
        }
        val call = current ?: return
        if (isRinging(call)) call.decline(Reason.Declined) else call.terminate()
    }

    // ---------------------------------------------------------------- in-call controls

    fun toggleMute() {
        val call = current ?: return
        setMuted(call, !isMuted(call))
        publish()
    }

    /** In a three-way call the microphone belongs to the conference, not to one of its calls. */
    private fun setMuted(call: Call, muted: Boolean) {
        val conference = conference
        if (conference != null) conference.microphoneMuted = muted else call.microphoneMuted = muted
    }

    fun toggleHold() {
        if (conference != null) return
        val call = current ?: return
        when (call.state) {
            Call.State.Paused, Call.State.Pausing -> call.resume()
            Call.State.StreamsRunning -> call.pause()
            else -> Unit
        }
    }

    fun sendDtmf(digit: Char) {
        if (Formatters.isDtmf(digit)) current?.sendDtmf(digit)
    }

    /** Sends a string of digits one after the other (a DTMF shortcut key). */
    fun sendDtmfs(digits: String): String? {
        val call = live(current)?.takeIf { it.state == Call.State.StreamsRunning } ?: return "仅可在通话中发送按键"
        call.sendDtmfs(digits.filter(Formatters::isDtmf))
        return null
    }

    fun setRoute(route: AudioRoute) {
        val call = current ?: return
        if (CallSupport.applyRoute(core, call, route)) publish()
    }

    /** Switches a supervisor call between listen / whisper / barge (DTMF understood by the PBX). */
    fun setSupervisionMode(mode: SupervisionMode) {
        val call = live(current) ?: return
        val meta = metaOf(call)
        if (meta.supervision == null) return
        call.sendDtmf(mode.dtmf)
        meta.supervision = mode
        publish()
    }

    /** Swaps the held call and the current one. */
    fun swap() {
        if (conference != null) return
        val held = live(other)?.takeIf { !isRinging(it) } ?: return
        other = current
        current = held
        consulting = false
        // Resuming preempts the audio: the core holds the other call by itself.
        held.resume()
        publish()
    }

    // ---------------------------------------------------------------- video

    /** Adds video to the current call, or ends it (re-INVITE). */
    fun toggleVideo(): String? = video.toggleVideo(live(current), conference != null)

    /** Stops or starts sending the own camera; the picture of the other side keeps coming. */
    fun toggleCamera(): String? = video.toggleCamera(live(current))

    /** Front and back camera, in turn. */
    fun switchCamera() = video.switchCamera(live(current))

    /** The user's answer to the other side's request to add video (see [CallInfo.videoOffer]). */
    fun answerVideoRequest(accept: Boolean) = video.answerRequest(accept)

    /** The call screen came to the front or left it: the camera is only used while it is in front. */
    fun onCallScreenShown(shown: Boolean) = video.onCallScreenShown(shown)

    /**
     * The statistics of [call] came in, about once a second while it runs: the pictures of a
     * video call can have changed their size since (the other side turned its phone round, say).
     */
    fun onCallStatsUpdated(call: Call) {
        if (!same(call, current)) return
        val shown = state.value?.video ?: return
        if (CallVideoSupport.stateOf(call) != shown) publish()
    }

    /** Video calls were switched on or off in the settings; off, running video ends. */
    fun onVideoAllowedChanged() = video.onAllowedChanged()

    // ---------------------------------------------------------------- transfer / park / merge

    /** Blind transfer: the PBX connects the other party to [target] and releases us. */
    fun blindTransfer(target: String): String? {
        val call = live(current) ?: return "当前无通话"
        val phase = phaseOf(call)
        if (phase != CallPhase.CONNECTED && phase != CallPhase.HELD) return "通话接通后方可转接"
        val cleaned = DialTarget.of(target)
        if (cleaned.isEmpty()) return "请输入转接号码"
        val address = (call.params.account ?: core.defaultAccount)?.normalizeSipUri(cleaned)
            ?: return "号码或地址无效"
        attendedTransfer = false
        parking = false
        transferStatus = TransferStatus.IN_PROGRESS
        if (call.transferTo(address) != 0) {
            transferStatus = TransferStatus.FAILED
            publish()
            return "转接请求发送失败"
        }
        publish()
        return null
    }

    /** Parks the current call: a blind transfer to the PBX parking extension. */
    fun park(): String? {
        val extension = settings.current.features.park.trim()
        if (extension.isEmpty()) return FeatureDial.missingCode("驻留")
        val error = blindTransfer(extension)
        parking = error == null
        return error
    }

    /** Second step of an attended transfer: connects the held party with the consulted one. */
    fun completeTransfer(): String? {
        val consulted = live(current)
        val held = live(other)
        if (!consulting || consulted == null || held == null) return "无待完成的转接"
        if (phaseOf(consulted) != CallPhase.CONNECTED) return "对方接听后方可转接"
        attendedTransfer = true
        transferStatus = TransferStatus.IN_PROGRESS
        if (held.transferToAnother(consulted) != 0) {
            attendedTransfer = false
            transferStatus = TransferStatus.FAILED
            publish()
            return "转接请求发送失败"
        }
        publish()
        return null
    }

    /** Merges the current and the held call into a local three-way conference. */
    fun merge(): String? {
        val first = live(current)
        val second = live(other)?.takeIf { !isRinging(it) }
        if (first == null || second == null) return "需要两路已接通的通话"
        val params = core.createConferenceParams(null)
        params.isVideoEnabled = false
        val created = core.createConferenceWithParams(params) ?: return "无法创建三方通话"
        // Muting the call the user is talking in carries over to the three-way call.
        val muted = isMuted(first)
        created.addParticipants(arrayOf(first, second))
        conference = created
        consulting = false
        setMuted(first, muted)
        publish()
        return null
    }

    // ---------------------------------------------------------------- core events

    /** Called when the call screen becomes visible: completes a pending intercom auto answer. */
    fun onCallUiVisible() {
        if (autoAnswerPending) answer()
    }

    fun onAudioChanged() {
        if (current != null) publish()
    }

    fun onTransferStateChanged(call: Call, transferState: Call.State?) {
        when (transferState) {
            Call.State.OutgoingInit, Call.State.OutgoingProgress,
            Call.State.OutgoingRinging, Call.State.OutgoingEarlyMedia,
            -> transferStatus = TransferStatus.IN_PROGRESS

            Call.State.Connected, Call.State.StreamsRunning -> {
                transferStatus = TransferStatus.SUCCEEDED
                when {
                    attendedTransfer -> {
                        live(current)?.terminate()
                        live(other)?.terminate()
                    }
                    // The PBX announces the parking slot on this call and then hangs it up itself;
                    // the timer only covers a PBX that does neither.
                    parking -> handler.postDelayed({ if (!isEnded(call)) call.terminate() }, PARK_ANNOUNCE_TIMEOUT_MS)
                    !isEnded(call) -> call.terminate()
                }
                attendedTransfer = false
            }

            Call.State.Error, Call.State.End -> {
                transferStatus = TransferStatus.FAILED
                attendedTransfer = false
            }

            else -> Unit
        }
        publish()
    }

    fun onCallStateChanged(call: Call, callState: Call.State?, message: String) {
        when (callState) {
            Call.State.IncomingReceived -> onIncomingReceived(call)
            Call.State.OutgoingInit -> {
                onOutgoingInit(call)
                video.applyCamera(call)
            }

            Call.State.Connected, Call.State.StreamsRunning -> {
                val meta = metaOf(call)
                if (meta.connectedAt == null) meta.connectedAt = SystemClock.elapsedRealtime()
                if (same(call, current) || same(call, other)) host?.dismissIncomingCall()
                if (callState == Call.State.StreamsRunning) {
                    video.onStreaming(call)
                    if (same(call, current)) onCurrentStreaming(call, meta)
                }
                publish()
            }

            Call.State.Error, Call.State.End -> {
                metaOf(call).let {
                    it.endReason = CallSupport.endReason(call, callState, it.connectedAt != null, message)
                }
                if (same(call, current) || same(call, other)) host?.dismissIncomingCall()
                publish()
            }

            Call.State.UpdatedByRemote -> video.onUpdatedByRemote(call, onScreen = same(call, current) && conference == null)
            Call.State.Released -> onReleased(call)
            else -> publish()
        }
    }

    private fun onIncomingReceived(call: Call) {
        // A call that replaces one of ours (attended transfer done by the remote side) is answered
        // by the core itself; we only have to follow it.
        val replaced = call.replacedCall
        if (replaced != null && (same(replaced, current) || same(replaced, other))) {
            if (same(replaced, current)) current = call else other = call
            publish()
            return
        }

        val active = live(current)
        val decision = IncomingPolicy.decide(
            settings.current,
            hasActiveCall = active != null,
            hasSecondCall = other != null,
            anonymous = NameLookup.isWithheld(CallSupport.party(call).address),
        )
        when (decision) {
            IncomingPolicy.Decision.RejectDoNotDisturb -> call.decline(Reason.DoNotDisturb)
            IncomingPolicy.Decision.RejectAnonymous -> {
                // 433 is what SIP has for exactly this (RFC 5079).
                val refusal = Factory.instance().createErrorInfo()
                refusal.set("SIP", Reason.Declined, ANONYMITY_DISALLOWED, "Anonymity Disallowed", null)
                call.declineWithErrorInfo(refusal)
            }
            IncomingPolicy.Decision.RejectBusy -> call.decline(Reason.Busy)
            is IncomingPolicy.Decision.Forward -> forward(call, decision.target)

            IncomingPolicy.Decision.Waiting -> {
                other = call
                metaOf(call).intercom = detectIntercom(call)
                publish()
                // Without a visible call screen the user would not notice the second call.
                if (host?.isAppVisible != true) host?.showIncomingCall(describe(call))
            }

            is IncomingPolicy.Decision.Ring -> {
                // A call that already ended but is still lingering on screen gives way.
                generation++
                current = call
                resetScenario()
                val meta = metaOf(call)
                meta.intercom = detectIntercom(call)
                val info = publish() ?: return
                ring(call, info, meta.intercom)
                val target = decision.forwardTo
                if (target != null) {
                    handler.postDelayed({
                        if (same(call, current) && isRinging(call)) forward(call, target)
                    }, decision.forwardAfterSeconds * 1000L)
                }
            }
        }
    }

    private fun ring(call: Call, info: CallInfo, intercom: Boolean) {
        val autoAnswer = intercom && settings.current.intercomAutoAnswer
        if (autoAnswer && host?.isAppVisible == true) {
            // The microphone is only usable while the app is visible, so answer right away.
            handler.postDelayed({ if (same(call, current) && isRinging(call)) accept(call, video = false) }, AUTO_ANSWER_DELAY_MS)
        } else {
            // Not visible: Android would mute the microphone. The call screen is brought up first
            // and the answer happens as soon as it is shown (see onCallUiVisible).
            autoAnswerPending = autoAnswer
        }
        host?.showIncomingCall(info)
    }

    private fun forward(call: Call, target: String) {
        // A bare number is completed with the domain of the account that was called.
        val address = (call.params.account ?: core.defaultAccount)?.normalizeSipUri(DialTarget.of(target))
            ?: core.interpretUrl(target, false)
        if (address == null || call.redirectTo(address) != 0) call.decline(Reason.Busy)
    }

    private fun onOutgoingInit(call: Call) {
        val kind = pending
        val active = live(current)
        val referrer = call.transfererCall
        when {
            // Not started by us: the core follows a REFER received on one of our calls.
            referrer != null -> {
                if (same(referrer, other)) other = null
                referredFrom = referrer
                current = call
                metaOf(call).intercom = metaOf(referrer).intercom
            }

            active == null -> {
                generation++
                current = call
                resetScenario()
                metaOf(call).supervision = kind?.supervision
            }

            // A second call next to the active one: the consultation leg of an attended transfer.
            else -> {
                if (other == null) other = active
                current = call
                consulting = true
            }
        }
        publish()
    }

    private fun onCurrentStreaming(call: Call, meta: Meta) {
        // The call we were referred to is up: the original one is not needed any more.
        referredFrom?.let { old ->
            referredFrom = null
            if (!isEnded(old)) old.terminate()
        }
        if (meta.intercom && settings.current.intercomSpeaker && !meta.speakerApplied) {
            meta.speakerApplied = true
            CallSupport.applyRoute(core, call, AudioRoute.SPEAKER)
        }
        val video = CallVideoSupport.hasVideo(call)
        if (video && !meta.hadVideo) {
            val route = CallSupport.routeOf(call.outputAudioDevice?.type) ?: AudioRoute.EARPIECE
            if (VideoRules.speakerForVideo(route, CallSupport.availableRoutes(core))) {
                CallSupport.applyRoute(core, call, AudioRoute.SPEAKER)
            }
        }
        meta.hadVideo = video
    }

    private fun onReleased(call: Call) {
        val meta = metas.remove(call.nativePointer)
        video.onReleased(call)
        when {
            same(call, current) -> {
                host?.dismissIncomingCall()
                val next = live(other) ?: live(referredFrom)
                if (next != null) {
                    // The remaining call takes over the screen.
                    current = next
                    if (same(next, other)) other = null else referredFrom = null
                    consulting = false
                    conference = null
                    when {
                        isRinging(next) -> publish()?.let { ring(next, it, metaOf(next).intercom) }
                        // Our own hold is lifted, unless that call is just being transferred away.
                        next.state == Call.State.Paused && transferStatus != TransferStatus.IN_PROGRESS &&
                            transferStatus != TransferStatus.SUCCEEDED -> next.resume()
                    }
                    publish()
                } else {
                    finish(meta)
                }
            }

            same(call, other) -> {
                other = null
                consulting = false
                conference = null
                publish()
            }

            same(call, referredFrom) -> referredFrom = null
        }
    }

    /** Last call gone: keep the "call ended" state on screen for a moment, then clear it. */
    private fun finish(meta: Meta?) {
        val last = state.value
        current = null
        other = null
        referredFrom = null
        conference = null
        val reason = transferEndReason() ?: meta?.endReason ?: last?.endReason ?: "通话结束"
        resetScenario()
        state.value = last?.copy(
            phase = CallPhase.ENDED,
            endReason = reason,
            heldCall = null,
            waitingCall = null,
            consulting = false,
            conference = false,
        )
        host?.onCallActivityChanged()
        val token = ++generation
        handler.postDelayed({ if (token == generation && current == null) state.value = null }, END_LINGER_MS)
    }

    // ---------------------------------------------------------------- helpers

    /** A completed transfer or parking explains the end of the call better than the SIP reason. */
    private fun transferEndReason(): String? = when {
        transferStatus != TransferStatus.SUCCEEDED -> null
        parking -> "通话已驻留"
        else -> "转接成功"
    }

    private fun resetScenario() {
        consulting = false
        attendedTransfer = false
        transferStatus = null
        parking = false
        autoAnswerPending = false
    }

    private fun isMuted(call: Call): Boolean = conference?.microphoneMuted ?: call.microphoneMuted

    private fun same(a: Call?, b: Call?): Boolean = a != null && b != null && a.nativePointer == b.nativePointer

    private fun live(call: Call?): Call? = call?.takeIf { !isEnded(it) }

    private fun metaOf(call: Call): Meta = metas.getOrPut(call.nativePointer) { Meta() }

    private fun detectIntercom(call: Call): Boolean {
        if (call.askedToAutoanswer()) return true
        val remote = call.remoteParams ?: return false
        return IntercomHeaders.requestsAutoAnswer(
            callInfo = remote.getCustomHeader(IntercomHeaders.CALL_INFO),
            alertInfo = remote.getCustomHeader(IntercomHeaders.ALERT_INFO),
            answerMode = remote.getCustomHeader(IntercomHeaders.ANSWER_MODE),
        )
    }

    /** The other side of a call, by its name in the directory when it has one there. */
    private fun partyOf(call: Call): SideCall {
        val party = CallSupport.party(call)
        val outgoing = call.dir == Call.Dir.Outgoing
        val line = call.params.account?.let(accounts::idOf)
        val name = PartyName.of(party.name, party.address, outgoing, settings.current.features) { names.nameOf(it, !outgoing, line) }
        return party.copy(name = name)
    }

    /** The directory found a name: show it on the call that is up, and where that call is being announced. */
    private fun onNamesFound() {
        val call = current ?: return
        val info = publish() ?: return
        if (isRinging(call) && call.dir == Call.Dir.Incoming) host?.updateIncomingCall(info)
    }

    /** Snapshot of a single call, without the second-call fields. */
    private fun describe(call: Call): CallInfo {
        val meta = metaOf(call)
        val party = partyOf(call)
        val account = call.params.account?.params?.identityAddress
        val ringing = isRinging(call)
        return CallInfo(
            phase = phaseOf(call),
            remoteName = party.name,
            remoteAddress = party.address,
            accountLabel = account?.let { "${it.username.orEmpty()}@${it.domain.orEmpty()}" }.orEmpty(),
            incoming = call.dir == Call.Dir.Incoming,
            intercom = meta.intercom,
            muted = isMuted(call),
            route = CallSupport.routeOf(call.outputAudioDevice?.type) ?: state.value?.route ?: AudioRoute.EARPIECE,
            availableRoutes = CallSupport.availableRoutes(core),
            connectedAt = meta.connectedAt,
            endReason = meta.endReason,
            supervision = meta.supervision,
            video = if (videoAllowed && !ringing) CallVideoSupport.stateOf(call) else null,
            videoOffer = videoAllowed && (if (ringing) CallVideoSupport.offered(call) else video.isRequesting(call)),
            videoAllowed = videoAllowed && conference == null,
        )
    }

    private fun publish(): CallInfo? {
        val call = current ?: return null
        val second = live(other)
        val previous = state.value
        val described = describe(call)
        val info = described.copy(
            endReason = transferEndReason() ?: described.endReason,
            heldCall = second?.takeIf { !isRinging(it) }?.let(::partyOf),
            waitingCall = second?.takeIf(::isRinging)?.let(::partyOf),
            consulting = consulting && second != null,
            transfer = transferStatus,
            parked = parking && transferStatus == TransferStatus.SUCCEEDED,
            conference = conference != null && second != null,
        )
        state.value = info
        if (previous?.phase != info.phase) host?.onCallActivityChanged()
        return info
    }

    private companion object {
        const val END_LINGER_MS = 1400L
        const val AUTO_ANSWER_DELAY_MS = 400L
        const val PARK_ANNOUNCE_TIMEOUT_MS = 12_000L

        /** SIP status for a caller who withholds the number where that is not accepted. */
        const val ANONYMITY_DISALLOWED = 433
    }
}
