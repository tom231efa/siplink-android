package com.sky.siplink.core

import android.os.Handler
import android.os.SystemClock
import android.util.Log
import com.sky.siplink.core.logic.ServerChoice
import org.linphone.core.Call

/**
 * Places a call out once more over the other server of its account, when the server it went to
 * gave it no answer within the fast timeout ([ServerChoice.retryAfter]) and the router finds that
 * server silent and the other one answering ([AccountRouter.leaveSilentServer]). The call screen
 * stays up: the new call takes the place of the old one, which ends without a word and leaves no
 * entry in the history. Once per call; not for the room's calls, nor for a consultation.
 *
 * The new call goes out once the account is registered on the other server, which may know its
 * users by their registration, or after [REGISTRATION_WAIT_MS] at the latest; a call that rings
 * meanwhile, or that the user ends, is left alone.
 *
 * Core thread only.
 *
 * @param redial places [Call] again on its account, now on its other server, having ended it with
 *   the function it is given once it took what it needs of it; false when it could not
 * @param gaveUp the call could not be placed again: it ended, without an answer from its server
 */
internal class CallRetry(
    private val handler: Handler,
    private val accounts: AccountManager,
    private val redial: (Call, () -> Unit) -> Boolean,
    private val gaveUp: (Call) -> Unit,
) {
    private val watched = HashMap<Long, Runnable>()

    /** Calls that were placed again: they end without a word. */
    private val superseded = HashSet<Long>()

    /** Calls that are themselves a second try. */
    private val retries = HashSet<Long>()

    /** The call being placed again, while [redial] places its successor; null otherwise. */
    var replacing: Call? = null
        private set

    /** A call out was placed on its account ([consultation]: next to another call). */
    fun watch(call: Call, inRoom: Boolean, consultation: Boolean) {
        val account = call.params.account ?: return
        val id = accounts.idOf(account)
        val after = ServerChoice.retryAfter(accounts.serverTimeoutMs(id)) ?: return
        val pointer = call.nativePointer
        if (!ServerChoice.mayRetry(inRoom, consultation, retried = pointer in retries, waiting = waiting(call))) return
        val server = hostPortOf(account.params.serverAddress)
        later(pointer, after) {
            if (!waiting(call)) return@later
            accounts.leaveSilentServer(id, server) { moved ->
                if (moved && waiting(call)) awaitRegistration(call, id, SystemClock.elapsedRealtime())
            }
        }
    }

    /**
     * Places [call] again once its account is registered on the server it moved to (a
     * registration after the move, which the router knows: the core shows the old one until the
     * new one is done), or the wait since [since] is over.
     */
    private fun awaitRegistration(call: Call, id: String, since: Long) {
        if (!waiting(call)) return
        val registered = accounts.registeredSinceMove(id)
        val waited = SystemClock.elapsedRealtime() - since
        if (registered || waited >= REGISTRATION_WAIT_MS) {
            Log.i(TAG, "A call out is placed again after $waited ms, ${if (registered) "registered" else "not registered yet"} on the other server")
            placeAgain(call)
        } else {
            later(call.nativePointer, POLL_MS) { awaitRegistration(call, id, since) }
        }
    }

    private fun later(pointer: Long, delayMs: Long, task: () -> Unit) {
        val runnable = Runnable {
            watched.remove(pointer)
            task()
        }
        watched[pointer] = runnable
        handler.postDelayed(runnable, delayMs)
    }

    /**
     * Ends [call] without a word and places it again. The old call goes first: the SIP core places
     * no call while another one being set up holds the sound ("sound are locked by another call").
     * Where the new one cannot be placed, the old one counts again, as the call that got no answer
     * it is, and is shown as ended at once.
     */
    private fun placeAgain(call: Call) {
        val pointer = call.nativePointer
        var ended = false
        replacing = call
        val placed = try {
            redial(call) {
                ended = true
                superseded += pointer
                accounts.supersede(call)
                call.terminate()
            }
        } finally {
            replacing = null
        }
        when {
            placed -> Log.i(TAG, "A call out got no answer from a server out of reach: placed again over the other server")
            ended -> {
                Log.w(TAG, "A call out could not be placed again over the other server")
                superseded -= pointer
                accounts.unsupersede(call)
                gaveUp(call)
            }
        }
    }

    /** The successor of [replacing] is set up: it is not placed again in its turn. */
    fun onReplacement(call: Call) {
        retries += call.nativePointer
    }

    /** Whether [call] was placed again: what happens to it is no longer shown. */
    fun isSuperseded(call: Call): Boolean = call.nativePointer in superseded

    /** [call] is gone. */
    fun onReleased(call: Call) {
        val pointer = call.nativePointer
        watched.remove(pointer)?.let(handler::removeCallbacks)
        superseded -= pointer
        retries -= pointer
    }

    private companion object {
        const val TAG = "SipLink.Call"

        /** The longest wait for the registration on the other server before the call goes out all the same. */
        const val REGISTRATION_WAIT_MS = 3_000L
        const val POLL_MS = 100L

        /**
         * No answer that the SDK shows: being set up, nothing ringing, and nothing of a provisional
         * answer either. A 100 Trying changes no state; it leaves a User-Agent (sal.cpp:327,
         * op.cpp:890) or a Contact (sal.cpp:331) where it carries one, which counts as an answer.
         */
        fun waiting(call: Call): Boolean =
            (call.state == Call.State.OutgoingInit || call.state == Call.State.OutgoingProgress) &&
                call.remoteUserAgent.isNullOrEmpty() && call.remoteContact.isNullOrEmpty() && call.remoteTag.isNullOrEmpty()
    }
}
