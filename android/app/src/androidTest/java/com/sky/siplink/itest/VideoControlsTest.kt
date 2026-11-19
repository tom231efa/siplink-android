package com.sky.siplink.itest

import android.Manifest
import android.content.pm.ActivityInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.sky.siplink.core.model.CallPhase
import com.sky.siplink.openCallScreen
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The controls of a video call go by themselves once the picture flows and nothing is touched,
 * come back with a tap on the picture, and go again with the next one; upright and on its side.
 * They stay while the keypad is open.
 */
class VideoControlsTest : ScreenTestBase("video-controls") {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    /** A video call to the echo of the PBX, which sends the camera back: the picture flows. */
    private fun videoCall() {
        instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName, Manifest.permission.CAMERA)
        showApp()
        register()
        ok(onMain { engine.calls.dial("2000", null, video = true) })
        awaitCall("the picture of the echo") { it.phase == CallPhase.CONNECTED && it.video?.received != null }
        instrumentation.targetContext.openCallScreen()
        callScreen()
    }

    private fun controlsShown(): Boolean = shown(HANG_UP).isNotEmpty()

    /** A tap in the middle of the picture, between the name at the top and the controls at the bottom. */
    private fun tapPicture() {
        val metrics = callScreen().resources.displayMetrics
        tapAt(metrics.widthPixels * 0.5f, metrics.heightPixels * PICTURE_SPOT)
    }

    private fun hideTapShowTapHide(how: String) {
        assertTrue("No controls at first ($how)", settles(SCREEN_TIMEOUT_MS) { controlsShown() })
        assertTrue("The controls did not go by themselves ($how)", settles(HIDE_TIMEOUT_MS) { !controlsShown() })
        picture("hidden-$how")
        tapPicture()
        assertTrue("A tap on the picture did not bring the controls back ($how)", settles(SCREEN_TIMEOUT_MS) { controlsShown() })
        picture("shown-$how")
        tapPicture()
        assertTrue("A tap while they are shown did not take them away ($how)", settles(SCREEN_TIMEOUT_MS) { !controlsShown() })
        tapPicture()
        assertTrue("A second tap did not bring them back ($how)", settles(SCREEN_TIMEOUT_MS) { controlsShown() })
    }

    @Test
    fun theControlsGoByThemselvesAndATapBringsThemBack() {
        videoCall()
        val screen = callScreen()
        try {
            turnCallScreen(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
            hideTapShowTapHide("upright")
            turnCallScreen(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
            await("the call screen to be turned") { screen.resources.displayMetrics.let { it.widthPixels > it.heightPixels } }
            hideTapShowTapHide("on-its-side")
        } finally {
            runCatching { onMain { screen.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT } }
        }
    }

    @Test
    fun theControlsStayWhileTheKeypadIsOpen() {
        videoCall()
        turnCallScreen(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
        assertTrue(settles(SCREEN_TIMEOUT_MS) { controlsShown() })
        tap("键盘")
        see("收起键盘")
        // The keypad covers the hang up button (what it covers is no longer on the screen for a
        // screen reader); the room button at the top stands for the controls meanwhile.
        never("the controls gone with the keypad open", HIDE_TIMEOUT_MS) { shown(ROOM).isEmpty() }
        picture("keypad")
        tap("收起键盘")
        assertTrue("The controls stayed after the keypad closed", settles(HIDE_TIMEOUT_MS) { !controlsShown() })
    }

    private companion object {
        const val HANG_UP = "挂断"
        const val ROOM = "会议室"

        /** Four seconds without a touch, and some for the fading and the look at the screen. */
        const val HIDE_TIMEOUT_MS = 9_000L

        /** How far down the screen the picture is tapped: below the name, above the controls. */
        const val PICTURE_SPOT = 0.42f
    }
}
