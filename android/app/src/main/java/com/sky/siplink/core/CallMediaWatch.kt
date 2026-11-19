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
 */
internal class CallMediaWatch(private val core: Core, private val idOf: (Account) -> String) {
    /** The latest counters of a call whose sound runs, copied: the core reuses its objects. */
    private class Running(val startedAt: Long) {
        var counted = false
        var sent = 0L
        var received = 0L
        var lossDown = 0f
        var lossUp = 0f
        var jitterMs = 0f
        var roundTripMs = 0f
        var ice = ""

        fun take(stats: CallStats) {
            counted = true
            sent = stats.rtpPacketSent
            received = stats.rtpPacketRecv
            // The core's "sender" figures come from the reports this phone sent, about what it received.
            lossDown = stats.senderLossRate
            lossUp = stats.receiverLossRate
            jitterMs = stats.senderInterarrivalJitter * MS_PER_S
            roundTripMs = stats.roundTripDelay * MS_PER_S
            ice = stats.iceState?.name.orEmpty()
        }
    }

    private val running = HashMap<Long, Running>()
    private val last = HashMap<String, CallMediaSummary>()

    private val listener = object : CoreListenerStub() {
        override fun onCallStateChanged(core: Core, call: Call, state: Call.State?, message: String) {
            when (state) {
                Call.State.StreamsRunning -> running.getOrPut(call.nativePointer) { Running(SystemClock.elapsedRealtime()) }
                Call.State.End, Call.State.Error -> finish(call)
                Call.State.Released -> running.remove(call.nativePointer)
                else -> Unit
            }
        }

        override fun onCallStatsUpdated(core: Core, call: Call, stats: CallStats) {
            if (stats.type == StreamType.Audio) running[call.nativePointer]?.take(stats)
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

    private fun finish(call: Call) {
        val entry = running.remove(call.nativePointer) ?: return
        val account = call.params.account ?: return
        // A call too short for a report of its own: what the core has at its end.
        if (!entry.counted) call.audioStats?.let(entry::take)
        if (!entry.counted) return
        val elapsed = SystemClock.elapsedRealtime() - entry.startedAt
        val flow = LineVerdict.flow(entry.sent, entry.received, entry.lossUp, elapsed) ?: return
        val error = call.errorInfo
        last[idOf(account)] = CallMediaSummary(
            flow = flow,
            lossDown = entry.lossDown,
            lossUp = entry.lossUp,
            jitterMs = entry.jitterMs,
            roundTripMs = entry.roundTripMs,
            ice = entry.ice,
            mediaLost = error.reason == Reason.IOError && error.protocolCode == MEDIA_LOST_CODE && error.phrase == MEDIA_LOST_PHRASE,
        )
    }

    private companion object {
        const val MS_PER_S = 1000f

        /** What the SIP core ends a call with when no sound arrived for too long (MediaSession::terminateBecauseOfLostMedia). */
        const val MEDIA_LOST_CODE = 503
        const val MEDIA_LOST_PHRASE = "Media lost"
    }
}
