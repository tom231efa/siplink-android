package com.sky.siplink.core

import android.os.SystemClock
import com.sky.siplink.R
import com.sky.siplink.core.CallSupport.isEnded
import com.sky.siplink.core.logic.DialTarget
import com.sky.siplink.core.logic.Formatters
import com.sky.siplink.core.logic.RoomHealth
import com.sky.siplink.core.logic.RoomPacing
import com.sky.siplink.core.logic.RoomRules
import com.sky.siplink.core.model.AudioRoute
import com.sky.siplink.core.model.MemberState
import com.sky.siplink.core.model.Phrase
import com.sky.siplink.core.model.RoomInfo
import com.sky.siplink.core.model.RoomMember
import com.sky.siplink.core.model.RoomReadings
import com.sky.siplink.core.model.SideCall
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.linphone.core.Account
import org.linphone.core.Call
import org.linphone.core.Conference
import org.linphone.core.Core

/**
 * The conference room of [CallManager]. The phone is the host: a local conference of the SIP core
 * mixes the sound of every call in it, the user's own included, and there is no video in it.
 *
 * The room is opened from the calls that are up ([open]); members are added by calling them from
 * inside the room ([invite]: the SDK places the calls without taking the user out of the room, a
 * few at a time, and each joins as it is answered) or by taking a call that comes in ([join]). Ending the call
 * of one member ([hangUp]) leaves the others alone: the room stays as long as one member is in
 * it, and closes when the last one is gone. Must be used from the thread that owns the core.
 *
 * @param party who the other side of a call is, as the call screen names it
 * @param limit how many members the room holds at most besides the user (a setting)
 * @param lineOn whether calls may go out on a line: it is switched on
 * @param render a text in the app's language, for the snapshot ([snapshot])
 * @param onChanged what the call screen shows of the room changed
 */
internal class ConferenceRoom(
    private val core: Core,
    private val monitor: RoomMonitor,
    private val party: (Call) -> SideCall,
    private val limit: () -> Int,
    private val lineOn: (Account) -> Boolean,
    private val render: (Phrase) -> String,
    private val onChanged: () -> Unit,
) {
    private class Member(val id: Long, var call: Call?, var side: SideCall) {
        /** [side] is to be looked up again from the call: its state changed, or how it is named did ([snapshot]). */
        var sideStale = false

        /** Why the call ended; set from its End or Error. */
        var endReason: Phrase? = null

        /** The member's call that this one takes the place of ([replace]); it ends once this one is up. */
        var replaces: Call? = null

        /** Its call ended as another one took its place: that says nothing about the PBX, and its row goes at once. */
        var replaced = false

        /** Its call was answered and ended at once by the other side (RoomPacing.shortRun): its row stays while the run is open. */
        var endedShort = false

        /** What is dialled for a member that is yet to be called ([queued]) or was held back ([heldBack]). */
        var target: String? = null

        /** Waits its turn to be called (RoomPacing). */
        var queued = false

        /** Not called after the PBX had had enough: why, until the user calls it again. */
        var heldBack: Phrase? = null

        /** SystemClock.elapsedRealtime() before which a held back member is not called again (Retry-After). */
        var retryAt = 0L

        /** SystemClock.elapsedRealtime() when its call was placed; the call has had an answer once [answered]. */
        var placedAt = 0L
        var answered = true

        /** SystemClock.elapsedRealtime() when its call was answered; 0 before. */
        var connectedAt = 0L

        /** The user ends its call (or the room): its end says nothing about the PBX. */
        var endedByUser = false
    }

    private val handler = CoreThread.handler()
    private var conference: Conference? = null

    /** In the order they came in; one that left stays [RoomRules.LEFT_LINGER_MS] without its call. */
    private val members = ArrayList<Member>()
    private var nextId = 1L
    private var openedAt = 0L
    private var sample: RoomMonitor.Sample? = null
    private val readingsState = MutableStateFlow<RoomReadings?>(null)

    /** The last measurements of the open room, for its readings only; null while there is none. */
    val readings: StateFlow<RoomReadings?> = readingsState.asStateFlow()

    /** The user ends the room: being out of its sound from now on is no absence. */
    private var ending = false

    /** The member whose call [place] has the SDK place: its OutgoingInit arrives from inside it. */
    private var placing: Member? = null

    /** Failed set-ups of the kinds that say something about the PBX, in a row (RoomPacing). */
    private var failureRun = 0
    private val pacer = Runnable { pump() }

    /** Short calls in a row ([RoomPacing.shortRun]), when the last one ended, and until when nobody new is called after one. */
    private var shortRun = 0
    private var lastShortAt = 0L
    private var pausedUntil = 0L
    private val shortRunOver = Runnable { closeShortRun() }

    /** True while [place] has the SDK place a call: its OutgoingInit arrives from inside it. */
    val inviting: Boolean get() = placing != null

    val isOpen: Boolean get() = conference != null

    fun isMember(call: Call): Boolean = find(call) != null

    /** The members that are in the room, on their way in, or waiting to be called. */
    private val present: Int get() = members.count { it.queued || it.call?.let(::isEnded) == false }

    var muted: Boolean
        get() = conference?.microphoneMuted ?: false
        set(value) {
            conference?.microphoneMuted = value
        }

    /**
     * Brings the user back into the room's sound. The SDK takes the user out of it when another
     * app takes the sound (a phone call of the mobile network, say) and leaves the members talking
     * among themselves; nothing brings the user back by itself.
     *
     * @return a user readable error, or null
     */
    fun rejoin(): Phrase? {
        val conference = conference ?: return FAILED
        if (conference.isIn) return null
        return if (conference.enter() == 0) null else Phrase.of(R.string.room_rejoin_failed)
    }

    /** The route the room's sound is played on; null while it is not known. */
    val route: AudioRoute? get() = CallSupport.routeOf(conference?.outputAudioDevice?.type)

    /** The line the room calls out on. */
    val account: Account? get() = conference?.account

    /** The line the room's calls go out on: that of its calls, else the default one, as for a call of the dial pad. */
    private fun line(): Account? = conference?.account ?: core.defaultAccount

    /** Null when calls may go out on [line]; else why not, as CallManager says it. */
    private fun lineRefusal(line: Account?): Phrase? = when {
        line == null -> Phrase.of(R.string.call_add_account_first)
        !lineOn(line) -> Phrase.of(R.string.call_line_off)
        else -> null
    }

    /**
     * Opens the room with [calls], which are up (connected or on hold). Their video ends.
     *
     * @return a user readable error, or null when the room is open
     */
    fun open(calls: List<Call>, muted: Boolean): Phrase? {
        if (conference != null) return null
        // Asked first, so that nothing has to be undone: the SDK takes a call that is up in these states only.
        if (calls.any { it.state !in JOINABLE }) return Phrase.of(R.string.room_not_now)
        val params = core.createConferenceParams(null)
        params.isVideoEnabled = false
        // A room is a room: it stays with a single member, to whom the user can add more.
        params.isOneParticipantConferenceEnabled = true
        calls.firstNotNullOfOrNull { it.params.account }?.let { params.account = it }
        val created = core.createConferenceWithParams(params) ?: return FAILED
        // One by one: addParticipants answers 1 for success and 0 for failure (liblinphone 5.5,
        // c-conference.cpp), unlike addParticipant, which answers 0 and -1.
        val added = ArrayList<Call>()
        for (call in calls) {
            if (created.addParticipant(call) != 0) {
                undo(created, added)
                return FAILED
            }
            added += call
        }
        conference = created
        core.maxCalls = RoomRules.MAX_CALLS
        created.microphoneMuted = muted
        openedAt = SystemClock.elapsedRealtime()
        calls.forEach(::add)
        monitor.start(members = ::memberCalls) {
            sample = it
            readingsState.value = RoomReadings(it.device, it.links.filterKeys { id -> id in it.health.poorMembers })
            onChanged()
        }
        return null
    }

    /**
     * Takes the calls out of a room that could not be opened without ending them. Taken out, a
     * call the SDK took into it as it was stays up, on hold, and the conference ends by itself
     * once it is empty (ServerConference::removeParticipant, liblinphone 5.5); terminate() would
     * hang them up.
     */
    @Suppress("DEPRECATION") // The call itself, not a participant found by its address: two calls to one number stay apart.
    private fun undo(created: Conference, added: List<Call>) {
        if (added.isEmpty()) created.terminate() else added.forEach { created.removeParticipant(it) }
    }

    /**
     * Calls [targets] into the room: numbers or addresses, on the line of the room, a few at a
     * time (RoomPacing); those whose turn has not come yet wait in the list. Those already in it
     * are left out; more than fit are refused.
     *
     * @return a user readable error, or null when the members were taken in
     */
    fun invite(targets: List<String>): Phrase? {
        if (conference == null) return FAILED
        val taken = members.filter { it.queued || it.call?.let(::isEnded) == false }
            .mapNotNull { member -> member.call?.remoteAddress?.username ?: member.target?.let(Formatters::userPart) }
        val wanted = RoomRules.newTargets(targets, taken)
        if (wanted.isEmpty()) return Phrase.of(if (targets.any { it.isNotBlank() }) R.string.room_already_in else R.string.call_enter_number)
        RoomRules.refusal(present, wanted.size, limit())?.let { return it }
        val account = line()
        lineRefusal(account)?.let { return it }
        if (wanted.any { account?.normalizeSipUri(DialTarget.of(it)) == null }) return Phrase.of(R.string.call_invalid_address)
        // One that was held back is called again in its own row, once its Retry-After has passed.
        val rows = wanted.associateWith { target ->
            members.firstOrNull { it.heldBack != null && it.call == null && it.target?.let(Formatters::userPart) == Formatters.userPart(target) }
        }
        if (rows.values.any { it != null && SystemClock.elapsedRealtime() < it.retryAt }) return RETRY_LATER
        for (target in wanted) {
            val again = rows[target]
            if (again != null) {
                again.heldBack = null
                again.queued = true
            } else {
                members += Member(nextId++, null, SideCall(name = Formatters.userPart(target), address = target)).apply {
                    this.target = target
                    queued = true
                }
            }
        }
        failureRun = 0
        pump()
        onChanged()
        return null
    }

    /**
     * Calls a member that was held back again ([com.sky.siplink.core.model.MemberState.HELD_BACK]),
     * once its earlier call, if any, is gone.
     *
     * @return a user readable error, or null
     */
    fun retry(id: Long): Phrase? {
        val member = members.firstOrNull { it.id == id && it.heldBack != null && it.call == null } ?: return null
        if (SystemClock.elapsedRealtime() < member.retryAt) return RETRY_LATER
        RoomRules.refusal(present, 1, limit())?.let { return it }
        member.heldBack = null
        member.queued = true
        failureRun = 0
        pump()
        onChanged()
        return null
    }

    /**
     * Starts the set-ups whose turn has come (none for a moment after a short call), and looks
     * again once the oldest one may have waited long enough.
     */
    private fun pump() {
        handler.removeCallbacks(pacer)
        val conference = conference ?: return
        val now = SystemClock.elapsedRealtime()
        if (now >= pausedUntil) {
            val inSetup = members.filter { member ->
                !member.answered && member.call?.let(::isEnded) == false && RoomPacing.holdsBack(member.placedAt, now)
            }
            repeat(RoomPacing.free(inSetup.size)) {
                val next = members.firstOrNull { it.queued } ?: return@repeat
                place(conference, next)
            }
        }
        if (members.any { it.queued }) handler.postDelayed(pacer, PACE_CHECK_MS)
    }

    private fun place(conference: Conference, member: Member) {
        member.queued = false
        val account = line()
        val address = account?.normalizeSipUri(DialTarget.of(member.target.orEmpty()))
        val params = core.createCallParams(null)
        // The SDK has no answer for a call the core refuses while it invites: it must not come to that.
        val full = params == null || core.callsNb + 1 >= core.maxCalls
        if (account == null || !lineOn(account) || address == null || params == null || full) {
            member.heldBack = lineRefusal(account) ?: Phrase.of(if (address == null) R.string.call_invalid_address else RoomPacing.Failure.LINE_FULL.label)
            return
        }
        params.account = account
        CallVideoSupport.apply(params, video = false, camera = false)
        member.placedAt = SystemClock.elapsedRealtime()
        member.answered = false
        placing = member
        try {
            // One at a time through the same call as for many: the user stays in the room meanwhile.
            conference.inviteParticipants(arrayOf(address), params)
        } finally {
            placing = null
        }
        // No call came of it, whatever the SDK answered: the row stays, to be called again or taken off.
        if (member.call == null) member.heldBack = Phrase.of(R.string.call_failed)
    }

    /**
     * What happened to the call of a member being called: an answer frees its set-up for the next
     * one; a failure that says something about the PBX keeps the member in the list with the
     * reason, for the user to call again, and may stop the calling of the others.
     */
    fun onStateChanged(call: Call, state: Call.State?) {
        val all = find(call) ?: return
        all.sideStale = true
        if (state == Call.State.Connected && all.connectedAt == 0L) all.connectedAt = SystemClock.elapsedRealtime()
        if (state == Call.State.End || state == Call.State.Error) {
            // Another call took its place ([replace]): its end says nothing about the PBX.
            if (all.replaced || replacedByAnother(all)) {
                all.replaced = true
                return
            }
            if (state == Call.State.End && onShortCall(all)) return
        }
        val member = all.takeIf { !it.answered } ?: return
        when (state) {
            Call.State.OutgoingRinging, Call.State.OutgoingEarlyMedia, Call.State.Connected, Call.State.StreamsRunning -> {
                member.answered = true
                failureRun = 0
            }
            Call.State.Error, Call.State.End -> {
                member.answered = true
                val failure = RoomPacing.failureOf(call.errorInfo.protocolCode)
                if (failure != null) {
                    val retryAt = RoomPacing.retryAt(SystemClock.elapsedRealtime(), call.errorInfo.retryAfter)
                    member.heldBack = Phrase.of(failure.label)
                    member.retryAt = retryAt
                    failureRun++
                    val connected = members.any { it.call?.state == Call.State.StreamsRunning }
                    val verdict = RoomPacing.afterFailure(failure, failureRun, connected)
                    if (verdict.stop) holdBack(Phrase.of(verdict.label ?: failure.label), retryAt)
                }
            }
            else -> return
        }
        // Not from inside the SDK's report of a call: the next calls are placed right after it.
        handler.removeCallbacks(pacer)
        handler.post(pacer)
    }

    /**
     * A member the room called hung up right after answering, while others talk: the PBX's
     * announcement that the line has no more calls (FreePBX answers and plays one instead of
     * refusing the call), or a person who hung up at once. One such call only pauses the calling
     * a moment and keeps its row while the run lasts; the second in a row ([RoomPacing.shortRun])
     * is taken for a full line: the members of the run keep their rows to be called again, and
     * nobody else is called.
     *
     * @return true when it was such a call
     */
    private fun onShortCall(member: Member): Boolean {
        if (member.target == null || member.connectedAt == 0L) return false
        val now = SystemClock.elapsedRealtime()
        val othersTalk = members.any { it !== member && it.call?.state == Call.State.StreamsRunning }
        if (!RoomPacing.endedLikeALimit(now - member.connectedAt, member.endedByUser, othersTalk)) return false
        // A call answered since the last short one and up for a while since: the line took it.
        val kept = members.any {
            it !== member && it.connectedAt > lastShortAt && it.call?.let(::isEnded) == false &&
                now - it.connectedAt >= RoomPacing.SHORT_CALL_MS
        }
        shortRun = RoomPacing.shortRun(shortRun, now - lastShortAt, kept)
        lastShortAt = now
        member.endedShort = true
        if (RoomPacing.lineFullAfter(shortRun)) {
            val label = Phrase.of(RoomPacing.Failure.LINE_FULL.label)
            members.filter { it.endedShort && it.heldBack == null }.forEach { it.heldBack = label }
            holdBack(label, retryAt = 0L)
        } else {
            pausedUntil = now + RoomPacing.SHORT_CALL_PAUSE_MS
        }
        handler.removeCallbacks(shortRunOver)
        handler.postDelayed(shortRunOver, RoomPacing.SHORT_RUN_MS)
        return true
    }

    /** No short call for [RoomPacing.SHORT_RUN_MS]: the run is over, and the rows of its calls that were not taken for a full line go. */
    private fun closeShortRun() {
        shortRun = 0
        val gone = members.filter { it.endedShort && it.heldBack == null && it.call == null }
        members.forEach { it.endedShort = false }
        if (members.removeAll(gone.toSet())) onChanged()
    }

    /** Nobody else is called: those still waiting keep [label] until the user calls them again. */
    private fun holdBack(label: Phrase, retryAt: Long) {
        members.filter { it.queued }.forEach {
            it.queued = false
            it.heldBack = label
            it.retryAt = retryAt
        }
    }

    /** A call placed by [place] begins: it is the call of the member whose turn it is. */
    fun onInvited(call: Call) {
        val member = placing
        if (member == null) {
            add(call)
        } else {
            member.call = call
            member.side = party(call)
        }
    }

    /**
     * Answers the ringing [call] into the room, audio only.
     *
     * @return a user readable error, or null when it was answered
     */
    fun join(call: Call): Phrase? {
        val conference = conference ?: return FAILED
        RoomRules.refusal(present, 1, limit())?.let { return it }
        // Taken into the room first: answered then, it does not take the user out of the room.
        if (conference.addParticipant(call) != 0) return Phrase.of(R.string.room_join_failed)
        add(call)
        val params = core.createCallParams(call)
        if (params == null) {
            call.accept()
        } else {
            CallVideoSupport.apply(params, video = false, camera = false)
            call.acceptWithParams(params)
        }
        onChanged()
        return null
    }

    /**
     * [call] takes the place of the member's call [old]: the SDK placed it on a REFER received on
     * [old], which ends once [call] is up ([onStreaming]), or it came in with Replaces aimed at
     * [old], and the SDK answers it and ends [old] by itself. In the place of a call that is gone,
     * or that another one takes already, it is a member more and needs room for one.
     *
     * @return false when the room has no place for it; the caller ends it then
     */
    fun replace(call: Call, old: Call): Boolean {
        val conference = conference ?: return false
        val takesItsPlace = find(old)?.let { it.call?.let(::isEnded) == false && !replacedByAnother(it) } == true
        if (!takesItsPlace && RoomRules.refusal(present, 1, limit()) != null) return false
        if (conference.addParticipant(call) != 0) return false
        add(call)
        find(call)?.replaces = old
        return true
    }

    /** Whether another member's call that is not over takes the place of [member]'s ([replace]). */
    private fun replacedByAnother(member: Member): Boolean {
        val call = member.call ?: return false
        return members.any { it !== member && it.replaces?.nativePointer == call.nativePointer && it.call?.let(::isEnded) == false }
    }

    /**
     * Ends the call of the member [id]; the others stay. Without others, the room ends with it.
     * A member that waits to be called, or was held back, is taken off the list instead.
     */
    fun hangUp(id: Long) {
        val member = members.firstOrNull { it.id == id } ?: return
        if (member.queued || member.heldBack != null) {
            member.queued = false
            member.heldBack = null
            member.endedShort = false
            // A call that is still being released takes the row with it ([onReleased]).
            if (member.call == null) members.remove(member)
            onChanged()
            return
        }
        val call = member.call?.takeIf { !isEnded(it) } ?: return
        member.endedByUser = true
        if (present == 1) leaveSoundCard()
        call.terminate()
    }

    /** Ends the room, every call in it with it; the SDK closes the conference after the last call. */
    fun end() {
        members.forEach {
            it.queued = false
            it.endedByUser = true
        }
        leaveSoundCard()
        members.mapNotNull { it.call }.filter { !isEnded(it) }.forEach { it.terminate() }
    }

    fun setRoute(route: AudioRoute): Boolean {
        val conference = conference ?: return false
        val (output, input) = CallSupport.devicesFor(core, route) ?: return false
        conference.outputAudioDevice = output
        input?.let { conference.inputAudioDevice = it }
        return true
    }

    /** The streams of a member's [call] run: the call it takes the place of is not needed any more. */
    fun onStreaming(call: Call) {
        val member = find(call) ?: return
        val old = member.replaces ?: return
        member.replaces = null
        find(old)?.replaced = true
        if (!isEnded(old)) old.terminate()
    }

    /** The members may be named otherwise now (the directory found names, the feature codes changed): looked up again. */
    fun onSidesChanged() = members.forEach { it.sideStale = true }

    /** A member's [call] ended for [reason]. */
    fun onEnded(call: Call, reason: Phrase?) {
        find(call)?.endReason = reason
    }

    /**
     * Takes the user's side of the room off the sound card while a call is still up, before the
     * user ends the last one. The SDK stops the room's sound on the main thread, and its sound
     * card player keeps restarting itself until it is stopped: left to the SDK, after the last
     * call ended, that held the main thread for 2 s and more on the test phone (three tries out of
     * three); stopped first, for 1.2 to 1.4 s. (When the last member hangs up, the SDK gets there
     * before the app hears of it.)
     */
    private fun leaveSoundCard() {
        ending = true
        conference?.takeIf { it.isIn }?.leave()
    }

    /**
     * A member's [call] is gone: its row stays a moment with the reason, until the user calls the
     * member again when it was held back, and while its run lasts after a short call; it goes at
     * once when another call took its place. When it was the last call, the room closes, and
     * those that were still to be called are not.
     *
     * @return true when the room closed
     */
    fun onReleased(call: Call): Boolean {
        val member = find(call) ?: return false
        member.call = null
        when {
            member.replaced -> members.remove(member)
            member.heldBack != null || member.endedShort -> Unit
            else -> handler.postDelayed({
                if (members.remove(member)) onChanged()
            }, RoomRules.LEFT_LINGER_MS)
        }
        if (members.any { it.call != null }) return false
        close()
        return true
    }

    /**
     * What the call screen shows of the room; null while there is none. The other side of a
     * member's call is looked up only after its state changed, names were found or the feature
     * codes changed: with many members that takes the SDK most of the time of a snapshot, taken
     * every two seconds.
     */
    fun snapshot(): RoomInfo? {
        if (conference == null) return null
        val health = sample?.health
        val poor = health?.poorMembers.orEmpty()
        return RoomInfo(
            members = members.map { member ->
                val call = member.call
                if (call != null && member.sideStale) {
                    member.side = party(call)
                    member.sideStale = false
                }
                RoomMember(
                    id = member.id,
                    name = member.side.name,
                    address = member.side.address,
                    state = when {
                        member.queued -> MemberState.QUEUED
                        member.heldBack != null -> MemberState.HELD_BACK
                        call == null -> MemberState.LEFT
                        else -> CallSupport.memberStateOf(call)
                    },
                    endReason = (member.heldBack ?: member.endReason)?.let(render),
                    poorLink = member.id in poor,
                    signal = RoomHealth.signalBars(sample?.links?.get(member.id)?.quality),
                )
            },
            limit = limit(),
            openedAt = openedAt,
            away = !ending && conference?.isIn == false,
            warnings = health?.warnings.orEmpty(),
        )
    }

    private fun add(call: Call) {
        if (find(call) == null) members += Member(nextId++, call, party(call))
    }

    private fun find(call: Call): Member? = members.firstOrNull { member -> member.call?.nativePointer == call.nativePointer }

    private fun memberCalls(): Map<Long, Call> = members.mapNotNull { member -> member.call?.let { member.id to it } }.toMap()

    private fun close() {
        monitor.stop()
        sample = null
        readingsState.value = null
        ending = false
        // The SDK ends a conference by itself once its last participant is gone.
        conference = null
        members.clear()
        shortRun = 0
        lastShortAt = 0L
        pausedUntil = 0L
        handler.removeCallbacksAndMessages(null)
        core.maxCalls = RoomRules.MAX_CALLS_WITHOUT_ROOM
    }

    private companion object {
        val FAILED = Phrase.of(R.string.room_create_failed)
        val RETRY_LATER = Phrase.of(R.string.room_retry_later)

        /**
         * The states of a call that is up in which the SDK takes it into a conference
         * (ServerConference::addParticipant, liblinphone 5.5): not while its media are being
         * agreed on anew, nor while the other side holds it.
         */
        val JOINABLE = setOf(Call.State.StreamsRunning, Call.State.Paused, Call.State.Pausing, Call.State.Resuming)

        /** How often the calling looks again whether a set-up has waited long enough for the next one to start. */
        const val PACE_CHECK_MS = 1_000L
    }
}
