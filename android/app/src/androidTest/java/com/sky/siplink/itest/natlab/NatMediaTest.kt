package com.sky.siplink.itest.natlab

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sky.siplink.core.CallSupport
import com.sky.siplink.core.model.CallPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The sound of the calls: what the call screen says while none comes in, and the way by the
 * server for a PBX that announces an address of its own network.
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
    fun aPbxBehindANatOfItsOwnIsReachedByWayOfTheServer() {
        addAccount(BEHIND_ACCOUNT, port = lab.innerPort)
        awaitRegistered(BEHIND_ACCOUNT)
        val id = account(BEHIND_ACCOUNT)!!.id
        dial(ECHO)
        await("the call to connect") { call()?.phase == CallPhase.CONNECTED }
        Thread.sleep(QUIET_WINDOW_MS)
        onMain { engine.calls.hangUp() }
        await("the way by the server") { onMain { engine.accounts.adaptation(id) }?.viaServer == true }
        assertEquals(1, onMain { engine.core.config.getInt("rtp", "symmetric", 0) })
        assertTrue(onMain { engine.accounts.find(id)!!.params.isOutboundProxyEnabled })
        await("the PBX to have no channel") { pbx.list("CoreShowChannels").isEmpty() }

        // The ACK reaches the PBX now: the call outlasts the half minute the PBX waits for it,
        // and the BYE ends it there as well.
        dial(ECHO)
        await("the call to connect") { call()?.phase == CallPhase.CONNECTED }
        never("the call to end", NO_ACK_WINDOW_MS) { call()?.phase != CallPhase.CONNECTED }
        onMain { engine.calls.hangUp() }
        await("the PBX to have no channel", BYE_TIMEOUT_MS) { pbx.list("CoreShowChannels").isEmpty() }
        val report = check(BEHIND_ACCOUNT)
        println("natlab evidence: PBX behind a NAT of its own $report")
        assertTrue(report.viaServer)

        // Restored, the account goes its own way again, and so does RTP.
        onMain { engine.accounts.resetAdaptation(id) }
        assertEquals(0, onMain { engine.core.config.getInt("rtp", "symmetric", 0) })
        assertEquals(false, onMain { engine.accounts.find(id)!!.params.isOutboundProxyEnabled })
    }

    private companion object {
        const val QUIET_WINDOW_MS = 8_000L

        /** Five seconds without sound, and the time the statistics take to come. */
        const val NO_SOUND_TIMEOUT_MS = 20_000L

        /** The SIP core's 30 seconds (nortp_timeout), and a margin for the slow emulator. */
        const val MEDIA_LOST_TIMEOUT_MS = 90_000L

        /** Past the 32 seconds after which the PBX gives a call without ACK up (64 × T1). */
        const val NO_ACK_WINDOW_MS = 40_000L
        const val BYE_TIMEOUT_MS = 10_000L
    }
}
