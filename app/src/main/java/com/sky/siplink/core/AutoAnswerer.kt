package com.sky.siplink.core

import android.os.Handler
import org.linphone.core.Call

/**
 * Answers a ringing call by itself once its time has come ([com.sky.siplink.core.logic.AutoAnswerRules]).
 * Only one call waits for that at a time: a newer one, or any answer, decline or end, takes its place.
 *
 * Android mutes the microphone of an app that is not visible. A call whose time has come while the
 * app is in the background waits for the call screen, which was brought up as it began to ring,
 * and is answered as that screen shows ([onCallUiVisible]); never before its time.
 *
 * @param canUseMicrophone whether the app is visible
 * @param answerable whether the call still is the one to answer (rings, is the current call, ...)
 * @param answer answers the call, audio only
 */
internal class AutoAnswerer(
    private val handler: Handler,
    private val canUseMicrophone: () -> Boolean,
    private val answerable: (Call, intercom: Boolean) -> Boolean,
    private val answer: (Call) -> Unit,
) {
    private var call: Call? = null
    private var intercom = false

    /** The time has come and the call waits for the call screen. */
    private var due = false
    private val timer = Runnable { onDue() }

    /** [call] began to ring: answer it after [delayMs], or, for null, leave it to the user. */
    fun start(call: Call, intercom: Boolean, delayMs: Long?) {
        cancel()
        if (delayMs == null) return
        this.call = call
        this.intercom = intercom
        handler.postDelayed(timer, delayMs)
    }

    /** Nothing is to be answered by itself any more. */
    fun cancel() {
        handler.removeCallbacks(timer)
        call = null
        due = false
    }

    fun onCallUiVisible() {
        if (due) onDue()
    }

    private fun onDue() {
        val target = call ?: return
        when {
            !answerable(target, intercom) -> cancel()
            canUseMicrophone() -> {
                cancel()
                answer(target)
            }
            else -> due = true
        }
    }
}
