package com.sky.siplink.core.logic

import com.sky.siplink.core.logic.LineVerdict.FarReports
import com.sky.siplink.core.model.MediaFlow

/**
 * The counters of one call whose sound runs, as the SIP core reports them, and what they say:
 * whether sound comes in now, and which way it went.
 *
 * @param startedAt when the sound started (ms, one monotonic clock for all times given)
 * @param judged its sound is to be judged at all: not an intercom or paging call, and both sides
 *   send (an intercom's far end is often muted; a paging call sends one way by design)
 */
class CallTally(private val startedAt: Long, val judged: Boolean) {
    /**
     * One report of the SIP core.
     *
     * @param lossDown of what this phone received, in percent
     * @param lossUp of what this phone sent, as the other side's last report gave it (0 without one)
     * @param farRtcp RTCP from the other side arrived lately
     * @param aboutUs the other side's last report was about this phone's stream (it gave a round
     *   trip, a loss or a jitter of it)
     * @param ice the SIP core's ICE state; null once the call's streams stopped (and with them its ICE session)
     */
    class Sample(
        val sent: Long,
        val received: Long,
        val lossDown: Float,
        val lossUp: Float,
        val jitterMs: Float,
        val roundTripMs: Float,
        val farRtcp: Boolean,
        val aboutUs: Boolean,
        val ice: String?,
    )

    /** When the sound (re)started or the last packet came in. */
    private var heardAt = startedAt

    /** Whether anything came in since the sound (re)started. */
    private var heardSince = false
    private var farSince: Long? = null
    private var aboutUs = false

    var counted = false
        private set
    var sent = 0L
        private set
    var received = 0L
        private set
    var lossDown = 0f
        private set
    var lossUp = 0f
        private set
    var jitterMs = 0f
        private set
    var roundTripMs = 0f
        private set
    var ice = ""
        private set

    fun take(sample: Sample, now: Long) {
        counted = true
        sent = sample.sent
        if (sample.received > received) {
            heardAt = now
            heardSince = true
        }
        received = sample.received
        lossDown = sample.lossDown
        lossUp = sample.lossUp
        jitterMs = sample.jitterMs
        roundTripMs = sample.roundTripMs
        if (sample.farRtcp && farSince == null) farSince = now
        if (sample.aboutUs) aboutUs = true
        sample.ice?.let { ice = it }
    }

    /** The sound started again (after a hold, or a re-INVITE): the silence counts from [now]. */
    fun restart(now: Long) {
        heardAt = now
        heardSince = false
    }

    /**
     * Whether no sound comes in while the phone sends: nothing since the sound (re)started for
     * [LineVerdict.SILENT_AFTER_MS], or nothing for [GAP_AFTER_MS] after some came in (a far end
     * that suppresses its silence sends nothing while it is quiet, RFC 3389). Never for a call
     * that is not [judged].
     */
    fun hearsNothing(now: Long): Boolean {
        if (!judged || sent == 0L) return false
        return now - heardAt >= if (heardSince) GAP_AFTER_MS else LineVerdict.SILENT_AFTER_MS
    }

    /**
     * What the other side's reports said of this phone's sound by [now]. Their silence counts once
     * they came for [FAR_SILENT_MS]: the first report may be sent before any of this phone's
     * packets arrived, the second one no longer.
     */
    fun farReports(now: Long): FarReports {
        val since = farSince
        return when {
            aboutUs -> FarReports.ABOUT_US
            since != null && now - since >= FAR_SILENT_MS -> FarReports.SILENT
            else -> FarReports.NONE
        }
    }

    /** Which way the sound went by [now] ([LineVerdict.flow]); null too early to tell. A call not [judged] shows it, and teaches nothing. */
    fun flow(now: Long): MediaFlow? {
        if (!counted) return null
        return LineVerdict.flow(sent, received, farReports(now), lossUp, now - startedAt)
    }

    companion object {
        /** How long sound may pause after some came in before the call says none comes. The SIP core itself gives up after 30 seconds. */
        const val GAP_AFTER_MS = 15_000L

        /** Two RTCP intervals of the least 5 seconds RFC 3550 §6.2 recommends between reports. */
        const val FAR_SILENT_MS = 10_000L
    }
}
