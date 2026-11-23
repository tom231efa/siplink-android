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
 * A network that does not let UDP to the SIP port through, or drops the IP fragments of a large
 * one: the line moves to TCP by itself, and only while it adapts at all. A transport the server
 * refuses ends its test: the line goes back to its own one, unless the refusal is a challenge
 * to a login that never registered (a wrong password). A line that registered on TCP is settled
 * there: a refusal of the account changes nothing.
 */
@RunWith(AndroidJUnit4::class)
class NatTransportTest : NatLabTestBase() {
    @Test
    fun withoutAdaptationBlockedUdpLeavesTheLineUnregistered() {
        onCore { engine.updateSettings { it.copy(autoNetwork = false) } }
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
        onCore { engine.calls.answer() }
        await("the call to connect") { call()?.phase == CallPhase.CONNECTED }
        await("the app to hear the PBX") { playVolume() > AUDIBLE_DB }
        onCore { engine.calls.hangUp() }
        await("the call to be gone") { call() == null || call()?.phase == CallPhase.ENDED }

        // Switched off, the line goes back to its own transport, where it does not get through.
        onCore { engine.updateSettings { it.copy(autoNetwork = false) } }
        await("the line back on UDP") { adapted(NAT_ACCOUNT) == null }
        await("the registration over blocked UDP to fail", CALL_FAIL_MS) { account(NAT_ACCOUNT)?.state == RegState.FAILED }
    }

    @Test
    fun withoutAdaptationDroppedFragmentsLoseTheCall() {
        onCore { engine.updateSettings { it.copy(autoNetwork = false) } }
        addAccount(NAT_ACCOUNT)
        awaitRegistered(NAT_ACCOUNT)
        box("fragments off")
        // With video in its offer the INVITE is larger than one packet.
        dial(ECHO, video = true)
        await("the call to fail", CALL_FAIL_MS) { call()?.phase == CallPhase.ENDED || call() == null }
        println("natlab evidence: fragments dropped, no adaptation: ${call()?.endReason}")
        assertEquals(null, adapted(NAT_ACCOUNT))
    }

    @Test
    fun droppedFragmentsMoveTheLineToTcp() {
        addAccount(NAT_ACCOUNT)
        awaitRegistered(NAT_ACCOUNT)
        box("fragments off")
        dial(ECHO, video = true)
        await("the call to fail", CALL_FAIL_MS) { call()?.phase == CallPhase.ENDED || call() == null }
        await("the line to register over TCP", ADAPT_TIMEOUT_MS) {
            account(NAT_ACCOUNT)?.state == RegState.OK && adapted(NAT_ACCOUNT) == SipTransport.TCP
        }
        await("the call screen to close") { call() == null }
        dial(ECHO, video = true)
        await("the call to connect") { call()?.phase == CallPhase.CONNECTED }
        await("the app to hear its echo") { playVolume() > AUDIBLE_DB }
    }

    @Test
    fun aTransportTheServerRefusesEndsItsTestAndTheLineGoesBack() {
        // Its login registers over UDP first: the 401 over TCP that follows is the transport's refusal.
        addAccount(NO_TCP_ACCOUNT)
        awaitRegistered(NO_TCP_ACCOUNT)
        // The PBX takes this account over UDP only, whatever it sends over TCP.
        box("tcp-alias on")
        box("udp-sip off")
        onCore { engine.accounts.registerAnew() }
        await("the line on TCP for a test", ADAPT_TIMEOUT_MS) { adapted(NO_TCP_ACCOUNT) == SipTransport.TCP }
        await("the line back on its own transport", ADAPT_TIMEOUT_MS) { adapted(NO_TCP_ACCOUNT) == null }
        println("natlab evidence: refused over TCP: ${account(NO_TCP_ACCOUNT)?.error}, PBX contacts ${pbxContacts(NO_TCP_ACCOUNT)}")
        assertTrue(pbxContacts(NO_TCP_ACCOUNT).none { "transport=tcp" in it.lowercase() })
        // UDP still gets no answer: TCP, which refused it, is not tried again for now.
        never("the line on TCP again", BLOCKED_WINDOW_MS) { adapted(NO_TCP_ACCOUNT) != null }

        // Once UDP gets through, it registers there.
        box("udp-sip on")
        onCore { engine.accounts.registerAnew() }
        await("the registration over UDP", CALL_FAIL_MS) { account(NO_TCP_ACCOUNT)?.state == RegState.OK }
        assertEquals(null, adapted(NO_TCP_ACCOUNT))
    }

    @Test
    fun aWrongPasswordOnTestShowsAsSuchAndTheLineStays() {
        box("udp-sip off")
        // A new account whose login never registered: the 401 over TCP is the login's, not TCP's.
        val wrong = onCore { engine.accounts.add(draft(NAT_ACCOUNT).copy(password = "${lab.sipPassword}-wrong")) }
        assertTrue("Account rejected: $wrong", wrong.isEmpty())
        await("the line on TCP for a test", ADAPT_TIMEOUT_MS) { adapted(NAT_ACCOUNT) == SipTransport.TCP }
        await("the refusal of the login", CALL_FAIL_MS) { account(NAT_ACCOUNT)?.error?.startsWith("401") == true }
        println("natlab evidence: wrong password on test: ${account(NAT_ACCOUNT)?.error}, line on ${adapted(NAT_ACCOUNT)}")
        never("the line back on blocked UDP", SETTLED_WINDOW_MS) { adapted(NAT_ACCOUNT) != SipTransport.TCP }
        // Still the login's error, not a timeout over UDP.
        await("the refusal of the login, still", CALL_FAIL_MS) { account(NAT_ACCOUNT)?.error?.startsWith("401") == true }
    }

    @Test
    fun aLineSettledOnTcpStaysThereWhenTheServerRefusesTheAccount() {
        box("udp-sip off")
        addAccount(NAT_ACCOUNT)
        await("the line to register over TCP", ADAPT_TIMEOUT_MS) {
            account(NAT_ACCOUNT)?.state == RegState.OK && adapted(NAT_ACCOUNT) == SipTransport.TCP
        }
        // The password on the PBX changed and the app has the old one: the account is refused, not TCP.
        val id = account(NAT_ACCOUNT)!!.id
        val wrong = onCore { engine.accounts.update(id, draft(NAT_ACCOUNT).copy(password = "${lab.sipPassword}-old")) }
        assertTrue("Change rejected: $wrong", wrong.isEmpty())
        await("the refusal over TCP", CALL_FAIL_MS) { account(NAT_ACCOUNT)?.state == RegState.FAILED }
        println("natlab evidence: settled on TCP, refused: ${account(NAT_ACCOUNT)?.error}")
        never("the line to leave TCP", SETTLED_WINDOW_MS) { adapted(NAT_ACCOUNT) != SipTransport.TCP }

        // The right password again: it registers where it was.
        val right = onCore { engine.accounts.update(id, draft(NAT_ACCOUNT)) }
        assertTrue("Change rejected: $right", right.isEmpty())
        await("the registration over TCP", CALL_FAIL_MS) {
            account(NAT_ACCOUNT)?.state == RegState.OK && adapted(NAT_ACCOUNT) == SipTransport.TCP
        }
    }

    private companion object {
        /** About two failed registrations over UDP and the time between them. */
        const val BLOCKED_WINDOW_MS = 150_000L

        /** How long a UDP request of the SIP core waits for its answer (64 × T1), with a margin. */
        const val CALL_FAIL_MS = 90_000L

        /** Longer than a line on test takes to be sent back after a refusal: a probe at most every half minute, and its answer. */
        const val SETTLED_WINDOW_MS = 60_000L
    }
}
