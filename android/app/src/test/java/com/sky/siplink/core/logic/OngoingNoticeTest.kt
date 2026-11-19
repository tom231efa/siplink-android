package com.sky.siplink.core.logic

import com.sky.siplink.core.model.CallInfo
import com.sky.siplink.core.model.CallPhase
import com.sky.siplink.core.model.MemberState
import com.sky.siplink.core.model.RoomInfo
import com.sky.siplink.core.model.RoomMember
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OngoingNoticeTest {
    private val call = CallInfo(
        phase = CallPhase.CONNECTED,
        remoteName = "2501",
        remoteAddress = "2501@pbx",
        accountLabel = "",
        incoming = false,
        intercom = false,
        connectedAt = 10L,
    )

    private fun room(present: Int) = RoomInfo(
        members = (1..present).map { RoomMember(id = it.toLong(), name = "25$it", address = "25$it@pbx", state = MemberState.CONNECTED) },
        limit = RoomRules.LIMIT,
        openedAt = 20L,
    )

    private fun inRoom(present: Int) = call.copy(remoteName = "会议室", remoteAddress = "", connectedAt = 20L, room = room(present))

    @Test
    fun `the notification is posted again when the room opens, without a change of phase`() {
        assertNotEquals(OngoingNotice.of(call), OngoingNotice.of(inRoom(1)))
    }

    @Test
    fun `and when the hang up key of the room starts to ask first, or stops`() {
        assertEquals(OngoingNotice.of(inRoom(2)), OngoingNotice.of(inRoom(3)))
        assertNotEquals(OngoingNotice.of(inRoom(1)), OngoingNotice.of(inRoom(2)))
        assertNotEquals(OngoingNotice.of(inRoom(2)), OngoingNotice.of(inRoom(1)))
    }

    @Test
    fun `not for what it does not show`() {
        assertEquals(OngoingNotice.of(call), OngoingNotice.of(call.copy(muted = true)))
        assertNull(OngoingNotice.of(null))
    }
}
