package com.sky.siplink.core

import android.os.SystemClock
import com.sky.siplink.core.logic.LineVerdict
import com.sky.siplink.core.model.CallMediaSummary
import org.linphone.core.Account
import org.linphone.core.Call
import org.linphone.core.CallStats
import org.linphone.core.Core
import org.linphone.core.CoreListenerStub
import org.linphone.core.Reason
import org.linphone.core.StreamType

/**
 * Follows the sound of the calls with the counters of the SIP core, and keeps how the last call
 * of each account went: the line checks show it. Main thread only, as the core's listeners are.
 *
 * @param idOf the id of an account
 * @param onFinished a call whose sound ran ended on an account
 */
internal class CallMediaWatch(
    private val core: Core,
    private val idOf: (Account) -> String,
    private val onFinished: (Account, Finished) -> Unit,
) {
    /**
     * A call whose sound ran, at its end.
     *
     * @param summary how its sound went; null: too short to tell
     * @param contact the host of the other side's Contact
     * @param recordRoute the Record-Route of the other side's message that set the call up (the
     *   answer to a call out, the INVITE of one that came in); null without one
     * @param outgoing the call went out
     * @param setupMs from the start of a call out to its INVITE (the SIP core gathers its ICE
     *   candidates in between); null for a call that came in
     */
    class Finished(
        val summary: CallMediaSummary?,
        val contact: String?,
        val recordRoute: String?,
        val outgoing: Boolean,
        val setupMs: Long?,
    )

    /** The latest counters of a call whose sound runs, copied: the core reuses its objects. */
    private class Running(
        val startedAt: Long,
        val contact: String?,
        val recordRoute: String?,
        val outgoing: Boolean,
        val setupMs: Long?,
    ) {
        /** When the sound (re)started or the last packet came in. */
        var heardAt = startedAt
        var counted = false
        var sent = 0L
        var received = 0L
        var lossDown = 0f
        var lossUp = 0f
        var jitterMs = 0f
        var roundTripMs = 0f
        var ice = ""

        /** [ended]: the call's streams are stopped, and with them its ICE session; its last state stays. */
        fun take(stats: CallStats, ended: Boolean) {
            counted = true
            sent = stats.rtpPacketSent
            if (stats.rtpPacketRecv > received) heardAt = SystemClock.elapsedRealtime()
            received = stats.rtpPacketRecv
            // The core's "sender" figures come from the reports this phone sent, about what it received.
            lossDown = stats.senderLossRate
            lossUp = stats.receiverLossRate
            jitterMs = stats.senderInterarrivalJitter * MS_PER_S
            roundTripMs = stats.roundTripDelay * MS_PER_S
            if (!ended) ice = stats.iceState?.name.orEmpty()
        }
    }

    private val running = HashMap<Long, Running>()

    /** When the calls out started, until they sent their INVITE; then how long that took. By native pointer. */
    private val startedOut = HashMap<Long, Long>()
    private val setups = HashMap<Long, Long>()
    private val last = HashMap<String, CallMediaSummary>()

    private val listener = object : CoreListenerStub() {
        override fun onCallStateChanged(core: Core, call: Call, state: Call.State?, message: String) {
            when (state) {
                Call.State.OutgoingInit -> startedOut[call.nativePointer] = SystemClock.elapsedRealtime()
                Call.State.OutgoingProgress -> startedOut.remove(call.nativePointer)?.let { setups[call.nativePointer] = SystemClock.elapsedRealtime() - it }
                Call.State.StreamsRunning -> {
                    val now = SystemClock.elapsedRealtime()
                    val pointer = call.nativePointer
                    // Back from a hold, or after a re-INVITE: the silence counts from now.
                    running.getOrPut(pointer) {
                        Running(
                            now,
                            call.remoteContactAddress?.domain,
                            call.remoteParams?.getCustomHeader(RECORD_ROUTE),
                            call.dir == Call.Dir.Outgoing,
                            setups[pointer],
                        )
                    }.heardAt = now
                }
                Call.State.End, Call.State.Error -> finish(call)
                Call.State.Released -> {
                    running.remove(call.nativePointer)
                    startedOut.remove(call.nativePointer)
                    setups.remove(call.nativePointer)
                }
                else -> Unit
            }
        }

        override fun onCallStatsUpdated(core: Core, call: Call, stats: CallStats) {
            if (stats.type == StreamType.Audio) running[call.nativePointer]?.take(stats, ended(call))
        }
    }

    init {
        core.addListener(listener)
    }

    /** How the last call of account [id] went; null when it had none since the app started. */
    fun lastCall(id: String): CallMediaSummary? = last[id]

    fun forget(id: String) {
        last.remove(id)
    }

    /** No sound came in on [call] for [LineVerdict.SILENT_AFTER_MS] while it runs and the phone sends. */
    fun hearsNothing(call: Call): Boolean {
        val entry = running[call.nativePointer] ?: return false
        return call.state == Call.State.StreamsRunning && entry.sent > 0 &&
            SystemClock.elapsedRealtime() - entry.heardAt >= LineVerdict.SILENT_AFTER_MS
    }

    private fun finish(call: Call) {
        val entry = running.remove(call.nativePointer) ?: return
        val account = call.params.account ?: return
        val summary = summaryOf(call, entry)
        if (summary != null) last[idOf(account)] = summary
        onFinished(account, Finished(summary, entry.contact, entry.recordRoute, entry.outgoing, entry.setupMs))
    }

    private fun summaryOf(call: Call, entry: Running): CallMediaSummary? {
        // A call too short for a report of its own: what the core has at its end.
        if (!entry.counted) call.audioStats?.let { entry.take(it, ended = true) }
        if (!entry.counted) return null
        val elapsed = SystemClock.elapsedRealtime() - entry.startedAt
        val flow = LineVerdict.flow(entry.sent, entry.received, entry.lossUp, elapsed) ?: return null
        return CallMediaSummary(
            flow = flow,
            lossDown = entry.lossDown,
            lossUp = entry.lossUp,
            jitterMs = entry.jitterMs,
            roundTripMs = entry.roundTripMs,
            ice = entry.ice,
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

        /** Whether [call] ended because no sound arrived any more. */
        fun mediaLost(call: Call): Boolean {
            val error = call.errorInfo
            return error.reason == Reason.IOError && error.protocolCode == MEDIA_LOST_CODE && error.phrase == MEDIA_LOST_PHRASE
        }
    }
}
