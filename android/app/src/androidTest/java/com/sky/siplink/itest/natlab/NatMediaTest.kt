package com.sky.siplink.itest.natlab

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sky.siplink.core.CallSupport
import com.sky.siplink.core.model.CallPhase
import com.sky.siplink.core.model.MediaFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The sound of the calls: what the call screen says while none comes in; a PBX that names an
 * address of its own network for its calls, and one that does so for its sound only.
 */
@RunWith(AndroidJUnit4::class)
class NatMediaTest : NatLabTestBase() {
    @Test
    fun withoutSoundTheCallSaysSoAndEndsWithIt() {
        // The PBX without NAT handling sends its sound where nothing arrives.
        addAccount(PLAIN_ACCOUNT)
        awaitRegistered(PLAIN_ACCOUNT)
        dial(TONE)
        await("the call to connect") { call()?.phase == CallPhase.CONNECTED }
        await("the line about missing sound", NO_SOUND_TIMEOUT_MS) { call()?.noSound == true }
        onMain { engine.calls.hangUp() }
        await("the call to be gone") { call() == null || call()?.phase == CallPhase.ENDED }

        // A call with sound both ways says nothing, until the sound stops on the way.
        addAccount(NAT_ACCOUNT)
        awaitRegistered(NAT_ACCOUNT)
        val id = account(NAT_ACCOUNT)!!.id
        val error = onMain { engine.calls.dial(ECHO, id) }
        assertTrue("The app refused to dial: $error", error == null)
        await("the call to connect") { call()?.phase == CallPhase.CONNECTED }
        await("the app to hear its echo") { playVolume() > AUDIBLE_DB }
        never("the line about missing sound", QUIET_WINDOW_MS) { call()?.noSound == true }
        box("rtp off")
        await("the line about missing sound", NO_SOUND_TIMEOUT_MS) { call()?.noSound == true }
        // The SIP core gives up after its 30 seconds without sound.
        var ended: String? = null
        await("the call to end for want of sound", MEDIA_LOST_TIMEOUT_MS) {
            call()?.takeIf { it.phase == CallPhase.ENDED }?.let { ended = it.endReason }
            ended != null || call() == null
        }
        await("the last call to be known") { onMain { engine.accounts.lastCall(id) } != null }
        val last = onMain { engine.accounts.lastCall(id) }
        println("natlab evidence: no sound, ended '$ended', last call $last")
        assertEquals(CallSupport.NO_SOUND, ended)
        assertEquals(true, last?.mediaLost)
    }

    @Test
    fun aPbxThatNamesAHopOfItsOwnNetworkIsShownAndNothingChanges() {
        addAccount(BEHIND_ACCOUNT, port = lab.innerPort)
        awaitRegistered(BEHIND_ACCOUNT)
        val id = account(BEHIND_ACCOUNT)!!.id
        dial(ECHO)
        await("the call to connect") { call()?.phase == CallPhase.CONNECTED }
        Thread.sleep(QUIET_WINDOW_MS)
        onMain { engine.calls.hangUp() }
        await("the finding") { onMain { engine.accounts.pbxUnreachable(id) } }
        // The BYE went to the PBX's own address as well: the PBX ends the call itself.
        await("the PBX to have no channel", NO_ACK_WINDOW_MS + BYE_TIMEOUT_MS) { pbx.list("CoreShowChannels").isEmpty() }
        val report = check(BEHIND_ACCOUNT)
        println("natlab evidence: PBX behind a NAT of its own $report")
        assertTrue(report.pbxUnreachable)
        // Nothing on the phone repairs it, and nothing is changed.
        assertNull(onMain { engine.accounts.adaptation(id) })
        assertEquals(false, onMain { engine.accounts.find(id)!!.params.isOutboundProxyEnabled })
        assertEquals(0, onMain { engine.core.config.getInt("rtp", "symmetric", 0) })
    }

    @Test
    fun aPbxThatKnowsOnlyItsSignallingAddressKeepsItsCallsButNotItsSound() {
        addAccount(SIGNALLING_ACCOUNT, port = lab.signallingPort)
        awaitRegistered(SIGNALLING_ACCOUNT)
        val id = account(SIGNALLING_ACCOUNT)!!.id
        // Without sound the SIP core ends a call after its 30 seconds (nortp_timeout, counted
        // from the start when nothing ever came): off here, so that the call shows whether its
        // ACK reached the PBX.
        val noRtpTimeout = onMain { engine.core.nortpTimeout }
        onMain { engine.core.nortpTimeout = 0 }
        try {
            dial(ECHO)
            await("the call to connect") { call()?.phase == CallPhase.CONNECTED }
            // The ACK reaches the PBX: the call outlasts the half minute the PBX waits for it.
            never("the call to end", NO_ACK_WINDOW_MS) { call()?.phase != CallPhase.CONNECTED }
            onMain { engine.calls.hangUp() }
            await("the PBX to have no channel", BYE_TIMEOUT_MS) { pbx.list("CoreShowChannels").isEmpty() }
        } finally {
            onMain { engine.core.nortpTimeout = noRtpTimeout }
        }
        val report = check(SIGNALLING_ACCOUNT)
        println("natlab evidence: PBX with its own media address $report")
        assertFalse(report.pbxUnreachable)
        // Its sound goes to the phone's own address, the phone's to the PBX's: nothing comes in.
        assertTrue("${report.lastCall}", report.lastCall?.flow in setOf(MediaFlow.SEND_ONLY, MediaFlow.NONE))
        assertNull(onMain { engine.accounts.adaptation(id) })
        assertEquals(0, onMain { engine.core.config.getInt("rtp", "symmetric", 0) })
    }

    private companion object {
        /** The SIP core's 30 seconds (nortp_timeout), and a margin for the slow emulator. */
        const val MEDIA_LOST_TIMEOUT_MS = 90_000L

        /** Past the 32 seconds after which the PBX gives a call without ACK up (64 × T1). */
        const val NO_ACK_WINDOW_MS = 40_000L
        const val BYE_TIMEOUT_MS = 10_000L
    }
}
