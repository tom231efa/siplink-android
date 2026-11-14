package com.sky.siplink.core.logic

import com.sky.siplink.core.logic.IncomingPolicy.Decision
import com.sky.siplink.core.model.Settings
import org.junit.Assert.assertEquals
import org.junit.Test

class IncomingPolicyTest {
    private val defaults = Settings()

    @Test
    fun `idle line rings without forwarding by default`() {
        assertEquals(Decision.Ring(null, 20), IncomingPolicy.decide(defaults, hasActiveCall = false, hasSecondCall = false))
    }

    @Test
    fun `do not disturb wins over everything`() {
        val settings = defaults.copy(doNotDisturb = true, forwardAlways = "2000", forwardBusy = "2001")
        assertEquals(Decision.RejectDoNotDisturb, IncomingPolicy.decide(settings, false, false))
        assertEquals(Decision.RejectDoNotDisturb, IncomingPolicy.decide(settings, true, false))
    }

    @Test
    fun `unconditional forward applies idle or busy`() {
        val settings = defaults.copy(forwardAlways = " 2000 ", forwardBusy = "2001")
        assertEquals(Decision.Forward("2000"), IncomingPolicy.decide(settings, false, false))
        assertEquals(Decision.Forward("2000"), IncomingPolicy.decide(settings, true, false))
    }

    @Test
    fun `busy line forwards when a busy target is set`() {
        val settings = defaults.copy(forwardBusy = "2001")
        assertEquals(Decision.Forward("2001"), IncomingPolicy.decide(settings, true, false))
        // The busy target is irrelevant while the line is idle.
        assertEquals(Decision.Ring(null, 20), IncomingPolicy.decide(settings, false, false))
    }

    @Test
    fun `busy line offers call waiting once`() {
        assertEquals(Decision.Waiting, IncomingPolicy.decide(defaults, true, false))
        assertEquals(Decision.RejectBusy, IncomingPolicy.decide(defaults, true, true))
        assertEquals(Decision.RejectBusy, IncomingPolicy.decide(defaults.copy(callWaiting = false), true, false))
    }

    @Test
    fun `no answer forward carries a clamped delay`() {
        val settings = defaults.copy(forwardNoAnswer = "2002", forwardNoAnswerSeconds = 1)
        assertEquals(Decision.Ring("2002", 5), IncomingPolicy.decide(settings, false, false))
        assertEquals(
            Decision.Ring("2002", 120),
            IncomingPolicy.decide(settings.copy(forwardNoAnswerSeconds = 999), false, false),
        )
    }
}
