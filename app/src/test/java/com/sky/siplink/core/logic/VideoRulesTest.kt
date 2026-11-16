package com.sky.siplink.core.logic

import com.sky.siplink.core.model.AudioRoute
import com.sky.siplink.core.model.CallPhase
import com.sky.siplink.core.model.VideoSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoRulesTest {
    /** What the SDK lists on the test phone (Samsung, Android 16). */
    private val devices = listOf("FrontFacingCamera", "BackFacingCamera", VideoRules.STATIC_PICTURE)

    @Test
    fun aFrameRateThatCannotBeChosenIsLeftToTheCore() {
        assertEquals(listOf(0, 15, 24, 30), VideoRules.FRAME_RATES)
        VideoRules.FRAME_RATES.forEach { assertEquals(it, VideoRules.frameRate(it)) }
        listOf(-1, 1, 20, 25, 60).forEach { assertEquals(VideoRules.AUTO_FRAME_RATE, VideoRules.frameRate(it)) }
    }

    @Test
    fun aFrameRateOfTheAppsChoosingComesWithALimitOnWhatIsSent() {
        // Left to the SIP core, the rate needs none: the core is modest by itself.
        VideoSize.entries.forEach { assertEquals(VideoRules.NO_UPLOAD_LIMIT, VideoRules.uploadLimit(it, VideoRules.AUTO_FRAME_RATE)) }
        assertEquals(356, VideoRules.uploadLimit(VideoSize.QVGA, 30))
        assertEquals(868, VideoRules.uploadLimit(VideoSize.VGA, 30))
        assertEquals(1636, VideoRules.uploadLimit(VideoSize.HD, 30))
        // Half the frames, half the bits for the picture; the sound keeps its share.
        assertEquals(484, VideoRules.uploadLimit(VideoSize.VGA, 15))
        assertEquals(304, VideoRules.uploadLimit(VideoSize.QVGA, 24))
        // A larger picture or more frames never get less.
        for (rate in VideoRules.FRAME_RATES.filter { it != VideoRules.AUTO_FRAME_RATE }) {
            val limits = VideoSize.entries.map { VideoRules.uploadLimit(it, rate) }
            assertEquals(limits.sorted(), limits)
            assertTrue(limits.all { it > 0 })
        }
    }

    @Test
    fun theStillPictureIsNoCamera() {
        assertEquals(listOf("FrontFacingCamera", "BackFacingCamera"), VideoRules.cameras(devices))
    }

    @Test
    fun videoStartsWithTheFrontCamera() {
        assertEquals("FrontFacingCamera", VideoRules.startCamera(devices))
        assertEquals("FrontFacingCamera", VideoRules.startCamera(devices.reversed()))
        assertEquals("Back", VideoRules.startCamera(listOf("Back", VideoRules.STATIC_PICTURE)))
        assertNull(VideoRules.startCamera(listOf(VideoRules.STATIC_PICTURE)))
    }

    @Test
    fun switchingGoesRoundTheCameras() {
        assertEquals("BackFacingCamera", VideoRules.nextCamera(devices, "FrontFacingCamera"))
        assertEquals("FrontFacingCamera", VideoRules.nextCamera(devices, "BackFacingCamera"))
        // From the still picture (or anything unknown) to the first camera.
        assertEquals("FrontFacingCamera", VideoRules.nextCamera(devices, VideoRules.STATIC_PICTURE))
        assertNull(VideoRules.nextCamera(listOf("FrontFacingCamera", VideoRules.STATIC_PICTURE), "FrontFacingCamera"))
    }

    @Test
    fun videoMovesTheSoundFromTheEarpieceToTheSpeaker() {
        val builtIn = listOf(AudioRoute.EARPIECE, AudioRoute.SPEAKER)
        assertTrue(VideoRules.speakerForVideo(AudioRoute.EARPIECE, builtIn))
        assertFalse(VideoRules.speakerForVideo(AudioRoute.SPEAKER, builtIn))
        assertFalse(VideoRules.speakerForVideo(AudioRoute.EARPIECE, builtIn + AudioRoute.HEADSET))
        assertFalse(VideoRules.speakerForVideo(AudioRoute.EARPIECE, builtIn + AudioRoute.BLUETOOTH))
        assertFalse(VideoRules.speakerForVideo(AudioRoute.BLUETOOTH, builtIn + AudioRoute.BLUETOOTH))
        assertFalse(VideoRules.speakerForVideo(AudioRoute.EARPIECE, listOf(AudioRoute.EARPIECE)))
    }

    @Test
    fun theProximitySensorStaysOffOverVideo() {
        assertTrue(VideoRules.proximityWanted(CallPhase.CONNECTED, AudioRoute.EARPIECE, video = false))
        assertTrue(VideoRules.proximityWanted(CallPhase.OUTGOING, AudioRoute.EARPIECE, video = false))
        assertTrue(VideoRules.proximityWanted(CallPhase.HELD, AudioRoute.EARPIECE, video = false))
        assertFalse(VideoRules.proximityWanted(CallPhase.CONNECTED, AudioRoute.EARPIECE, video = true))
        assertFalse(VideoRules.proximityWanted(CallPhase.CONNECTED, AudioRoute.SPEAKER, video = false))
        assertFalse(VideoRules.proximityWanted(CallPhase.INCOMING, AudioRoute.EARPIECE, video = false))
        assertFalse(VideoRules.proximityWanted(CallPhase.ENDED, AudioRoute.EARPIECE, video = false))
    }

    @Test
    fun addingVideoIsAskedOnlyWhereItCanBeShown() {
        fun update(allowed: Boolean = true, hasVideo: Boolean = false, offersVideo: Boolean = true, onScreen: Boolean = true) =
            VideoRules.remoteUpdate(allowed, hasVideo, offersVideo, onScreen)
        assertEquals(VideoRules.Update.ASK, update())
        assertEquals(VideoRules.Update.DECLINE, update(allowed = false))
        assertEquals(VideoRules.Update.DECLINE, update(onScreen = false))
        // Nothing to decide: no video in the request, or video there already.
        assertEquals(VideoRules.Update.ACCEPT, update(offersVideo = false))
        assertEquals(VideoRules.Update.ACCEPT, update(offersVideo = false, allowed = false))
        assertEquals(VideoRules.Update.ACCEPT, update(hasVideo = true))
    }
}
