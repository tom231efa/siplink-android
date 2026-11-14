package com.sky.siplink.itest

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sky.siplink.core.model.CallPhase
import com.sky.siplink.core.model.MediaSecurity
import com.sky.siplink.core.model.SipTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.linphone.core.MediaEncryption

/** Outgoing and incoming calls, hold, DTMF, intercom (feature code, auto answer) and SRTP. */
@RunWith(AndroidJUnit4::class)
class BasicCallTest : PbxTestBase() {
    @Test
    fun outgoingCallSendsDtmf() {
        register()
        val mark = pbx.mark()
        dial("2010") // reports the digits it receives
        awaitConnected("2010")
        "1234".forEach { digit ->
            onMain { engine.calls.sendDtmf(digit) }
            Thread.sleep(600)
        }
        val event = pbx.awaitEvent("UserEvent", mark) { it["UserEvent"] == "SipLinkDtmf" }
        assertEquals("1234", event["Digits"])
        awaitNoCall() // the PBX hangs up once it has the digits
    }

    @Test
    fun outgoingCallHoldResumeAndHangUp() {
        register()
        dial("2000") // echo
        awaitConnected("2000")
        awaitAppHearsAudio() // our own tone comes back: media flows in both directions

        var mark = pbx.mark()
        onMain { engine.calls.toggleHold() }
        awaitCall("the call to be on hold") { it.phase == CallPhase.HELD }
        pbx.awaitEvent("Hold", mark) { it["Channel"].orEmpty().startsWith(pbxLeg(APP)) }

        mark = pbx.mark()
        onMain { engine.calls.toggleHold() }
        awaitConnected("2000")
        pbx.awaitEvent("Unhold", mark) { it["Channel"].orEmpty().startsWith(pbxLeg(APP)) }

        onMain { engine.calls.hangUp() }
        awaitNoCall()
        await("the PBX to release the call") { pbxChannel(pbxLeg(APP)) == null }
    }

    @Test
    fun incomingCallAnswerThenRemoteHangUp() {
        register()
        val mark = phones.mark()
        phoneCalls(AGENT, "$APP")
        val ringing = awaitCall("the incoming call") { it.phase == CallPhase.INCOMING }
        assertTrue(ringing.incoming)
        assertTrue("Unexpected caller ${ringing.remoteAddress}", ringing.remoteAddress.startsWith("$AGENT@"))
        assertEquals("Ext $AGENT", ringing.remoteName)

        onMain { engine.calls.answer() }
        awaitConnected("$AGENT")
        awaitPhoneHears(AGENT, mark) // the caller hears the app
        awaitBridged(APP, AGENT)

        phones.command("channel request hangup all")
        awaitNoCall()
    }

    @Test
    fun incomingCallDecline() {
        register()
        phoneCalls(AGENT, "$APP")
        awaitCall("the incoming call") { it.phase == CallPhase.INCOMING }
        onMain { engine.calls.decline() }
        awaitNoCall()
        await("the caller to be released") { !phoneHasCall(AGENT) }
    }

    @Test
    fun intercomIsACallToTheFeatureCodeOfThePbx() {
        // What an "intercom" shortcut key does: the feature code in front of the extension. The
        // PBX then asks that phone to answer by itself (1004 answers and echoes).
        register()
        dial("*80$ECHO_PHONE")
        // Once it is answered the PBX names the phone that was reached, not the code that was dialled.
        awaitCall("the intercom call to be answered") {
            it.phase == CallPhase.CONNECTED && (it.remoteAddress.startsWith("$ECHO_PHONE@") || it.remoteAddress.startsWith("*80$ECHO_PHONE@"))
        }
        awaitAppHearsAudio()
        onMain { engine.calls.hangUp() }
        awaitNoCall()
    }

    @Test
    fun incomingIntercomIsAnsweredAutomaticallyWhenEnabled() {
        register()
        // Off (the default): an intercom call is recognised but rings like any other call.
        phoneCalls(AGENT, "*80$APP")
        val ringing = awaitCall("the intercom call to ring") { it.phase == CallPhase.INCOMING }
        assertTrue("Not recognised as intercom", ringing.intercom)
        never("auto answered although the option is off", 4_000) { call()?.phase == CallPhase.CONNECTED }
        onMain { engine.calls.decline() }
        awaitNoCall()
        await("the caller to be released") { !phoneHasCall(AGENT) }

        // On: answered without anyone touching the device, as long as the app is on screen
        // (Android does not let an app in the background open the microphone).
        showApp()
        settings { it.copy(intercomAutoAnswer = true) }
        val mark = phones.mark()
        phoneCalls(AGENT, "*80$APP")
        assertTrue(awaitConnected("$AGENT").intercom)
        awaitPhoneHears(AGENT, mark)
    }

    @Test
    fun srtpCallOverTls() {
        onMain { engine.tls.import(env.caPem) }
        settings { it.copy(mediaSecurity = MediaSecurity.SRTP_MANDATORY) }
        register(APP, SipTransport.TLS)
        dial("2000")
        awaitConnected("2000")
        assertEquals(MediaEncryption.SRTP, onMain { engine.core.currentCall?.currentParams?.mediaEncryption })
        awaitAppHearsAudio() // encrypted media is understood in both directions
        assertFalse(call()!!.incoming)
    }
}
