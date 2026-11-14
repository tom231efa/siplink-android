package com.sky.siplink.itest

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sky.siplink.core.logic.FeatureDial
import com.sky.siplink.core.model.CallPhase
import com.sky.siplink.core.model.SupervisionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Supervisor features: listen (监听), whisper (耳语), barge (强插) and force hang up (强拆), both
 * with the app as the supervisor and with the app's own call as the target.
 *
 * Who hears the supervisor is measured on the scripted phones: agent 1002 and customer 1003
 * are silent and report (TALK_DETECT) whether audio reaches them; the app sends a tone.
 */
@RunWith(AndroidJUnit4::class)
class SupervisionTest : PbxTestBase() {
    /** Dials a supervisor feature code for the agent, the way the feature sheet does. */
    private fun supervise(action: FeatureDial.Action) {
        val number = FeatureDial.compose(action, "$AGENT", engine.settings.current.features)
        assertNotNull(number)
        dial(number!!, supervision = FeatureDial.supervisionOf(action))
    }

    private fun awaitSpying(from: Int) {
        pbx.awaitEvent("ChanSpyStart", from) {
            it["SpyerChannel"].orEmpty().startsWith(pbxLeg(APP)) && it["SpyeeChannel"].orEmpty().startsWith(pbxLeg(AGENT))
        }
    }

    @Test
    fun listenHearsTheCallWithoutBeingHeard() {
        register()
        agentCall(agentBehaviour = "tone") // the agent "talks": a tone the customer hears
        val pbxMark = pbx.mark()
        val mark = phones.mark()
        supervise(FeatureDial.Action.LISTEN)
        awaitConnected()
        awaitSpying(pbxMark)
        assertEquals(SupervisionMode.LISTEN, call()!!.supervision)

        awaitAppHearsAudio() // the supervisor hears the conversation
        // The agent's leg would report it if the supervisor's tone reached the agent.
        assertFalse("The agent hears the listening supervisor", phoneHearsWithin(AGENT, mark))
    }

    @Test
    fun switchesBetweenListenWhisperAndBargeDuringTheCall() {
        register()
        agentCall()
        val pbxMark = pbx.mark()
        var mark = phones.mark()
        supervise(FeatureDial.Action.LISTEN)
        awaitConnected()
        awaitSpying(pbxMark)
        assertFalse("Listening, but the agent hears the supervisor", phoneHearsWithin(AGENT, mark))
        assertFalse("Listening, but the customer hears the supervisor", phoneHearsWithin(CUSTOMER, mark, 1_000))

        // Whisper: only the agent hears the supervisor.
        mark = phones.mark()
        onMain { engine.calls.setSupervisionMode(SupervisionMode.WHISPER) }
        assertEquals(SupervisionMode.WHISPER, call()!!.supervision)
        awaitPhoneHears(AGENT, mark)
        assertFalse("Whispering, but the customer hears the supervisor", phoneHearsWithin(CUSTOMER, mark))

        // Barge: the customer hears the supervisor as well.
        mark = phones.mark()
        onMain { engine.calls.setSupervisionMode(SupervisionMode.BARGE) }
        awaitPhoneHears(CUSTOMER, mark)

        // Back to listening: both stop hearing the supervisor.
        mark = phones.mark()
        onMain { engine.calls.setSupervisionMode(SupervisionMode.LISTEN) }
        awaitPhoneHearsSilence(AGENT, mark)
        awaitPhoneHearsSilence(CUSTOMER, mark)
        assertEquals(CallPhase.CONNECTED, call()!!.phase)
    }

    @Test
    fun whisperCodeReachesOnlyTheAgent() {
        register()
        agentCall()
        val mark = phones.mark()
        supervise(FeatureDial.Action.WHISPER)
        awaitConnected()
        assertEquals(SupervisionMode.WHISPER, call()!!.supervision)
        awaitPhoneHears(AGENT, mark)
        assertFalse("The customer hears the whisper", phoneHearsWithin(CUSTOMER, mark))
    }

    @Test
    fun bargeCodeReachesBothParties() {
        register()
        agentCall()
        val mark = phones.mark()
        supervise(FeatureDial.Action.BARGE)
        awaitConnected()
        assertEquals(SupervisionMode.BARGE, call()!!.supervision)
        awaitPhoneHears(AGENT, mark)
        awaitPhoneHears(CUSTOMER, mark)
    }

    @Test
    fun forceHangUpCodeReleasesTheAgentsCall() {
        register()
        agentCall()
        supervise(FeatureDial.Action.FORCE_HANG_UP)
        await("the agent's call to be cut off") { pbxChannel(pbxLeg(AGENT)) == null && pbxChannel(pbxLeg(CUSTOMER)) == null }
        await("the phones to be idle") { !phoneHasCall(AGENT) && !phoneHasCall(CUSTOMER) }
        awaitNoCall() // the feature call itself ends too
    }

    @Test
    fun ownCallCanBeBargedInto() {
        register()
        dial("$CUSTOMER")
        awaitConnected("$CUSTOMER")
        // Mute the app so that the customer hears nothing before the barge.
        var mark = phones.mark()
        onMain { engine.calls.toggleMute() }
        awaitPhoneHearsSilence(CUSTOMER, mark)

        // The agent barges into the app's call, talking (a tone).
        val pbxMark = pbx.mark()
        mark = phones.mark()
        phoneCalls(AGENT, "*93$APP", "tone")
        pbx.awaitEvent("ChanSpyStart", pbxMark) { it["SpyeeChannel"].orEmpty().startsWith(pbxLeg(APP)) }
        awaitPhoneHears(CUSTOMER, mark) // the customer hears the barging agent
        awaitAppHearsAudio() // and so does the app
        assertEquals(CallPhase.CONNECTED, call()!!.phase)
        assertTrue(call()!!.remoteAddress.startsWith("$CUSTOMER@"))
    }

    @Test
    fun ownCallCanBeForceReleased() {
        register()
        dial("2000")
        awaitConnected("2000")
        phoneCalls(AGENT, "*94$APP")
        awaitEnded()
        awaitNoCall()
        await("the app's leg to be gone") { pbxChannel(pbxLeg(APP)) == null }
    }
}
