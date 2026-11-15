package com.sky.siplink.itest

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sky.siplink.core.model.CallPhase
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Video without the camera permission, as after the user refused it: the call works, and the
 * video of the other side still comes in. Taking a permission away kills the app process, so
 * these tests rely on testenv/run-tests.sh revoking it before the class starts.
 */
@RunWith(AndroidJUnit4::class)
class VideoNoCameraTest : VideoTestBase() {
    @Before
    fun requireNoCamera() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeFalse(
            "The camera permission is granted; run-tests.sh revokes it before each class",
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
    }

    @Test
    fun aVideoCallWithoutTheCameraOnlyReceives() {
        register()
        dialVideo("2000")
        awaitConnected("2000")
        awaitVideo { !it.sending && it.receiving }
        awaitAppHearsAudio()
        // The camera cannot be switched on without the permission.
        assertNotNull(onMain { engine.calls.toggleCamera() })
        awaitVideo { !it.sending }
    }

    @Test
    fun acceptingVideoWithoutTheCameraOnlyReceives() {
        register()
        pbxCallsApp("upgrade")
        awaitCall("the incoming call") { it.phase == CallPhase.INCOMING }
        onMain { engine.calls.answer() }
        awaitCall("the request to add video") { it.phase == CallPhase.CONNECTED && it.videoOffer }
        onMain { engine.calls.answerVideoRequest(true) }
        awaitVideo { !it.sending && it.receiving }
        awaitAppHearsAudio()
    }

    @Test
    fun answeringWithVideoWithoutTheCameraOnlyReceives() {
        register()
        pbxCallsApp("echo")
        awaitCall("the incoming video call") { it.phase == CallPhase.INCOMING && it.videoOffer }
        onMain { engine.calls.answer(video = true) }
        awaitVideo { !it.sending && it.receiving }
        awaitAppHearsAudio()
    }
}
