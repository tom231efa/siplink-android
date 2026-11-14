package com.sky.siplink.core.logic

import com.sky.siplink.core.logic.FeatureDial.Action
import com.sky.siplink.core.model.FeatureCodes
import com.sky.siplink.core.model.SupervisionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureDialTest {
    private val codes = FeatureCodes()

    @Test
    fun `default codes compose prefix plus extension`() {
        assertEquals("*911002", FeatureDial.compose(Action.LISTEN, "1002", codes))
        assertEquals("*921002", FeatureDial.compose(Action.WHISPER, "1002", codes))
        assertEquals("*931002", FeatureDial.compose(Action.BARGE, "1002", codes))
        assertEquals("*941002", FeatureDial.compose(Action.FORCE_HANG_UP, "1002", codes))
        assertEquals("**1002", FeatureDial.compose(Action.PICKUP, "1002", codes))
    }

    @Test
    fun `the extension is taken from a full sip address`() {
        assertEquals("*931002", FeatureDial.compose(Action.BARGE, " sip:1002@pbx.example.com;transport=tls ", codes))
        assertEquals("1002", FeatureDial.extensionOf("1002@pbx.example.com"))
    }

    @Test
    fun `unconfigured code or empty target yields nothing`() {
        assertNull(FeatureDial.compose(Action.LISTEN, "1002", codes.copy(listen = " ")))
        assertNull(FeatureDial.compose(Action.LISTEN, "  ", codes))
    }

    @Test
    fun `supervision modes and their dtmf switches`() {
        assertEquals(SupervisionMode.LISTEN, FeatureDial.supervisionOf(Action.LISTEN))
        assertEquals(SupervisionMode.WHISPER, FeatureDial.supervisionOf(Action.WHISPER))
        assertEquals(SupervisionMode.BARGE, FeatureDial.supervisionOf(Action.BARGE))
        assertNull(FeatureDial.supervisionOf(Action.FORCE_HANG_UP))
        assertNull(FeatureDial.supervisionOf(Action.PICKUP))
        assertEquals(listOf('4', '5', '6'), SupervisionMode.entries.map { it.dtmf })
    }

    @Test
    fun `feature codes are limited to dial pad characters`() {
        assertTrue(FeatureDial.isValidCode("*91"))
        assertTrue(FeatureDial.isValidCode("##"))
        assertTrue(FeatureDial.isValidCode(""))
        assertFalse(FeatureDial.isValidCode("*9a"))
        assertFalse(FeatureDial.isValidCode("70 0"))
    }
}
