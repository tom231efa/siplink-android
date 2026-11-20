package com.sky.siplink.core

import android.os.Handler
import android.os.Looper
import com.sky.siplink.R
import com.sky.siplink.core.CallSupport.isEnded
import com.sky.siplink.core.logic.VideoRules
import com.sky.siplink.core.model.Phrase
import org.linphone.core.Call
import org.linphone.core.CallListenerStub
import org.linphone.core.CallParams
import org.linphone.core.Core

/**
 * The video side of [CallManager]: starting and ending video in a call, the camera, and the
 * other side's requests to add video. The camera never starts by itself: video is only set up
 * where the user asked for it, and the camera only runs while the call screen is in front.
 * Must be used from the thread that owns the core (the main thread).
 *
 * @param cameraPermitted whether the app holds the camera permission; without it video is only received
 * @param onChanged the state of a call changed in a way the call screen shows
 */
internal class VideoController(
    private val core: Core,
    private val settings: SettingsStore,
    private val cameraPermitted: () -> Boolean,
    private val onChanged: () -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())

    /** The call screen is in front: the camera may be used, and only then. */
    private var callScreenShown = false

    /** A call whose other side asked to add video; its request waits for the user's answer. */
    private var request: Call? = null
    private val declineRequest = Runnable { answerRequest(false) }

    /** Tells of the first picture of the other side, whose size the call screen lays it out by. */
    private val firstPicture = object : CallListenerStub() {
        override fun onNextVideoFrameDecoded(call: Call) {
            call.removeListener(this)
            onChanged()
        }
    }

    private val allowed: Boolean get() = settings.current.videoCalls

    /** Sets up the video of a call that is placed or answered: with [video], if video calls are on. */
    fun prepare(params: CallParams, video: Boolean) {
        val withVideo = video && allowed
        CallVideoSupport.apply(params, withVideo, cameraPermitted())
        if (withVideo) CallVideoSupport.useStartCamera(core)
    }

    fun isRequesting(call: Call): Boolean = request?.nativePointer == call.nativePointer

    /** Adds video to [call], or ends it (re-INVITE). */
    fun toggleVideo(call: Call?): Phrase? {
        if (call?.state != Call.State.StreamsRunning) return Phrase.of(R.string.call_video_after_connect)
        if (!allowed) return Phrase.of(R.string.call_video_not_supported)
        val params = core.createCallParams(call) ?: return Phrase.of(R.string.call_cannot_change)
        val on = !CallVideoSupport.hasVideo(call)
        CallVideoSupport.apply(params, on, cameraPermitted())
        if (on) CallVideoSupport.useStartCamera(core)
        call.update(params)
        return null
    }

    /** Stops or starts sending the own camera in [call]; the picture of the other side keeps coming. */
    fun toggleCamera(call: Call?): Phrase? {
        if (call?.state != Call.State.StreamsRunning) return Phrase.of(R.string.call_camera_after_connect)
        val video = CallVideoSupport.stateOf(call) ?: return Phrase.of(R.string.call_no_video)
        if (!video.sending && !cameraPermitted()) return Phrase.of(R.string.call_camera_permission)
        val params = core.createCallParams(call) ?: return Phrase.of(R.string.call_cannot_change)
        CallVideoSupport.apply(params, video = true, camera = !video.sending)
        call.update(params)
        return null
    }

    /** Front and back camera, in turn. */
    fun switchCamera(call: Call?) {
        if (call == null || CallVideoSupport.stateOf(call)?.sending != true) return
        // The official Linphone app follows a change of the device with an update without parameters.
        if (CallVideoSupport.switchCamera(core) && call.state == Call.State.StreamsRunning) call.update(null)
    }

    /** The user's answer to the open request to add video. */
    fun answerRequest(accept: Boolean) {
        handler.removeCallbacks(declineRequest)
        val call = request ?: return
        request = null
        if (call.state == Call.State.UpdatedByRemote) {
            val params = core.createCallParams(call)
            if (params != null) prepare(params, accept)
            call.acceptUpdate(params)
        }
        onChanged()
    }

    fun onCallScreenShown(shown: Boolean) {
        callScreenShown = shown
        core.calls.forEach(::applyCamera)
    }

    fun onAllowedChanged() {
        if (allowed) return
        answerRequest(false)
        for (call in core.calls) {
            if (call.state != Call.State.StreamsRunning || !CallVideoSupport.hasVideo(call)) continue
            core.createCallParams(call)?.let {
                CallVideoSupport.apply(it, video = false, camera = false)
                call.update(it)
            }
        }
    }

    /** The streams of [call] run, at first or after a change. */
    fun onStreaming(call: Call) {
        applyCamera(call)
        val video = CallVideoSupport.stateOf(call) ?: return
        if (!video.receiving || video.received != null) return
        // Later changes of the size are seen with the statistics of the call, once a second;
        // the first picture should not wait that long for its place on the screen.
        call.removeListener(firstPicture)
        call.addListener(firstPicture)
        call.requestNotifyNextVideoFrameDecoded()
    }

    /**
     * Without the call screen in front Android does not let the app have the camera: a still
     * picture is sent in its place, which needs no new offer to the other side.
     */
    fun applyCamera(call: Call) {
        if (isEnded(call) || !CallVideoSupport.hasVideo(call)) return
        if (call.isCameraEnabled != callScreenShown) call.isCameraEnabled = callScreenShown
    }

    /** Must run inside the state callback: an update is only held back (deferred) from there. */
    fun onUpdatedByRemote(call: Call, onScreen: Boolean) {
        val decision = VideoRules.remoteUpdate(
            allowed = allowed,
            hasVideo = CallVideoSupport.hasVideo(call),
            offersVideo = CallVideoSupport.offered(call),
            onScreen = onScreen && request == null,
        )
        when (decision) {
            VideoRules.Update.ACCEPT -> Unit
            VideoRules.Update.ASK -> {
                call.deferUpdate()
                request = call
                handler.postDelayed(declineRequest, REQUEST_TIMEOUT_MS)
                onChanged()
            }
            VideoRules.Update.DECLINE -> {
                call.deferUpdate()
                // Answered once the callback has returned, as a request the user turned down.
                handler.post {
                    if (call.state == Call.State.UpdatedByRemote) {
                        val params = core.createCallParams(call)
                        params?.let { CallVideoSupport.apply(it, video = false, camera = false) }
                        call.acceptUpdate(params)
                    }
                }
            }
        }
    }

    fun onReleased(call: Call) {
        call.removeListener(firstPicture)
        if (!isRequesting(call)) return
        request = null
        handler.removeCallbacks(declineRequest)
    }

    private companion object {
        /** How long the other side's request to add video waits for the user before it is declined. */
        const val REQUEST_TIMEOUT_MS = 20_000L
    }
}
