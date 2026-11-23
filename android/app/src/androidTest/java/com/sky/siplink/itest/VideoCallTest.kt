package com.sky.siplink.itest

import android.Manifest
import android.content.pm.ActivityInfo
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sky.siplink.core.logic.VideoRules
import com.sky.siplink.core.model.AudioRoute
import com.sky.siplink.core.model.CallPhase
import com.sky.siplink.core.model.PictureSize
import com.sky.siplink.core.model.VideoSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.linphone.core.Call

/**
 * Video calls against the test PBX: number 2000 echoes what it gets, video included; calls the
 * PBX places to the app (AMI Originate, context "videotests") offer video, and one of them asks
 * to add video after it was answered audio-only.
 *
 * The camera permission is granted here. The tests run with the app in the background, where
 * the app sends the SDK's still picture instead of the camera (see VideoController): video
 * flows all the same. [theCameraOnlyRunsWhileTheCallScreenIsShown] brings up the call screen.
 */
@RunWith(AndroidJUnit4::class)
class VideoCallTest : VideoTestBase() {
    @Before
    fun grantCamera() = grant(Manifest.permission.CAMERA)

    @Test
    fun theSdkProvidesVideoCodecsAndCameras() {
        val codecs = engine.codecs.videoCodecs.value
        val devices = onCore { engine.core.videoDevicesList.toList() }
        Log.i(TAG, "Video codecs: $codecs; video devices: $devices; current: ${onCore { engine.core.videoDevice }}")
        assertTrue("No video codec", codecs.isNotEmpty())
        assertTrue("No camera among $devices", VideoRules.cameras(devices).isNotEmpty())
        assertTrue("The still picture is not listed as ${VideoRules.STATIC_PICTURE}: $devices", VideoRules.STATIC_PICTURE in devices)
        assertNotNull(VideoRules.startCamera(devices))
    }

    @Test
    fun anAudioCallHasNoVideo() {
        register()
        dial("2000")
        val call = awaitConnected("2000")
        assertNull(call.video)
        assertFalse(onCore { engine.core.currentCall!!.currentParams.isVideoEnabled })
        assertFalse("The PBX was offered video", pbxHasVideo(APP))
        awaitAppHearsAudio()
    }

    @Test
    fun aVideoCallSendsAndReceivesVideo() {
        register()
        dialVideo("2000")
        awaitConnected("2000")
        awaitVideo { it.sending && it.receiving }
        awaitVideoReceived()
        awaitAppHearsAudio()
        assertTrue("The PBX has no video stream", pbxHasVideo(APP))
    }

    @Test
    fun theSoundOfAVideoCallGoesToTheSpeaker() {
        // With file audio the SDK reports no output device: use the real ones, with a party
        // that stays silent (2002), so that nothing is echoed out of the loudspeaker.
        onCore { engine.core.useFiles = false }
        register()
        dial("2002")
        val audio = awaitConnected("2002")
        assertEquals(AudioRoute.EARPIECE, audio.route)
        ok(onCore { engine.calls.toggleVideo() })
        awaitVideo { it.sending }
        if (AudioRoute.HEADSET in audio.availableRoutes || AudioRoute.BLUETOOTH in audio.availableRoutes) {
            never("the speaker with a headset connected", QUIET_WINDOW_MS) { call()?.route == AudioRoute.SPEAKER }
        } else {
            awaitCall("the speaker to be used") { it.route == AudioRoute.SPEAKER }
        }
    }

    @Test
    fun videoCanBeAddedAndEndedDuringACall() {
        register()
        dial("2000")
        awaitConnected("2000")
        assertNull(call()?.video)
        ok(onCore { engine.calls.toggleVideo() })
        awaitVideo { it.sending && it.receiving }
        awaitVideoReceived()
        ok(onCore { engine.calls.toggleVideo() })
        awaitCall("the video to end") { it.phase == CallPhase.CONNECTED && it.video == null }
        assertFalse(onCore { engine.core.currentCall!!.currentParams.isVideoEnabled })
        awaitAppHearsAudio()
    }

    @Test
    fun theOwnCameraCanBeSwitchedOffAndOnWhileTheCallGoesOn() {
        register()
        dialVideo("2000")
        awaitConnected("2000")
        awaitVideo { it.sending && it.receiving }
        ok(onCore { engine.calls.toggleCamera() })
        awaitVideo { !it.sending && it.receiving }
        val sent = videoPacketsSent()
        Thread.sleep(QUIET_WINDOW_MS)
        assertEquals("Video was still sent", sent, videoPacketsSent())
        ok(onCore { engine.calls.toggleCamera() })
        awaitVideo { it.sending && it.receiving }
        awaitVideoReceived()
    }

    @Test
    fun theCameraCanBeSwitched() {
        register()
        dialVideo("2000")
        awaitConnected("2000")
        awaitVideo { it.sending }
        val devices = onCore { engine.core.videoDevicesList.toList() }
        val before = onCore { engine.core.videoDevice }
        assertEquals(VideoRules.startCamera(devices), before)
        onCore { engine.calls.switchCamera() }
        assertEquals(VideoRules.nextCamera(devices, before), onCore { engine.core.videoDevice })
        awaitConnected("2000")
        awaitVideoReceived()
    }

    @Test
    fun anIncomingVideoCallCanBeAnsweredWithVideo() {
        register()
        pbxCallsApp("echo")
        val ringing = awaitCall("the incoming video call") { it.phase == CallPhase.INCOMING }
        assertTrue("The offer of video is not shown", ringing.videoOffer)
        onCore { engine.calls.answer(video = true) }
        awaitVideo { it.sending && it.receiving }
        awaitVideoReceived()
    }

    @Test
    fun anIncomingVideoCallCanBeAnsweredAudioOnly() {
        register()
        pbxCallsApp("echo")
        awaitCall("the incoming video call") { it.phase == CallPhase.INCOMING && it.videoOffer }
        onCore { engine.calls.answer() }
        val call = awaitCall("the answered call") { it.phase == CallPhase.CONNECTED }
        assertNull(call.video)
        never("video in an audio-only answer", QUIET_WINDOW_MS) { call()?.video != null }
        assertFalse(onCore { engine.core.currentCall!!.currentParams.isVideoEnabled })
        awaitAppHearsAudio()
    }

    @Test
    fun aRequestToAddVideoIsAskedAndCanBeAccepted() {
        register()
        pbxCallsApp("upgrade")
        awaitCall("the incoming call") { it.phase == CallPhase.INCOMING }
        onCore { engine.calls.answer() }
        awaitCall("the request to add video") { it.phase == CallPhase.CONNECTED && it.videoOffer }
        // Nothing starts before the user answers.
        assertNull(call()?.video)
        onCore { engine.calls.answerVideoRequest(true) }
        awaitVideo { it.sending && it.receiving }
        assertFalse(call()!!.videoOffer)
        awaitVideoReceived()
    }

    @Test
    fun aRequestToAddVideoCanBeDeclined() {
        register()
        pbxCallsApp("upgrade")
        awaitCall("the incoming call") { it.phase == CallPhase.INCOMING }
        onCore { engine.calls.answer() }
        awaitCall("the request to add video") { it.phase == CallPhase.CONNECTED && it.videoOffer }
        onCore { engine.calls.answerVideoRequest(false) }
        awaitCall("the request to be answered") { it.phase == CallPhase.CONNECTED && !it.videoOffer }
        never("video after declining", QUIET_WINDOW_MS) { call()?.video != null }
        awaitAppHearsAudio()
    }

    @Test
    fun withVideoCallsSwitchedOffVideoIsDeclined() {
        settings { it.copy(videoCalls = false) }
        register()
        // Asked for or offered, there is no video.
        dialVideo("2000")
        awaitConnected("2000")
        assertNull(call()?.video)
        assertFalse(onCore { engine.core.currentCall!!.currentParams.isVideoEnabled })
        assertNotNull("Video was switched on while video calls are off", onCore { engine.calls.toggleVideo() })
        onCore { engine.calls.hangUp() }
        awaitNoCall()
        await("the PBX to release the call") { pbxChannel(pbxLeg(APP)) == null }

        pbxCallsApp("upgrade")
        val ringing = awaitCall("the incoming call") { it.phase == CallPhase.INCOMING }
        assertFalse(ringing.videoOffer)
        onCore { engine.calls.answer(video = true) }
        awaitConnected()
        never("video while video calls are off", 3 * QUIET_WINDOW_MS / 2) { call()?.video != null || call()?.videoOffer == true }
        assertFalse(onCore { engine.core.currentCall!!.currentParams.isVideoEnabled })
        awaitAppHearsAudio()
    }

    @Test
    fun anAutoAnsweredIntercomCallStaysAudio() {
        settings { it.copy(intercomAutoAnswer = true) }
        register()
        showApp()
        pbxCallsApp(channel = "Local/$APP@videointercom", exten = "echo")
        val call = awaitCall("the intercom call to be answered") { it.phase == CallPhase.CONNECTED }
        assertTrue(call.intercom)
        assertNull(call.video)
        assertFalse(onCore { engine.core.currentCall!!.currentParams.isVideoEnabled })
    }

    @Test
    fun aVideoCallCanBeHeldAndResumed() {
        register()
        dialVideo("2000")
        awaitConnected("2000")
        awaitVideo { it.sending && it.receiving }
        onCore { engine.calls.toggleHold() }
        // A paused call is not the core's current call any more.
        await("the call to be held") { onCore { engine.core.calls.firstOrNull()?.state } == Call.State.Paused }
        onCore { engine.calls.toggleHold() }
        awaitCall("the call to be resumed") { it.phase == CallPhase.CONNECTED && it.video?.sending == true && it.video.receiving }
        awaitVideoReceived()
        awaitAppHearsAudio()
    }

    @Test
    fun aSecondCallNextToAVideoCall() {
        register()
        dialVideo("2000")
        awaitConnected("2000")
        awaitVideo { it.sending && it.receiving }
        // Consultation: the video call is held, the new one is audio-only.
        ok(onCore { engine.calls.startConsultation("2002") })
        val second = awaitCall("the second call") { it.phase == CallPhase.CONNECTED && it.remoteAddress.startsWith("2002@") }
        assertNull(second.video)
        assertNotNull(second.heldCall)
        onCore { engine.calls.hangUp() }
        awaitCall("the video call to come back") { it.remoteAddress.startsWith("2000@") && it.phase == CallPhase.CONNECTED }
        awaitVideo { it.sending && it.receiving }
        awaitVideoReceived()
    }

    @Test
    fun theCameraOnlyRunsWhileTheCallScreenIsShown() {
        register()
        dialVideo("2000")
        awaitConnected("2000")
        awaitVideo { it.sending }
        assertFalse("The camera runs without the call screen", cameraInUse())
        openCallScreen()
        await("the camera to start") { cameraInUse() }
        leaveCallScreen()
        await("the camera to stop") { !cameraInUse() }
        // The call and its video go on: the still picture is sent in place of the camera.
        awaitConnected("2000")
        awaitVideo { it.sending && it.receiving }
        awaitVideoSent()
        openCallScreen()
        await("the camera to start again") { cameraInUse() }
        onCore { engine.calls.hangUp() }
        awaitNoCall()
        await("the camera to be released") { !cameraInUse() }
    }

    @Test
    fun turningThePhoneTurnsTheCameraAndKeepsTheCallScreen() {
        fun upright(size: PictureSize?) = size != null && size.height > size.width
        fun onItsSide(size: PictureSize?) = size != null && size.width > size.height
        register()
        dialVideo("2000")
        awaitConnected("2000")
        openCallScreen()
        await("the camera to start") { cameraInUse() }
        // The call screen lays the pictures out by their sizes. A phone held upright sends an
        // upright picture, and the PBX sends back what it gets.
        awaitVideo { upright(it.sent) && upright(it.received) }
        val screen = callScreen()
        try {
            turnCallScreen(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
            awaitVideo { onItsSide(it.sent) && onItsSide(it.received) }
            // Were the call screen started anew, the views the video is drawn into would go with the
            // old one, and the camera with its view: it once stayed off after that.
            assertSame("The call screen was started anew", screen, callScreen())
            assertTrue("The camera stopped as the phone was turned", cameraInUse())
            awaitVideoSent()
            awaitVideoReceived()

            // Upright, said as such: "unspecified" would leave it to the system, which with
            // auto-rotate on goes by the sensor and may keep the screen on its side.
            turnCallScreen(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
            awaitVideo { upright(it.sent) && upright(it.received) }
            assertSame("The call screen was started anew", screen, callScreen())
            assertTrue("The camera stopped as the phone was turned back", cameraInUse())
        } finally {
            // The orientation goes back to the system; the screen may be gone by now, which is no fault of the test.
            runCatching { onMain { screen.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED } }
        }
        onCore { engine.calls.hangUp() }
        awaitNoCall()
    }

    @Test
    fun aCallScreenThatIsStartedAnewKeepsTheVideo() {
        register()
        dialVideo("2000")
        awaitConnected("2000")
        openCallScreen()
        await("the camera to start") { cameraInUse() }
        awaitVideoSent()
        val screen = callScreen()
        // What the system does to a screen when its theme or its font size changes, for one.
        onMain { screen.recreate() }
        await("the call screen to be started anew") { callScreen() !== screen }
        // The SDK once forgot the views of the new screen in this hand-over: the camera, which
        // waits for its view, stayed off, and nothing was sent.
        await("the camera to start again") { cameraInUse() }
        awaitVideoSent()
        awaitVideoReceived()
        assertNotNull("The SIP core has no view for the own camera", onCore { engine.core.nativePreviewWindowId })
        assertNotNull("The SIP core has no view for the other side", onCore { engine.core.nativeVideoWindowId })
        onCore { engine.calls.hangUp() }
        awaitNoCall()
        // With the call the screen goes, and its views with it.
        await("the SIP core to let go of the views") {
            onCore { engine.core.nativePreviewWindowId == null && engine.core.nativeVideoWindowId == null }
        }
    }

    @Test
    fun theCameraIsSentInTheSizeAndAtTheRateThatWereChosen() {
        register()
        // Neither is what the SDK starts with (VGA, and a rate of its own of about 15), so each shows.
        for ((size, rate) in listOf(VideoSize.QVGA to 24, VideoSize.HD to VideoRules.AUTO_FRAME_RATE, VideoSize.FHD to VideoRules.AUTO_FRAME_RATE)) {
            settings { it.copy(videoSize = size, videoFrameRate = rate) }
            // What the SIP core is told. A rate of the app's choosing only counts under its preset "custom".
            val told = onCore { Triple(engine.core.preferredVideoDefinition?.name, engine.core.preferredFramerate, engine.core.videoPreset) }
            assertEquals(Triple(size.definition, rate.toFloat(), if (rate == VideoRules.AUTO_FRAME_RATE) null else "custom"), told)

            dialVideo("2000")
            awaitConnected("2000")
            // It is the camera that is to be measured, which only runs while the call screen is shown;
            // without it the SDK sends its still picture, one frame a second.
            openCallScreen()
            await("the camera to start") { cameraInUse() }
            await("the camera to be sent as ${size.width}x${size.height}") {
                videoSent()?.let { (long, short, frames) -> long == size.width && short == size.height && frames > 1 } == true
            }
            // What comes back from the PBX is what really went out, whatever the app says it sends.
            await("the picture to come back as ${size.width}x${size.height}, not ${videoReceived()}") {
                videoReceived() == (size.width to size.height)
            }
            // How many frames the camera gives is up to the light as well: in the dark the one of
            // the test phone gives 15 whatever it is asked for. So the chosen rate is only an upper limit here.
            val limit = VideoRules.uploadLimit(size, rate)
            assertEquals("The limit the SIP core is told", limit, onCore { engine.core.uploadBandwidth })
            if (rate != VideoRules.AUTO_FRAME_RATE) {
                // The camera starts at a rate of its own and takes a moment to come to the chosen one.
                await("the camera to come down to $rate frames per second") {
                    (videoSent()?.third ?: Float.MAX_VALUE) <= rate + FRAME_RATE_TOLERANCE
                }
                never("the camera to be sent faster than at $rate frames per second", QUIET_WINDOW_MS) {
                    (videoSent()?.third ?: 0f) > rate + FRAME_RATE_TOLERANCE
                }
                // Told a frame rate and no limit, the SIP core once sent 4 Mbit/s of this small picture.
                never("the picture to be sent with far more than the $limit kbit/s it is allowed", QUIET_WINDOW_MS) {
                    videoUpload() > 2 * limit
                }
            }
            Log.i(TAG, "Chosen $size at $rate, sent ${videoSent()}, received ${videoReceived()}")
            onCore { engine.calls.hangUp() }
            awaitNoCall()
            await("the PBX to release the call") { pbxChannel(pbxLeg(APP)) == null }
        }
    }

    private companion object {
        const val TAG = "VideoCallTest"

        /**
         * How far the measured frame rate may be above the chosen one: it is counted over a short
         * while and wanders by two or three frames. Still below the 30 the camera gives unasked.
         */
        const val FRAME_RATE_TOLERANCE = 5f
    }
}
