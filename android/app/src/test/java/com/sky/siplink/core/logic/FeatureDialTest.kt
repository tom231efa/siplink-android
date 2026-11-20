package com.sky.siplink.core.logic

import com.sky.siplink.core.logic.FeatureDial.Action
import com.sky.siplink.core.model.FeatureCodes
import com.sky.siplink.core.model.Phrase
import com.sky.siplink.core.model.SupervisionMode
import com.sky.siplink.zh
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureDialTest {
    private val codes = FeatureCodes()

    @Test
    fun `default codes compose prefix plus extension`() {
        assertEquals("*801002", FeatureDial.compose(Action.INTERCOM, "1002", codes))
        assertEquals("*911002", FeatureDial.compose(Action.LISTEN, "1002", codes))
        assertEquals("*921002", FeatureDial.compose(Action.WHISPER, "1002", codes))
        assertEquals("*931002", FeatureDial.compose(Action.BARGE, "1002", codes))
        assertEquals("*941002", FeatureDial.compose(Action.FORCE_HANG_UP, "1002", codes))
        assertEquals("**1002", FeatureDial.compose(Action.PICKUP, "1002", codes))
    }

    @Test
    fun `a dialled number is read back into the feature and the extension`() {
        assertEquals(Action.INTERCOM to "1002", FeatureDial.parse("*801002", codes))
        assertEquals(Action.PICKUP to "1002", FeatureDial.parse("**1002", codes))
        assertNull(FeatureDial.parse("1002", codes))
        assertNull(FeatureDial.parse("*80", codes))
        assertNull(FeatureDial.parse("*80#9", codes))
        // Of two codes that fit, the longer one is meant: "*8" for one, "*80" for the other.
        val overlapping = codes.copy(pickup = "*8", intercom = "*80")
        assertEquals(Action.INTERCOM to "1002", FeatureDial.parse("*801002", overlapping))
        assertEquals(Action.PICKUP to "1002", FeatureDial.parse("*81002", overlapping))
    }

    @Test
    fun `a code dialled as it is has a name`() {
        assertEquals("语音信箱", zh(FeatureDial.plainLabel("*97", codes)?.let { Phrase.of(it) }))
        assertEquals("驻留", zh(FeatureDial.plainLabel("700", codes)?.let { Phrase.of(it) }))
        assertNull(FeatureDial.plainLabel("701", codes))
        assertNull(FeatureDial.plainLabel("", codes.copy(voicemail = "")))
    }

    @Test
    fun `the extension is taken from a full sip address`() {
        assertEquals("*931002", FeatureDial.compose(Action.BARGE, " sip:1002@pbx.example.com;transport=tls ", codes))
        assertEquals("1002", FeatureDial.extensionOf("1002@pbx.example.com"))
        assertEquals("*931002", FeatureDial.extensionOf("sip:%2A931002@pbx.example.com"))
        assertEquals(Action.BARGE to "1002", FeatureDial.parse(FeatureDial.extensionOf("sip:%2A931002@pbx"), codes))
    }

    @Test
    fun `unconfigured code or empty target yields nothing`() {
        assertNull(FeatureDial.compose(Action.LISTEN, "1002", codes.copy(listen = " ")))
        assertNull(FeatureDial.compose(Action.LISTEN, "  ", codes))
    }

    @Test
    fun `a feature without a code is not in use`() {
        assertTrue(Action.entries.all { FeatureDial.isConfigured(it, codes) })
        assertFalse(FeatureDial.isConfigured(Action.INTERCOM, codes.copy(intercom = "")))
        assertFalse(FeatureDial.isConfigured(Action.PICKUP, codes.copy(pickup = "  ")))
        assertTrue(FeatureDial.isConfigured(Action.LISTEN, codes.copy(intercom = "")))
    }

    @Test
    fun `do not disturb by the pbx needs both of its codes`() {
        assertTrue(FeatureDial.serverDndConfigured(codes))
        assertFalse(FeatureDial.serverDndConfigured(codes.copy(dndOn = "")))
        assertFalse(FeatureDial.serverDndConfigured(codes.copy(dndOff = " ")))
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
