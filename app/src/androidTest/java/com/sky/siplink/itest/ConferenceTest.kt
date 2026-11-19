package com.sky.siplink.itest

import android.Manifest
import android.content.pm.ActivityInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.sky.siplink.core.logic.RoomRules
import com.sky.siplink.core.model.CallPhase
import com.sky.siplink.core.model.MemberState
import com.sky.siplink.openCallScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The conference room: opened from a call, members added by calling them and by taking a call
 * that comes in, one member hung up while the others stay, no video, the limit, and the end.
 *
 * The members are numbers of the test PBX that answer and beep now and then (25XX, 26XX ring
 * first; testenv/asterisk/config/extensions.conf) and report what they hear; that a member hears
 * the others proves that the app mixes the room. The SDK only mixes with the sound card, so the
 * app does not send its tone file here: its microphone is muted instead.
 */
class ConferenceTest : RoomTestBase("conference") {
    /** The PBX leg of the app that runs the member [number]. */
    private fun legOf(number: String): String? = pbxChannels()
        .firstOrNull { it["Channel"].orEmpty().startsWith(pbxLeg(APP)) && it["Exten"] == number }?.get("Channel")

    /** Every leg of [legs] hears the others through the app: the PBX reports sound on it. */
    private fun awaitEveryLegHears(legs: Collection<String>, from: Int) {
        for (leg in legs) pbx.awaitEvent("ChannelTalkingStart", from, ROOM_SOUND_TIMEOUT_MS) { it["Channel"] == leg }
    }

    @Test
    fun aCallBecomesARoomAndMembersAreCalledIntoIt() {
        callFirstMember()
        tap("会议室")
        val opened = awaitRoom("the room") { it.present == 1 }
        assertEquals(RoomRules.LIMIT, opened.limit)
        assertTrue("The microphone was unmuted by opening the room", call()!!.muted)
        see(RoomRules.count(1, RoomRules.LIMIT))
        picture("room-1")

        // Several at once, from the sheet: typed numbers.
        tap("添加成员")
        for (number in listOf("2502", "2601", "2599")) {
            typeInto("号码或姓名", number)
            tap("添加号码")
        }
        tap("呼叫（3）")
        // The ringing one shows that it rings, the long name fades.
        awaitRoom("the member that rings first") { room -> room.members.any { it.state == MemberState.RINGING } }
        back()
        picture("room-ringing")
        val full = awaitRoom("four members in the room") { it.present == 4 && connected(it) == 4 }
        assertTrue(full.members.any { it.name.startsWith("Participant with a rather long") })
        await("the PBX to have four legs of the app up") { appLegsUp().size == 4 }
        assertEquals(CallPhase.CONNECTED, call()!!.phase)
        picture("room-4")

        // Each member hears the others: the app mixes them.
        val mark = pbx.mark()
        awaitEveryLegHears(appLegsUp(), mark)

        // On its side, the list stands beside the controls.
        val screen = callScreen()
        try {
            turnCallScreen(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
            await("the call screen to be turned") { screen.resources.displayMetrics.let { it.widthPixels > it.heightPixels } }
            see("添加成员")
            see("挂断 ${full.members.last().name}")
            // The system turns the window with an animation of its own, which the tree does not show.
            Thread.sleep(TURN_MS)
            picture("room-4-landscape")
        } finally {
            runCatching { onMain { screen.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT } }
        }
    }

    @Test
    fun hangingUpOneMemberLeavesTheOthersAlone() {
        callFirstMember()
        ok(onMain { engine.calls.openRoom() })
        ok(onMain { engine.calls.inviteToRoom(listOf("2502", "2503")) })
        awaitRoom("three members") { connected(it) == 3 }
        val gone = legOf("2502")!!
        val stay = appLegsUp() - gone

        val name = room()!!.members.first { it.address.startsWith("2502@") }.name
        // Looked for without scrolling: a tap right after the list moved is not taken (RoomRules.rowTapTaken).
        await("the row of the member") { shown("挂断 $name").isNotEmpty() }
        tap("挂断 $name")
        await("the member's leg to go") { pbxChannel(gone) == null }
        val left = awaitRoom("the member to leave the list") { room -> room.members.none { it.address.startsWith("2502@") } }
        assertEquals(2, connected(left))
        assertEquals("The others were disturbed", stay.toSet(), appLegsUp().toSet())
        // ... and still hear each other through the app.
        val mark = pbx.mark()
        awaitEveryLegHears(stay, mark)
    }

    @Test
    fun theRoomEndsWithItsLastMember() {
        callFirstMember()
        ok(onMain { engine.calls.openRoom() })
        ok(onMain { engine.calls.inviteToRoom(listOf("2502")) })
        awaitRoom("two members") { connected(it) == 2 }

        // The PBX hangs up one member after the other: the room stays with one, and goes with the last.
        pbx.command("channel request hangup ${legOf("2501")}")
        awaitRoom("one member left") { it.present == 1 }
        never("the room closed with a member in it", QUIET_WINDOW_MS) { room() == null }
        pbx.command("channel request hangup ${legOf("2502")}")
        assertNull("The ended screen still shows the room", awaitEnded().room)
        awaitNoCall()
    }

    @Test
    fun endingTheRoomEndsEveryCallOfIt() {
        callFirstMember()
        ok(onMain { engine.calls.openRoom() })
        ok(onMain { engine.calls.inviteToRoom(listOf("2502", "2601")) })
        awaitRoom("a member connected and one ringing") { room -> room.members.any { it.state == MemberState.RINGING } }
        // Ending it for everyone is asked for again; a no leaves the room alone.
        tap("结束会议")
        see("结束会议？")
        picture("end-confirmation")
        tap("取消")
        never("the room ended without a yes", QUIET_WINDOW_MS) { room() == null }
        tap("结束会议")
        tap("确认结束")
        awaitNoCall()
        await("the PBX to have no leg of the app") { pbxChannels().none { it["Channel"].orEmpty().startsWith(pbxLeg(APP)) } }
    }

    @Test
    fun anIncomingCallJoinsTheRoomOnlyWhenTheUserSaysSo() {
        callFirstMember()
        // Neither answering by itself nor the intercom switch takes a call into the room.
        settings { it.copy(autoAnswer = true, autoAnswerSeconds = 0, intercomAutoAnswer = true) }
        ok(onMain { engine.calls.openRoom() })
        awaitRoom("the room") { it.present == 1 }

        phoneCalls(AGENT, "*80$APP")
        awaitCall("the intercom call to wait") { it.waitingCall != null && it.room != null }
        never("an intercom call answered into the room by itself", QUIET_WINDOW_MS) { (room()?.present ?: 0) > 1 }
        tap("拒接")
        await("the intercom call to be turned away") { call()?.waitingCall == null && !phoneHasCall(AGENT) }

        phoneCalls(CUSTOMER, "$APP", "tone")
        awaitCall("the call to wait") { it.waitingCall != null }
        never("a call answered into the room by itself", QUIET_WINDOW_MS) { (room()?.present ?: 0) > 1 }
        see("加入会议室")
        picture("room-waiting")
        val mark = phones.mark()
        tap("加入会议室")
        val joined = awaitRoom("the caller in the room") { connected(it) == 2 }
        assertNull(call()!!.waitingCall)
        assertTrue(joined.members.any { it.address.startsWith("$CUSTOMER@") })
        // The caller hears the room (the beeps of 2501) through the app.
        awaitPhoneHears(CUSTOMER, mark)
    }

    @Test
    fun theRoomHasNoVideo() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        onMain { engine.core.useFiles = false }
        showApp()
        register()
        // A video call becomes a room: its video ends.
        ok(onMain { engine.calls.dial("2000", null, video = true) })
        awaitCall("the video call") { it.phase == CallPhase.CONNECTED && it.video != null }
        onMain { engine.calls.toggleMute() }
        context.openCallScreen()
        callScreen()
        tap("会议室")
        see("会议室仅支持语音，视频将关闭。")
        tap("开启")
        awaitRoom("the room") { it.present == 1 }
        await("the video of the call to end") { !anyVideo() }
        // The app asked for the change: what the PBX answered is the description of the other side.
        await("the PBX to close the video stream") { onMain { engine.core.calls.none { it.remoteParams?.isVideoEnabled == true } } }
        // The video key of the room takes no tap.
        locked("视频")
        assertEquals("会议室不支持视频", onMain { engine.calls.toggleVideo() })

        // A member that asks to add video stays without it.
        pbx.send(
            "Originate",
            "Channel" to "PJSIP/$APP",
            "Context" to "videotests",
            "Exten" to "upgrade",
            "Priority" to "1",
            "Codecs" to "ulaw,alaw,vp8",
            "CallerID" to "Video <2100>",
            "Async" to "true",
        )
        awaitCall("the video call to wait") { it.waitingCall != null }
        ok(onMain { engine.calls.joinRoom() })
        awaitRoom("two members") { connected(it) == 2 }
        // The PBX asks for video three seconds after the answer.
        never("video in the room", QUIET_WINDOW_MS * 2) { anyVideo() }
        assertEquals(2, room()!!.present)
    }

    @Test
    fun noMoreMembersThanTheRoomHolds() {
        callFirstMember()
        ok(onMain { engine.calls.openRoom() })
        awaitRoom("the room") { it.present == 1 }
        val tooMany = (1..RoomRules.LIMIT).map { "${2510 + it}" }
        assertEquals(RoomRules.refusal(1, RoomRules.LIMIT), onMain { engine.calls.inviteToRoom(tooMany) })
        never("a call placed beyond the limit", QUIET_WINDOW_MS) { appLegsUp().size > 1 }
        // Nor is a member called twice.
        assertEquals("已在会议室中", onMain { engine.calls.inviteToRoom(listOf("2501")) })
    }

    /**
     * Whether a call of the app carries video, as the app and the PBX last agreed on it. (The PBX's
     * own view of the channel, its NativeFormats, keeps the video codec after the stream was closed.)
     */
    private fun anyVideo(): Boolean = onMain { engine.core.calls.any { it.currentParams.isVideoEnabled } }

    private companion object {
        /** A member beeps every 3 s; the PBX reports sound once a beep of another member came through. */
        const val ROOM_SOUND_TIMEOUT_MS = 20_000L

        /** Longer than the animation that turns the screen. */
        const val TURN_MS = 1_000L
    }
}
