package com.sky.siplink.core.logic

import com.sky.siplink.core.logic.LineVerdict.FarReports
import com.sky.siplink.core.model.Mapped
import com.sky.siplink.core.model.MediaFlow
import com.sky.siplink.core.model.NatKind
import com.sky.siplink.core.model.OptionsAnswer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LineVerdictTest {
    private val local = listOf("192.168.10.55")
    private fun answer(received: String? = "203.0.113.7", rport: Int? = 40001, intact: Boolean = true, length: Boolean = true) =
        OptionsAnswer(200, 20, received, rport, intact, length)

    @Test
    fun `the nat is told by where the server saw the phone`() {
        assertEquals(NatKind.UNKNOWN, LineVerdict.nat(local, null))
        assertEquals(NatKind.NONE, LineVerdict.nat(local, Mapped("192.168.10.55", 40000)))
        assertEquals(NatKind.PRESENT, LineVerdict.nat(local, Mapped("203.0.113.7", 40000)))
        assertEquals(NatKind.CARRIER, LineVerdict.nat(listOf("100.70.1.2"), Mapped("203.0.113.7", 40000)))
        assertEquals(NatKind.PRESENT, LineVerdict.nat(local, Mapped("203.0.113.7", 40000), Mapped("203.0.113.7", 40000)))
        assertEquals(NatKind.SYMMETRIC, LineVerdict.nat(local, Mapped("203.0.113.7", 40000), Mapped("203.0.113.7", 51000)))
        assertEquals(NatKind.PRESENT, LineVerdict.nat(local, null, Mapped("203.0.113.7", 51000)))
    }

    @Test
    fun `the via of the answer says where the server saw the request`() {
        val from = Mapped("192.168.10.55", 40000)
        assertEquals(Mapped("203.0.113.7", 40001), LineVerdict.seen(answer(), from))
        assertEquals(from, LineVerdict.seen(answer(received = null, rport = null), from))
        assertNull(LineVerdict.seen(null, from))
    }

    @Test
    fun `a rewritten answer points at an alg`() {
        assertEquals(false, LineVerdict.algSuspected(answer()))
        assertEquals(true, LineVerdict.algSuspected(answer(intact = false)))
        assertEquals(true, LineVerdict.algSuspected(answer(received = null)))
        assertEquals(true, LineVerdict.algSuspected(answer(length = false)))
        assertNull(LineVerdict.algSuspected(null))
    }

    @Test
    fun `addresses count however they are written`() {
        val six = listOf("2001:db8:0:0::55")
        assertEquals(NatKind.NONE, LineVerdict.nat(six, Mapped("2001:db8::55", 40000)))
        assertEquals(NatKind.NONE, LineVerdict.nat(six, Mapped("[2001:DB8::55]", 40000)))
        assertEquals(NatKind.SYMMETRIC, LineVerdict.nat(local, Mapped("2001:db8::7", 40000), Mapped("2001:0db8::0:7", 51000)))
    }

    @Test
    fun `missing sound is judged after a few seconds, sound both ways at once`() {
        // As the SIP core gives them: no loss figure (0) until a report of the other side came.
        assertNull(LineVerdict.flow(10, 0, FarReports.NONE, 0f, 2_000))
        assertEquals(MediaFlow.BOTH, LineVerdict.flow(10, 8, FarReports.NONE, 0f, 1_000))
        assertEquals(MediaFlow.BOTH, LineVerdict.flow(300, 290, FarReports.ABOUT_US, 0f, 6_000))
        assertEquals(MediaFlow.SEND_ONLY, LineVerdict.flow(300, 0, FarReports.NONE, 0f, 6_000))
        assertEquals(MediaFlow.NONE, LineVerdict.flow(0, 0, FarReports.NONE, 0f, 6_000))
        assertEquals(MediaFlow.BOTH, LineVerdict.flow(1, 1, FarReports.ABOUT_US, 50f, 6_000))
    }

    @Test
    fun `sound that does not reach the other side shows in its reports`() {
        // Its reports came, and none was about this phone's stream: nothing of it arrived.
        assertEquals(MediaFlow.RECEIVE_ONLY, LineVerdict.flow(300, 280, FarReports.SILENT, 0f, 6_000))
        // It reported all of it lost.
        assertEquals(MediaFlow.RECEIVE_ONLY, LineVerdict.flow(300, 280, FarReports.ABOUT_US, 100f, 6_000))
        // Without reports nothing is known of it: it counts as arriving.
        assertEquals(MediaFlow.BOTH, LineVerdict.flow(300, 280, FarReports.NONE, 0f, 6_000))
        assertNull(LineVerdict.flow(300, 280, FarReports.SILENT, 0f, 2_000))
    }
}
