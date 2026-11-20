package com.sky.siplink.core

import com.sky.siplink.core.CallSupport.isRinging
import com.sky.siplink.core.CallSupport.phaseOf
import com.sky.siplink.core.logic.AssertedParties
import com.sky.siplink.core.logic.PartyName
import com.sky.siplink.core.model.AudioRoute
import com.sky.siplink.core.model.CallInfo
import com.sky.siplink.core.model.CallPhase
import com.sky.siplink.core.model.RoomInfo
import com.sky.siplink.core.model.SideCall
import com.sky.siplink.core.model.SupervisionMode
import org.linphone.core.Call
import org.linphone.core.Core

/** Facts about a call that the SIP core does not keep for us ([CallManager]). */
internal class CallMeta(
    var intercom: Boolean = false,
    var supervision: SupervisionMode? = null,
    var connectedAt: Long? = null,
    /** The sound went to the speaker for an intercom call, or to [chosenRoute] once the sound ran: done once. */
    var speakerApplied: Boolean = false,
    var endReason: String? = null,
    /** The call had video the last time it was looked at (sound moves to the speaker as video starts). */
    var hadVideo: Boolean = false,
    /** It came in next to another call or the room and waited there: it is never answered by itself (CallRules, rule 2). */
    var waited: Boolean = false,
    /** Where the user last sent the sound of the call; null: nowhere by hand. */
    var chosenRoute: AudioRoute? = null,
)

/** What the call screen shows of one call ([CallInfo]), for [CallManager]. Main thread only. */
internal class CallDescriber(
    private val core: Core,
    private val settings: SettingsStore,
    private val accounts: AccountManager,
    private val names: NameResolver,
) {
    /** Whom the PBX asserted the other side of each call to be. */
    private val asserted = AssertedParties()

    /** [call] reached [state]: the message that brought it may assert who the other side is, and a released call is forgotten. */
    fun onStateChanged(call: Call, state: Call.State?) {
        if (state == Call.State.Released) asserted.forget(call.nativePointer) else assertedOf(call)
    }

    private fun assertedOf(call: Call): SideCall? = asserted.of(
        call.nativePointer,
        CallSupport.asserted(call),
        ringingIn = phaseOf(call) == CallPhase.INCOMING,
        released = call.state == Call.State.Released,
    )

    /** The other side of a call, by its name in the directory when it has one there. */
    fun partyOf(call: Call): SideCall {
        val party = assertedOf(call) ?: CallSupport.side(call.remoteAddress)
        val outgoing = call.dir == Call.Dir.Outgoing
        val line = call.params.account?.let(accounts::idOf)
        val name = PartyName.of(party.name, party.address, outgoing, settings.current.features) { names.nameOf(it, !outgoing, line) }
        return party.copy(name = name)
    }

    /**
     * Snapshot of a single call, without the second-call fields.
     *
     * @param lastRoute where the sound went in the snapshot before, for a call that does not tell
     */
    fun describe(call: Call, meta: CallMeta, lastRoute: AudioRoute?, video: VideoController, recorder: CallRecorder): CallInfo {
        val party = partyOf(call)
        val ringing = isRinging(call)
        val videoAllowed = settings.current.videoCalls
        return CallInfo(
            phase = phaseOf(call),
            remoteName = party.name,
            remoteAddress = party.address,
            accountLabel = CallSupport.lineOf(call.params.account),
            incoming = call.dir == Call.Dir.Incoming,
            intercom = meta.intercom,
            muted = call.microphoneMuted,
            route = CallSupport.routeOf(call.outputAudioDevice?.type) ?: lastRoute ?: AudioRoute.EARPIECE,
            availableRoutes = CallSupport.availableRoutes(core),
            connectedAt = meta.connectedAt,
            endReason = meta.endReason,
            supervision = meta.supervision,
            video = if (videoAllowed && !ringing) CallVideoSupport.stateOf(call) else null,
            videoOffer = videoAllowed && (if (ringing) CallVideoSupport.offered(call) else video.isRequesting(call)),
            videoAllowed = videoAllowed,
            recordable = recorder.recordable(call),
            recordingSince = recorder.startedAt(call),
            noSound = accounts.media.hearsNothing(call),
        )
    }

    /** The room in place of a call, with the call that waits beside it ([waiting]). */
    fun roomInfo(room: RoomInfo, conference: ConferenceRoom, previous: CallInfo?, waiting: SideCall?) = CallInfo(
        phase = CallPhase.CONNECTED,
        remoteName = ROOM_NAME,
        remoteAddress = "",
        accountLabel = CallSupport.lineOf(conference.account),
        incoming = false,
        intercom = false,
        muted = conference.muted,
        route = conference.route ?: previous?.route ?: AudioRoute.EARPIECE,
        availableRoutes = CallSupport.availableRoutes(core),
        connectedAt = room.openedAt,
        waitingCall = waiting,
        room = room,
    )

    private companion object {
        /** What the call screen and the notification call the conference room. */
        const val ROOM_NAME = "会议室"
    }
}
