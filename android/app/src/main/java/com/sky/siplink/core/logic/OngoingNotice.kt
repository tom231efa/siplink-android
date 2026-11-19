package com.sky.siplink.core.logic

import com.sky.siplink.core.model.CallInfo
import com.sky.siplink.core.model.CallPhase

/**
 * What the notification of a call that is up shows of it (service/CallNotifier.ongoingCall): its
 * name, its time, and where its hang up key leads. When that changes, the notification is posted
 * again; a room that opens or grows past one member changes it without a change of phase.
 */
object OngoingNotice {
    data class Face(
        val phase: CallPhase,
        val name: String,
        val address: String,
        val connectedAt: Long?,
        /** Null for a call; for the room, whether its hang up key asks first (CallRules.confirmRoomEnd). */
        val roomEndAsks: Boolean?,
    )

    fun of(info: CallInfo?): Face? = info?.let {
        Face(it.phase, it.remoteName, it.remoteAddress, it.connectedAt, it.room?.let { room -> CallRules.confirmRoomEnd(room.present) })
    }
}
