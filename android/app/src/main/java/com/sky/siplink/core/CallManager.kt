package com.sky.siplink.core

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.sky.siplink.core.CallSupport.isEnded
import com.sky.siplink.core.CallSupport.isRinging
import com.sky.siplink.core.CallSupport.phaseOf
import com.sky.siplink.core.logic.AutoAnswerRules
import com.sky.siplink.core.logic.CallRules
import com.sky.siplink.core.logic.DialTarget
import com.sky.siplink.core.logic.Formatters
import com.sky.siplink.core.logic.IncomingPolicy
import com.sky.siplink.core.logic.NameLookup
import com.sky.siplink.core.logic.OngoingNotice
import com.sky.siplink.core.logic.PartyName
import com.sky.siplink.core.logic.VideoRules
import com.sky.siplink.core.model.AudioRoute
import com.sky.siplink.core.model.CallInfo
import com.sky.siplink.core.model.CallPhase
import com.sky.siplink.core.model.RoomInfo
import com.sky.siplink.core.model.SideCall
import com.sky.siplink.core.model.SupervisionMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.linphone.core.Account
import org.linphone.core.Call
import org.linphone.core.Core
import org.linphone.core.Factory
import org.linphone.core.Reason

/**
 * Call controller. The UI follows one "current" call; a second call may sit next to it, either
 * on hold (consultation for an attended transfer, call swapped away) or ringing (call waiting).
 *
 * Besides the basics (dial, answer, hold, mute, DTMF, routing) it implements the PBX-style
 * features: blind and attended transfer, parking, do not disturb, forwarding, and the bookkeeping
 * needed when the PBX rearranges our calls (REFER, replaced or force-released calls).
 *
 * The calls can become a conference room ([openRoom], see [ConferenceRoom]). While it is open the
 * room takes the place of the current call: its calls are neither [current] nor [other], and only
 * a call that comes in waits next to it. Must be used from the thread that owns the core (the
 * main thread).
 */
class CallManager(
    private val core: Core,
    private val settings: SettingsStore,
    private val accounts: AccountManager,
    private val names: NameResolver,
    recordings: RecordingStore,
    roomMonitor: RoomMonitor,
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
        /** It came in next to another call or the room and waited there: it is never answered by itself (CallRules, rule 2). */
        var waited: Boolean = false,
    )

    /** What the next outgoing call started by [invite] is meant to be. */
    private class Pending(val supervision: SupervisionMode?, val consultation: Boolean)

    private val handler = Handler(Looper.getMainLooper())
    private val metas = HashMap<Long, Meta>()

    private var current: Call? = null
    private var other: Call? = null

    /** The call in which the PBX asked us (REFER) to call someone else; [current] is that new call. */
    private var referredFrom: Call? = null
    private var pending: Pending? = null
    private var consulting = false
    private var generation = 0

    private val autoAnswer = AutoAnswerer(
        handler,
        canUseMicrophone = { host?.isAppVisible == true },
        // Never next to another call or into the room (CallRules).
        answerable = { call, _ -> same(call, current) && isRinging(call) && CallRules.mayAnswerByItself(live(other) != null, room.isOpen) },
    ) { call -> accept(call, video = false) }

    private val video = VideoController(core, settings, { host?.canUseCamera == true }) { publish() }
    private val videoAllowed: Boolean get() = settings.current.videoCalls

    private val recorder = CallRecorder(
        recordings,
        allowed = { settings.current.callRecording },
        byDefault = { settings.current.recordByDefault },
        party = ::partyOf,
    ) { publish() }

    private val room = ConferenceRoom(core, roomMonitor, ::partyOf, limit = { settings.current.roomLimit }, lineOn = accounts::isSwitchedOn) { publish() }
    private val transfer = CallTransfer(core, settings) { publish() }

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
        val recordFile = recorder.prepare(params)
        // The core reports OutgoingInit from inside the invite call; `pending` tells it apart there.
        pending = kind
        val created = core.inviteAddressWithParams(address, params)
        pending = null
        created?.let { recorder.attach(it, recordFile) }
        return if (created == null) "呼叫失败" else null
    }

    // ---------------------------------------------------------------- answering / ending

    /**
     * Answers the ringing call: the waiting one if there is one, else the current one.
     *
     * @param video take the video the caller offers (the camera is sent if the app may use
     *   it); without, the call is answered audio-only
     * @return a user readable error, or null: next to the room the call can only be answered
     *   into it, and one that does not fit keeps ringing
     */
    fun answer(video: Boolean = false): String? {
        autoAnswer.cancel()
        if (room.isOpen) return joinRoom()
        val waiting = other?.takeIf(::isRinging)
        if (waiting != null) {
            // The core puts the running call on hold by itself when another one is accepted.
            other = current
            current = waiting
            accept(waiting, video)
            publish()
            return null
        }
        current?.takeIf(::isRinging)?.let { accept(it, video) }
        return null
    }

    private fun accept(call: Call, video: Boolean) {
        val params = core.createCallParams(call)
        if (params == null) {
            call.accept()
            return
        }
        this.video.prepare(params, video && CallVideoSupport.offered(call))
        recorder.attach(call, recorder.prepare(params))
        call.acceptWithParams(params)
    }

    /** Declines the ringing call: the waiting one if there is one, else the current one. */
    fun decline() {
        autoAnswer.cancel()
        val waiting = other?.takeIf(::isRinging)
        if (waiting != null) {
            waiting.decline(Reason.Busy)
            return
        }
        current?.takeIf(::isRinging)?.decline(Reason.Declined)
    }

    fun hangUp() {
        if (room.isOpen) {
            room.end()
            return
        }
        val call = current ?: return
        if (isRinging(call)) call.decline(Reason.Declined) else call.terminate()
    }

    // ---------------------------------------------------------------- in-call controls

    /** In the room the microphone belongs to the room, not to one of its calls. */
    fun toggleMute() {
        if (room.isOpen) {
            room.muted = !room.muted
        } else {
            val call = current ?: return
            call.microphoneMuted = !call.microphoneMuted
        }
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
        val call = current ?: return
        if (!Formatters.isDtmf(digit)) return
        call.sendDtmf(digit)
        // On a supervised call the PBX takes the mode keys from the keypad too.
        val meta = metaOf(call)
        val mode = CallRules.modeAfterKey(meta.supervision, digit, streaming = call.state == Call.State.StreamsRunning, roomOpen = room.isOpen)
        if (mode != meta.supervision) {
            meta.supervision = mode
            publish()
        }
    }

    /** Sends a string of digits one after the other (a DTMF shortcut key). */
    fun sendDtmfs(digits: String): String? {
        val call = live(current)?.takeIf { it.state == Call.State.StreamsRunning } ?: return "仅可在通话中发送按键"
        call.sendDtmfs(digits.filter(Formatters::isDtmf))
        return null
    }

    fun setRoute(route: AudioRoute) {
        val call = current
        val applied = when {
            room.isOpen -> room.setRoute(route)
            call != null -> CallSupport.applyRoute(core, call, route)
            else -> false
        }
        if (applied) publish()
    }

    /** Switches a supervisor call between listen / whisper / barge (DTMF understood by the PBX). */
    fun setSupervisionMode(mode: SupervisionMode) {
        val call = live(current) ?: return
        val meta = metaOf(call)
        if (!CallRules.supervisionKeysGo(meta.supervision != null, call.state == Call.State.StreamsRunning, room.isOpen)) return
        call.sendDtmf(mode.dtmf)
        meta.supervision = mode
        publish()
    }

    /** Swaps the held call and the current one. */
    fun swap() {
        val held = live(other)?.takeIf { !isRinging(it) } ?: return
        other = current
        current = held
        consulting = false
        // Resuming preempts the audio: the core holds the other call by itself.
        held.resume()
        publish()
    }

    /** Starts or stops recording the current call. */
    fun toggleRecording() = recorder.toggle(live(current))

    /** Recording was allowed or forbidden in the settings; forbidden, running recordings end. */
    fun onRecordingAllowedChanged() = recorder.onAllowedChanged(core.calls.toList())

    // ---------------------------------------------------------------- video

    /** Adds video to the current call, or ends it (re-INVITE). The room has none. */
    fun toggleVideo(): String? = if (room.isOpen) "会议室不支持视频" else video.toggleVideo(live(current))

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

    // ---------------------------------------------------------------- transfer / park

    /** Blind transfer: the PBX connects the other party to [target] and releases us. */
    fun blindTransfer(target: String): String? = transfer.blind(live(current), target)

    /** Parks the current call: a blind transfer to the PBX parking extension. */
    fun park(): String? = transfer.park(live(current))

    /** Second step of an attended transfer: connects the held party with the consulted one. */
    fun completeTransfer(): String? {
        val consulted = live(current)
        val held = live(other)
        if (!consulting || consulted == null || held == null) return "无待完成的转接"
        return transfer.complete(consulted, held)
    }

    // ---------------------------------------------------------------- conference room

    /**
     * Turns the calls that are up into a conference room: the current one and the one on hold
     * (merging them). A call that rings next to them waits on. Video ends.
     *
     * @return a user readable error, or null when the room is open
     */
    fun openRoom(): String? {
        if (room.isOpen) return null
        val first = live(current) ?: return "当前无通话"
        val phase = phaseOf(first)
        if (phase != CallPhase.CONNECTED && phase != CallPhase.HELD) return "通话接通后方可开启会议室"
        val second = live(other)?.takeIf { !isRinging(it) }
        val calls = listOfNotNull(first, second)
        CallRules.roomRefusal(supervised = calls.any { metaOf(it).supervision != null }, transferring = transfer.moving)?.let { return it }
        // Muting the call the user is talking in carries over to the room.
        room.open(calls, muted = first.microphoneMuted)?.let { return it }
        recorder.onMerging(calls)
        current = null
        if (second != null) other = null
        resetScenario()
        publish()
        return null
    }

    /**
     * Calls [targets] (numbers or addresses) into the room; each joins as it is answered.
     *
     * @return a user readable error, or null when the calls were placed
     */
    fun inviteToRoom(targets: List<String>): String? =
        targets.firstNotNullOfOrNull { CallRules.memberRefusal(it, settings.current.features) } ?: room.invite(targets)

    /** Brings the user back into the room's sound after another app had taken it ([com.sky.siplink.core.model.RoomInfo.away]). */
    fun rejoinRoom(): String? = room.rejoin().also { publish() }

    /** Answers the waiting call into the room. @return a user readable error, or null */
    fun joinRoom(): String? {
        val waiting = other?.takeIf(::isRinging) ?: return null
        autoAnswer.cancel()
        room.join(waiting)?.let { return it }
        other = null
        host?.dismissIncomingCall()
        publish()
        return null
    }

    /** Ends the call of one member of the room ([com.sky.siplink.core.model.RoomMember.id]); the others stay. */
    fun hangUpMember(id: Long) = room.hangUp(id)

    /** Calls a member of the room again that was not called, or whose call failed, after an answer of the PBX. @return a user readable error, or null */
    fun retryMember(id: Long): String? = room.retry(id)

    // ---------------------------------------------------------------- core events

    /** Called when the call screen becomes visible: completes an auto answer whose time has come. */
    fun onCallUiVisible() = autoAnswer.onCallUiVisible()

    fun onAudioChanged() {
        publish()
    }

    fun onTransferStateChanged(call: Call, transferState: Call.State?) =
        transfer.onStateChanged(call, transferState, both = listOfNotNull(live(current), live(other)))

    fun onCallStateChanged(call: Call, callState: Call.State?, message: String) {
        room.onStateChanged(call, callState)
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
                    recorder.onStreaming(call, inRoom = room.isMember(call))
                    room.onStreaming(call)
                    if (same(call, current)) onCurrentStreaming(call, meta)
                }
                publish()
            }

            Call.State.Error, Call.State.End -> {
                val meta = metaOf(call)
                meta.endReason = CallSupport.endReason(call, callState, meta.connectedAt != null, message)
                room.onEnded(call, meta.endReason)
                if (same(call, current) || same(call, other)) host?.dismissIncomingCall()
                publish()
            }

            // The calls of the room are never the current one: video they are offered is declined.
            Call.State.UpdatedByRemote -> video.onUpdatedByRemote(call, onScreen = same(call, current))
            Call.State.Released -> onReleased(call)
            else -> publish()
        }
    }

    private fun onIncomingReceived(call: Call) {
        // A call that replaces one of ours (attended transfer done by the remote side) is answered
        // by the core itself, which ends the old one; we only have to follow it. In the room it
        // takes the member's place, if there is room for it.
        val replaced = call.replacedCall
        if (replaced != null && (same(replaced, current) || same(replaced, other))) {
            if (same(replaced, current)) current = call else other = call
            publish()
            return
        }
        if (replaced != null && room.isMember(replaced)) {
            if (room.replace(call, replaced)) publish() else call.decline(Reason.Busy)
            return
        }

        val decision = IncomingPolicy.decide(
            settings.current,
            hasActiveCall = live(current) != null || room.isOpen,
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
                metaOf(call).intercom = CallSupport.isIntercom(call)
                metaOf(call).waited = true
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
                meta.intercom = CallSupport.isIntercom(call)
                val info = publish() ?: return
                ring(call, info)
                val target = decision.forwardTo
                if (target != null) {
                    handler.postDelayed({
                        if (same(call, current) && isRinging(call)) forward(call, target)
                    }, decision.forwardAfterSeconds * 1000L)
                }
            }
        }
    }

    private fun ring(call: Call, info: CallInfo) {
        val meta = metaOf(call)
        autoAnswer.start(call, meta.intercom, AutoAnswerRules.delayMs(settings.current, meta.intercom, cameDuringCall = meta.waited))
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
            // Placed by the room, or by the PBX in place of a call of the room (if there is room for it).
            room.inviting -> room.onInvited(call)
            referrer != null && room.isMember(referrer) -> if (!room.replace(call, referrer)) refuse(call)

            // Not started by us: the core follows a REFER received on one of our calls, one at a
            // time (a second one would leave the first new call without a place on screen).
            referrer != null && referredFrom != null -> refuse(call)
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

    /** Ends a call the core placed on a REFER that has no place; not from inside the core's report of it. */
    private fun refuse(call: Call) {
        handler.post { if (!isEnded(call)) call.terminate() }
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
        recorder.onReleased(call)
        when {
            same(call, current) -> {
                host?.dismissIncomingCall()
                autoAnswer.cancel()
                val next = live(other) ?: live(referredFrom)
                if (next != null) {
                    // The remaining call takes over the screen.
                    current = next
                    if (same(next, other)) other = null else referredFrom = null
                    consulting = false
                    when {
                        isRinging(next) -> publish()?.let { ring(next, it) }
                        // Our own hold is lifted, unless that call is just being transferred away.
                        next.state == Call.State.Paused && !transfer.moving -> next.resume()
                    }
                    publish()
                } else {
                    finish(meta)
                }
            }

            same(call, other) -> {
                other = null
                consulting = false
                publish()
            }

            same(call, referredFrom) -> referredFrom = null

            room.isMember(call) -> if (room.onReleased(call)) onRoomClosed() else publish()
        }
    }

    /** The last member left the room, or the user ended it: a call that waits takes over, else the screen ends. */
    private fun onRoomClosed() {
        val next = live(other)
        if (next == null) {
            finish(null)
            return
        }
        current = next
        other = null
        val info = publish() ?: return
        if (isRinging(next)) ring(next, info)
    }

    /** Last call gone: keep the "call ended" state on screen for a moment, then clear it. */
    private fun finish(meta: Meta?) {
        val last = state.value
        current = null
        other = null
        referredFrom = null
        val reason = transfer.endReason() ?: meta?.endReason ?: last?.endReason ?: "通话结束"
        resetScenario()
        state.value = last?.copy(
            phase = CallPhase.ENDED,
            endReason = reason,
            heldCall = null,
            waitingCall = null,
            consulting = false,
            room = null,
        )
        host?.onCallActivityChanged()
        val token = ++generation
        handler.postDelayed({ if (token == generation && current == null) state.value = null }, END_LINGER_MS)
    }

    // ---------------------------------------------------------------- helpers

    private fun resetScenario() {
        consulting = false
        transfer.reset()
        autoAnswer.cancel()
    }

    private fun same(a: Call?, b: Call?): Boolean = a != null && b != null && a.nativePointer == b.nativePointer

    private fun live(call: Call?): Call? = call?.takeIf { !isEnded(it) }

    private fun metaOf(call: Call): Meta = metas.getOrPut(call.nativePointer) { Meta() }

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
        val info = publish() ?: return
        val call = current ?: return
        if (isRinging(call) && call.dir == Call.Dir.Incoming) host?.updateIncomingCall(info)
    }

    /** Snapshot of a single call, without the second-call fields. */
    private fun describe(call: Call): CallInfo {
        val meta = metaOf(call)
        val party = partyOf(call)
        val ringing = isRinging(call)
        return CallInfo(
            phase = phaseOf(call),
            remoteName = party.name,
            remoteAddress = party.address,
            accountLabel = CallSupport.lineOf(call.params.account),
            incoming = call.dir == Call.Dir.Incoming,
            intercom = meta.intercom,
            muted = call.microphoneMuted,
            route = CallSupport.routeOf(call.outputAudioDevice?.type) ?: state.value?.route ?: AudioRoute.EARPIECE,
            availableRoutes = CallSupport.availableRoutes(core),
            connectedAt = meta.connectedAt,
            endReason = meta.endReason,
            supervision = meta.supervision,
            video = if (videoAllowed && !ringing) CallVideoSupport.stateOf(call) else null,
            videoOffer = videoAllowed && (if (ringing) CallVideoSupport.offered(call) else video.isRequesting(call)),
            videoAllowed = videoAllowed,
            recordable = recorder.recordable(call),
            recordingSince = recorder.startedAt(call),
        )
    }

    private fun publish(): CallInfo? {
        val previous = state.value
        val info = room.snapshot()?.let { roomInfo(it, previous) } ?: current?.let(::callInfo) ?: return null
        state.value = info
        // The notification shows the call or the room: posted again when what it shows changes.
        if (OngoingNotice.of(previous) != OngoingNotice.of(info)) host?.onCallActivityChanged()
        return info
    }

    /** The current call with what stands beside it. */
    private fun callInfo(call: Call): CallInfo {
        val second = live(other)
        val described = describe(call)
        return described.copy(
            endReason = transfer.endReason() ?: described.endReason,
            heldCall = second?.takeIf { !isRinging(it) }?.let(::partyOf),
            waitingCall = second?.takeIf(::isRinging)?.let(::partyOf),
            consulting = consulting && second != null,
            transfer = transfer.status,
            parked = transfer.parked,
        )
    }

    /** The room in place of a call, with the call that waits beside it. */
    private fun roomInfo(room: RoomInfo, previous: CallInfo?) = CallInfo(
        phase = CallPhase.CONNECTED,
        remoteName = ROOM_NAME,
        remoteAddress = "",
        accountLabel = CallSupport.lineOf(this.room.account),
        incoming = false,
        intercom = false,
        muted = this.room.muted,
        route = this.room.route ?: previous?.route ?: AudioRoute.EARPIECE,
        availableRoutes = CallSupport.availableRoutes(core),
        connectedAt = room.openedAt,
        waitingCall = live(other)?.takeIf(::isRinging)?.let(::partyOf),
        room = room,
    )

    private companion object {
        /** What the call screen and the notification call the conference room. */
        const val ROOM_NAME = "会议室"
        const val END_LINGER_MS = 1400L

        /** SIP status for a caller who withholds the number where that is not accepted. */
        const val ANONYMITY_DISALLOWED = 433
    }
}
