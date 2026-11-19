package com.sky.siplink.core

import com.sky.siplink.core.logic.VideoRules
import com.sky.siplink.core.model.CallVideo
import com.sky.siplink.core.model.PictureSize
import com.sky.siplink.core.model.VideoSize
import org.linphone.core.Call
import org.linphone.core.CallParams
import org.linphone.core.Core
import org.linphone.core.Factory
import org.linphone.core.MediaDirection
import org.linphone.core.VideoDefinition

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
     * The size and the frame rate the camera is sent with. They hold for the video that starts
     * after this; what really goes out is still up to the camera, the codec and the network.
     *
     * @param frameRate frames per second, or [VideoRules.AUTO_FRAME_RATE]
     */
    fun setQuality(core: Core, size: VideoSize, frameRate: Int) {
        core.setPreferredVideoDefinitionByName(size.definition)
        // The core picks a rate of its own (about 15 on a phone) unless its preset is "custom";
        // only then does it go by the preferred one.
        val fixed = frameRate != VideoRules.AUTO_FRAME_RATE
        core.videoPreset = if (fixed) CUSTOM_PRESET else null
        core.preferredFramerate = if (fixed) frameRate.toFloat() else 0f
        // Under that preset the core sends with all the bits it is allowed.
        core.uploadBandwidth = VideoRules.uploadLimit(size, frameRate)
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
        val sending = direction == MediaDirection.SendRecv || direction == MediaDirection.SendOnly
        val receiving = direction == MediaDirection.SendRecv || direction == MediaDirection.RecvOnly
        return CallVideo(
            sending = sending,
            receiving = receiving,
            received = if (receiving) sizeOf(params.receivedVideoDefinition) else null,
            sent = if (sending) sizeOf(params.sentVideoDefinition) else null,
        )
    }

    /** The core gives a size of 0 x 0 for a picture there has been none of yet. */
    private fun sizeOf(definition: VideoDefinition?): PictureSize? =
        definition?.takeIf { it.width > 0 && it.height > 0 }?.let { PictureSize(it.width, it.height) }

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

    /** The video preset of the SIP core under which its preferred frame rate counts. */
    private const val CUSTOM_PRESET = "custom"
}
