package com.sky.siplink.core.logic

import com.sky.siplink.core.model.IntercomHeaderMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IntercomHeadersTest {
    @Test
    fun `call-info mode asks for an immediate answer`() {
        val headers = IntercomHeaders.outgoing(IntercomHeaderMode.CALL_INFO, "pbx.example.com")
        assertEquals(listOf(IntercomHeaders.Header("Call-Info", "<sip:pbx.example.com>;answer-after=0")), headers)
    }

    @Test
    fun `all mode sends the three known variants`() {
        val names = IntercomHeaders.outgoing(IntercomHeaderMode.ALL, "").map { it.name }
        assertEquals(listOf("Call-Info", "Alert-Info", "Answer-Mode"), names)
    }

    @Test
    fun `every generated header is recognised on the receiving side`() {
        IntercomHeaderMode.entries.forEach { mode ->
            val headers = IntercomHeaders.outgoing(mode, "pbx.example.com").associate { it.name to it.value }
            assertTrue(
                mode.name,
                IntercomHeaders.requestsAutoAnswer(
                    headers[IntercomHeaders.CALL_INFO],
                    headers[IntercomHeaders.ALERT_INFO],
                    headers[IntercomHeaders.ANSWER_MODE],
                ),
            )
        }
    }

    @Test
    fun `vendor specific values are recognised`() {
        assertTrue(IntercomHeaders.requestsAutoAnswer("<sip:x>;Answer-After=0", null, null))
        assertTrue(IntercomHeaders.requestsAutoAnswer(null, "Auto Answer", null))
        assertTrue(IntercomHeaders.requestsAutoAnswer(null, "<http://x>;info=alert-autoanswer;delay=0", null))
        assertTrue(IntercomHeaders.requestsAutoAnswer(null, null, "Auto;require"))
    }

    @Test
    fun `ordinary calls are not auto answered`() {
        assertFalse(IntercomHeaders.requestsAutoAnswer(null, null, null))
        assertFalse(IntercomHeaders.requestsAutoAnswer("<http://example.com/photo.jpg>;purpose=icon", null, null))
        assertFalse(IntercomHeaders.requestsAutoAnswer(null, "<http://example.com/ring.wav>", "Manual"))
    }
}
