package com.sky.siplink.itest

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.sky.siplink.CallActivity
import com.sky.siplink.core.CallSupport
import com.sky.siplink.core.ConferenceRoom
import com.sky.siplink.core.RoomMonitor
import com.sky.siplink.core.logic.RoomRules
import com.sky.siplink.core.model.CallPhase
import com.sky.siplink.core.model.MemberState
import com.sky.siplink.core.model.RoomWarning
import com.sky.siplink.openCallScreen
import com.sky.siplink.service.CallNotifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.linphone.core.Call
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * The conference room: opened from a call, members added by calling them and by taking a call
 * that comes in, one member hung up while the others stay, no video, the limit, and the end;
 * a warning that comes and goes, the notification of the room, the name of a member on its way
 * out, a row that moves under a tap, a call that does not fit, and a room that cannot be opened.
 *
 * The members are numbers of the test PBX that answer and beep now and then (25XX, 26XX ring
 * first; testenv/asterisk/config/extensions.conf) and report what they hear; that a member hears
 * the others proves that the app mixes the room. The SDK only mixes with the sound card, so the
 * app does not send its tone file here: its microphone is muted instead.
 */
class ConferenceTest : RoomTestBase("conference") {
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
        tapRow("挂断 $name")
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

    @Test
    fun aWarningThatGoesLeavesTheRoomStanding() {
        openRoom("2501", "2502")
        // A thread of the app's process that keeps a core busy: the room warns of the load.
        val busy = AtomicBoolean(true)
        val burner = thread(name = "itest-load") {
            var spins = 0L
            while (busy.get()) spins++
        }
        try {
            awaitRoom("the warning of the load") { RoomWarning.LOAD in it.warnings }
            see("设备负载过高")
            picture("warning-load")
        } finally {
            busy.set(false)
            burner.join()
        }
        // It goes again while the room stays; its chip fades out.
        awaitRoom("the warning to go") { it.warnings.isEmpty() }
        await("the chip to be gone") { shown("设备负载过高").isEmpty() }
        assertEquals(2, connected(room()!!))
        see("结束会议")
    }

    @Test
    fun theNotificationShowsTheRoomAndItsHangUpAsksFirst() {
        callFirstMember()
        await("the notification of the call") { ongoingTitle() != null }
        ok(onMain { engine.calls.openRoom() })
        await("the notification to show the room") { ongoingTitle() == "会议室" && ongoingHangUp()?.isBroadcast == true }
        ok(onMain { engine.calls.inviteToRoom(listOf("2502")) })
        awaitRoom("two members") { connected(it) == 2 }
        // With more than one member its hang up key opens the call screen, which asks first (CallRules, rule 6).
        await("the hang up key of the notification to ask first") { ongoingHangUp()?.isActivity == true }
        ongoingHangUp()!!.send()
        see("结束会议？")
        tap("取消")
        never("the room ended without a yes", QUIET_WINDOW_MS) { room() == null }
        onMain { engine.calls.hangUp() }
        awaitNoCall()
    }

    @Test
    fun aMemberThePbxHangsUpKeepsItsNameUntilItsRowGoes() {
        openRoom("2501", "2502")
        val name = room()!!.members.first { it.address.startsWith("2502@") }.name
        assertEquals("成员02", name)
        pbx.command("channel request hangup ${legOf("2502")}")
        // Its row in every state of the room, until it goes (RoomRules.LEFT_LINGER_MS after the member left).
        val seen = LinkedHashSet<String>()
        await("the member to leave the list") {
            val member = room()!!.members.firstOrNull { it.address.startsWith("2502@") }
            member?.let { seen += "${it.name}/${it.state}" }
            member == null
        }
        assertTrue("Its row never showed that it left: $seen", seen.any { it.endsWith("/${MemberState.LEFT}") })
        assertTrue("It was named otherwise on its way out: $seen", seen.all { it.startsWith("$name/") })
        onMain { engine.calls.hangUp() }
        awaitNoCall()
    }

    @Test
    fun aTapOnARowThatMovedUnderTheFingerIsNotTaken() {
        openRoom("2501", "2502", "2503")
        val staying = room()!!.members.first { it.address.startsWith("2503@") }.name
        val stayingLeg = legOf("2503")!!
        // What the room's own set-up moved has stood still past the guard: only the row that goes below can keep the tap back.
        awaitRowsStill("挂断 $staying")
        // The PBX ends the call of the member above; its row stays a moment, then goes, and the row below moves up.
        pbx.command("channel request hangup ${legOf("2502")}")
        awaitRoom("the member to leave") { room -> room.members.any { it.address.startsWith("2502@") && it.state == MemberState.LEFT } }
        awaitRowsStill("挂断 $staying")
        val button = chain(shown("挂断 $staying").single()).first { it.isClickable }
        val bounds = Rect()
        button.getBoundsInScreen(bounds)
        val stoodAt = bounds.top
        assertTrue("The row of the member that left went before the rows stood still", room()!!.members.any { it.address.startsWith("2502@") })
        val goneAt = until("the row of the member that left to go") { room()!!.members.none { it.address.startsWith("2502@") } }
        val movingAt = until("the row below to move") {
            button.refresh()
            button.getBoundsInScreen(bounds)
            bounds.top != stoodAt
        }
        // A tap right then, aimed at the row where it stood, is not taken.
        assertTrue(button.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        val tappedAt = SystemClock.uptimeMillis()
        Log.i(TAG, "row gone, the row below moving after ${movingAt - goneAt} ms, tapped after ${tappedAt - goneAt} ms")
        // The tap must have come while the row's going still kept taps back; a phone too slow for that fails here, as it tested nothing.
        assertTrue(
            "The tap came ${tappedAt - goneAt} ms after the row went, later than the ${RoomRules.ROW_TAP_GUARD_MS} ms a tap is kept back: it tests nothing",
            tappedAt - goneAt < RoomRules.ROW_TAP_GUARD_MS,
        )
        never("the member hung up by a tap on a row that moved under the finger", QUIET_WINDOW_MS) { pbxChannel(stayingLeg) == null }
        // Once the rows are still, it is.
        tapRow("挂断 $staying")
        await("the member's leg to go") { pbxChannel(stayingLeg) == null }
    }

    /** Waits, looking every few milliseconds, until [condition] holds. @return SystemClock.uptimeMillis() when it was seen to */
    private fun until(what: String, condition: () -> Boolean): Long {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
        while (!condition()) {
            if (SystemClock.uptimeMillis() > deadline) throw AssertionError("Timed out waiting for $what. App call: ${call()}")
            Thread.sleep(QUICK_POLL_MS)
        }
        return SystemClock.uptimeMillis()
    }

    @Test
    fun aCallThatDoesNotFitIntoTheRoomKeepsRingingAndSaysWhy() {
        settings { it.copy(roomLimit = 2) }
        openRoom("2501", "2502")
        phoneCalls(CUSTOMER, "$APP", "tone")
        awaitCall("the call to wait") { it.waitingCall != null }
        // Answered from its notification: through the call screen, which says why that cannot be.
        context.startActivity(Intent(context, CallActivity::class.java).setAction(CallActivity.ACTION_ANSWER).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        see(zh(RoomRules.refusal(present = 2, adding = 1, limit = 2)!!))
        never("the waiting call answered or gone", QUIET_WINDOW_MS) { call()?.waitingCall == null }
        assertEquals(2, room()!!.present)
        tap("拒接")
        await("the caller to be turned away") { call()?.waitingCall == null && !phoneHasCall(CUSTOMER) }
    }

    @Test
    fun aRoomThatCannotOpenGivesItsCallBackUnended() {
        callFirstMember()
        val first = onMain { engine.core.currentCall!! }
        // A room past the call manager, asked to take the same call twice: the SDK takes it once only.
        val other = onMain { ConferenceRoom(engine.core, RoomMonitor(context), { CallSupport.side(it.remoteAddress) }, { RoomRules.LIMIT }, { true }, engine.texts::render) {} }
        assertEquals("无法创建会议室", onMain { other.open(listOf(first, first), muted = true) }?.let(::zh))
        assertEquals(RoomRules.MAX_CALLS_WITHOUT_ROOM, onMain { engine.core.maxCalls })
        // Taken out of the conference again, the call goes on, on hold; no conference is left.
        await("the call to be out of the conference") { onMain { first.conference == null } }
        never("the call ended", QUIET_WINDOW_MS) { onMain { CallSupport.isEnded(first) } }
        Log.i(TAG, "after the undo: state=${onMain { first.state }}")
        // With no call left in it, the user is in no conference either (only the core itself can tell).
        @Suppress("DEPRECATION")
        assertFalse(onMain { engine.core.isInConference })
        // The user takes it off hold and talks on.
        if (onMain { first.state } == Call.State.Paused) onMain { engine.calls.toggleHold() }
        await("the call to run again") { onMain { first.state } == Call.State.StreamsRunning }
        onMain { engine.calls.hangUp() }
        awaitNoCall()
    }

    /** What the notification of the call that is up says, and where its hang up key leads. */
    private fun ongoing(): Notification? = context.getSystemService(NotificationManager::class.java).activeNotifications
        .firstOrNull { it.id == CallNotifier.ID_SERVICE }?.notification

    private fun ongoingTitle(): String? = ongoing()?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()

    private fun ongoingHangUp(): PendingIntent? = ongoing()?.extras?.getParcelable(Notification.EXTRA_HANG_UP_INTENT, PendingIntent::class.java)

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

        /** How often [until] looks: well under the time a tap is kept back after the rows moved. */
        const val QUICK_POLL_MS = 5L
        const val TAG = "SipLinkConferenceTest"
    }
}
