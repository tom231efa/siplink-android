package com.sky.siplink.core

import androidx.annotation.StringRes
import com.sky.siplink.R
import com.sky.siplink.core.CallLegs.Companion.live
import com.sky.siplink.core.CallLegs.Companion.same
import com.sky.siplink.core.CallSupport.isEnded
import com.sky.siplink.core.CallSupport.isRinging
import com.sky.siplink.core.CallSupport.phaseOf
import com.sky.siplink.core.logic.CallRules
import com.sky.siplink.core.logic.DialTarget
import com.sky.siplink.core.logic.Formatters
import com.sky.siplink.core.logic.OngoingNotice
import com.sky.siplink.core.model.AudioRoute
import com.sky.siplink.core.model.CallInfo
import com.sky.siplink.core.model.CallPhase
import com.sky.siplink.core.model.Phrase
import com.sky.siplink.core.model.RoomReadings
import com.sky.siplink.core.model.SideCall
import com.sky.siplink.core.model.SupervisionMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.linphone.core.Account
import org.linphone.core.Address
import org.linphone.core.Call
import org.linphone.core.Core
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
 * room takes the place of the current call: its calls are neither the current one nor the other, and only
 * a call that comes in waits next to it. Used on the thread of the core
 * ([CoreThread]); the functions the screens call take the hop themselves.
 */
class CallManager(
    private val core: Core,
    private val settings: SettingsStore,
    private val accounts: AccountManager,
    private val names: NameResolver,
    private val texts: AppTexts,
    recordings: RecordingStore,
    roomMonitor: RoomMonitor,
) {
    /**
     * Hooks implemented by the app layer (notifications, call screen, foreground service). The
     * core reads its values at once, from its own thread; its functions are called on the main thread.
     */
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

    /** Set on the main thread. */
    @Volatile
    var host: Host? = null

    /** [host] as the core uses it: its values read at once, its functions posted to the main thread. */
    private val toHost = object : Host {
        override val isAppVisible: Boolean get() = host?.isAppVisible == true
        override val canUseCamera: Boolean get() = host?.canUseCamera == true

        override fun showIncomingCall(info: CallInfo) = CoreThread.toMain { host?.showIncomingCall(info) }

        override fun updateIncomingCall(info: CallInfo) = CoreThread.toMain { host?.updateIncomingCall(info) }

        override fun dismissIncomingCall() = CoreThread.toMain { host?.dismissIncomingCall() }

        override fun onCallActivityChanged() = CoreThread.toMain { host?.onCallActivityChanged() }
    }

    private val state = MutableStateFlow<CallInfo?>(null)
    val call: StateFlow<CallInfo?> = state.asStateFlow()

    /** The last measurements of the open conference room, for its readings only; null while there is none. */
    val roomReadings: StateFlow<RoomReadings?> get() = room.readings

    init {
        names.addListener(::onNamesFound)
    }

    /**
     * The room as it was when the user ended it, while its end is on screen before its calls are
     * down ([CallFollower.onRoomEnding]): the service and its notification follow the calls, not the screen.
     */
    @Volatile private var goingDown: CallInfo? = null

    /** The call or room that is being set up or is established (not merely ringing in), as its notification shows it. */
    val ongoingCall: CallInfo?
        get() = (goingDown ?: state.value)?.takeIf {
            it.phase == CallPhase.OUTGOING || it.phase == CallPhase.CONNECTED || it.phase == CallPhase.HELD
        }

    /** True while a call is being set up or is established (not merely ringing in). */
    val hasOngoingCall: Boolean
        get() = ongoingCall != null

    private val handler = CoreThread.handler()
    private val describer = CallDescriber(core, settings, accounts, names, texts)
    private val legs = CallLegs()

    private val autoAnswer = AutoAnswerer(
        handler,
        canUseMicrophone = { toHost.isAppVisible },
        // Never next to another call or into the room (CallRules).
        answerable = { call, _ -> same(call, legs.current) && isRinging(call) && CallRules.mayAnswerByItself(live(legs.other) != null, room.isOpen) },
    ) { call -> accept(call, video = false) }

    private val video = VideoController(core, settings, { toHost.canUseCamera }) { publish() }
    private val videoAllowed: Boolean get() = settings.current.videoCalls

    private val recorder = CallRecorder(
        recordings,
        allowed = { settings.current.callRecording },
        byDefault = { settings.current.recordByDefault },
        party = ::partyOf,
    ) { publish() }

    private val room: ConferenceRoom = ConferenceRoom(
        core, roomMonitor, ::partyOf,
        limit = { settings.current.roomLimit },
        lineOn = accounts::isSwitchedOn,
        render = texts::render,
        onEnding = { follower.onRoomEnding() },
    ) { publish() }
    private val transfer = CallTransfer(core, settings) { publish() }
    private val retry = CallRetry(handler, accounts, ::redial, ::retryGaveUp)

    private val feed = object : CallFeed {
        override val host: Host get() = toHost
        override var shown: CallInfo?
            get() = state.value
            set(value) {
                state.value = value
            }
        override var goingDown: CallInfo?
            get() = this@CallManager.goingDown
            set(value) {
                this@CallManager.goingDown = value
            }

        override fun publish(): CallInfo? = this@CallManager.publish()

        override fun describe(call: Call): CallInfo = this@CallManager.describe(call)
    }
    private val follower: CallFollower = CallFollower(core, settings, texts, legs, feed, handler, CallParts(describer, room, video, recorder, transfer, retry, autoAnswer))

    /** A call out was placed again over the other server ([CallRetry]): its entry in the history goes, by its Call-ID. */
    var onSuperseded: ((String) -> Unit)? by follower::onSuperseded

    // ---------------------------------------------------------------- placing calls

    /**
     * @param video a video call: the camera is sent if the app may use it, else the video is only received
     * @return a user readable error, or null when the call was started
     */
    fun dial(target: String, accountId: String?, supervision: SupervisionMode? = null, video: Boolean = false): String? = CoreThread.wait("CallManager.dial") {
        if (live(legs.current) != null || core.calls.any { !isEnded(it) }) return@wait say(R.string.call_busy_already)
        val account = accountId?.let(accounts::find) ?: core.defaultAccount
        invite(target, account, Pending(supervision, consultation = false), video && videoAllowed).said()
    }

    /** Puts the current call on hold and calls [target]: first step of an attended transfer. */
    fun startConsultation(target: String): String? = CoreThread.wait("CallManager.startConsultation") {
        val active = live(legs.current) ?: return@wait say(R.string.call_none)
        if (legs.other != null) return@wait say(R.string.call_two_already)
        val phase = phaseOf(active)
        if (phase != CallPhase.CONNECTED && phase != CallPhase.HELD) return@wait say(R.string.call_consult_after_connect)
        invite(target, active.params.account ?: core.defaultAccount, Pending(null, consultation = true)).said()
    }

    private fun invite(target: String, account: Account?, kind: Pending, video: Boolean = false): Phrase? {
        val cleaned = DialTarget.of(target)
        if (cleaned.isEmpty()) return Phrase.of(R.string.call_enter_number)
        if (account == null) return Phrase.of(R.string.call_add_account_first)
        // A line that is switched off stays out of use, even where the PBX would take its calls.
        if (!accounts.isSwitchedOn(account)) return Phrase.of(R.string.call_line_off)
        val address = account.normalizeSipUri(cleaned) ?: return Phrase.of(R.string.call_invalid_address)
        val created = place(address, account, kind, video) ?: return Phrase.of(R.string.call_failed)
        retry.watch(created, inRoom = room.isOpen, consultation = kind.consultation)
        return null
    }

    private fun place(address: Address, account: Account, kind: Pending, video: Boolean): Call? {
        val params = core.createCallParams(null) ?: return null
        params.account = account
        this.video.prepare(params, video)
        val recordFile = recorder.prepare(params)
        // The core reports OutgoingInit from inside the invite call; `pending` tells it apart there.
        legs.pending = kind
        val created = core.inviteAddressWithParams(address, params)
        legs.pending = null
        created?.let { recorder.attach(it, recordFile) }
        return created
    }

    /** Places the current call [old] again, as it was placed, on its account now on its other server, once [end] ended it ([CallRetry]). */
    private fun redial(old: Call, end: () -> Unit): Boolean {
        val account = old.params.account
        if (!same(old, legs.current) || room.isOpen || account == null) return false
        // Its facts go with it: the old call's are gone once it is released, which may be at once.
        val meta = metaOf(old)
        val kind = Pending(meta.supervision, consultation = false)
        val address = old.remoteAddress.clone()
        val video = old.params.isVideoEnabled
        // What the user set for the call before it rang stays: the microphone, and where the sound goes.
        val muted = old.microphoneMuted
        end()
        val placed = place(address, account, kind, video) ?: return false
        placed.microphoneMuted = muted
        // The route chosen goes with the call's facts (CallMeta.chosenRoute), applied as its sound starts.
        legs.carry(placed, meta)
        return true
    }

    /** The current call [old] could not be placed again ([CallRetry]): it ended without an answer from its server, and shows so at once. */
    private fun retryGaveUp(old: Call) {
        if (!same(old, legs.current)) return
        metaOf(old).endReason = CallSupport.SERVER_SILENT
        follower.finish(legs.forget(old))
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
    fun answer(video: Boolean = false): String? = CoreThread.wait("CallManager.answer") {
        autoAnswer.cancel()
        if (room.isOpen) return@wait joinRoom()
        val waiting = legs.other?.takeIf(::isRinging)
        if (waiting != null) {
            // The core puts the running call on hold by itself when another one is accepted.
            legs.other = legs.current
            legs.current = waiting
            accept(waiting, video)
            publish()
            return@wait null
        }
        legs.current?.takeIf(::isRinging)?.let { accept(it, video) }
        null
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
    fun decline() = CoreThread.post {
        autoAnswer.cancel()
        val waiting = legs.other?.takeIf(::isRinging)
        if (waiting != null) {
            waiting.decline(Reason.Busy)
            return@post
        }
        legs.current?.takeIf(::isRinging)?.decline(Reason.Declined)
    }

    fun hangUp() = CoreThread.post {
        if (room.isOpen) {
            room.end()
            return@post
        }
        val call = legs.current ?: return@post
        if (isRinging(call)) call.decline(Reason.Declined) else call.terminate()
    }

    // ---------------------------------------------------------------- in-call controls

    /** In the room the microphone belongs to the room, not to one of its calls. */
    fun toggleMute() = CoreThread.post {
        if (room.isOpen) {
            room.muted = !room.muted
        } else {
            val call = legs.current ?: return@post
            call.microphoneMuted = !call.microphoneMuted
        }
        publish()
    }

    fun toggleHold() = CoreThread.post {
        val call = legs.current ?: return@post
        when (call.state) {
            Call.State.Paused, Call.State.Pausing -> call.resume()
            Call.State.StreamsRunning -> call.pause()
            else -> Unit
        }
    }

    fun sendDtmf(digit: Char) = CoreThread.post {
        val call = legs.current ?: return@post
        if (!Formatters.isDtmf(digit)) return@post
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
    fun sendDtmfs(digits: String): String? = CoreThread.wait("CallManager.sendDtmfs") {
        val call = live(legs.current)?.takeIf { it.state == Call.State.StreamsRunning } ?: return@wait say(R.string.call_dtmf_in_call_only)
        call.sendDtmfs(digits.filter(Formatters::isDtmf))
        null
    }

    fun setRoute(route: AudioRoute) = CoreThread.post {
        val call = legs.current
        val applied = when {
            room.isOpen -> room.setRoute(route)
            call != null -> CallSupport.applyRoute(core, call, route).also { if (it) metaOf(call).chosenRoute = route }
            else -> false
        }
        if (applied) publish()
    }

    /** Switches a supervisor call between listen / whisper / barge (DTMF understood by the PBX). */
    fun setSupervisionMode(mode: SupervisionMode) = CoreThread.post {
        val call = live(legs.current) ?: return@post
        val meta = metaOf(call)
        if (!CallRules.supervisionKeysGo(meta.supervision != null, call.state == Call.State.StreamsRunning, room.isOpen)) return@post
        call.sendDtmf(mode.dtmf)
        meta.supervision = mode
        publish()
    }

    /** Swaps the held call and the current one. */
    fun swap() = CoreThread.post {
        val held = live(legs.other)?.takeIf { !isRinging(it) } ?: return@post
        legs.other = legs.current
        legs.current = held
        legs.consulting = false
        // Resuming preempts the audio: the core holds the other call by itself.
        held.resume()
        publish()
    }

    /** Starts or stops recording the current call. */
    fun toggleRecording() = CoreThread.post { recorder.toggle(live(legs.current)) }

    /** Recording was allowed or forbidden in the settings; forbidden, running recordings end. */
    fun onRecordingAllowedChanged() = recorder.onAllowedChanged(core.calls.toList())

    // ---------------------------------------------------------------- video

    /** Adds video to the current call, or ends it (re-INVITE). The room has none. */
    fun toggleVideo(): String? = CoreThread.wait("CallManager.toggleVideo") {
        if (room.isOpen) say(R.string.call_room_no_video) else video.toggleVideo(live(legs.current)).said()
    }

    /** Stops or starts sending the own camera; the picture of the other side keeps coming. */
    fun toggleCamera(): String? = CoreThread.wait("CallManager.toggleCamera") { video.toggleCamera(live(legs.current)).said() }

    /** Front and back camera, in turn. */
    fun switchCamera() = CoreThread.post { video.switchCamera(live(legs.current)) }

    /** The user's answer to the other side's request to add video (see [CallInfo.videoOffer]). */
    fun answerVideoRequest(accept: Boolean) = CoreThread.post { video.answerRequest(accept) }

    /** The call screen came to the front or left it: the camera is only used while it is in front. */
    fun onCallScreenShown(shown: Boolean) = CoreThread.post { video.onCallScreenShown(shown) }

    /**
     * The statistics of [call] came in, about once a second while it runs: the pictures of a
     * video call can have changed their size since (the other side turned its phone round, say).
     */
    fun onCallStatsUpdated(call: Call) {
        if (!same(call, legs.current)) return
        val video = state.value?.video ?: return
        if (CallVideoSupport.stateOf(call) != video) publish()
    }

    /** Video calls were switched on or off in the settings; off, running video ends. */
    fun onVideoAllowedChanged() = video.onAllowedChanged()

    // ---------------------------------------------------------------- transfer / park

    /** Blind transfer: the PBX connects the other party to [target] and releases us. */
    fun blindTransfer(target: String): String? = CoreThread.wait("CallManager.blindTransfer") { transfer.blind(live(legs.current), target).said() }

    /** Parks the current call: a blind transfer to the PBX parking extension. */
    fun park(): String? = CoreThread.wait("CallManager.park") { transfer.park(live(legs.current)).said() }

    /** Second step of an attended transfer: connects the held party with the consulted one. */
    fun completeTransfer(): String? = CoreThread.wait("CallManager.completeTransfer") {
        val consulted = live(legs.current)
        val held = live(legs.other)
        if (!legs.consulting || consulted == null || held == null) return@wait say(R.string.call_no_pending_transfer)
        transfer.complete(consulted, held).said()
    }

    // ---------------------------------------------------------------- conference room

    /**
     * Turns the calls that are up into a conference room: the current one and the one on hold
     * (merging them). A call that rings next to them waits on. Video ends.
     *
     * @return a user readable error, or null when the room is open
     */
    fun openRoom(): String? = CoreThread.wait("CallManager.openRoom") {
        if (room.isOpen) return@wait null
        val first = live(legs.current) ?: return@wait say(R.string.call_none)
        val phase = phaseOf(first)
        if (phase != CallPhase.CONNECTED && phase != CallPhase.HELD) return@wait say(R.string.call_room_after_connect)
        val second = live(legs.other)?.takeIf { !isRinging(it) }
        val calls = listOfNotNull(first, second)
        CallRules.roomRefusal(supervised = calls.any { metaOf(it).supervision != null }, transferring = transfer.moving)?.let { return@wait say(it) }
        // Muting the call the user is talking in carries over to the room.
        room.open(calls, muted = first.microphoneMuted)?.let { return@wait texts.render(it) }
        recorder.onMerging(calls)
        legs.current = null
        if (second != null) legs.other = null
        follower.resetScenario()
        publish()
        null
    }

    /**
     * Calls [targets] (numbers or addresses) into the room; each joins as it is answered.
     *
     * @return a user readable error, or null when the calls were placed
     */
    fun inviteToRoom(targets: List<String>): String? = CoreThread.wait("CallManager.inviteToRoom") {
        targets.firstNotNullOfOrNull { CallRules.memberRefusal(it, settings.current.features) }?.let(::say) ?: room.invite(targets).said()
    }

    /** Brings the user back into the room's sound after another app had taken it ([com.sky.siplink.core.model.RoomInfo.away]). */
    fun rejoinRoom(): String? = CoreThread.wait("CallManager.rejoinRoom") { room.rejoin().also { publish() }.said() }

    /** Answers the waiting call into the room. @return a user readable error, or null */
    fun joinRoom(): String? = CoreThread.wait("CallManager.joinRoom") {
        val waiting = legs.other?.takeIf(::isRinging) ?: return@wait null
        autoAnswer.cancel()
        room.join(waiting)?.let { return@wait texts.render(it) }
        legs.other = null
        toHost.dismissIncomingCall()
        publish()
        null
    }

    /** Ends the call of one member of the room ([com.sky.siplink.core.model.RoomMember.id]); the others stay. */
    fun hangUpMember(id: Long) = CoreThread.post { room.hangUp(id) }

    /** Calls a member of the room again that was not called, or whose call failed, after an answer of the PBX. @return a user readable error, or null */
    fun retryMember(id: Long): String? = CoreThread.wait("CallManager.retryMember") { room.retry(id).said() }

    // ---------------------------------------------------------------- core events

    /** Called when the call screen becomes visible: completes an auto answer whose time has come. */
    fun onCallUiVisible() = CoreThread.post { autoAnswer.onCallUiVisible() }

    fun onAudioChanged() {
        publish()
    }

    fun onTransferStateChanged(call: Call, transferState: Call.State?) =
        transfer.onStateChanged(call, transferState, both = listOfNotNull(live(legs.current), live(legs.other)))

    fun onCallStateChanged(call: Call, callState: Call.State?, message: String) = follower.onCallStateChanged(call, callState, message)

    // ---------------------------------------------------------------- helpers

    /** The answers of the commands are said in the app's language of the moment. */
    private fun say(@StringRes id: Int): String = texts.get(id)

    private fun Phrase?.said(): String? = this?.let(texts::render)

    /** The language of the app changed: the snapshot is rendered again, and so is the call being announced. */
    fun onLanguageChanged() {
        onNamesFound()
        follower.onLanguageChanged()
    }

    private fun metaOf(call: Call): CallMeta = legs.metaOf(call)

    private fun partyOf(call: Call): SideCall = describer.partyOf(call)

    /** The directory found a name: show it on the call that is up, and where that call is being announced. */
    private fun onNamesFound() {
        room.onSidesChanged()
        val info = publish() ?: return
        val call = legs.current ?: return
        if (isRinging(call) && call.dir == Call.Dir.Incoming) toHost.updateIncomingCall(info)
    }

    /**
     * The feature codes changed in the settings: they name the numbers dialled with them
     * ("*801005" is the intercom to 1005), in the room too. An incoming call is not named by them.
     */
    fun onFeaturesChanged() {
        room.onSidesChanged()
        publish()
    }

    /** Snapshot of a single call, without the second-call fields. */
    private fun describe(call: Call): CallInfo = describer.describe(call, metaOf(call), state.value?.route, video, recorder)

    private fun publish(): CallInfo? {
        CoreThread.check("The calls")
        // The end of the room is on screen already: nothing of the room comes back while it goes down.
        if (legs.endShownAt != null) return state.value
        val previous = state.value
        val info = room.snapshot()?.let { room ->
            // The room says no reason; a call says its own ([callInfo]).
            legs.endReason = null
            describer.roomInfo(room, this.room, previous, live(legs.other)?.takeIf(::isRinging)?.let(::partyOf))
        } ?: legs.current?.let(::callInfo) ?: return null
        state.value = info
        // The notification shows the call or the room: posted again when what it shows changes.
        if (OngoingNotice.of(previous) != OngoingNotice.of(info)) toHost.onCallActivityChanged()
        return info
    }

    /** The current call with what stands beside it. */
    private fun callInfo(call: Call): CallInfo {
        val second = live(legs.other)
        val reason = transfer.endReason() ?: metaOf(call).endReason
        legs.endReason = reason
        return describe(call).copy(
            endReason = reason?.let(texts::render),
            heldCall = second?.takeIf { !isRinging(it) }?.let(::partyOf),
            waitingCall = second?.takeIf(::isRinging)?.let(::partyOf),
            consulting = legs.consulting && second != null,
            transfer = transfer.status,
            parked = transfer.parked,
        )
    }
}
