package com.sky.siplink.core

import android.os.Handler
import android.os.SystemClock
import com.sky.siplink.R
import com.sky.siplink.core.CallLegs.Companion.live
import com.sky.siplink.core.CallLegs.Companion.same
import com.sky.siplink.core.CallSupport.isEnded
import com.sky.siplink.core.CallSupport.isRinging
import com.sky.siplink.core.logic.AutoAnswerRules
import com.sky.siplink.core.logic.DialTarget
import com.sky.siplink.core.logic.IncomingPolicy
import com.sky.siplink.core.logic.NameLookup
import com.sky.siplink.core.logic.VideoRules
import com.sky.siplink.core.model.AudioRoute
import com.sky.siplink.core.model.CallInfo
import com.sky.siplink.core.model.CallPhase
import com.sky.siplink.core.model.Phrase
import org.linphone.core.Call
import org.linphone.core.Core
import org.linphone.core.Factory
import org.linphone.core.Reason

/** What [CallFollower] needs of the screen [CallManager] keeps: the call it shows, and the app's hooks. */
internal interface CallFeed {
    /** The app's hooks, called on the main thread. */
    val host: CallManager.Host

    /** The snapshot on screen now. */
    var shown: CallInfo?

    /** Publishes the snapshot of the calls as they are; null when there is none to show. */
    fun publish(): CallInfo?

    /** Snapshot of a single call, without the second-call fields. */
    fun describe(call: Call): CallInfo
}

/**
 * Follows the reports of the SIP core on the calls of [CallManager]: places a call that came in,
 * was placed or ended into [legs], hands each report on to the parts that care, and keeps the
 * "call ended" state on screen for a moment once the last call is gone. Core thread only.
 */
internal class CallFollower(
    private val core: Core,
    private val settings: SettingsStore,
    private val texts: AppTexts,
    private val legs: CallLegs,
    private val feed: CallFeed,
    private val handler: Handler,
    private val parts: CallParts,
) {
    private val room get() = parts.room
    private val video get() = parts.video
    private val recorder get() = parts.recorder
    private val transfer get() = parts.transfer
    private val retry get() = parts.retry
    private val autoAnswer get() = parts.autoAnswer
    private val host get() = feed.host

    /** A call out was placed again over the other server ([CallRetry]): its entry in the history goes, by its Call-ID. */
    var onSuperseded: ((String) -> Unit)? = null

    fun onCallStateChanged(call: Call, callState: Call.State?, message: String) {
        parts.describer.onStateChanged(call, callState)
        if (retry.isSuperseded(call)) {
            // Placed again: its successor is on the screen, and it leaves nothing behind.
            if (callState == Call.State.Released) {
                legs.forget(call)
                video.onReleased(call)
                recorder.onReleased(call)
                retry.onReleased(call)
                call.callLog.callId?.let { onSuperseded?.invoke(it) }
            }
            return
        }
        room.onStateChanged(call, callState)
        when (callState) {
            Call.State.IncomingReceived -> onIncomingReceived(call)
            Call.State.OutgoingInit -> {
                onOutgoingInit(call)
                video.applyCamera(call)
            }

            Call.State.Connected, Call.State.StreamsRunning -> {
                val meta = legs.metaOf(call)
                if (meta.connectedAt == null) meta.connectedAt = SystemClock.elapsedRealtime()
                if (same(call, legs.current) || same(call, legs.other)) host.dismissIncomingCall()
                if (callState == Call.State.StreamsRunning) {
                    video.onStreaming(call)
                    recorder.onStreaming(call, inRoom = room.isMember(call))
                    room.onStreaming(call)
                    if (same(call, legs.current)) onCurrentStreaming(call, meta)
                }
                feed.publish()
            }

            Call.State.Error, Call.State.End -> {
                val meta = legs.metaOf(call)
                meta.endReason = CallSupport.endReason(call, callState, meta.connectedAt != null, message)
                room.onEnded(call, meta.endReason)
                if (same(call, legs.current) || same(call, legs.other)) host.dismissIncomingCall()
                feed.publish()
            }

            // The calls of the room are never the current one: video they are offered is declined.
            Call.State.UpdatedByRemote -> video.onUpdatedByRemote(call, onScreen = same(call, legs.current))
            Call.State.Released -> onReleased(call)
            else -> feed.publish()
        }
    }

    private fun onIncomingReceived(call: Call) {
        // A call that replaces one of ours (attended transfer done by the remote side) is answered
        // by the core itself, which ends the old one; we only have to follow it. In the room it
        // takes the member's place, if there is room for it.
        val replaced = call.replacedCall
        if (replaced != null && (same(replaced, legs.current) || same(replaced, legs.other))) {
            if (same(replaced, legs.current)) legs.current = call else legs.other = call
            feed.publish()
            return
        }
        if (replaced != null && room.isMember(replaced)) {
            if (room.replace(call, replaced)) feed.publish() else call.decline(Reason.Busy)
            return
        }

        val decision = IncomingPolicy.decide(
            settings.current,
            hasActiveCall = live(legs.current) != null || room.isOpen,
            hasSecondCall = legs.other != null,
            anonymous = NameLookup.isWithheld(CallSupport.plain(call.remoteAddress)),
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
                legs.other = call
                legs.metaOf(call).intercom = CallSupport.isIntercom(call)
                legs.metaOf(call).waited = true
                feed.publish()
                // Without a visible call screen the user would not notice the second call.
                if (!host.isAppVisible) host.showIncomingCall(feed.describe(call))
            }

            is IncomingPolicy.Decision.Ring -> {
                // A call that already ended but is still lingering on screen gives way.
                legs.generation++
                legs.current = call
                resetScenario()
                val meta = legs.metaOf(call)
                meta.intercom = CallSupport.isIntercom(call)
                val info = feed.publish() ?: return
                ring(call, info)
                val target = decision.forwardTo
                if (target != null) {
                    handler.postDelayed({
                        if (same(call, legs.current) && isRinging(call)) forward(call, target)
                    }, decision.forwardAfterSeconds * 1000L)
                }
            }
        }
    }

    private fun ring(call: Call, info: CallInfo) {
        val meta = legs.metaOf(call)
        autoAnswer.start(call, meta.intercom, AutoAnswerRules.delayMs(settings.current, meta.intercom, cameDuringCall = meta.waited))
        host.showIncomingCall(info)
    }

    private fun forward(call: Call, target: String) {
        // A bare number is completed with the domain of the account that was called.
        val address = (call.params.account ?: core.defaultAccount)?.normalizeSipUri(DialTarget.of(target))
            ?: core.interpretUrl(target, false)
        if (address == null || call.redirectTo(address) != 0) call.decline(Reason.Busy)
    }

    private fun onOutgoingInit(call: Call) {
        val kind = legs.pending
        val active = live(legs.current)
        val referrer = call.transfererCall
        val replaced = retry.replacing
        when {
            // The same call, placed again over the other server: it takes the place of the one before.
            replaced != null && same(replaced, legs.current) -> {
                legs.current = call
                retry.onReplacement(call)
            }

            // Placed by the room, or by the PBX in place of a call of the room (if there is room for it).
            room.inviting -> room.onInvited(call)
            referrer != null && room.isMember(referrer) -> if (!room.replace(call, referrer)) refuse(call)

            // Not started by us: the core follows a REFER received on one of our calls, one at a
            // time (a second one would leave the first new call without a place on screen).
            referrer != null && legs.referredFrom != null -> refuse(call)
            referrer != null -> {
                if (same(referrer, legs.other)) legs.other = null
                legs.referredFrom = referrer
                legs.current = call
                legs.metaOf(call).intercom = legs.metaOf(referrer).intercom
            }

            active == null -> {
                legs.generation++
                legs.current = call
                resetScenario()
                legs.metaOf(call).supervision = kind?.supervision
            }

            // A second call next to the active one: the consultation leg of an attended transfer.
            else -> {
                if (legs.other == null) legs.other = active
                legs.current = call
                legs.consulting = true
            }
        }
        feed.publish()
    }

    /** Ends a call the core placed on a REFER that has no place; not from inside the core's report of it. */
    private fun refuse(call: Call) {
        handler.post { if (!isEnded(call)) call.terminate() }
    }

    private fun onCurrentStreaming(call: Call, meta: CallMeta) {
        // The call we were referred to is up: the original one is not needed any more.
        legs.referredFrom?.let { old ->
            legs.referredFrom = null
            if (!isEnded(old)) old.terminate()
        }
        // A route chosen before the sound ran is the SDK's to forget as the sound starts: chosen again.
        meta.chosenRoute?.takeIf { !meta.speakerApplied }?.let { route ->
            meta.speakerApplied = true
            CallSupport.applyRoute(core, call, route)
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
        retry.onReleased(call)
        val meta = legs.forget(call)
        video.onReleased(call)
        recorder.onReleased(call)
        when {
            same(call, legs.current) -> {
                host.dismissIncomingCall()
                autoAnswer.cancel()
                val next = live(legs.other) ?: live(legs.referredFrom)
                if (next != null) {
                    // The remaining call takes over the screen.
                    legs.current = next
                    if (same(next, legs.other)) legs.other = null else legs.referredFrom = null
                    legs.consulting = false
                    when {
                        isRinging(next) -> feed.publish()?.let { ring(next, it) }
                        // Our own hold is lifted, unless that call is just being transferred away.
                        next.state == Call.State.Paused && !transfer.moving -> next.resume()
                    }
                    feed.publish()
                } else {
                    finish(meta)
                }
            }

            same(call, legs.other) -> {
                legs.other = null
                legs.consulting = false
                feed.publish()
            }

            same(call, legs.referredFrom) -> legs.referredFrom = null

            room.isMember(call) -> if (room.onReleased(call)) onRoomClosed() else feed.publish()
        }
    }

    /** The last member left the room, or the user ended it: a call that waits takes over, else the screen ends. */
    fun onRoomClosed() {
        val next = live(legs.other)
        if (next == null) {
            finish(null)
            return
        }
        legs.current = next
        legs.other = null
        val info = feed.publish() ?: return
        if (isRinging(next)) ring(next, info)
    }

    /** Last call gone: keep the "call ended" state on screen for a moment, then clear it. */
    fun finish(meta: CallMeta?) {
        val last = feed.shown
        legs.current = null
        legs.other = null
        legs.referredFrom = null
        val reason = transfer.endReason() ?: meta?.endReason ?: legs.endReason ?: Phrase.of(R.string.call_ended)
        legs.endReason = reason
        resetScenario()
        feed.shown = last?.copy(
            phase = CallPhase.ENDED,
            endReason = texts.render(reason),
            heldCall = null,
            waitingCall = null,
            consulting = false,
            room = null,
        )
        host.onCallActivityChanged()
        val token = ++legs.generation
        handler.postDelayed({ if (token == legs.generation && legs.current == null) feed.shown = null }, END_LINGER_MS)
    }

    /** The language of the app changed: an ended call that lingers on screen says why in it. */
    fun onLanguageChanged() {
        val shown = feed.shown?.takeIf { it.phase == CallPhase.ENDED } ?: return
        feed.shown = shown.copy(endReason = legs.endReason?.let(texts::render))
    }

    /** A new scenario starts on screen: no transfer, consultation or auto answer of the last one carries over. */
    fun resetScenario() {
        legs.consulting = false
        transfer.reset()
        autoAnswer.cancel()
    }

    private companion object {
        const val END_LINGER_MS = 1400L

        /** SIP status for a caller who withholds the number where that is not accepted. */
        const val ANONYMITY_DISALLOWED = 433
    }
}
