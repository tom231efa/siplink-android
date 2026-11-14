package com.sky.siplink.core.logic

import com.sky.siplink.core.model.DndMode
import com.sky.siplink.core.model.Settings

/** Decides what happens to a new incoming call: do not disturb, forwarding, call waiting. */
object IncomingPolicy {
    const val MIN_NO_ANSWER_SECONDS = 5
    const val MAX_NO_ANSWER_SECONDS = 120

    sealed interface Decision {
        /** Refuse the call: do not disturb is on. */
        data object RejectDoNotDisturb : Decision

        /** Refuse the call: the line is busy and nothing else applies. */
        data object RejectBusy : Decision

        /** Send the caller somewhere else right away. */
        data class Forward(val target: String) : Decision

        /** Offer the call on top of the running one (call waiting). */
        data object Waiting : Decision

        /** Ring normally, optionally forwarding when nobody answers in time. */
        data class Ring(val forwardTo: String?, val forwardAfterSeconds: Int) : Decision
    }

    /**
     * @param hasActiveCall a call is already being set up or established
     * @param hasSecondCall a second (held or waiting) call already exists
     */
    fun decide(settings: Settings, hasActiveCall: Boolean, hasSecondCall: Boolean): Decision {
        // In server mode the PBX holds the callers back; what it still sends is meant to ring.
        if (settings.doNotDisturb && settings.dndMode == DndMode.SOFTWARE) return Decision.RejectDoNotDisturb

        val always = settings.forwardAlways.trim()
        if (always.isNotEmpty()) return Decision.Forward(always)

        if (hasActiveCall) {
            val busy = settings.forwardBusy.trim()
            return when {
                busy.isNotEmpty() -> Decision.Forward(busy)
                settings.callWaiting && !hasSecondCall -> Decision.Waiting
                else -> Decision.RejectBusy
            }
        }

        val noAnswer = settings.forwardNoAnswer.trim()
        return Decision.Ring(
            forwardTo = noAnswer.ifEmpty { null },
            forwardAfterSeconds = settings.forwardNoAnswerSeconds
                .coerceIn(MIN_NO_ANSWER_SECONDS, MAX_NO_ANSWER_SECONDS),
        )
    }
}
