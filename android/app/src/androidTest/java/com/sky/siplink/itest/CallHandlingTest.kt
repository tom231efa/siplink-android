package com.sky.siplink.itest

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sky.siplink.core.logic.FeatureDial
import com.sky.siplink.core.model.CallPhase
import com.sky.siplink.core.model.DndMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Call waiting, do not disturb, forwarding, auto answer, call pickup and voicemail (MWI). */
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
        onCore { engine.calls.answer() }
        val second = awaitCall("the second call to be active") {
            it.phase == CallPhase.CONNECTED && it.remoteAddress.startsWith("$CUSTOMER@") && it.heldCall != null
        }
        assertTrue(second.heldCall!!.address.startsWith("$AGENT@"))
        assertNull(second.waitingCall)

        onCore { engine.calls.swap() }
        awaitCall("the calls to be swapped") {
            it.phase == CallPhase.CONNECTED && it.remoteAddress.startsWith("$AGENT@") &&
                it.heldCall?.address.orEmpty().startsWith("$CUSTOMER@")
        }

        // Hanging up the active call brings the held one back.
        onCore { engine.calls.hangUp() }
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
            onCore { engine.calls.decline() }
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
    fun aRegularCallIsAnsweredByItselfOnceItsTimeHasPassed() {
        register()
        // On screen: Android does not let an app in the background open the microphone.
        showApp()
        settings { it.copy(autoAnswer = true, autoAnswerSeconds = AUTO_ANSWER_SECONDS) }
        val mark = phones.mark()
        phoneCalls(AGENT, "$APP")
        val ringing = awaitCall("the call to ring") { it.phase == CallPhase.INCOMING }
        assertFalse("Taken for an intercom call", ringing.intercom)
        // Seen ringing a little after it began to: what is left of its time, less a margin, it has to go on ringing.
        never("answered before its time", AUTO_ANSWER_SECONDS * 1000L - TIME_MARGIN_MS) { call()?.phase == CallPhase.CONNECTED }
        awaitConnected("$AGENT")
        awaitPhoneHears(AGENT, mark)
    }

    @Test
    fun aRegularCallRingsOnWhileAutoAnswerIsOff() {
        register()
        showApp()
        // Answering intercom calls by itself says nothing about the others.
        settings { it.copy(autoAnswer = false, autoAnswerSeconds = 0, intercomAutoAnswer = true) }
        phoneCalls(AGENT, "$APP")
        awaitCall("the call to ring") { it.phase == CallPhase.INCOMING }
        never("answered although auto answer is off", QUIET_WINDOW_MS) { call()?.phase == CallPhase.CONNECTED }
        onCore { engine.calls.decline() }
        awaitNoCall()
    }

    @Test
    fun anIntercomCallFollowsOnlyItsOwnSwitch() {
        register()
        showApp()
        settings { it.copy(autoAnswer = true, autoAnswerSeconds = 0, intercomAutoAnswer = false) }
        phoneCalls(AGENT, "*80$APP")
        val ringing = awaitCall("the intercom call to ring") { it.phase == CallPhase.INCOMING }
        assertTrue("Not recognised as intercom", ringing.intercom)
        never("an intercom call answered by the switch of regular calls", QUIET_WINDOW_MS) { call()?.phase == CallPhase.CONNECTED }
        onCore { engine.calls.decline() }
        awaitNoCall()
    }

    @Test
    fun aSecondCallDuringACallIsNotAnsweredByItself() {
        register()
        showApp()
        settings { it.copy(autoAnswer = true, autoAnswerSeconds = 0) }
        dial("$AGENT")
        awaitConnected("$AGENT")

        phoneCalls(CUSTOMER, "$APP")
        awaitCall("the waiting call") { it.waitingCall != null }
        never("the waiting call answered by itself", QUIET_WINDOW_MS) { call()?.remoteAddress.orEmpty().startsWith("$CUSTOMER@") }

        // Nor once it is left alone: it came during a call.
        onCore { engine.calls.hangUp() }
        awaitCall("the waiting call to ring on its own") { it.phase == CallPhase.INCOMING && it.remoteAddress.startsWith("$CUSTOMER@") }
        never("the call that came during another one answered by itself", QUIET_WINDOW_MS) { call()?.phase == CallPhase.CONNECTED }
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
        // The PBX answers first and starts the mailbox a moment later.
        await("the PBX to open the mailbox") { pbxChannel(pbxLeg(APP))?.get("Application") == "VoiceMailMain" }
        onCore { engine.calls.hangUp() }
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
            onCore { engine.accounts.remove(account(APP)!!.id) }
            assertEquals("The count outlived its account", 0, engine.voicemail.value)
        } finally {
            for (id in MESSAGE_ID.findAll(pbx.command("voicemail show mailbox $APP default")).map { it.value }.toList()) {
                pbx.command("voicemail remove $APP default INBOX $id")
            }
        }
    }

    @Test
    fun theMailboxIsSubscribedToWhenTheAccountAsksForIt() {
        addAccount(draft(APP).copy(subscribeMwi = true))
        awaitRegistered()
        assertTrue(account(APP)!!.subscribeMwi)
        await("the PBX to have the subscription of the app") {
            val subscriptions = pbx.command("pjsip show subscriptions inbound")
            "message-summary" in subscriptions && Regex("\\b$APP\\b").containsMatchIn(subscriptions)
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

        /** The time of auto answer in the tests: one that can be chosen, long enough to watch it ring. */
        const val AUTO_ANSWER_SECONDS = 5

        /** What of that time the call may have rung before the test saw it ring. */
        const val TIME_MARGIN_MS = 1_500L

        /** A message id in the output of "voicemail show mailbox", like 1794645458-00000000. */
        val MESSAGE_ID = Regex("\\b\\d{9,}-[0-9a-f]{8}\\b")
    }
}
