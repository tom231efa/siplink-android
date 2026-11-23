package com.sky.siplink.itest

import android.app.Activity
import android.util.Log
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.sky.siplink.CallActivity
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A call that comes in while the call screen of the call before fades out gets a call screen.
 * The start of the screen then reaches the screen that is fading out, so that one must not close
 * once its fade is over. When the next call arrives depends on the PBX and the phone; the test
 * rings the app at every moment of the fade and just after it.
 */
class NextCallScreenTest : PbxTestBase() {

    /** The call screen in front that stays: resumed and not on its way out. */
    private fun screenInFront(): Activity? = onMain {
        ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
            .firstOrNull { it is CallActivity && !it.isFinishing }
    }

    /**
     * Declines the call and returns the moment the app has none, to the millisecond: [awaitNoCall]
     * looks only every few hundred milliseconds, which would smear the moments the sweep rings at.
     */
    private fun declineAndWait(): Long {
        onCore { engine.calls.decline() }
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (call() != null) {
            assertTrue("The declined call did not go", System.currentTimeMillis() < deadline)
            Thread.sleep(1)
        }
        return System.currentTimeMillis()
    }

    @Test
    fun aCallDuringTheFadeOfTheScreenBeforeGetsItsScreen() {
        showApp()
        register()
        ringing(CALLER)
        callScreen()
        var gone = declineAndWait()
        val missed = ArrayList<Long>()
        for (wait in 0L..MAX_WAIT_MS step STEP_MS) {
            // The app has had no call since [gone]: its call screen fades out now.
            Thread.sleep(maxOf(0L, gone + wait - System.currentTimeMillis()))
            ringing(CALLER)
            val rang = System.currentTimeMillis() - gone
            // Past the end of any fade that was going on: a screen still in front then is the call's own.
            Thread.sleep(PAST_FADE_MS)
            val shown = settles(SCREEN_TIMEOUT_MS) { screenInFront() != null }
            Log.i(TAG, "wait $wait ms: the call rang ${rang} ms after the app had no call, screen ${if (shown) "shown" else "missing"}")
            if (!shown) missed += wait
            gone = declineAndWait()
        }
        assertTrue("No call screen for a call rung ${missed.joinToString()} ms after the call before was gone", missed.isEmpty())
    }

    private companion object {
        const val TAG = "NextCallScreenTest"
        const val CALLER = "\"Next\" <1002>"

        /** The fade lasts 220 ms; the next call arrives some 200 to 250 ms after it is rung. */
        const val MAX_WAIT_MS = 400L

        /**
         * The moments that lose the screen are the last few milliseconds of the fade: on the test
         * phone steps of 25 ms passed over them in seven runs, steps of 5 ms caught them at once.
         */
        const val STEP_MS = 5L

        /** More than the fade of the screen and the frames it lags behind the engine. */
        const val PAST_FADE_MS = 1000L

        /** A call screen comes to the front well within this. */
        const val SCREEN_TIMEOUT_MS = 4000L
    }
}
