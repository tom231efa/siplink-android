package com.sky.siplink.itest

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sky.siplink.core.logic.FeatureDial
import com.sky.siplink.core.model.CallPhase
import com.sky.siplink.core.model.DndMode
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
    fun doNotDisturbCanBeKeptByThePbx() {
        register()
        settings { it.copy(dndMode = DndMode.SERVER, doNotDisturb = true) }
        try {
            // The switch alone refuses nothing here: what the PBX still sends is meant to ring.
            phoneCalls(AGENT, "$APP")
            awaitCall("the call to ring although the switch is on") { it.phase == CallPhase.INCOMING }
            onMain { engine.calls.decline() }
            awaitNoCall()

            // Told by its feature code, the PBX turns the callers away itself.
            dial(features().dndOn)
            awaitCall("the PBX to take the code") { it.phase == CallPhase.CONNECTED }
            awaitNoCall()
            val mark = phones.mark()
            phoneCalls(AGENT, "$APP")
            awaitPhoneCallReleased(AGENT, mark)
            assertNull("The PBX sent a call although it was told not to", call())

            dial(features().dndOff)
            awaitCall("the PBX to take the code") { it.phase == CallPhase.CONNECTED }
            awaitNoCall()
            phoneCalls(AGENT, "$APP")
            awaitCall("calls to arrive again") { it.phase == CallPhase.INCOMING }
        } finally {
            pbx.command("database deltree DND")
        }
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
        leaveMessage()
        await("the new message to be announced to the app (MWI)") { engine.voicemail.value > before }

        dial(features().voicemail)
        awaitCall("the voicemail box to answer") { it.phase == CallPhase.CONNECTED }
        assertEquals("VoiceMailMain", pbxChannel(pbxLeg(APP))?.get("Application"))
        onMain { engine.calls.hangUp() }
        awaitNoCall()

        // Once the messages are gone from the mailbox, so is the announcement.
        for (id in MESSAGE_ID.findAll(pbx.command("voicemail show mailbox $APP default")).map { it.value }.toList()) {
            pbx.command("voicemail remove $APP default INBOX $id")
        }
        await("the announcement to be withdrawn") { engine.voicemail.value == 0 }
    }

    @Test
    fun theAnnouncementOfARemovedAccountIsForgotten() {
        register()
        leaveMessage()
        await("the new message to be announced to the app (MWI)") { engine.voicemail.value > 0 }
        try {
            onMain { engine.accounts.remove(account(APP)!!.id) }
            assertEquals("The count outlived its account", 0, engine.voicemail.value)
        } finally {
            for (id in MESSAGE_ID.findAll(pbx.command("voicemail show mailbox $APP default")).map { it.value }.toList()) {
                pbx.command("voicemail remove $APP default INBOX $id")
            }
        }
    }

    /** A scripted party leaves a message (a tone) in the mailbox of the app's extension. */
    private fun leaveMessage() {
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
    }

    private companion object {
        const val MESSAGE_MS = 6_000L

        /** A message id in the output of "voicemail show mailbox", like 1794645458-00000000. */
        val MESSAGE_ID = Regex("\\b\\d{9,}-[0-9a-f]{8}\\b")
    }
}
