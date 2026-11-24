package com.sky.siplink.itest.natlab

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sky.siplink.core.logic.LineAdapt
import com.sky.siplink.core.logic.MediaAdapt.Ice
import com.sky.siplink.core.model.CallPhase
import com.sky.siplink.core.model.MediaFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.linphone.core.IceState

/**
 * ICE for a line whose sound went one way: tried after such a call, kept once a call with it has
 * sound both ways. The IPv6 setting stays as it is (on).
 */
@RunWith(AndroidJUnit4::class)
class NatIceTest : NatLabTestBase() {
    @Before
    fun stun() = useStunServer()

    @Test
    fun iceGivesTheSoundOfAPbxWithoutNatHandlingItsWay() {
        addAccount(PLAIN_ACCOUNT)
        awaitRegistered(PLAIN_ACCOUNT)
        val id = account(PLAIN_ACCOUNT)!!.id
        // Without ICE the PBX sends its tone to the phone's own address, where nothing arrives.
        oneWayCall(id, TONE)

        // With it the call's default address is the one the STUN server sees: the tone comes in.
        dial(TONE)
        await("the call to connect") { call()?.phase == CallPhase.CONNECTED }
        await("the app to hear the tone") { playVolume() > AUDIBLE_DB }
        hangUp()
        await("ICE to be kept") { ice(id) == Ice.ON }
        val out = onCore { engine.accounts.lastCall(id) }
        println("natlab evidence: ICE towards a PBX without it, last call $out")
        assertEquals(MediaFlow.BOTH, out?.flow)

        // A call in from the PBX offers no ICE: it goes without, and the line keeps ICE.
        val iceState = callFromPbx(PLAIN_ACCOUNT)
        val incoming = onCore { engine.accounts.lastCall(id) }
        println("natlab evidence: a call in without ICE on a line with ICE: $iceState, last call $incoming")
        assertEquals(IceState.NotActivated, iceState)
        assertEquals(Ice.ON, ice(id))
        assertEquals(true, onCore { engine.accounts.adaptation(id) }?.ice)
    }

    @Test
    fun aPbxThatDoesIceNegotiatesIt() {
        addAccount(ICE_ACCOUNT)
        awaitRegistered(ICE_ACCOUNT)
        val id = account(ICE_ACCOUNT)!!.id
        oneWayCall(id, TONE)

        dial(TONE)
        await("the call to connect") { call()?.phase == CallPhase.CONNECTED }
        await("the app to hear the tone") { playVolume() > AUDIBLE_DB }
        await("the ICE checks to succeed") { iceState() in CONNECTED }
        // Long enough for the statistics of the call to come.
        Thread.sleep(QUIET_WINDOW_MS)
        hangUp()
        await("ICE to be kept") { ice(id) == Ice.ON }
        val last = onCore { engine.accounts.lastCall(id) }
        println("natlab evidence: ICE with a PBX that does ICE, last call $last")
        assertTrue("ICE state: ${last?.ice}", CONNECTED.any { it.name == last?.ice })
    }

    @Test
    fun aCallInWithoutIceHasItsSoundOnALineWithIce() {
        addAccount(NAT_ACCOUNT)
        awaitRegistered(NAT_ACCOUNT)
        val id = account(NAT_ACCOUNT)!!.id
        // The echo is lost on its way back.
        box("rtp half")
        oneWayCall(id, ECHO)
        box("rtp on")

        // A PBX without ICE that sends its sound back where the phone's comes from.
        dial(ECHO)
        await("the call to connect") { call()?.phase == CallPhase.CONNECTED }
        await("the app to hear its echo") { playVolume() > AUDIBLE_DB }
        hangUp()
        await("ICE to be kept") { ice(id) == Ice.ON }

        val iceState = callFromPbx(NAT_ACCOUNT, hears = true)
        val incoming = onCore { engine.accounts.lastCall(id) }
        println("natlab evidence: a call in without ICE at a PBX with NAT handling: $iceState, last call $incoming")
        assertEquals(IceState.NotActivated, iceState)
        assertEquals(MediaFlow.BOTH, incoming?.flow)
        assertEquals(Ice.ON, ice(id))
    }

    @Test
    fun aTrialWhoseCallsFailInTheirSetUpIsGivenUp() {
        addAccount(PLAIN_ACCOUNT)
        awaitRegistered(PLAIN_ACCOUNT)
        val id = account(PLAIN_ACCOUNT)!!.id
        oneWayCall(id, TONE)

        // The INVITE with the candidates is larger than one packet: its fragments are dropped.
        box("fragments off")
        dial(TONE)
        await("the call to fail", CALL_FAIL_MS) { call() == null || call()?.phase == CallPhase.ENDED }
        await("ICE to be given up") { ice(id) == Ice.REFUSED }
        await("ICE out of the core") { onCore { engine.accounts.adaptation(id) }?.ice != true }
        println("natlab evidence: ICE trial, INVITE in fragments dropped: ${call()?.endReason}, line on ${adapted(PLAIN_ACCOUNT)}")
    }

    /** A call out to [target] whose sound goes one way: the line [id] tries ICE afterwards. */
    private fun oneWayCall(id: String, target: String) {
        dial(target)
        await("the call to connect") { call()?.phase == CallPhase.CONNECTED }
        // Nothing comes in, for longer than the call needs to be told one way (LineVerdict.SILENT_AFTER_MS).
        never("the app to hear $target", QUIET_WINDOW_MS) { playVolume() > AUDIBLE_DB }
        hangUp()
        println("natlab evidence: one way, last call ${onCore { engine.accounts.lastCall(id) }}")
        await("ICE on trial") { ice(id) == Ice.TRIAL }
        await("ICE in the core") { onCore { engine.accounts.adaptation(id) }?.ice == true }
    }

    /** Has the PBX call the app's [extension] and answers; [hears]: the PBX's tone comes in. Returns the call's ICE state. */
    private fun callFromPbx(extension: Int, hears: Boolean = false): IceState? {
        pbxCallsApp(extension)
        await("the incoming call") { call()?.phase == CallPhase.INCOMING }
        onCore { engine.calls.answer() }
        await("the call to connect") { call()?.phase == CallPhase.CONNECTED }
        if (hears) await("the app to hear the PBX") { playVolume() > AUDIBLE_DB } else Thread.sleep(QUIET_WINDOW_MS)
        val state = iceState()
        println("natlab evidence: a call in, volume ${playVolume()} dBm0")
        hangUp()
        return state
    }

    private fun hangUp() {
        onCore { engine.calls.hangUp() }
        await("the call to be gone") { call() == null || call()?.phase == CallPhase.ENDED }
        await("the PBX to have no channel") { pbx.list("CoreShowChannels").isEmpty() }
    }

    private fun iceState(): IceState? = onCore { engine.core.currentCall?.audioStats?.iceState }

    /** Where the line [id] stands with ICE on this network; null: nothing remembered for it. */
    private fun ice(id: String): Ice? = onCore {
        val config = engine.core.config
        config.getKeysNamesList(ADAPT_SECTION).filter { LineAdapt.accountOf(it) == id }
            .firstNotNullOfOrNull { LineAdapt.decode(config.getString(ADAPT_SECTION, it, null)) }?.ice
    }

    private companion object {
        /** Where LineDoctor keeps what it remembers of the lines. */
        const val ADAPT_SECTION = "siplink_line_adapt"
        val CONNECTED = setOf(IceState.HostConnection, IceState.ReflexiveConnection, IceState.RelayConnection)

        /** How long a UDP request of the SIP core waits for its answer (64 × T1), with a margin. */
        const val CALL_FAIL_MS = 90_000L
    }
}
