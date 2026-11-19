package com.sky.siplink.itest.natlab

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sky.siplink.core.model.CallPhase
import com.sky.siplink.core.model.RegState
import com.sky.siplink.core.model.SipTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A network that lets UDP to the SIP port through, or only small UDP packets: the line moves to
 * TCP by itself, and only while it adapts at all.
 */
@RunWith(AndroidJUnit4::class)
class NatTransportTest : NatLabTestBase() {
    @Test
    fun withoutAdaptationBlockedUdpLeavesTheLineUnregistered() {
        onMain { engine.accounts.autoAdapt = false }
        box("udp-sip off")
        addAccount(NAT_ACCOUNT)
        never("a registration over blocked UDP", BLOCKED_WINDOW_MS) { account(NAT_ACCOUNT)?.state == RegState.OK }
        assertEquals(RegState.FAILED, account(NAT_ACCOUNT)?.state)
        assertEquals(null, adapted(NAT_ACCOUNT))
        println("natlab evidence: blocked UDP, no adaptation: ${account(NAT_ACCOUNT)?.error}")
    }

    @Test
    fun blockedUdpMovesTheLineToTcpAndCallsStillArrive() {
        box("udp-sip off")
        addAccount(NAT_ACCOUNT)
        await("the line to register over TCP", ADAPT_TIMEOUT_MS) {
            account(NAT_ACCOUNT)?.state == RegState.OK && adapted(NAT_ACCOUNT) == SipTransport.TCP
        }
        // The account keeps its own transport; the PBX has it on TCP.
        assertEquals(SipTransport.UDP, account(NAT_ACCOUNT)?.transport)
        assertTrue("PBX contacts: ${pbxContacts(NAT_ACCOUNT)}", pbxContacts(NAT_ACCOUNT).any { "transport=tcp" in it.lowercase() })

        pbxCallsApp(NAT_ACCOUNT)
        await("the incoming call") { call()?.phase == CallPhase.INCOMING }
        onMain { engine.calls.answer() }
        await("the call to connect") { call()?.phase == CallPhase.CONNECTED }
        await("the app to hear the PBX") { playVolume() > AUDIBLE_DB }
        onMain { engine.calls.hangUp() }
        await("the call to be gone") { call() == null || call()?.phase == CallPhase.ENDED }

        // Switched off, the line goes back to its own transport, where it does not get through.
        onMain { engine.accounts.autoAdapt = false }
        await("the line back on UDP") { adapted(NAT_ACCOUNT) == null }
        await("the registration over blocked UDP to fail", CALL_FAIL_MS) { account(NAT_ACCOUNT)?.state == RegState.FAILED }
    }

    @Test
    fun withoutAdaptationLargeUdpPacketsLoseTheCall() {
        onMain { engine.accounts.autoAdapt = false }
        addAccount(NAT_ACCOUNT)
        awaitRegistered(NAT_ACCOUNT)
        box("max-udp $MAX_UDP")
        dial(ECHO)
        await("the call to fail", CALL_FAIL_MS) { call()?.phase == CallPhase.ENDED || call() == null }
        println("natlab evidence: large UDP dropped, no adaptation: ${call()?.endReason}")
        assertEquals(null, adapted(NAT_ACCOUNT))
    }

    @Test
    fun largeUdpPacketsMoveTheLineToTcp() {
        addAccount(NAT_ACCOUNT)
        awaitRegistered(NAT_ACCOUNT)
        box("max-udp $MAX_UDP")
        dial(ECHO)
        await("the call to fail", CALL_FAIL_MS) { call()?.phase == CallPhase.ENDED || call() == null }
        await("the line to register over TCP", ADAPT_TIMEOUT_MS) {
            account(NAT_ACCOUNT)?.state == RegState.OK && adapted(NAT_ACCOUNT) == SipTransport.TCP
        }
        await("the call screen to close") { call() == null }
        dial(ECHO)
        await("the call to connect") { call()?.phase == CallPhase.CONNECTED }
        await("the app to hear its echo") { playVolume() > AUDIBLE_DB }
    }

    private companion object {
        /** About two failed registrations over UDP and the time between them. */
        const val BLOCKED_WINDOW_MS = 150_000L

        /** How long a UDP request of the SIP core waits for its answer (64 × T1), with a margin. */
        const val CALL_FAIL_MS = 90_000L

        /** IP packets longer than this are dropped: a REGISTER gets through, an INVITE does not. */
        const val MAX_UDP = 1000
    }
}
