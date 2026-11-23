package com.sky.siplink.itest.natlab

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sky.siplink.core.model.CallPhase
import com.sky.siplink.core.model.MediaFlow
import com.sky.siplink.core.model.NatKind
import com.sky.siplink.core.model.RegState
import com.sky.siplink.core.model.SipTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** What the line checks find behind the kinds of NAT the lab has, and what the last call tells. */
@RunWith(AndroidJUnit4::class)
class NatDiagnosisTest : NatLabTestBase() {
    @Test
    fun coneNatWithAPbxThatHandlesItWorksBothWays() {
        addAccount(NAT_ACCOUNT)
        awaitRegistered(NAT_ACCOUNT)
        val report = check(NAT_ACCOUNT)
        println("natlab evidence: cone $report")
        assertEquals(NatKind.PRESENT, report.nat)
        assertEquals(BOX_INET, report.publicAddress?.address)
        assertNotNull(report.udp)
        assertEquals(true, report.udpInvite)
        assertEquals(true, report.udpLarge)
        assertNotNull(report.tcpMs)
        assertEquals(false, report.algSuspected)
        assertEquals(RegState.OK, report.registration)
        assertNull(report.adaptedTransport)

        dial(ECHO)
        await("the call to connect") { call()?.phase == CallPhase.CONNECTED }
        await("the app to hear its echo") { playVolume() > AUDIBLE_DB }
        onCore { engine.calls.hangUp() }
        await("the last call to be known") { onCore { engine.accounts.lastCall(account(NAT_ACCOUNT)!!.id) } != null }
        assertEquals(MediaFlow.BOTH, onCore { engine.accounts.lastCall(account(NAT_ACCOUNT)!!.id) }?.flow)
    }

    @Test
    fun aSymmetricNatIsToldWithAStunServer() {
        box("mode symmetric")
        useStunServer()
        addAccount(NAT_ACCOUNT)
        awaitRegistered(NAT_ACCOUNT)
        val report = check(NAT_ACCOUNT)
        println("natlab evidence: symmetric $report")
        assertEquals(NatKind.SYMMETRIC, report.nat)
        assertEquals(BOX_INET, report.publicAddress?.address)
    }

    @Test
    fun aSipAlgThatRewritesTheViaIsSuspected() {
        box("alg on")
        addAccount(NAT_ACCOUNT)
        awaitRegistered(NAT_ACCOUNT)
        val report = check(NAT_ACCOUNT)
        println("natlab evidence: alg $report")
        assertEquals(true, report.algSuspected)
    }

    @Test
    fun aPbxWithoutNatHandlingLeavesTheAppDeaf() {
        addAccount(PLAIN_ACCOUNT)
        awaitRegistered(PLAIN_ACCOUNT)
        dial(TONE)
        await("the call to connect") { call()?.phase == CallPhase.CONNECTED }
        never("the app hearing the PBX", DEAF_WINDOW_MS) { playVolume() > AUDIBLE_DB }
        onCore { engine.calls.hangUp() }
        val id = account(PLAIN_ACCOUNT)!!.id
        await("the last call to be known") { onCore { engine.accounts.lastCall(id) } != null }
        val last = onCore { engine.accounts.lastCall(id) }
        println("natlab evidence: PBX without NAT handling, last call $last")
        assertEquals(MediaFlow.SEND_ONLY, last?.flow)
    }

    /**
     * A NAT that forgets a UDP mapping after 20 seconds, which the keep-alive every 30 seconds
     * cannot hold: every registration finds the line at another port. Twice, and the line
     * registers every 300 seconds; twice more, and it moves to TCP.
     */
    @Test
    fun aNatThatForgetsTheLineShortensItsIntervalThenLeavesUdp() {
        assumeTrue("Takes about half an hour: run.sh with NATLAB_LONG=1", lab.long)
        box("mode symmetric")
        box("timeout $SHORT_TIMEOUT_S")
        addAccount(NAT_ACCOUNT, expires = LONG_EXPIRES)
        awaitRegistered(NAT_ACCOUNT)
        val id = account(NAT_ACCOUNT)!!.id
        // Without probing the network on every look: only what the doctor holds.
        await("a shorter registration interval", BINDING_TIMEOUT_MS) { onCore { engine.accounts.adaptation(id) }?.expires != null }
        val report = check(NAT_ACCOUNT)
        println("natlab evidence: forgetting NAT $report")
        assertEquals(300, report.adaptedExpires)
        assertEquals(LONG_EXPIRES, account(NAT_ACCOUNT)?.expires)
        assertTrue(onCore { engine.accounts.adaptedTransports.value[id] } == null)

        await("the line on TCP", SHORT_BINDING_TIMEOUT_MS) { adapted(NAT_ACCOUNT) == SipTransport.TCP && account(NAT_ACCOUNT)?.state == RegState.OK }
        val onTcp = check(NAT_ACCOUNT)
        println("natlab evidence: forgetting NAT, later $onTcp")
        assertEquals(null, onTcp.adaptedExpires)
        pbxCallsApp(NAT_ACCOUNT)
        await("the incoming call over TCP") { call()?.phase == CallPhase.INCOMING }
    }

    private companion object {
        /** Where the PBX sees every client: the NAT box's address on its side. */
        const val BOX_INET = "172.31.252.2"
        const val DEAF_WINDOW_MS = 10_000L
        const val SHORT_TIMEOUT_S = 20
        const val LONG_EXPIRES = 600
        const val BINDING_TIMEOUT_MS = 25 * 60_000L

        /** Two registrations at 300 seconds, and a margin. */
        const val SHORT_BINDING_TIMEOUT_MS = 15 * 60_000L
    }
}
