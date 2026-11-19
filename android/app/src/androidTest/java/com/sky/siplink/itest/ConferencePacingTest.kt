package com.sky.siplink.itest

import android.util.Log
import com.sky.siplink.core.logic.RoomPacing
import com.sky.siplink.core.logic.RoomRules
import com.sky.siplink.core.model.MemberState
import com.sky.siplink.core.model.RoomInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.linphone.core.Call

/**
 * The room calls its members without overrunning the PBX: a few set-ups at a time, the others
 * waiting their turn in the list; a PBX that has had enough stops the calling, and the user calls
 * the members it held back again by hand. The room is as big as the setting allows.
 *
 * The members: 27XX answer after two seconds without ringing, 28XX are turned away with 503, 29XX
 * are busy, 24XX answer and hang up right away as FreePBX does over an extension's limit of
 * calls (testenv/asterisk/config/extensions.conf).
 */
class ConferencePacingTest : RoomTestBase("pacing") {
    private fun heldBack(room: RoomInfo, label: String): List<String> =
        room.members.filter { it.state == MemberState.HELD_BACK && it.endReason == label }.map { it.name }

    /** The calls of the app the PBX has taken in since [mark] for numbers starting with [prefix]. */
    private fun callsTo(prefix: String, mark: Int): Int = pbx.events("Newexten", mark) {
        it["Channel"].orEmpty().startsWith(pbxLeg(APP)) && it["Exten"].orEmpty().startsWith(prefix) && it["Priority"] == "1"
    }.size

    @Test
    fun membersAreCalledAFewAtATime() {
        openRoom("2501")
        val numbers = (1..9).map { "27%02d".format(it) }
        ok(onMain { engine.calls.inviteToRoom(numbers) })
        see("等待呼叫")
        // The rows fade in.
        Thread.sleep(ROWS_SHOWN_MS)
        picture("queued")
        // How many set-ups are under way at once, as the PBX and the SIP core see them.
        var atPbx = 0
        var inCore = 0
        await("every member connected", LONG_TIMEOUT_MS) {
            atPbx = maxOf(atPbx, pbxChannels().count { it["Exten"].orEmpty().startsWith("27") && it["ChannelStateDesc"] != "Up" })
            inCore = maxOf(inCore, onMain { engine.core.calls.count { it.state in SETTING_UP } })
            connected(room()!!) == 1 + numbers.size
        }
        Log.i(TAG, "set-ups at once: pbx=$atPbx core=$inCore")
        assertTrue("$atPbx set-ups at once at the PBX", atPbx <= RoomPacing.AT_ONCE)
        assertTrue("$inCore set-ups at once in the core", inCore <= RoomPacing.AT_ONCE)
        assertTrue("The set-ups never overlapped: $atPbx", atPbx >= 2)
        // Called one by one, they joined the room the user is in.
        assertTrue(onMain { engine.core.calls.any { it.conference?.isIn == true } })
        onMain { engine.calls.hangUp() }
        awaitNoCall()
    }

    @Test
    fun aFullLineStopsTheCallingAndTheUserCallsAgainByHand() {
        openRoom("2501")
        val mark = pbx.mark()
        val numbers = (1..6).map { "28%02d".format(it) }
        ok(onMain { engine.calls.inviteToRoom(numbers) })
        awaitRoom("every member held back") { heldBack(it, "线路已满").size == numbers.size }
        see("线路已满")
        picture("held-back")
        // The first answer stops the calling: only what was under way by then reached the PBX.
        val placed = callsTo("28", mark)
        Log.i(TAG, "calls placed before the PBX said 503: $placed")
        assertTrue("$placed calls placed", placed <= RoomPacing.AT_ONCE)
        never("a member called again by itself", QUIET_WINDOW_MS) { callsTo("28", mark) > placed }
        assertEquals(1, connected(room()!!))
        assertEquals(1, room()!!.present)

        // By hand: one is called again, and held back again; another is taken off the list.
        tapRow("重拨 2801")
        await("the call again") { callsTo("28", mark) == placed + 1 }
        awaitRoom("the member held back again") { heldBack(it, "线路已满").size == numbers.size }
        tapRow("移除 2802")
        awaitRoom("the member off the list") { room -> room.members.none { it.name == "2802" } }
        onMain { engine.calls.hangUp() }
        awaitNoCall()
    }

    @Test
    fun busyAnswersInARowWhileMembersTalkAreTakenForTheLinesLimit() {
        openRoom("2501")
        val mark = pbx.mark()
        val numbers = (1..8).map { "29%02d".format(it) }
        ok(onMain { engine.calls.inviteToRoom(numbers) })
        val room = awaitRoom("the calling to stop") { room -> room.members.none { it.state in WAITING } }
        val busy = heldBack(room, "忙")
        val full = heldBack(room, "线路已满")
        val placed = callsTo("29", mark)
        Log.i(TAG, "busy=$busy full=$full placed=$placed")
        assertEquals(placed, busy.size)
        assertTrue("$placed calls placed", placed in RoomPacing.FAILURES_TO_STOP until numbers.size)
        assertEquals(numbers.size - placed, full.size)
        never("a member called again by itself", QUIET_WINDOW_MS) { callsTo("29", mark) > placed }
        onMain { engine.calls.hangUp() }
        awaitNoCall()
    }

    @Test
    fun aMemberThatHangsUpRightAfterAnsweringStopsTheCalling() {
        openRoom("2501")
        val mark = pbx.mark()
        val numbers = (1..9).map { "24%02d".format(it) }
        ok(onMain { engine.calls.inviteToRoom(numbers) })
        awaitRoom("every member held back") { heldBack(it, "线路已满").size == numbers.size }
        val placed = callsTo("24", mark)
        Log.i(TAG, "calls placed before the announcements stopped the calling: $placed")
        assertTrue("$placed calls placed", placed < numbers.size)
        never("a member called again by itself", QUIET_WINDOW_MS) { callsTo("24", mark) > placed }
        assertEquals(1, connected(room()!!))
        picture("announcement")
        onMain { engine.calls.hangUp() }
        awaitNoCall()
    }

    @Test
    fun theRoomHoldsWhatTheSettingAllows() {
        settings { it.copy(roomLimit = 3) }
        openRoom("2501")
        see(RoomRules.count(1, 3))
        assertEquals("最多还可添加 2 方", onMain { engine.calls.inviteToRoom(listOf("2502", "2503", "2504")) })
        ok(onMain { engine.calls.inviteToRoom(listOf("2502", "2503")) })
        awaitRoom("three members") { connected(it) == 3 }
        assertEquals("会议室已满（最多 3 方）", onMain { engine.calls.inviteToRoom(listOf("2504")) })
        onMain { engine.calls.hangUp() }
        awaitNoCall()
    }

    private companion object {
        const val TAG = "SipLinkPacingTest"
        const val ROWS_SHOWN_MS = 600L
        val SETTING_UP = setOf(Call.State.OutgoingInit, Call.State.OutgoingProgress)
        val WAITING = setOf(MemberState.QUEUED, MemberState.CALLING)
    }
}
