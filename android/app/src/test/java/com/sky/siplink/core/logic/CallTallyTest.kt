package com.sky.siplink.core.logic

import com.sky.siplink.core.logic.LineVerdict.FarReports
import com.sky.siplink.core.model.MediaFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallTallyTest {
    private val start = 1_000_000L

    private fun sample(sent: Long, received: Long, farRtcp: Boolean = false, aboutUs: Boolean = false, lossUp: Float = 0f, ice: String? = "") =
        CallTally.Sample(sent, received, 0f, lossUp, 1f, 20f, farRtcp, aboutUs, ice)

    @Test
    fun `nothing at all since the start is told after five seconds`() {
        val tally = CallTally(start, judged = true)
        tally.take(sample(100, 0), start + 2_000)
        assertFalse(tally.hearsNothing(start + LineVerdict.SILENT_AFTER_MS - 1))
        assertTrue(tally.hearsNothing(start + LineVerdict.SILENT_AFTER_MS))
    }

    @Test
    fun `a pause after sound came in is told only when it lasts`() {
        val tally = CallTally(start, judged = true)
        tally.take(sample(100, 90), start + 1_000)
        // A far end that suppresses its silence sends nothing while it is quiet.
        tally.take(sample(400, 90), start + 9_000)
        assertFalse(tally.hearsNothing(start + 1_000 + CallTally.GAP_AFTER_MS - 1))
        assertTrue(tally.hearsNothing(start + 1_000 + CallTally.GAP_AFTER_MS))
        // Sound again: quiet at once.
        tally.take(sample(900, 95), start + 17_000)
        assertFalse(tally.hearsNothing(start + 17_000))
    }

    @Test
    fun `after a hold the silence counts anew`() {
        val tally = CallTally(start, judged = true)
        tally.take(sample(100, 90), start + 1_000)
        tally.restart(start + 30_000)
        assertFalse(tally.hearsNothing(start + 34_999))
        assertTrue(tally.hearsNothing(start + 35_000))
    }

    @Test
    fun `an intercom or paging call, or one where the phone does not send, says nothing`() {
        val intercom = CallTally(start, judged = false)
        intercom.take(sample(100, 0), start + 2_000)
        assertFalse(intercom.hearsNothing(start + 60_000))
        // Its flow is still shown, and teaches nothing ([CallTally.judged]).
        assertEquals(MediaFlow.SEND_ONLY, intercom.flow(start + 10_000))
        val mute = CallTally(start, judged = true)
        mute.take(sample(0, 0), start + 2_000)
        assertFalse(mute.hearsNothing(start + 60_000))
    }

    @Test
    fun `the other side's reports tell whether this phone's sound arrives`() {
        val tally = CallTally(start, judged = true)
        tally.take(sample(100, 90), start + 1_000)
        assertEquals(FarReports.NONE, tally.farReports(start + 1_000))
        assertEquals(MediaFlow.BOTH, tally.flow(start + 1_000))
        // Its reports come, none about this phone's stream.
        tally.take(sample(300, 280, farRtcp = true), start + 3_000)
        assertEquals(FarReports.NONE, tally.farReports(start + 3_000 + LineVerdict.SILENT_AFTER_MS - 1))
        assertEquals(FarReports.SILENT, tally.farReports(start + 3_000 + LineVerdict.SILENT_AFTER_MS))
        assertEquals(MediaFlow.RECEIVE_ONLY, tally.flow(start + 10_000))
        // One report about it is enough.
        tally.take(sample(500, 480, farRtcp = true, aboutUs = true), start + 11_000)
        assertEquals(FarReports.ABOUT_US, tally.farReports(start + 20_000))
        assertEquals(MediaFlow.BOTH, tally.flow(start + 20_000))
    }

    @Test
    fun `the ice state of the running call is kept once its streams stopped`() {
        val tally = CallTally(start, judged = true)
        assertNull(tally.flow(start + 10_000))
        tally.take(sample(100, 90, ice = "ReflexiveConnection"), start + 1_000)
        tally.take(sample(120, 95, ice = null), start + 2_000)
        assertEquals("ReflexiveConnection", tally.ice)
        assertEquals(120L, tally.sent)
    }
}
