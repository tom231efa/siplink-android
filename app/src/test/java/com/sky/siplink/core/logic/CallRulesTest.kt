package com.sky.siplink.core.logic

import com.sky.siplink.core.model.FeatureCodes
import com.sky.siplink.core.model.SupervisionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallRulesTest {
    private val codes = FeatureCodes()

    @Test
    fun `a call is answered by itself only with no other call and no room`() {
        assertTrue(CallRules.mayAnswerByItself(otherCallUp = false, roomOpen = false))
        assertFalse("Next to a call, held or not", CallRules.mayAnswerByItself(otherCallUp = true, roomOpen = false))
        assertFalse("Into the room", CallRules.mayAnswerByItself(otherCallUp = false, roomOpen = true))
    }

    @Test
    fun `a supervised call or a transfer under way keeps the calls out of the room`() {
        assertNull(CallRules.roomRefusal(supervised = false, transferring = false))
        assertEquals("监听通话不能加入会议室", CallRules.roomRefusal(supervised = true, transferring = false))
        assertEquals("监听通话不能加入会议室", CallRules.roomRefusal(supervised = true, transferring = true))
        assertEquals("转接进行中", CallRules.roomRefusal(supervised = false, transferring = true))
    }

    @Test
    fun `supervision and force hang up codes are not called into the room`() {
        for (code in listOf(codes.listen, codes.whisper, codes.barge, codes.forceHangUp)) {
            assertEquals(code, "监听通话不能加入会议室", CallRules.memberRefusal("${code}1005", codes))
        }
        assertNull(CallRules.memberRefusal("1005", codes))
        assertNull("Intercom into the room is fine", CallRules.memberRefusal("${codes.intercom}1005", codes))
        assertNull(CallRules.memberRefusal("sip:1005@pbx.example", codes))
        assertEquals("监听通话不能加入会议室", CallRules.memberRefusal("sip:${codes.barge}1005@pbx.example", codes))
    }

    @Test
    fun `the keys of a supervision mode go to the supervised call alone while its sound runs`() {
        assertTrue(CallRules.supervisionKeysGo(supervised = true, streaming = true, roomOpen = false))
        assertFalse("Not a supervised call", CallRules.supervisionKeysGo(supervised = false, streaming = true, roomOpen = false))
        assertFalse("On hold", CallRules.supervisionKeysGo(supervised = true, streaming = false, roomOpen = false))
        assertFalse("In the room", CallRules.supervisionKeysGo(supervised = true, streaming = true, roomOpen = true))
    }

    @Test
    fun `mode keys typed on the keypad of a supervised call switch the mode shown`() {
        assertEquals(SupervisionMode.LISTEN, CallRules.supervisionModeOf('4'))
        assertEquals(SupervisionMode.WHISPER, CallRules.supervisionModeOf('5'))
        assertEquals(SupervisionMode.BARGE, CallRules.supervisionModeOf('6'))
        assertNull(CallRules.supervisionModeOf('1'))
        assertNull(CallRules.supervisionModeOf('#'))
    }

    @Test
    fun `ending the room for everyone is asked for again once more than one member is in it`() {
        assertFalse(CallRules.confirmRoomEnd(0))
        assertFalse(CallRules.confirmRoomEnd(1))
        assertTrue(CallRules.confirmRoomEnd(2))
        assertTrue(CallRules.confirmRoomEnd(RoomRules.LIMIT))
    }
}
