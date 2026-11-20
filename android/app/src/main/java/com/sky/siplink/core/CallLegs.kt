package com.sky.siplink.core

import com.sky.siplink.core.model.Phrase
import com.sky.siplink.core.model.SupervisionMode
import org.linphone.core.Call

/** What the next outgoing call started by [CallManager] is meant to be. */
internal class Pending(val supervision: SupervisionMode?, val consultation: Boolean)

/** The helpers [CallManager] made for its calls, for [CallFollower] too. */
internal class CallParts(
    val describer: CallDescriber,
    val room: ConferenceRoom,
    val video: VideoController,
    val recorder: CallRecorder,
    val transfer: CallTransfer,
    val retry: CallRetry,
    val autoAnswer: AutoAnswerer,
)

/**
 * Where the calls of [CallManager] stand, shared by the commands of the user ([CallManager]) and
 * the reports of the core ([CallFollower]): the "current" call the UI follows, the one beside it,
 * and what is known of each ([CallMeta]). Main thread only.
 */
internal class CallLegs {
    var current: Call? = null
    var other: Call? = null

    /** The call in which the PBX asked us (REFER) to call someone else; [current] is that new call. */
    var referredFrom: Call? = null

    /** Set while the core reports OutgoingInit from inside the invite call, to tell our own call apart there. */
    var pending: Pending? = null
    var consulting = false

    /** Why the call on screen ended, as its snapshot says it; kept to say it again in another language. */
    var endReason: Phrase? = null

    /** Counts the scenarios on screen: a lingering end only clears the screen while no new one began. */
    var generation = 0
    private val metas = HashMap<Long, CallMeta>()

    fun metaOf(call: Call): CallMeta = metas.getOrPut(call.nativePointer) { CallMeta() }

    fun forget(call: Call): CallMeta? = metas.remove(call.nativePointer)

    /** [placed] takes over what was known of the call it was placed in place of. */
    fun carry(placed: Call, meta: CallMeta) {
        metas[placed.nativePointer] = meta
    }

    companion object {
        fun same(a: Call?, b: Call?): Boolean = a != null && b != null && a.nativePointer == b.nativePointer

        fun live(call: Call?): Call? = call?.takeIf { !CallSupport.isEnded(it) }
    }
}
