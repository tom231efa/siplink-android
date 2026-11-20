package com.sky.siplink.core

import android.os.SystemClock
import com.sky.siplink.core.logic.CallTally
import com.sky.siplink.core.model.CallMediaSummary
import org.linphone.core.Account
import org.linphone.core.Call
import org.linphone.core.CallStats
import org.linphone.core.Core
import org.linphone.core.CoreListenerStub
import org.linphone.core.MediaDirection
import org.linphone.core.Reason
import org.linphone.core.StreamType

/**
 * Follows the sound of the calls with the counters of the SIP core ([CallTally]), and keeps how
 * the last call of each account went: the line checks show it. Main thread only, as the core's
 * listeners are.
 *
 * @param idOf the id of an account
 * @param onFinished a call whose sound ran ended on an account, or a call out failed before it ran
 */
internal class CallMediaWatch(
    private val core: Core,
    private val idOf: (Account) -> String,
    private val onFinished: (Account, Finished) -> Unit,
) {
    /**
     * A call at its end.
     *
     * @param summary how its sound went; null: too short to tell, or it never ran
     * @param contact the host of the other side's Contact
     * @param recordRoute the Record-Route of the other side's message that set the call up (the
     *   answer to a call out, the INVITE of one that came in); null without one
     * @param outgoing the call went out
     * @param setupMs from the start of a call out to its INVITE (the SIP core gathers its ICE
     *   candidates in between); null for a call that came in
     * @param iceOffered a call out offered ICE candidates: its account's NAT policy had ICE as it started
     * @param judged its sound tells something of the line ([CallTally.judged])
     * @param failedWith the SIP status of a call out that failed before its sound ran; null for one whose sound ran
     */
    class Finished(
        val summary: CallMediaSummary?,
        val contact: String?,
        val recordRoute: String?,
        val outgoing: Boolean,
        val setupMs: Long?,
        val iceOffered: Boolean,
        val judged: Boolean,
        val failedWith: Int?,
    )

    /** A call whose sound runs: its counters, and what was known of it as its sound started. */
    private class Running(
        val tally: CallTally,
        val contact: String?,
        val recordRoute: String?,
        val outgoing: Boolean,
        val setupMs: Long?,
        val iceOffered: Boolean,
    )

    /** Whether a call is an intercom or paging call: its far end is often muted, or sends alone. */
    var isIntercom: (Call) -> Boolean = CallSupport::isIntercom

    private val running = HashMap<Long, Running>()

    /** When the calls out started, until they sent their INVITE; then how long that took. By native pointer. */
    private val startedOut = HashMap<Long, Long>()
    private val setups = HashMap<Long, Long>()

    /** Whether the calls out offered ICE, from their start. */
    private val iceOffered = HashMap<Long, Boolean>()
    private val last = HashMap<String, CallMediaSummary>()

    private val listener = object : CoreListenerStub() {
        override fun onCallStateChanged(core: Core, call: Call, state: Call.State?, message: String) {
            val pointer = call.nativePointer
            when (state) {
                Call.State.OutgoingInit -> {
                    startedOut[pointer] = SystemClock.elapsedRealtime()
                    // The call takes the NAT policy of its account, else the core's (MediaSession::getNatPolicy).
                    iceOffered[pointer] = (call.params.account?.params?.natPolicy ?: core.natPolicy)?.isIceEnabled == true
                }
                Call.State.OutgoingProgress -> startedOut.remove(pointer)?.let { setups[pointer] = SystemClock.elapsedRealtime() - it }
                Call.State.StreamsRunning -> started(call)
                Call.State.End, Call.State.Error -> finish(call, state)
                Call.State.Released -> {
                    running.remove(pointer)
                    startedOut.remove(pointer)
                    setups.remove(pointer)
                    iceOffered.remove(pointer)
                }
                else -> Unit
            }
        }

        override fun onCallStatsUpdated(core: Core, call: Call, stats: CallStats) {
            if (stats.type != StreamType.Audio) return
            running[call.nativePointer]?.tally?.take(sampleOf(stats, ended(call)), SystemClock.elapsedRealtime())
        }
    }

    init {
        core.addListener(listener)
    }

    private fun started(call: Call) {
        val now = SystemClock.elapsedRealtime()
        val pointer = call.nativePointer
        // Back from a hold, or after a re-INVITE: the silence counts from now.
        running[pointer]?.let {
            it.tally.restart(now)
            return
        }
        val judged = !isIntercom(call) && call.currentParams.audioDirection == MediaDirection.SendRecv
        running[pointer] = Running(
            CallTally(now, judged),
            call.remoteContactAddress?.domain,
            call.remoteParams?.getCustomHeader(RECORD_ROUTE),
            call.dir == Call.Dir.Outgoing,
            setups[pointer],
            iceOffered[pointer] == true,
        )
    }

    /** How the last call of account [id] went; null when it had none since the app started. */
    fun lastCall(id: String): CallMediaSummary? = last[id]

    fun forget(id: String) {
        last.remove(id)
    }

    /** No sound comes in on [call] while it runs and the phone sends ([CallTally.hearsNothing]). */
    fun hearsNothing(call: Call): Boolean =
        call.state == Call.State.StreamsRunning && running[call.nativePointer]?.tally?.hearsNothing(SystemClock.elapsedRealtime()) == true

    private fun finish(call: Call, state: Call.State) {
        val pointer = call.nativePointer
        val entry = running.remove(pointer)
        val account = call.params.account ?: return
        if (entry == null) {
            // A call out that failed before its sound ran: that it offered ICE may be why.
            if (state == Call.State.Error && call.dir == Call.Dir.Outgoing) {
                val code = call.errorInfo.protocolCode
                onFinished(account, Finished(null, null, null, true, setups[pointer], iceOffered[pointer] == true, judged = false, failedWith = code))
            }
            return
        }
        val summary = summaryOf(call, entry)
        if (summary != null) last[idOf(account)] = summary
        onFinished(account, Finished(summary, entry.contact, entry.recordRoute, entry.outgoing, entry.setupMs, entry.iceOffered, entry.tally.judged, failedWith = null))
    }

    private fun summaryOf(call: Call, entry: Running): CallMediaSummary? {
        val now = SystemClock.elapsedRealtime()
        val tally = entry.tally
        // A call too short for a report of its own: what the core has at its end.
        if (!tally.counted) call.audioStats?.let { tally.take(sampleOf(it, ended = true), now) }
        val flow = tally.flow(now) ?: return null
        return CallMediaSummary(
            flow = flow,
            lossDown = tally.lossDown,
            lossUp = tally.lossUp,
            jitterMs = tally.jitterMs,
            roundTripMs = tally.roundTripMs,
            ice = tally.ice,
            mediaLost = mediaLost(call),
        )
    }

    companion object {
        private const val MS_PER_S = 1000f
        private const val RECORD_ROUTE = "Record-Route"

        /** What the SIP core ends a call with when no sound arrived for too long (MediaSession::terminateBecauseOfLostMedia). */
        private const val MEDIA_LOST_CODE = 503
        private const val MEDIA_LOST_PHRASE = "Media lost"

        private fun ended(call: Call): Boolean = call.state.let { it == Call.State.End || it == Call.State.Error || it == Call.State.Released }

        /** [ended]: the call's streams are stopped, and with them its ICE session; its last state stays. */
        private fun sampleOf(stats: CallStats, ended: Boolean): CallTally.Sample {
            // The core's "sender" figures come from the reports this phone sent, about what it
            // received; its "receiver" ones from the other side's last report (0 without one).
            val lossUp = stats.receiverLossRate
            val roundTrip = stats.roundTripDelay
            return CallTally.Sample(
                sent = stats.rtpPacketSent,
                received = stats.rtpPacketRecv,
                lossDown = stats.senderLossRate,
                lossUp = lossUp,
                jitterMs = stats.senderInterarrivalJitter * MS_PER_S,
                roundTripMs = roundTrip * MS_PER_S,
                farRtcp = stats.rtcpDownloadBandwidth > 0f,
                aboutUs = roundTrip > 0f || lossUp > 0f || stats.receiverInterarrivalJitter > 0f,
                ice = if (ended) null else stats.iceState?.name.orEmpty(),
            )
        }

        /** Whether [call] ended because no sound arrived any more. */
        fun mediaLost(call: Call): Boolean {
            val error = call.errorInfo
            return error.reason == Reason.IOError && error.protocolCode == MEDIA_LOST_CODE && error.phrase == MEDIA_LOST_PHRASE
        }
    }
}
