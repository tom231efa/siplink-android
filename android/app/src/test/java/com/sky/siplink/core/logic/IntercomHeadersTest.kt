package com.sky.siplink.core.logic

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IntercomHeadersTest {
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
