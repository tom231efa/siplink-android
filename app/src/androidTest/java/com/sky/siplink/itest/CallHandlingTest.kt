package com.sky.siplink.itest

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sky.siplink.core.logic.FeatureDial
import com.sky.siplink.core.model.CallPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Call waiting, do not disturb, forwarding, call pickup and voicemail (MWI). */
@RunWith(AndroidJUnit4::class)
class CallHandlingTest : PbxTestBase() {
    private fun features() = engine.settings.current.features

    @Test
    fun callWaitingAnswerSwapAndRelease() {
        register()
        dial("$AGENT")
        awaitConnected("$AGENT")

        phoneCalls(CUSTOMER, "$APP")
        val waiting = awaitCall("the waiting call") { it.waitingCall != null }
        assertTrue(waiting.waitingCall!!.address.startsWith("$CUSTOMER@"))
        assertEquals("The running call was disturbed", CallPhase.CONNECTED, waiting.phase)

        // Answering the new call holds the first one.
        onMain { engine.calls.answer() }
        val second = awaitCall("the second call to be active") {
            it.phase == CallPhase.CONNECTED && it.remoteAddress.startsWith("$CUSTOMER@") && it.heldCall != null
        }
        assertTrue(second.heldCall!!.address.startsWith("$AGENT@"))
        assertNull(second.waitingCall)

        onMain { engine.calls.swap() }
        awaitCall("the calls to be swapped") {
            it.phase == CallPhase.CONNECTED && it.remoteAddress.startsWith("$AGENT@") &&
                it.heldCall?.address.orEmpty().startsWith("$CUSTOMER@")
        }

        // Hanging up the active call brings the held one back.
        onMain { engine.calls.hangUp() }
        awaitCall("the held call to take over") {
            it.phase == CallPhase.CONNECTED && it.remoteAddress.startsWith("$CUSTOMER@") && it.heldCall == null
        }
        await("only one call to remain on the PBX") {
            pbxChannels().count { it["Channel"].orEmpty().startsWith(pbxLeg(APP)) } == 1
        }
    }

    @Test
    fun secondCallIsRefusedWhenCallWaitingIsOff() {
        register()
        settings { it.copy(callWaiting = false) }
        dial("$AGENT")
        awaitConnected("$AGENT")

        val mark = phones.mark()
        phoneCalls(CUSTOMER, "$APP")
        awaitPhoneCallReleased(CUSTOMER, mark) // turned away as busy
        assertNull(call()!!.waitingCall)
        assertEquals(CallPhase.CONNECTED, call()!!.phase)
    }

    @Test
    fun doNotDisturbRefusesCalls() {
        register()
        settings { it.copy(doNotDisturb = true) }
        val mark = phones.mark()
        phoneCalls(AGENT, "$APP")
        awaitPhoneCallReleased(AGENT, mark) // the caller is turned away
        assertNull("A call was shown although do not disturb is on", call())
    }

    @Test
    fun forwardsAllCalls() {
        register()
        settings { it.copy(forwardAlways = "$CUSTOMER") }
        phoneCalls(AGENT, "$APP")
        await("the forwarded call to be answered by $CUSTOMER") { phoneAnswered(CUSTOMER, AGENT) }
        assertNull("The call was shown although it is forwarded", call())
    }

    @Test
    fun forwardsWhenBusy() {
        register()
        settings { it.copy(forwardBusy = "$CUSTOMER") }
        dial("2000")
        awaitConnected("2000")
        phoneCalls(AGENT, "$APP")
        await("the forwarded call to be answered by $CUSTOMER") { phoneAnswered(CUSTOMER, AGENT) }
        assertNull(call()!!.waitingCall)
        assertEquals(CallPhase.CONNECTED, call()!!.phase)
    }

    @Test
    fun forwardsWhenNotAnswered() {
        register()
        settings { it.copy(forwardNoAnswer = "$CUSTOMER", forwardNoAnswerSeconds = 5) }
        phoneCalls(AGENT, "$APP")
        awaitCall("the call to ring first") { it.phase == CallPhase.INCOMING }
        await("the forwarded call to be answered by $CUSTOMER") { phoneAnswered(CUSTOMER, AGENT) }
        awaitNoCall()
    }

    @Test
    fun directedPickup() {
        register()
        phoneCalls(AGENT, "$RINGING_PHONE") // rings forever
        await("$RINGING_PHONE to ring") { pbxChannel(pbxLeg(RINGING_PHONE)) != null }
        dial(FeatureDial.compose(FeatureDial.Action.PICKUP, "$RINGING_PHONE", features())!!)
        awaitCall("the picked up call") { it.phase == CallPhase.CONNECTED }
        awaitBridged(APP, AGENT)
        await("$RINGING_PHONE to stop ringing") { pbxChannel(pbxLeg(RINGING_PHONE)) == null }
    }

    @Test
    fun groupPickup() {
        register()
        phoneCalls(AGENT, "$RINGING_PHONE")
        await("$RINGING_PHONE to ring") { pbxChannel(pbxLeg(RINGING_PHONE)) != null }
        dial(features().groupPickup)
        awaitCall("the picked up call") { it.phase == CallPhase.CONNECTED }
        awaitBridged(APP, AGENT)
    }

    @Test
    fun voicemailIsAnnouncedAndReachable() {
        register()
        val before = engine.voicemail.value
        // A scripted party leaves a message (a tone) in the mailbox of the app's extension.
        val response = pbx.send(
            "Originate",
            "Channel" to "Local/2001@internal",
            "Context" to "internal",
            "Exten" to "*98$APP",
            "Priority" to "1",
            "Async" to "true",
        )
        assertEquals("Success", response["Response"])
        Thread.sleep(MESSAGE_MS)
        pbx.command("channel request hangup all")
        await("the new message to be announced to the app (MWI)") { engine.voicemail.value > before }

        dial(features().voicemail)
        awaitCall("the voicemail box to answer") { it.phase == CallPhase.CONNECTED }
        assertEquals("VoiceMailMain", pbxChannel(pbxLeg(APP))?.get("Application"))
    }

    private companion object {
        const val MESSAGE_MS = 6_000L
    }
}
