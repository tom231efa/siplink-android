package com.sky.siplink.core.logic

import com.sky.siplink.core.model.MemberState
import com.sky.siplink.core.model.RoomInfo
import com.sky.siplink.core.model.RoomMember
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomRulesTest {
    @Test
    fun `members fit up to the limit`() {
        assertNull(RoomRules.refusal(present = 0, adding = 30, limit = 30))
        assertNull(RoomRules.refusal(present = 29, adding = 1, limit = 30))
        assertEquals("会议室已满（最多 30 方）", RoomRules.refusal(present = 30, adding = 1, limit = 30))
    }

    @Test
    fun `more than fit are refused with how many still do`() {
        assertEquals("最多还可添加 2 方", RoomRules.refusal(present = 28, adding = 3, limit = 30))
        assertEquals(2, RoomRules.free(28, 30))
        assertEquals(0, RoomRules.free(31, 30))
    }

    @Test
    fun `the core takes the room, a waiting call and the overlaps`() {
        assertTrue(RoomRules.MAX_CALLS >= RoomRules.LIMIT + 1)
        // Without a room: the call that is up, one next to it, and the overlaps of a transfer.
        assertEquals(4, RoomRules.MAX_CALLS_WITHOUT_ROOM)
        assertTrue(RoomRules.MAX_CALLS_WITHOUT_ROOM < RoomRules.MAX_CALLS)
    }

    @Test
    fun `the count reads present of limit`() {
        assertEquals("12/30", RoomRules.count(12, 30))
    }

    @Test
    fun `targets are called once, and not when they are in the room`() {
        val targets = listOf(" 2501 ", "2502", "2501", "", "2503@pbx.example", "sip:2504@pbx.example", "2505")
        assertEquals(
            listOf("2501", "2503@pbx.example", "sip:2504@pbx.example"),
            RoomRules.newTargets(targets, inRoom = listOf("2502", "2505")),
        )
    }

    @Test
    fun `a target in the room is recognised by its number whatever its domain`() {
        assertEquals(emptyList<String>(), RoomRules.newTargets(listOf("2502@other.example", "sip:2502@pbx.example"), inRoom = listOf("2502")))
    }

    @Test
    fun `the state of a member reads plainly, a member that left shows why`() {
        assertEquals("呼叫中", RoomRules.stateLabel(MemberState.CALLING, null))
        assertEquals("振铃", RoomRules.stateLabel(MemberState.RINGING, null))
        assertEquals("已接通", RoomRules.stateLabel(MemberState.CONNECTED, null))
        assertEquals("对方正忙", RoomRules.stateLabel(MemberState.LEFT, "对方正忙"))
        assertEquals("已离开", RoomRules.stateLabel(MemberState.LEFT, null))
        assertEquals("等待呼叫", RoomRules.stateLabel(MemberState.QUEUED, null))
        assertEquals("线路已满", RoomRules.stateLabel(MemberState.HELD_BACK, "线路已满"))
    }

    @Test
    fun `members that left do not count against the limit`() {
        val room = RoomInfo(
            members = listOf(
                RoomMember(1, "A", "2501@pbx", MemberState.CONNECTED),
                RoomMember(2, "B", "2502@pbx", MemberState.LEFT, endReason = "通话结束"),
                RoomMember(3, "C", "2503@pbx", MemberState.RINGING),
                RoomMember(4, "D", "2504@pbx", MemberState.HELD_BACK, endReason = "线路已满"),
                RoomMember(5, "E", "2505@pbx", MemberState.QUEUED),
            ),
            limit = 30,
            openedAt = 0L,
        )
        // Those waiting to be called count, those held back do not until they are called again.
        assertEquals(3, room.present)
        assertEquals(listOf(1L, 3L, 5L), room.members.filter { it.taken }.map { it.id })
    }

    @Test
    fun `the size of the room a setting asks for stays within what the room holds`() {
        assertEquals(RoomRules.MIN_LIMIT, RoomRules.limitOf(0))
        assertEquals(RoomRules.MIN_LIMIT, RoomRules.limitOf(-5))
        assertEquals(2, RoomRules.limitOf(2))
        assertEquals(12, RoomRules.limitOf(12))
        assertEquals(RoomRules.LIMIT, RoomRules.limitOf(30))
        assertEquals(RoomRules.LIMIT, RoomRules.limitOf(500))
        assertEquals("会议室已满（最多 5 方）", RoomRules.refusal(present = 5, adding = 1, limit = RoomRules.limitOf(5)))
    }

    @Test
    fun `a tap on a row's hang up button counts only once the list has been still a moment`() {
        assertFalse("While the list moves", RoomRules.rowTapTaken(scrolling = true, sinceMovedMs = 10_000))
        assertFalse("The lift of the finger that stopped a fling", RoomRules.rowTapTaken(scrolling = false, sinceMovedMs = 120))
        assertFalse("While the rows slide after one went", RoomRules.rowTapTaken(scrolling = false, sinceMovedMs = -RoomRules.ROW_MOVE_MS))
        assertTrue(RoomRules.rowTapTaken(scrolling = false, sinceMovedMs = RoomRules.ROW_TAP_GUARD_MS))
        assertTrue("A list that never moved", RoomRules.rowTapTaken(scrolling = false, sinceMovedMs = Long.MAX_VALUE / 2))
    }

    @Test
    fun `the mixer of the SDK has an input for the user and for every call the core takes`() {
        // One more aborts the app; even with every call of the core in the room there is none.
        assertTrue(1 + RoomRules.MAX_CALLS <= RoomRules.SDK_MIXER_INPUTS)
        assertTrue(RoomRules.LIMIT < RoomRules.MAX_CALLS)
    }
}
