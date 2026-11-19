package com.sky.siplink.core.logic

import com.sky.siplink.core.model.Settings
import kotlin.math.abs

/**
 * When a ringing call is answered by itself. Intercom calls follow their own switch and are
 * answered at once; regular calls follow [Settings.autoAnswer] and wait [Settings.autoAnswerSeconds].
 */
object AutoAnswerRules {
    /** The seconds a regular call rings before it is answered; the user picks one of these. */
    val SECONDS_CHOICES = listOf(0, 3, 5, 10, 20, 30)
    const val DEFAULT_SECONDS = 3

    /** No call is answered sooner than this after it began to ring: the core needs a moment to set it up. */
    const val MIN_DELAY_MS = 400L

    /**
     * The one of [SECONDS_CHOICES] nearest to [value]. Halfway between two, the longer wait is
     * taken: a call that rings a little longer does less harm than one answered too soon.
     */
    fun seconds(value: Int): Int =
        SECONDS_CHOICES.minWith(compareBy<Int> { abs(it.toLong() - value) }.thenByDescending { it })

    /**
     * How long after it began to ring a call is answered by itself, in milliseconds; null: it is
     * left to the user.
     *
     * @param intercom the caller asked for the call to be answered by itself (an intercom call)
     * @param cameDuringCall the call arrived while another one or the room was up and waited next
     *   to it: such a call is never answered by itself, an intercom call neither, not even once
     *   it is the only one left (CallRules, rule 2)
     */
    fun delayMs(settings: Settings, intercom: Boolean, cameDuringCall: Boolean): Long? = when {
        cameDuringCall -> null
        intercom -> MIN_DELAY_MS.takeIf { settings.intercomAutoAnswer }
        !settings.autoAnswer -> null
        else -> maxOf(seconds(settings.autoAnswerSeconds) * 1000L, MIN_DELAY_MS)
    }
}
