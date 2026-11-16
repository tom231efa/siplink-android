package com.sky.siplink.core.logic

import com.sky.siplink.core.model.AudioRoute
import com.sky.siplink.core.model.CallPhase
import com.sky.siplink.core.model.PictureSize
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
        assertEquals(3172, VideoRules.uploadLimit(VideoSize.FHD, 30))
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

    /** The screen of the test phone, upright and on its side (9:22). */
    private val upright = 1080 to 2640
    private val onItsSide = 2640 to 1080

    private fun cut(picture: PictureSize, window: Pair<Int, Int>) = VideoRules.cutWhenFilled(picture, window.first, window.second)

    @Test
    fun aPictureOfTheShapeOfTheWindowLosesNothing() {
        assertEquals(0f, cut(PictureSize(1080, 2640), upright), 0.001f)
        assertEquals(0f, cut(PictureSize(540, 1320), upright), 0.001f)
        assertFalse(VideoRules.canFill(cut(PictureSize(540, 1320), upright)))
        assertEquals(1f, VideoRules.fillScale(0f), 0.001f)
        // Nothing to go by: nothing is cut, and nothing divided by zero.
        assertEquals(0f, cut(PictureSize(0, 0), upright), 0f)
        assertEquals(0f, VideoRules.cutWhenFilled(PictureSize(640, 480), 0, 0), 0f)
    }

    @Test
    fun anUprightPictureFillsTheUprightScreenWhereLittleIsLost() {
        // 9:16 from another phone: about a quarter is cut off at the sides.
        val phone = cut(PictureSize(720, 1280), upright)
        assertEquals(0.273f, phone, 0.001f)
        assertTrue(VideoRules.fillsByDefault(phone))
        assertTrue(VideoRules.canFill(phone))
        // 3:4 would lose nearly half: it is shown whole.
        val camera = cut(PictureSize(480, 640), upright)
        assertEquals(0.455f, camera, 0.001f)
        assertFalse(VideoRules.fillsByDefault(camera))
        assertTrue(VideoRules.canFill(camera))
    }

    @Test
    fun aWidePictureIsShownWholeOnTheUprightScreen() {
        for (picture in listOf(PictureSize(1920, 1080), PictureSize(1280, 720), PictureSize(640, 480), PictureSize(352, 288), PictureSize(720, 720))) {
            assertFalse("$picture", VideoRules.fillsByDefault(cut(picture, upright)))
        }
        // With the phone on its side the wide picture of a desk phone fills the screen, a 4:3 one does not.
        assertTrue(VideoRules.fillsByDefault(cut(PictureSize(1280, 720), onItsSide)))
        assertFalse(VideoRules.fillsByDefault(cut(PictureSize(640, 480), onItsSide)))
    }

    @Test
    fun theWholePictureKeepsItsShapeInsideTheWindow() {
        assertEquals(PictureSize(1080, 608), VideoRules.fitted(PictureSize(1280, 720), 1080, 2640))
        assertEquals(PictureSize(1080, 1440), VideoRules.fitted(PictureSize(480, 640), 1080, 2640))
        assertEquals(PictureSize(1080, 1920), VideoRules.fitted(PictureSize(720, 1280), 1080, 2640))
        assertEquals(PictureSize(1440, 1080), VideoRules.fitted(PictureSize(640, 480), 2640, 1080))
        assertEquals(PictureSize(1080, 1080), VideoRules.fitted(PictureSize(720, 720), 1080, 2640))
        // Without a size the view is the window.
        assertEquals(PictureSize(1080, 2640), VideoRules.fitted(PictureSize(0, 0), 1080, 2640))
    }

    @Test
    fun enlargedByTheFillScaleTheWholePictureCoversTheWindow() {
        for (picture in listOf(PictureSize(1280, 720), PictureSize(480, 640), PictureSize(720, 1280), PictureSize(720, 720))) {
            for (window in listOf(upright, onItsSide)) {
                val whole = VideoRules.fitted(picture, window.first, window.second)
                val scale = VideoRules.fillScale(cut(picture, window))
                // The whole picture is rounded to full pixels, which the scale enlarges with it.
                val slack = scale
                assertTrue("$picture in $window is too narrow", whole.width * scale >= window.first - slack)
                assertTrue("$picture in $window is too low", whole.height * scale >= window.second - slack)
                // No larger than it has to be: one of the sides just fits.
                assertTrue(
                    "$picture in $window is larger than the window needs",
                    whole.width * scale <= window.first + slack || whole.height * scale <= window.second + slack,
                )
            }
        }
        // A line of a picture does not make the scale run away.
        assertTrue(VideoRules.fillScale(1f) <= 20f)
    }

    @Test
    fun theWindowOfTheOwnCameraHasTheShapeOfWhatIsSent() {
        assertEquals(111f to 148f, VideoRules.previewSize(PictureSize(480, 640), 148f))
        assertEquals(148f to 111f, VideoRules.previewSize(PictureSize(640, 480), 148f))
        assertEquals(83.25f to 148f, VideoRules.previewSize(PictureSize(1080, 1920), 148f))
        assertEquals(148f to 83.25f, VideoRules.previewSize(PictureSize(1280, 720), 148f))
        // Nothing sent yet: an upright 4:3 camera. A shape beyond 16:9 is held to it.
        assertEquals(111f to 148f, VideoRules.previewSize(null, 148f))
        assertEquals(111f to 148f, VideoRules.previewSize(PictureSize(0, 0), 148f))
        assertEquals(148f to 83.25f, VideoRules.previewSize(PictureSize(3000, 1000), 148f))
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
