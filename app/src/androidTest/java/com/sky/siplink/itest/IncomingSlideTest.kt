package com.sky.siplink.itest

import android.content.pm.ActivityInfo
import android.graphics.Rect
import com.sky.siplink.core.model.CallPhase
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The slide of an incoming call, moved by a finger on the screen, upright and with the phone on
 * its side. No finger goes straight sideways: the slide has to take one that also goes up or down.
 */
class IncomingSlideTest : ScreenTestBase("slide") {

    /**
     * Rings the app and slides the dot with a finger that goes [slant] times as far down
     * (negative: up) as it goes sideways; [towardsAnswer] or towards "hang up". With
     * [liftInMotion] the finger is lifted while it still moves.
     */
    private fun slide(onItsSide: Boolean, slant: Float, towardsAnswer: Boolean = true, liftInMotion: Boolean = false) {
        phoneCalls(AGENT, "$APP")
        awaitCall("the incoming call") { it.phase == CallPhase.INCOMING }
        val screen = callScreen()
        try {
            // Said either way: left to the system, a screen that was on its side may stay there.
            turnCallScreen(if (onItsSide) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
            await("the call screen to be turned") { screen.resources.displayMetrics.let { it.widthPixels > it.heightPixels } == onItsSide }
            settle()
            val track = Rect().also { see(TRACK).getBoundsInScreen(it) }
            val reach = (track.width() / 2f - END_MARGIN_PX) * if (towardsAnswer) 1 else -1
            swipe(
                track.exactCenterX(), track.exactCenterY(), track.exactCenterX() + reach, track.exactCenterY() + reach * slant,
                liftInMotion,
            )
            val how = "${if (onItsSide) "on its side" else "upright"}, slant $slant${if (liftInMotion) ", lifted in motion" else ""}"
            if (towardsAnswer) {
                assertTrue("The slide did not answer ($how)", settles(ANSWER_TIMEOUT_MS) { call()?.phase == CallPhase.CONNECTED })
            } else {
                assertTrue("The slide did not decline ($how)", settles(ANSWER_TIMEOUT_MS) { call() == null })
            }
        } finally {
            // The orientation goes back to the system; the screen may be gone by now.
            runCatching { onMain { screen.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED } }
            phones.command("channel request hangup all")
            onMain { engine.calls.hangUp() }
            awaitNoCall()
            await("the caller to be released") { !phoneHasCall(AGENT) }
        }
    }

    @Test
    fun slidingAnswersAndDeclinesUpright() {
        showApp()
        register()
        for (slant in SLANTS) slide(onItsSide = false, slant = slant)
        slide(onItsSide = false, slant = 0f, towardsAnswer = false)
    }

    /**
     * Most fingers are lifted while they still move, and their last movement then reaches the app
     * together with the lifting. The slide once took that for a new movement, which stopped the dot
     * on its way to the end, and with it the answer.
     */
    @Test
    fun aFingerLiftedInMotionAnswersAndDeclines() {
        showApp()
        register()
        repeat(LIFTS) { slide(onItsSide = false, slant = 0f, liftInMotion = true) }
        slide(onItsSide = false, slant = 0f, towardsAnswer = false, liftInMotion = true)
    }

    /** On its side the controls stand in a column that scrolls: it once took every finger that did not go straight. */
    @Test
    fun slidingAnswersAndDeclinesOnItsSide() {
        showApp()
        register()
        for (slant in SLANTS) slide(onItsSide = true, slant = slant)
        slide(onItsSide = true, slant = STEEP, towardsAnswer = false)
    }

    private companion object {
        const val TRACK = "来电：向右滑动接听，向左滑动挂断"

        /** Up to nearly as far up or down as sideways. */
        const val STEEP = 0.9f
        val SLANTS = listOf(0f, STEEP, -STEEP)

        /** The finger stops this far before the end of the track. */
        const val END_MARGIN_PX = 20f
        const val ANSWER_TIMEOUT_MS = 5_000L

        /** How often the finger lifted in motion is tried: what it once ran into depended on timing. */
        const val LIFTS = 4
    }
}
