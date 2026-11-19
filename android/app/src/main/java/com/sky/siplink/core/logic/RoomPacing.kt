package com.sky.siplink.core.logic

/**
 * How the conference room calls its members without overrunning the PBX: a few call set-ups at
 * a time ([AT_ONCE]), the next one as soon as an earlier one has an answer (ringing, early media,
 * answered, or a final failure) or has waited [SETUP_TIMEOUT_MS] without one. Answers that say the
 * PBX or the line has had enough stop the calling of everyone still waiting ([afterFailure]); those
 * keep their place in the list with the reason, and the user calls them again by hand. Nothing is
 * called again by itself.
 */
object RoomPacing {
    /** Call set-ups in progress at a time: the test PBX took 10 at once without a fault, 3 leaves it room for other calls. */
    const val AT_ONCE = 3

    /** A set-up without an answer this long no longer holds back the next one (it goes on by itself). */
    const val SETUP_TIMEOUT_MS = 8_000L

    /** Failures in a row, without an answer in between, after which nobody else is called. */
    const val FAILURES_TO_STOP = 3

    /** What a final answer of the PBX to a set-up says, by its SIP status. */
    enum class Failure(val label: String) {
        /** 503: the PBX or the line takes no more calls now. */
        LINE_FULL("线路已满"),

        /** 403: the PBX does not let this line make the call. */
        REFUSED("PBX 拒绝"),

        /** 486, 600: busy. */
        BUSY("忙"),

        /** 480: not reachable now. */
        UNAVAILABLE("暂时无法接通"),

        /** 488: no common codec. */
        MEDIA("媒体协商失败"),
    }

    /** The kind of a final failure of a set-up; null for answers that say nothing about the PBX (404, a cancel, ...). */
    fun failureOf(status: Int): Failure? = when (status) {
        503 -> Failure.LINE_FULL
        403 -> Failure.REFUSED
        486, 600 -> Failure.BUSY
        480 -> Failure.UNAVAILABLE
        488 -> Failure.MEDIA
        else -> null
    }

    /**
     * What follows a failed set-up.
     *
     * @param stop nobody else is called; those still waiting keep [label]
     */
    data class Verdict(val stop: Boolean, val label: String?)

    /**
     * @param failure the kind of this failure
     * @param run the failures of these kinds in a row, this one included, since the last answer
     * @param connected members are in the room: the line itself works, so a run of busy or
     *   refused answers says that the PBX allows the line no more calls at once
     */
    fun afterFailure(failure: Failure, run: Int, connected: Boolean): Verdict = when {
        failure == Failure.LINE_FULL -> Verdict(stop = true, label = Failure.LINE_FULL.label)
        run < FAILURES_TO_STOP -> Verdict(stop = false, label = null)
        connected && failure in LIMIT_SIGNS -> Verdict(stop = true, label = Failure.LINE_FULL.label)
        else -> Verdict(stop = true, label = failure.label)
    }

    /**
     * A call the room placed, answered and then ended by the other side within this, is taken for
     * the PBX's announcement that the line has no more calls: FreePBX answers a call over the
     * extension's limit of calls at once and plays "simultaneous call limit reached" instead of
     * refusing it.
     */
    const val SHORT_CALL_MS = 8_000L

    /**
     * Whether a member's call ended the way such an announcement does.
     *
     * @param connectedForMs how long it was answered
     * @param endedByUser the user ended it, or the room
     * @param othersTalk other members are in the room: the line has calls up
     */
    fun endedLikeALimit(connectedForMs: Long, endedByUser: Boolean, othersTalk: Boolean): Boolean =
        !endedByUser && othersTalk && connectedForMs < SHORT_CALL_MS

    /** How many more set-ups may start while [inSetup] are under way. */
    fun free(inSetup: Int): Int = (AT_ONCE - inSetup).coerceAtLeast(0)

    /** Whether a set-up placed at [placedAt] still holds back the next one at [now] (both in ms of the same clock). */
    fun holdsBack(placedAt: Long, now: Long): Boolean = now - placedAt < SETUP_TIMEOUT_MS

    /** When a member stopped by an answer with Retry-After [seconds] may be called again; 0 for at once. */
    fun retryAt(now: Long, seconds: Int): Long = if (seconds > 0) now + seconds * 1000L else 0L

    private val LIMIT_SIGNS = setOf(Failure.BUSY, Failure.REFUSED, Failure.LINE_FULL)
}
