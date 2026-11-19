package com.sky.siplink.core.logic

import com.sky.siplink.core.model.AudioRoute
import com.sky.siplink.core.model.CallPhase
import com.sky.siplink.core.model.PictureSize
import com.sky.siplink.core.model.VideoSize
import kotlin.math.roundToInt

/**
 * Decisions about the video of a call that do not need the SIP core; core/VideoController.kt
 * and core/CallVideoSupport.kt apply them.
 */
object VideoRules {
    /**
     * The video device the SDK lists next to the cameras: a still picture it can send instead
     * of one. It is not a camera to switch to.
     */
    const val STATIC_PICTURE = "StaticImage: Static picture"

    /** The frame rates that can be chosen; [AUTO_FRAME_RATE] leaves the rate to the SIP core. */
    val FRAME_RATES = listOf(0, 15, 24, 30)
    const val AUTO_FRAME_RATE = 0

    /** [value] when it is one of [FRAME_RATES], else [AUTO_FRAME_RATE]: what a stored or handed out rate comes to. */
    fun frameRate(value: Int): Int = if (value in FRAME_RATES) value else AUTO_FRAME_RATE

    /**
     * How much the app sends at most, in kbit/s and the sound included, while the camera goes
     * out at a frame rate of the app's choosing; [NO_UPLOAD_LIMIT] with the rate left to the
     * SIP core, which then picks a modest bit rate of its own.
     *
     * Told a frame rate, the core would otherwise send the picture with all the bits its
     * encoder can use: about 10 Mbit/s were measured, whatever the size.
     */
    fun uploadLimit(size: VideoSize, frameRate: Int): Int {
        if (frameRate == AUTO_FRAME_RATE) return NO_UPLOAD_LIMIT
        val atFullRate = when (size) {
            VideoSize.QVGA -> 256
            VideoSize.VGA -> 768
            VideoSize.HD -> 1536
            VideoSize.FHD -> 3072
        }
        return atFullRate * frameRate / FULL_FRAME_RATE + SOUND_KBITS
    }

    /** What the SIP core takes for "no limit". */
    const val NO_UPLOAD_LIMIT = 0

    /**
     * The part of a picture that is cut off when it is enlarged until it fills a window of
     * another shape: 0 where the shapes are the same, towards 1 the more they differ.
     */
    fun cutWhenFilled(picture: PictureSize, windowWidth: Int, windowHeight: Int): Float {
        if (picture.width <= 0 || picture.height <= 0 || windowWidth <= 0 || windowHeight <= 0) return 0f
        val shape = picture.width.toFloat() / picture.height
        val window = windowWidth.toFloat() / windowHeight
        return 1f - minOf(shape, window) / maxOf(shape, window)
    }

    /**
     * Whether a picture fills the window while the user has not said how it is to be shown:
     * only where little of it is lost. A phone held upright then fills the screen with the
     * upright picture of another phone, and shows the wide one of a desk phone or a door
     * station whole.
     *
     * @param cut see [cutWhenFilled]
     */
    fun fillsByDefault(cut: Float): Boolean = cut <= MAX_CUT_BY_DEFAULT

    /** Whether the whole picture and the filled window differ enough for there to be a choice. */
    fun canFill(cut: Float): Boolean = cut >= MIN_CUT_TO_CHOOSE

    /** By how much the picture, shown whole, is enlarged to fill the window. */
    fun fillScale(cut: Float): Float = 1f / (1f - cut.coerceIn(0f, MAX_CUT))

    /** The largest size at which the whole picture fits into the window, in the units of the window. */
    fun fitted(picture: PictureSize, windowWidth: Int, windowHeight: Int): PictureSize {
        if (picture.width <= 0 || picture.height <= 0) return PictureSize(windowWidth, windowHeight)
        val scale = minOf(windowWidth.toFloat() / picture.width, windowHeight.toFloat() / picture.height)
        return PictureSize(
            (picture.width * scale).roundToInt().coerceIn(1, maxOf(windowWidth, 1)),
            (picture.height * scale).roundToInt().coerceIn(1, maxOf(windowHeight, 1)),
        )
    }

    /**
     * Width and height of the small window of the own camera: the shape of the picture that is
     * sent, its longer side [longSide] long. Before a picture goes out it has the shape of an
     * upright 4:3 camera; shapes more stretched than 16:9 are held to that.
     */
    fun previewSize(sent: PictureSize?, longSide: Float): Pair<Float, Float> {
        val shape = (sent?.takeIf { it.width > 0 && it.height > 0 }?.let { it.width.toFloat() / it.height } ?: UPRIGHT_CAMERA)
            .coerceIn(1f / WIDEST_PREVIEW, WIDEST_PREVIEW)
        return if (shape >= 1f) longSide to longSide / shape else longSide * shape to longSide
    }

    /**
     * A tap on the small picture swaps the two pictures (the own one large) once both are there:
     * the camera sends, and a picture of the other side came.
     */
    fun canSwap(sending: Boolean, received: Boolean): Boolean = sending && received

    /** The pictures stay swapped while the user wants it and both are there; without one of them they go back. */
    fun swapped(chosen: Boolean, sending: Boolean, received: Boolean): Boolean = chosen && canSwap(sending, received)

    /** The cameras among the video devices of the SDK, in its order. */
    fun cameras(devices: List<String>): List<String> = devices.filter { it != STATIC_PICTURE }

    /** The camera a video call starts with: the one facing the user, else the first there is. */
    fun startCamera(devices: List<String>): String? {
        val cameras = cameras(devices)
        return cameras.firstOrNull { it.contains(FRONT, ignoreCase = true) } ?: cameras.firstOrNull()
    }

    /** The camera after [current], round the list; null when there is nothing to switch to. */
    fun nextCamera(devices: List<String>, current: String?): String? {
        val cameras = cameras(devices)
        if (cameras.size < 2) return null
        return cameras[(cameras.indexOf(current) + 1) % cameras.size]
    }

    /**
     * Whether the sound moves to the loudspeaker as the video of a call starts: one looks at the
     * screen, not holds the phone to the ear. A headset or a Bluetooth device keeps the sound,
     * and so does a route other than the earpiece that the user picked.
     */
    fun speakerForVideo(route: AudioRoute, available: List<AudioRoute>): Boolean =
        route == AudioRoute.EARPIECE && AudioRoute.SPEAKER in available &&
            AudioRoute.HEADSET !in available && AudioRoute.BLUETOOTH !in available

    /** Whether the screen goes dark near the ear: with the earpiece in use, and never while video is shown. */
    fun proximityWanted(phase: CallPhase, route: AudioRoute, video: Boolean): Boolean =
        !video && route == AudioRoute.EARPIECE &&
            (phase == CallPhase.OUTGOING || phase == CallPhase.CONNECTED || phase == CallPhase.HELD)

    /** What becomes of a request of the other side to change a running call. */
    enum class Update {
        /** Nothing for the user to decide (no video added): it is taken as it comes. */
        ACCEPT,

        /** Video is to be added: the user is asked first, the camera stays off until then. */
        ASK,

        /** Video is to be added where it cannot be: taken without the video. */
        DECLINE,
    }

    /**
     * @param allowed video calls are on in the settings
     * @param hasVideo the call has video already
     * @param offersVideo the request of the other side has video in it
     * @param onScreen the call is the one on the call screen (not held, not in the conference room)
     */
    fun remoteUpdate(allowed: Boolean, hasVideo: Boolean, offersVideo: Boolean, onScreen: Boolean): Update = when {
        !offersVideo || hasVideo -> Update.ACCEPT
        !allowed || !onScreen -> Update.DECLINE
        else -> Update.ASK
    }

    private const val FRONT = "front"

    /** The frame rate the bit rates of [uploadLimit] are meant for; fewer frames get as much less. */
    private const val FULL_FRAME_RATE = 30

    /** Room for the sound next to the picture: G.711 with its packet headers stays below this. */
    private const val SOUND_KBITS = 100

    /** Up to this much of a picture may be cut off for it to fill the window without being asked. */
    private const val MAX_CUT_BY_DEFAULT = 0.3f

    /** Below this the whole picture and the filled window look the same. */
    private const val MIN_CUT_TO_CHOOSE = 0.02f

    /** Keeps [fillScale] finite for a picture that is all but a line. */
    private const val MAX_CUT = 0.95f

    private const val UPRIGHT_CAMERA = 3f / 4f
    private const val WIDEST_PREVIEW = 16f / 9f
}
