package com.sky.siplink.itest

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sky.siplink.core.logic.PartyName
import com.sky.siplink.core.model.CallInfo
import com.sky.siplink.core.model.CallPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the other side of a call is called: names and numbers in the shapes a PBX sends them,
 * and the feature codes the app dials itself.
 */
@RunWith(AndroidJUnit4::class)
class CallerIdTest : PbxTestBase() {
    /** The PBX calls the app and presents [callerId], written as `"name" <number>`. */
    private fun ringing(callerId: String): CallInfo {
        val response = pbx.send(
            "Originate",
            "Channel" to "PJSIP/$APP",
            "CallerID" to callerId,
            "Application" to "Wait",
            "Data" to "30",
            "Async" to "true",
        )
        assertEquals("Originate refused: $response", "Success", response["Response"])
        return awaitCall("the call of $callerId to ring") { it.phase == CallPhase.INCOMING }
    }

    private fun decline() {
        onMain { engine.calls.decline() }
        awaitNoCall()
    }

    private fun hangUp() {
        onMain { engine.calls.hangUp() }
        awaitNoCall()
    }

    private fun features() = engine.settings.current.features

    private fun logged() = engine.history.entries.value.size

    private fun lastLogged() = engine.history.entries.value.first()

    /** Waits for the call log to hold more than [before] calls: the call just ended is in it. */
    private fun awaitLogged(before: Int) = await("the call to be logged") { logged() > before }

    @Test
    fun aNameAndANumberWithACountryCode() {
        register()
        val before = logged()
        val call = ringing("\"张三\" <+8613800138000>")
        assertEquals("张三", call.remoteName)
        assertTrue(call.remoteAddress, call.remoteAddress.startsWith("+8613800138000@"))
        decline()
        awaitLogged(before)
        assertEquals("张三", lastLogged().name)
        assertTrue(lastLogged().address, lastLogged().address.startsWith("+8613800138000@"))
    }

    @Test
    fun aNumberWithoutAName() {
        register()
        assertEquals("02155512345", ringing("<02155512345>").remoteName)
        decline()
    }

    @Test
    fun aNameWithQuotesAndCommas() {
        register()
        assertEquals("O'Brien, Bob", ringing("\"O'Brien, Bob\" <1002>").remoteName)
        decline()
    }

    @Test
    fun aCallerWhoWithholdsTheNumber() {
        register()
        val before = logged()
        assertEquals(PartyName.WITHHELD, ringing("\"Anonymous\" <anonymous>").remoteName)
        decline()
        awaitLogged(before)
        assertEquals(PartyName.WITHHELD, lastLogged().name)
    }

    @Test
    fun aCallerWhoWithholdsTheNumberCanBeRefused() {
        register()
        settings { it.copy(rejectAnonymous = true) }
        val response = pbx.send(
            "Originate",
            "Channel" to "PJSIP/$APP",
            "CallerID" to "\"Anonymous\" <anonymous>",
            "Application" to "Wait",
            "Data" to "30",
            "Async" to "true",
        )
        assertEquals("Originate refused: $response", "Success", response["Response"])
        never("the anonymous call rang", QUIET_WINDOW_MS) { call() != null }
        await("the PBX to have given the call up") { pbxChannel(pbxLeg(APP)) == null }

        // A caller with a number still gets through.
        assertEquals("1002", ringing("<1002>").remoteName)
        decline()
    }

    @Test
    fun aFeatureCodeIsNamedAfterWhatItDoes() {
        register()
        val before = logged()
        dial(features().voicemail)
        assertEquals("语音信箱", awaitCall("the call to the voicemail code") { it.phase != CallPhase.ENDED }.remoteName)
        awaitCall("the voicemail box to answer") { it.phase == CallPhase.CONNECTED }
        hangUp()
        awaitLogged(before)
        assertEquals("语音信箱", lastLogged().name)

        dial("${features().intercom}$RINGING_PHONE")
        assertEquals("对讲 $RINGING_PHONE", awaitCall("the intercom call") { it.phase != CallPhase.ENDED }.remoteName)
        hangUp()
        awaitLogged(before + 1)
        assertEquals("对讲 $RINGING_PHONE", lastLogged().name)
    }

    /** Calls [NAMED], which the PBX names [NAMED_AS] through P-Asserted-Identity; @return the PBX leg of the call. */
    private fun callNamedByThePbx(): String {
        register()
        dial(NAMED)
        assertEquals(NAMED_AS, awaitConnected(NAMED).remoteName)
        return legOf(NAMED)!!
    }

    @Test
    fun theNameThePbxGaveACallOutStaysWhenThePbxHangsUp() {
        val leg = callNamedByThePbx()
        // Its BYE asserts no one: that takes nothing back.
        pbx.command("channel request hangup $leg")
        assertEquals(NAMED_AS, awaitEnded().remoteName)
        awaitNoCall()
    }

    @Test
    fun theNameThePbxGaveACallOutStaysWhenTheUserHangsUp() {
        callNamedByThePbx()
        onMain { engine.calls.hangUp() }
        assertEquals(NAMED_AS, awaitEnded().remoteName)
        awaitNoCall()
    }

    @Test
    fun aNumberWithAHashIsShownAndRedialledAsDialled() {
        register()
        val before = logged()
        dial(HASH_CODE)
        val call = awaitCall("the PBX to answer $HASH_CODE") { it.phase == CallPhase.CONNECTED }
        assertTrue(call.remoteAddress, call.remoteAddress.startsWith("$HASH_CODE@"))
        hangUp()
        awaitLogged(before)
        val logged = lastLogged()
        assertEquals(HASH_CODE, logged.name)
        assertTrue(logged.address, logged.address.startsWith("$HASH_CODE@"))

        // What the call log holds is what gets dialled again from there.
        dial(logged.address)
        awaitCall("the PBX to answer the redial") { it.phase == CallPhase.CONNECTED }
        // The call is connected while the PBX still runs Answer(); Wait() of the same code follows.
        await("the PBX to run $HASH_CODE", APPLICATION_TIMEOUT_MS) { pbxChannel(pbxLeg(APP))?.get("Application") == "Wait" }
    }

    private companion object {
        /** An extension of the test PBX with a character a SIP URI has to escape. */
        const val HASH_CODE = "#9"

        /** A member number of the test PBX, and the name it gives it (testenv/asterisk/config/extensions.conf). */
        const val NAMED = "2501"
        const val NAMED_AS = "成员01"

        /** From Answer() to Wait() of [HASH_CODE] (testenv/asterisk/config/extensions.conf). */
        const val APPLICATION_TIMEOUT_MS = 10_000L
    }
}
