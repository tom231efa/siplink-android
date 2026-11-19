package com.sky.siplink.core.logic

import com.sky.siplink.core.model.Mapped
import com.sky.siplink.core.model.MediaFlow
import com.sky.siplink.core.model.NatKind
import com.sky.siplink.core.model.OptionsAnswer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun `the direction of the sound is judged after a few seconds`() {
        assertNull(LineVerdict.flow(10, 0, null, 2_000))
        assertEquals(MediaFlow.BOTH, LineVerdict.flow(300, 290, 0f, 6_000))
        assertEquals(MediaFlow.SEND_ONLY, LineVerdict.flow(300, 0, null, 6_000))
        assertEquals(MediaFlow.RECEIVE_ONLY, LineVerdict.flow(300, 280, 100f, 6_000))
        assertEquals(MediaFlow.NONE, LineVerdict.flow(0, 0, null, 6_000))
        assertTrue(LineVerdict.flow(1, 1, 50f, 6_000) == MediaFlow.BOTH)
        assertFalse(LineVerdict.flow(1, 0, null, 6_000) == MediaFlow.BOTH)
    }
}
