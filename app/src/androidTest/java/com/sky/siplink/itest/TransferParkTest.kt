package com.sky.siplink.itest

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sky.siplink.core.model.CallPhase
import com.sky.siplink.core.model.TransferStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Blind transfer, attended transfer, parking, and merging two calls into a conference room. */
@RunWith(AndroidJUnit4::class)
class TransferParkTest : PbxTestBase() {
    @Test
    fun blindTransfer() {
        register()
        dial("$AGENT")
        awaitConnected("$AGENT")

        ok(onMain { engine.calls.blindTransfer("$CUSTOMER") })
        assertEquals("转接成功", awaitEnded().endReason)
        awaitBridged(AGENT, CUSTOMER)
        await("the app's leg to be released") { pbxChannel(pbxLeg(APP)) == null }
        awaitNoCall()
    }

    @Test
    fun blindTransferToUnknownNumberKeepsTheCall() {
        register()
        dial("$AGENT")
        awaitConnected("$AGENT")

        ok(onMain { engine.calls.blindTransfer("9999") }) // no such extension
        awaitCall("the transfer to be reported as failed") { it.transfer == TransferStatus.FAILED }
        assertEquals(CallPhase.CONNECTED, call()!!.phase)
        assertTrue("The original call was lost", bridged(pbxLeg(APP), pbxLeg(AGENT)))
    }

    @Test
    fun attendedTransfer() {
        register()
        phoneCalls(AGENT, "$APP")
        awaitCall("the incoming call") { it.phase == CallPhase.INCOMING }
        onMain { engine.calls.answer() }
        awaitConnected("$AGENT")

        // Consult 1003 first: the caller is put on hold meanwhile.
        ok(onMain { engine.calls.startConsultation("$CUSTOMER") })
        val consulting = awaitCall("the consultation call") {
            it.phase == CallPhase.CONNECTED && it.remoteAddress.startsWith("$CUSTOMER@") && it.consulting
        }
        assertTrue("Held party: ${consulting.heldCall}", consulting.heldCall?.address.orEmpty().startsWith("$AGENT@"))

        ok(onMain { engine.calls.completeTransfer() })
        awaitBridged(AGENT, CUSTOMER)
        awaitNoCall()
        await("both of the app's legs to be released") { pbxChannel(pbxLeg(APP)) == null }
    }

    @Test
    fun parkAndRetrieve() {
        register()
        dial("$AGENT")
        awaitConnected("$AGENT")

        val mark = pbx.mark()
        ok(onMain { engine.calls.park() })
        val parked = pbx.awaitEvent("ParkedCall", mark) { it["ParkeeChannel"].orEmpty().startsWith(pbxLeg(AGENT)) }
        val slot = parked["ParkingSpace"]!!
        // The PBX announces the slot to the app and then hangs up; the app must wait for that.
        awaitCall("the parked notice") { it.parked || it.phase == CallPhase.ENDED }
        assertEquals("通话已驻留", awaitEnded().endReason)
        awaitNoCall()

        // Anyone can fetch the call again by dialling the slot.
        dial(slot)
        awaitConnected()
        awaitBridged(APP, AGENT)
        assertTrue("Still parked", pbx.list("ParkedCalls").isEmpty())
    }

    @Test
    fun mergeIntoThreeWayCall() {
        assumeTrue("The SDK's conference audio processing needs AVX on x86", sdkAudioProcessingSupported())
        // With file audio the SDK does not connect the calls to its mixer at all, so this one
        // uses the sound card. The microphone is muted before anything is checked, which keeps
        // whatever it would pick up out of the result.
        onMain { engine.core.useFiles = false }
        showApp()
        register()
        dial("$AGENT")
        awaitConnected("$AGENT")
        ok(onMain { engine.calls.startConsultation("$CUSTOMER") })
        awaitCall("the second call") { it.phase == CallPhase.CONNECTED && it.remoteAddress.startsWith("$CUSTOMER@") }

        // Muted while talking to the second party: that stays so in the three-way call.
        onMain { engine.calls.toggleMute() }
        ok(onMain { engine.calls.openRoom() })
        assertTrue(awaitCall("the room of three") { it.room?.present == 2 }.muted)
        // Muting inside the three-way call must leave the mix alone as well.
        awaitBridgedWithApp()
        onMain { engine.calls.toggleMute() }
        onMain { engine.calls.toggleMute() }
        assertTrue(call()!!.muted)
        Thread.sleep(PHONES_NOTICE_SILENCE_MS)

        // The app mixes the call: each party hears the other one through it, although the
        // app's own microphone is muted.
        var mark = phones.mark()
        phoneTalks(AGENT)
        awaitPhoneHears(CUSTOMER, mark)
        mark = phones.mark()
        phoneTalks(CUSTOMER)
        awaitPhoneHears(AGENT, mark)
        onMain { engine.calls.toggleMute() }
        assertTrue(!call()!!.muted)

        onMain { engine.calls.hangUp() }
        awaitNoCall()
        await("both calls to be released") { pbxChannels().isEmpty() }
    }

    /** Both calls of the app are up again on the PBX (merging takes the first one off hold). */
    private fun awaitBridgedWithApp() = await("both parties to be connected to the app") {
        pbxChannels().count { it["ChannelStateDesc"] == "Up" && it["BridgeId"].orEmpty().isNotEmpty() } >= 4
    }

    private companion object {
        /** The desk phones report silence after one second without sound (TALK_DETECT). */
        const val PHONES_NOTICE_SILENCE_MS = 2_500L
    }
}
