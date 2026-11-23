package com.sky.siplink.itest

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real microphone instead of the tone file: the device has to deliver sound to the call, on
 * screen and after the user has left the app.
 *
 * Not part of the default run of testenv/run-tests.sh: it needs a real device that is not in a
 * silent room. What is checked is that the microphone is not handed out as digital silence,
 * which is how Android withholds it from an app. A test process may be treated more generously
 * than the installed app, so this does not replace trying a call by hand.
 *
 *   ./run-tests.sh com.sky.siplink.itest.RealAudioTest
 */
@RunWith(AndroidJUnit4::class)
class RealAudioTest : PbxTestBase() {
    @Test
    fun microphoneIsCapturedOnScreenAndInTheBackground() {
        onCore { engine.core.useFiles = false }
        showApp() // a call the user places starts with the app on screen
        register()
        dial("$AGENT")
        awaitConnected("$AGENT")
        assertTrue("No sound from the microphone with the app on screen", capturesSound())

        // The user leaves the app: the call goes on behind the foreground service.
        shell("input keyevent KEYCODE_HOME")
        Thread.sleep(BACKGROUND_SETTLE_MS)
        assertTrue("No sound from the microphone with the app in the background", capturesSound())
    }

    /** Whether the microphone level of the call rises above digital silence within a few seconds. */
    private fun capturesSound(): Boolean = settles(LISTEN_MS) {
        onCore { engine.core.currentCall?.recordVolume ?: SILENCE_DB } > SILENCE_DB + AUDIBLE_MARGIN_DB
    }

    private companion object {
        /** What the SDK reports for a microphone that delivers nothing. */
        const val SILENCE_DB = -120f
        const val AUDIBLE_MARGIN_DB = 20f
        const val LISTEN_MS = 8_000L
        const val BACKGROUND_SETTLE_MS = 5_000L
    }
}
