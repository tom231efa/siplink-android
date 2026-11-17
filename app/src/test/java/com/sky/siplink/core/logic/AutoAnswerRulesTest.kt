package com.sky.siplink.core.logic

import com.sky.siplink.core.model.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoAnswerRulesTest {
    @Test
    fun `a time that can be chosen stays as it is`() {
        AutoAnswerRules.SECONDS_CHOICES.forEach { assertEquals(it, AutoAnswerRules.seconds(it)) }
    }

    @Test
    fun `any other time becomes the nearest one that can be chosen`() {
        assertEquals(0, AutoAnswerRules.seconds(-5))
        assertEquals(0, AutoAnswerRules.seconds(1))
        assertEquals(3, AutoAnswerRules.seconds(2))
        assertEquals(5, AutoAnswerRules.seconds(6))
        assertEquals(10, AutoAnswerRules.seconds(12))
        assertEquals(20, AutoAnswerRules.seconds(24))
        assertEquals(30, AutoAnswerRules.seconds(26))
        assertEquals(30, AutoAnswerRules.seconds(Int.MAX_VALUE))
        assertEquals(0, AutoAnswerRules.seconds(Int.MIN_VALUE))
    }

    @Test
    fun `halfway between two the longer wait is taken`() {
        assertEquals(5, AutoAnswerRules.seconds(4))
        assertEquals(20, AutoAnswerRules.seconds(15))
        assertEquals(30, AutoAnswerRules.seconds(25))
    }

    @Test
    fun `the default is a time that can be chosen`() {
        assertEquals(AutoAnswerRules.DEFAULT_SECONDS, Settings().autoAnswerSeconds)
        assertEquals(AutoAnswerRules.DEFAULT_SECONDS, AutoAnswerRules.seconds(AutoAnswerRules.DEFAULT_SECONDS))
    }

    @Test
    fun `nothing is answered by itself by default`() {
        assertNull(AutoAnswerRules.delayMs(Settings(), intercom = false, cameDuringCall = false))
        assertNull(AutoAnswerRules.delayMs(Settings(), intercom = true, cameDuringCall = false))
    }

    @Test
    fun `a regular call waits its time, and never less than the short delay`() {
        val on = Settings(autoAnswer = true, autoAnswerSeconds = 5)
        assertEquals(5_000L, AutoAnswerRules.delayMs(on, intercom = false, cameDuringCall = false))
        assertEquals(AutoAnswerRules.MIN_DELAY_MS, AutoAnswerRules.delayMs(on.copy(autoAnswerSeconds = 0), intercom = false, cameDuringCall = false))
        // A time that cannot be chosen is not taken as it is.
        assertEquals(10_000L, AutoAnswerRules.delayMs(on.copy(autoAnswerSeconds = 11), intercom = false, cameDuringCall = false))
    }

    @Test
    fun `a regular call that came during another one is left to the user`() {
        val on = Settings(autoAnswer = true, autoAnswerSeconds = 0)
        assertNull(AutoAnswerRules.delayMs(on, intercom = false, cameDuringCall = true))
    }

    @Test
    fun `intercom and regular calls each follow only their own switch`() {
        val regularOnly = Settings(autoAnswer = true, autoAnswerSeconds = 20, intercomAutoAnswer = false)
        assertNull(AutoAnswerRules.delayMs(regularOnly, intercom = true, cameDuringCall = false))
        val intercomOnly = Settings(autoAnswer = false, intercomAutoAnswer = true)
        assertNull(AutoAnswerRules.delayMs(intercomOnly, intercom = false, cameDuringCall = false))
        // An intercom call is answered at once, whatever the time of regular calls, and also after another call, as before.
        val both = Settings(autoAnswer = true, autoAnswerSeconds = 20, intercomAutoAnswer = true)
        assertEquals(AutoAnswerRules.MIN_DELAY_MS, AutoAnswerRules.delayMs(both, intercom = true, cameDuringCall = false))
        assertEquals(AutoAnswerRules.MIN_DELAY_MS, AutoAnswerRules.delayMs(both, intercom = true, cameDuringCall = true))
    }
}
