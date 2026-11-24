package com.sky.siplink.core.logic

import com.sky.siplink.core.logic.LineVerdict.FarReports
import com.sky.siplink.core.model.MediaFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CallTallyTest {
    private val start = 1_000_000L

    private fun sample(sent: Long, received: Long, farRtcp: Boolean = false, aboutUs: Boolean = false, lossUp: Float = 0f, ice: String? = "") =
        CallTally.Sample(sent, received, 0f, lossUp, 1f, 20f, farRtcp, aboutUs, ice)

    @Test
    fun `an intercom or paging call still shows its flow`() {
        val intercom = CallTally(start, judged = false)
        intercom.take(sample(100, 0), start + 2_000)
        // It teaches nothing ([CallTally.judged]).
        assertEquals(MediaFlow.SEND_ONLY, intercom.flow(start + 10_000))
    }

    @Test
    fun `the other side's reports tell whether this phone's sound arrives`() {
        val tally = CallTally(start, judged = true)
        tally.take(sample(100, 90), start + 1_000)
        assertEquals(FarReports.NONE, tally.farReports(start + 1_000))
        assertEquals(MediaFlow.BOTH, tally.flow(start + 1_000))
        // Its reports come, none about this phone's stream. The first one may have been sent
        // before this phone's packets arrived: only two report intervals without one count.
        tally.take(sample(300, 280, farRtcp = true), start + 3_000)
        assertEquals(FarReports.NONE, tally.farReports(start + 3_000 + CallTally.FAR_SILENT_MS - 1))
        assertEquals(MediaFlow.BOTH, tally.flow(start + 3_000 + CallTally.FAR_SILENT_MS - 1))
        assertEquals(FarReports.SILENT, tally.farReports(start + 3_000 + CallTally.FAR_SILENT_MS))
        assertEquals(MediaFlow.RECEIVE_ONLY, tally.flow(start + 3_000 + CallTally.FAR_SILENT_MS))
        // One report about it is enough.
        tally.take(sample(500, 480, farRtcp = true, aboutUs = true), start + 14_000)
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
