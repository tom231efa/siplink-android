package com.sky.siplink.core.logic

/**
 * Which of its two servers an account with a backup server goes to ([AccountRoute]), decided by
 * asking both at the same time: after a start, a change of network, and, with the fast timeout,
 * whenever its registration starts or is refreshed.
 *
 * The fast timeout is the time the first server gets to answer. Without an answer in that time
 * the account takes the backup as soon as that one answers, which may take longer (mobile data, a
 * TLS handshake): the backup's answer is waited for as long as a probe waits ([PROBE_WAIT_MS]).
 * Off, the first server gets that long as well.
 */
object ServerChoice {
    /** The shortest fast timeout: below it a server on mobile data cannot answer in time. */
    const val MIN_TIMEOUT_MS = 200

    /** The longest fast timeout: beyond it the timeout is no faster than a probe. */
    const val MAX_TIMEOUT_MS = 10_000

    /** Whether [ms] is a fast timeout an account may have; 0 is off. */
    fun isValidTimeout(ms: Int): Boolean = ms == 0 || ms in MIN_TIMEOUT_MS..MAX_TIMEOUT_MS

    /** A fast timeout of the remote configuration: 0 or less is off, the rest is held within the bounds. */
    fun fromRemote(ms: Int): Int = if (ms <= 0) 0 else ms.coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)

    /** How long a probe waits for an answer, as the line checks do: two requests, two seconds each. */
    const val PROBE_WAIT_MS = 4_000L

    /** A request over UDP without an answer is sent again after this long. */
    const val RESEND_MS = 2_000L

    /** Requests within the fast timeout: one lost packet alone does not count as a server out of reach. */
    private const val FAST_TRIES = 3

    /** Connections within the fast timeout: the system sends a lost SYN again only after about a second. */
    private const val FAST_CONNECTS = 2

    /**
     * When a probe sends (UDP) or connects (TCP, TLS), in ms after its start, with the fast
     * timeout [timeoutMs] (0: off). Within the fast timeout UDP is asked three times (at 0, 1/3 and
     * 2/3 of it) and a second connection is started halfway; after that UDP is asked again every
     * [RESEND_MS] while the probe waits ([waitMs]).
     */
    fun sendTimes(timeoutMs: Int, reliable: Boolean): List<Long> {
        val wait = waitMs(timeoutMs)
        if (reliable) {
            return if (timeoutMs > 0) List(FAST_CONNECTS) { timeoutMs.toLong() * it / FAST_CONNECTS } else listOf(0L)
        }
        val fast = if (timeoutMs > 0) List(FAST_TRIES) { timeoutMs.toLong() * it / FAST_TRIES } else listOf(0L)
        val later = generateSequence(RESEND_MS) { it + RESEND_MS }.takeWhile { it < wait }.filter { it > fast.last() }
        return fast + later
    }

    /** How long a probe waits for an answer in all: as long as a probe, and the fast timeout at least. */
    fun waitMs(timeoutMs: Int): Long = maxOf(PROBE_WAIT_MS, timeoutMs.toLong())

    /** What came of asking one server. */
    enum class Answer {
        /** Asked, and within its time. */
        PENDING,

        /** It answered. */
        ANSWERED,

        /** The first server, past the fast timeout without an answer: still asked, while the backup is. */
        SILENT,

        /** No answer in all the probe's time, or it could not be asked. */
        FAILED,
    }

    /** What to do with the account. */
    enum class Move {
        /** Not decided yet: an answer still counts. */
        WAIT,

        /** It stays where it is. */
        STAY,

        /** To its first server, or it stays there. */
        PRIMARY,

        /** To its backup server, or it stays there. */
        BACKUP,
    }

    /**
     * What to do with an account now on its backup ([onBackup]) or first server, after what its
     * first server ([primary]) and its backup ([backup]) answered so far.
     *
     * The first server is taken when it answers. Without an answer from it in time, the backup is
     * taken when it answers. Both silent: the account stays, and the SIP core's own retries go on.
     * While a call is up ([callUp]) the account leaves a server only when that server failed for
     * all the probe's time: silence past the fast timeout alone may be a moment's jitter, which
     * the call would not survive a move for. With [silentLeaves] (a call out without an answer,
     * [mayRetry]) silence is enough. Back to the first server only once no call is up, as the
     * return after a failure always did.
     */
    fun next(onBackup: Boolean, primary: Answer, backup: Answer, callUp: Boolean, silentLeaves: Boolean = false): Move {
        val target = when {
            primary == Answer.ANSWERED -> Move.PRIMARY
            primary == Answer.PENDING -> return Move.WAIT
            backup == Answer.ANSWERED -> Move.BACKUP
            backup == Answer.PENDING || primary == Answer.SILENT -> return Move.WAIT
            else -> return Move.STAY
        }
        val moves = (target == Move.BACKUP) != onBackup
        if (!moves || !callUp) return target
        // The server the account is on, and with it its calls, has to have failed.
        val here = if (onBackup) backup else primary
        return when (here) {
            Answer.FAILED -> target
            Answer.SILENT -> if (silentLeaves) target else Move.WAIT
            Answer.PENDING -> Move.WAIT
            Answer.ANSWERED -> Move.STAY
        }
    }

    /**
     * Whether an account leaves the server it is on, which answered [here], now. A server the
     * account registered on, on this same network ([registeredHere]: no change of network since),
     * is left after silence past the fast timeout only the second time in a row ([strikes]: the
     * times before): one lost packet more than the tries cover does not move it. Right after a
     * change of network, or when the server failed for all the probe's time, once is enough.
     */
    fun leavesNow(here: Answer, registeredHere: Boolean, strikes: Int): Boolean =
        here != Answer.SILENT || !registeredHere || strikes >= 1

    /**
     * Whether a registration that failed for want of a server switches the account to its other
     * server at once: every other failure ([AccountRoute.switchesNow]), but not while both
     * servers are being asked ([checking]), nor within [MOVED_QUIET_MS] of a move ([sinceMoveMs]):
     * the first registration on a new network or a new server may fail before the way is up, and
     * the check decides better.
     */
    fun switchesBlindly(failures: Int, checking: Boolean, sinceMoveMs: Long): Boolean =
        AccountRoute.switchesNow(failures) && !checking && sinceMoveMs >= MOVED_QUIET_MS

    /**
     * Whether an account on its backup may go back to its first server now, when its last
     * [failedReturns] returns did not hold, the last one [sinceFailedReturnMs] ago: only once the
     * wait of the return after a failure is over ([AccountRoute.failbackDelay]). A first server
     * that answers OPTIONS and refuses registrations would have the account bounce otherwise.
     */
    fun mayReturn(failedReturns: Int, sinceFailedReturnMs: Long): Boolean =
        failedReturns == 0 || sinceFailedReturnMs >= AccountRoute.failbackDelay(failedReturns)

    /** After a change of network the servers are asked once more after this long: a new network may not carry at its first moment. */
    const val SETTLE_MS = 3_000L

    /** A registration that starts or refreshes has the servers asked at most this often. */
    const val MIN_CHECK_GAP_MS = 5_000L

    /**
     * How long after a move a failed registration neither switches the account blindly
     * ([switchesBlindly]) nor counts for the line doctor at once: the way to the new server may
     * not be up yet, and what failed may be the way to the server it left.
     */
    const val MOVED_QUIET_MS = 40_000L

    // ---------------------------------------------------------------- the server per network

    /**
     * The server an account registered on last on one network.
     *
     * @param usedAt when (ms since the epoch); what is not used for long is forgotten ([LineAdapt.staleKeys])
     */
    data class Remembered(val backup: Boolean, val usedAt: Long)

    private const val SEPARATOR = '|'

    fun encode(remembered: Remembered): String = "${if (remembered.backup) "b" else "p"}$SEPARATOR${remembered.usedAt}"

    fun decode(text: String?): Remembered? {
        val fields = text?.split(SEPARATOR) ?: return null
        if (fields.size != 2 || fields[0] !in setOf("b", "p")) return null
        return Remembered(fields[0] == "b", fields[1].toLongOrNull() ?: return null)
    }

    /** What is remembered is written down again at most this often while it stays the same. */
    const val USED_AT_STEP_MS = 60 * 60_000L

    /**
     * The record to keep after the account registered on its backup ([backup]) or first server at
     * [now]; null: nothing to write. The backup is remembered only where a probe on this network
     * found the first server silent ([primaryDown]): after a blind switch on a network where the
     * first server answers, every later arrival would go to the backup and straight back.
     */
    fun remember(known: Remembered?, backup: Boolean, primaryDown: Boolean, now: Long): Remembered? = when {
        backup && !primaryDown -> null
        known != null && known.backup == backup && now - known.usedAt < USED_AT_STEP_MS -> null
        else -> Remembered(backup, now)
    }

    // ---------------------------------------------------------------- calls out

    /**
     * An INVITE gets at least this long before it counts as unanswered. The SDK does not tell an
     * app about a 100 Trying; a call still without a ringing tone after the time, on a server the
     * probe finds silent, is taken as unanswered. Two seconds cover one lost INVITE (sent again
     * after 0.5 s) over a path of a second there and back (mobile data), or a lost TCP segment.
     */
    const val RETRY_FLOOR_MS = 2_000L

    /** When a call out with no answer yet is looked at, with the fast timeout [timeoutMs]; null: never (off). */
    fun retryAfter(timeoutMs: Int): Long? = if (timeoutMs > 0) maxOf(timeoutMs.toLong(), RETRY_FLOOR_MS) else null

    /** A move by a check that found the server it left silent counts for a call out on that server this long. */
    const val LEFT_SILENT_KEEP_MS = 30_000L

    /**
     * Whether a call out through [callServer] that has had no answer, on an account now on
     * another server, may take that move as the evidence that [callServer] is silent: only when
     * a check made it, having found [callServer] silent ([leftSilent]: that server, [sinceMs] ago).
     * Else both servers are asked now.
     */
    fun movedForSilence(callServer: String, leftSilent: String?, sinceMs: Long): Boolean =
        leftSilent != null && leftSilent.equals(callServer, ignoreCase = true) && sinceMs < LEFT_SILENT_KEEP_MS

    /**
     * Whether a call out is placed again over the other server, once, when the server it went to
     * is silent and the other answers. Only a call of its own, not one of the room or a
     * consultation next to another call, not one already placed again, and only while it has no
     * answer that the SDK shows ([waiting]: still being set up, nothing ringing).
     */
    fun mayRetry(inRoom: Boolean, consultation: Boolean, retried: Boolean, waiting: Boolean): Boolean =
        !inRoom && !consultation && !retried && waiting
}
