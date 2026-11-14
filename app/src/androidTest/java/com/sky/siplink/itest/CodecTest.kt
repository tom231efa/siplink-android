package com.sky.siplink.itest

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Audio codec settings: the order of preference and the selection decide what a call uses. */
@RunWith(AndroidJUnit4::class)
class CodecTest : PbxTestBase() {
    @After
    fun restoreCodecs() {
        onMain { engine.codecs.restoreDefaults() }
    }

    private fun keyOf(mime: String): String = engine.codecs.codecs.value.first { it.mime == mime && it.clockRate == NARROWBAND }.key

    private fun usedCodec(): String? = onMain { engine.core.currentCall?.currentParams?.usedAudioPayloadType?.mimeType }

    private fun callAndReadCodec(): String? {
        dial("2000") // echo
        awaitConnected("2000")
        awaitAppHearsAudio()
        val codec = usedCodec()
        onMain { engine.calls.hangUp() }
        awaitNoCall()
        await("the PBX to release the call") { pbxChannel(pbxLeg(APP)) == null }
        return codec
    }

    @Test
    fun theMostPreferredCodecIsUsed() {
        register()
        // The PBX accepts G.711 in both flavours, so the order of the app decides.
        for (mime in listOf("PCMA", "PCMU")) {
            onMain {
                val position = engine.codecs.codecs.value.indexOfFirst { it.key == keyOf(mime) }
                engine.codecs.move(keyOf(mime), -position)
            }
            assertEquals(mime, engine.codecs.codecs.value.first().mime)
            assertEquals(mime, callAndReadCodec())
        }
    }

    @Test
    fun aCodecThatIsSwitchedOffIsNotUsed() {
        register()
        onMain {
            val position = engine.codecs.codecs.value.indexOfFirst { it.key == keyOf("PCMU") }
            engine.codecs.move(keyOf("PCMU"), -position)
            assertTrue(engine.codecs.setEnabled(keyOf("PCMU"), false))
        }
        assertEquals("PCMA", callAndReadCodec())
    }

    @Test
    fun theLastCodecStaysOnAndTheDefaultsComeBack() {
        val initial = engine.codecs.codecs.value
        onMain {
            val codecs = engine.codecs
            codecs.codecs.value.drop(1).forEach { assertTrue(codecs.setEnabled(it.key, false)) }
            assertFalse("The only codec left was switched off", codecs.setEnabled(codecs.codecs.value.first().key, false))
            assertEquals(1, codecs.codecs.value.count { it.enabled })
            codecs.move(codecs.codecs.value.first().key, 2)
            codecs.restoreDefaults()
        }
        assertEquals(initial, engine.codecs.codecs.value)
    }

    private companion object {
        const val NARROWBAND = 8000
    }
}
