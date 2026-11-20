package com.sky.siplink.core

import android.os.Handler
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
 * Main thread only.
 *
 * @param redial places [Call] again on its account, now on its other server, having ended it with
 *   the function it is given once it took what it needs of it; false when it could not
 */
internal class CallRetry(
    private val handler: Handler,
    private val accounts: AccountManager,
    private val redial: (Call, () -> Unit) -> Boolean,
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
        val task = Runnable {
            watched.remove(pointer)
            if (!waiting(call)) return@Runnable
            accounts.leaveSilentServer(id, server) { moved ->
                if (moved && waiting(call)) placeAgain(call)
            }
        }
        watched[pointer] = task
        handler.postDelayed(task, after)
    }

    /**
     * Ends [call] without a word and places it again. The old call goes first: the SIP core places
     * no call while another one being set up holds the sound ("sound are locked by another call").
     * Where the new one cannot be placed, the old one's end is shown after all, once it is released.
     */
    private fun placeAgain(call: Call) {
        val pointer = call.nativePointer
        replacing = call
        val placed = try {
            redial(call) {
                superseded += pointer
                accounts.supersede(call)
                call.terminate()
            }
        } finally {
            replacing = null
        }
        if (placed) {
            Log.i(TAG, "A call out got no answer from a server out of reach: placed again over the other server")
        } else {
            Log.w(TAG, "A call out could not be placed again over the other server")
            superseded -= pointer
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

        /** No answer that the SDK shows: being set up, nothing ringing. */
        fun waiting(call: Call): Boolean = call.state == Call.State.OutgoingInit || call.state == Call.State.OutgoingProgress
    }
}
