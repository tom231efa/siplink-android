package com.sky.siplink.core.logic

import com.sky.siplink.core.model.AudioRoute
import com.sky.siplink.core.model.CallPhase

/** Decisions about the video of a call that do not need the SIP core; it applies them in core/CallVideo.kt. */
object VideoRules {
    /**
     * The video device the SDK lists next to the cameras: a still picture it can send instead
     * of one. It is not a camera to switch to.
     */
    const val STATIC_PICTURE = "StaticImage: Static picture"

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
     * @param onScreen the call is the one on the call screen (not held, not in a three-way call)
     */
    fun remoteUpdate(allowed: Boolean, hasVideo: Boolean, offersVideo: Boolean, onScreen: Boolean): Update = when {
        !offersVideo || hasVideo -> Update.ACCEPT
        !allowed || !onScreen -> Update.DECLINE
        else -> Update.ASK
    }

    private const val FRONT = "front"
}
