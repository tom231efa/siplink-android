package com.sky.siplink.itest

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import com.sky.siplink.core.model.CallVideo
import com.sky.siplink.openCallScreen
import org.junit.Assert.assertTrue

/** Helpers of the video tests: calls with video, what the SDK sends and receives, the camera. */
abstract class VideoTestBase : PbxTestBase() {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    protected fun grant(permission: String) =
        instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName, permission)

    protected fun dialVideo(target: String) = ok(onMain { engine.calls.dial(target, null, video = true) })

    protected fun awaitVideo(what: (CallVideo) -> Boolean): CallVideo =
        awaitCall("the video of the call to match") { it.video?.let(what) == true }.video!!

    /** Video RTP packets of the current call, received and sent. */
    private fun videoPackets(): Pair<Long, Long> = onMain {
        engine.core.currentCall?.videoStats?.let { it.rtpPacketRecv to it.rtpPacketSent } ?: (0L to 0L)
    }

    protected fun videoPacketsSent(): Long = videoPackets().second

    /** Waits until video packets keep coming in (the PBX echoes them). */
    protected fun awaitVideoReceived() {
        val start = videoPackets().first
        await("video packets to arrive") { videoPackets().first > start + VIDEO_PACKETS }
    }

    protected fun awaitVideoSent() {
        val start = videoPackets().second
        await("video packets to be sent") { videoPackets().second > start + VIDEO_PACKETS }
    }

    /** True when the PBX leg of [extension] carries a video format. */
    protected fun pbxHasVideo(extension: Int): Boolean {
        val channel = pbxChannel(pbxLeg(extension))?.get("Channel") ?: return false
        val formats = pbx.command("core show channel $channel").lineSequence()
            .firstOrNull { it.trimStart().startsWith("NativeFormats") }.orEmpty()
        return VIDEO_FORMATS.any { it in formats }
    }

    /**
     * The PBX calls the app with a call that offers video; once answered it goes on at [exten]
     * of the context "videotests" (testenv/asterisk/config/extensions.conf).
     */
    protected fun pbxCallsApp(exten: String, channel: String = "PJSIP/$APP") {
        val response = pbx.send(
            "Originate",
            "Channel" to channel,
            "Context" to "videotests",
            "Exten" to exten,
            "Priority" to "1",
            "Codecs" to "ulaw,alaw,vp8",
            "CallerID" to "Video <$VIDEO_CALLER>",
            "Async" to "true",
        )
        assertTrue("Originate refused: $response", response["Response"] == "Success")
    }

    /** Whether the camera service has a client in the app right now. */
    protected fun cameraInUse(): Boolean {
        val dump = shellOutput("dumpsys media.camera")
        val clients = dump.substringAfter("Active Camera Clients:", "").substringBefore("Allowed user IDs", "")
        return instrumentation.targetContext.packageName in clients
    }

    protected fun openCallScreen() {
        shell("input keyevent KEYCODE_WAKEUP")
        instrumentation.targetContext.openCallScreen()
    }

    protected fun leaveCallScreen() = shell("input keyevent KEYCODE_HOME")

    private fun shellOutput(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .use { it.readBytes().decodeToString() }

    protected companion object {
        const val VIDEO_PACKETS = 30
        const val VIDEO_CALLER = 2100
        val VIDEO_FORMATS = listOf("vp8", "h264")
    }
}
