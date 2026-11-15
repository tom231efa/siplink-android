package com.sky.siplink.core

import com.sky.siplink.core.logic.VideoRules
import com.sky.siplink.core.model.CallVideo
import org.linphone.core.Call
import org.linphone.core.CallParams
import org.linphone.core.Core
import org.linphone.core.Factory
import org.linphone.core.MediaDirection

/** Stateless helpers of [CallManager] for the video of a call. */
internal object CallVideoSupport {
    /** Video only when asked for: calls start, are answered and are changed audio-only otherwise. */
    fun configure(core: Core, enabled: Boolean) {
        val policy = Factory.instance().createVideoActivationPolicy()
        policy.automaticallyInitiate = false
        policy.automaticallyAccept = false
        core.videoActivationPolicy = policy
        setEnabled(core, enabled)
    }

    fun setEnabled(core: Core, enabled: Boolean) {
        core.isVideoCaptureEnabled = enabled
        core.isVideoDisplayEnabled = enabled
    }

    /**
     * Sets the video of [params]. With [video] the picture of the other side is received, and
     * the camera sent when [camera] (the user wants it and may use it); without, there is none.
     */
    fun apply(params: CallParams, video: Boolean, camera: Boolean) {
        params.isVideoEnabled = video
        if (video) params.videoDirection = if (camera) MediaDirection.SendRecv else MediaDirection.RecvOnly
    }

    /** True when the other side offers video: in an incoming call, or in a request to change the call. */
    fun offered(call: Call): Boolean {
        val remote = call.remoteParams ?: return false
        return remote.isVideoEnabled && remote.videoDirection != MediaDirection.Inactive
    }

    /** True when the call has video, or, while it is being set up, is meant to. */
    fun hasVideo(call: Call): Boolean = paramsOf(call).isVideoEnabled

    /** Which ways the video of the call goes; null when it has none. */
    fun stateOf(call: Call): CallVideo? {
        val params = paramsOf(call)
        if (!params.isVideoEnabled) return null
        val direction = params.videoDirection
        return CallVideo(
            sending = direction == MediaDirection.SendRecv || direction == MediaDirection.SendOnly,
            receiving = direction == MediaDirection.SendRecv || direction == MediaDirection.RecvOnly,
        )
    }

    /** What the call was set up with while it is, and what was agreed on once it runs. */
    private fun paramsOf(call: Call): CallParams = when (call.state) {
        Call.State.OutgoingInit, Call.State.OutgoingProgress, Call.State.OutgoingRinging,
        Call.State.OutgoingEarlyMedia, Call.State.Idle,
        -> call.params
        else -> call.currentParams
    }

    /** A video call starts with the camera facing the user. */
    fun useStartCamera(core: Core) {
        val camera = VideoRules.startCamera(core.videoDevicesList.toList()) ?: return
        if (core.videoDevice != camera) core.videoDevice = camera
    }

    /** Switches to the next camera; false when there is no other. */
    fun switchCamera(core: Core): Boolean {
        val next = VideoRules.nextCamera(core.videoDevicesList.toList(), core.videoDevice) ?: return false
        core.videoDevice = next
        return true
    }
}
