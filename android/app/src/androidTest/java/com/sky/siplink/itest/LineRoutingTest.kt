package com.sky.siplink.itest

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sky.siplink.core.NetworkWatch
import com.sky.siplink.core.TlsManager
import com.sky.siplink.core.logic.ServerChoice
import com.sky.siplink.core.model.AudioRoute
import com.sky.siplink.core.model.CallPhase
import com.sky.siplink.core.model.NetworkFacts
import com.sky.siplink.core.model.RegState
import com.sky.siplink.core.model.SipTransport
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.InetSocketAddress

/** An account with a backup server, and an account with a DNS server of its own. */
@RunWith(AndroidJUnit4::class)
class LineRoutingTest : PbxTestBase() {
    private var relay: ServerRelay? = null

    /** The network the phone is on, as the app had it before the test fed it others ([moveTo]). */
    private var realNetwork: NetworkFacts? = null

    @After
    fun closeRelay() {
        relay?.close()
        realNetwork?.let { real -> onMain { NetworkWatch.publish(real) } }
    }

    /**
     * Has the app take the phone to be on another network, which differs from the real one only
     * in a name server it does not use (192.0.2.0/24 is for documentation): its own path from
     * Android's callbacks on (NetworkWatch). The phone itself stays on its network, so this does
     * not show what a real change does to sockets, routes and the SIP core's connections.
     */
    private fun network(tag: Int): NetworkFacts {
        val real = realNetwork ?: onMain { NetworkWatch.facts }.also { realNetwork = it }
        assumeTrue("The app does not know the network the phone is on", real != null)
        return real!!.copy(dns = real.dns + "192.0.2.$tag")
    }

    private fun moveTo(facts: NetworkFacts) = onMain { NetworkWatch.publish(facts) }

    private fun onBackupNow(): Boolean = account(APP)?.let { it.state == RegState.OK && it.onBackup } == true

    private fun onFirstNow(): Boolean = account(APP)?.let { it.state == RegState.OK && !it.onBackup } == true

    /** The relay as the first server, the PBX itself as the backup. */
    private fun addRoutedAccount(timeoutMs: Int) {
        startRelay()
        addAccount(draft(APP).copy(server = "$HOST:$RELAY_PORT", backupServer = env.pbxHost, serverTimeoutMs = timeoutMs))
        assertFalse(awaitRegistered().onBackup)
        // The servers were asked as the account started; a registration asks them again only after a while.
        Thread.sleep(ServerChoice.MIN_CHECK_GAP_MS + SLACK_MS)
    }

    private fun report(what: String, ms: Long) = Log.i(TAG, "$what: ${ms} ms")

    private fun startRelay() {
        relay = ServerRelay(InetSocketAddress(env.pbxHost, SIP_PORT), InetSocketAddress(InetAddress.getByName(HOST), RELAY_PORT))
    }

    @Test
    fun theBackupServerTakesOverAndTheFirstOneIsReturnedTo() {
        startRelay()
        addAccount(draft(APP).copy(server = "$HOST:$RELAY_PORT", backupServer = env.pbxHost))
        assertFalse(awaitRegistered().onBackup)
        assertTrue(pbxHasContact(APP))

        // The first server goes away. The next registration finds that out.
        relay?.close()
        onMain { engine.core.refreshRegisters() }
        await("the account to move to its backup server") { account(APP)?.let { it.state == RegState.OK && it.onBackup } == true }
        assertEquals("$HOST:$RELAY_PORT", account(APP)?.server)

        // Calls take the same way.
        dial("2000") // echo
        awaitConnected("2000")
        awaitAppHearsAudio()
        onMain { engine.calls.hangUp() }
        awaitNoCall()

        // The first server is back: the account returns to it by itself.
        startRelay()
        await("the account to return to its first server", RETURN_TIMEOUT_MS) {
            account(APP)?.let { it.state == RegState.OK && !it.onBackup } == true
        }
        dial("2000")
        awaitConnected("2000")
        onMain { engine.calls.hangUp() }
        awaitNoCall()
    }

    @Test
    fun aWrongPasswordDoesNotSendTheAccountToItsBackupServer() {
        startRelay()
        addAccount(draft(APP).copy(password = "not the password", server = env.pbxHost, backupServer = "$HOST:$RELAY_PORT"))
        await("the registration to be refused") { account(APP)?.state == RegState.FAILED }
        never("moved to the backup server over a refusal", QUIET_WINDOW_MS) { account(APP)?.onBackup == true }
    }

    @Test
    fun aLineFindsItsServerThroughItsOwnDnsServer() {
        if (env.dnsNameIsPrivate) {
            // The device itself does not know the name.
            addAccount(APP, domain = env.dnsName)
            await("the registration to fail without a DNS server that knows the name") { account(APP)?.state == RegState.FAILED }
            onMain { engine.accounts.remove(account(APP)!!.id) }
        }

        addAccount(draft(APP, domain = env.dnsName).copy(dnsServer = env.dnsServer))
        // Switched on from the first moment, although the registration waits for the address.
        assertTrue(account(APP)!!.enabled)
        awaitRegistered()
        assertTrue(pbxHasContact(APP))
        assertEquals(env.dnsServer, account(APP)?.dnsServer)

        dial("2000") // echo
        awaitConnected("2000")
        awaitAppHearsAudio()
        onMain { engine.calls.hangUp() }
        awaitNoCall()
    }

    @Test
    fun tlsStillChecksTheNameOfAServerFoundThroughTheLineDns() {
        assertEquals(TlsManager.ImportResult.Added(1), onMain { engine.tls.import(env.caPem) })
        addAccount(draft(APP, SipTransport.TLS, env.dnsName).copy(dnsServer = env.dnsServer))
        awaitRegistered()
        val aor = pbx.command("pjsip show aor $APP").lowercase()
        assertTrue("The PBX contact is not a TLS one:\n$aor", "transport=tls" in aor)
    }

    @Test
    fun aDnsServerThatDoesNotAnswerLeavesTheLookupToTheSystem() {
        // Nothing listens there. The name is one the device resolves by itself.
        assumeTrue("The device has no name for the PBX", env.pbxName != env.pbxHost)
        addAccount(draft(APP, domain = env.pbxName).copy(dnsServer = "${env.pbxHost}:$DEAD_PORT"))
        assertTrue(account(APP)!!.enabled)
        awaitRegistered()
    }

    /** With the fast timeout a first server that falls silent is left when the registration is refreshed. */
    @Test
    fun theFastTimeoutLeavesASilentServerInAMoment() {
        addRoutedAccount(timeoutMs = FAST_MS)
        relay!!.silent = true
        val start = SystemClock.elapsedRealtime()
        onMain { engine.core.refreshRegisters() }
        await("the account to move to its backup server", FAST_FAILOVER_MS) { onBackupNow() }
        report("Fast timeout $FAST_MS ms: registered on the backup after the first server fell silent", SystemClock.elapsedRealtime() - start)
    }

    /** The same without the fast timeout: the registration's own SIP timeout decides, for comparison. */
    @Test
    fun withoutTheFastTimeoutASilentServerCostsTheSipTimeout() {
        addRoutedAccount(timeoutMs = 0)
        relay!!.silent = true
        val start = SystemClock.elapsedRealtime()
        onMain { engine.core.refreshRegisters() }
        await("the account to move to its backup server", SIP_FAILOVER_MS) { onBackupNow() }
        report("No fast timeout: registered on the backup after the first server fell silent", SystemClock.elapsedRealtime() - start)
    }

    /** A change of network has both servers asked at once, with the fast timeout off as well. */
    @Test
    fun aChangeOfNetworkAsksBothServersAgain() {
        addRoutedAccount(timeoutMs = 0)
        relay!!.silent = true
        val start = SystemClock.elapsedRealtime()
        moveTo(network(1))
        await("the account to move to its backup server", NETWORK_FAILOVER_MS) { onBackupNow() }
        report("Change of network: registered on the backup", SystemClock.elapsedRealtime() - start)

        // The first server is there again on the next network: the account returns without the minute's wait.
        relay!!.silent = false
        val back = SystemClock.elapsedRealtime()
        moveTo(network(2))
        await("the account to return to its first server", NETWORK_FAILOVER_MS) { onFirstNow() }
        report("Change of network: back on the first server", SystemClock.elapsedRealtime() - back)
    }

    /** On a network it knows the account goes at once to the server it registered on there last. */
    @Test
    fun theServerOfANetworkIsRemembered() {
        val office = network(1)
        val home = network(2)
        moveTo(office)
        addRoutedAccount(timeoutMs = FAST_MS)
        relay!!.silent = true
        moveTo(home)
        await("the account to move to its backup server at home", NETWORK_FAILOVER_MS) { onBackupNow() }

        relay!!.silent = false
        // Back in the office: on its first server as soon as the network is known, before any answer.
        assertEquals(RELAY_PORT, onMain { NetworkWatch.publish(office); engine.accounts.find(account(APP)!!.id)!!.params.serverAddress!!.port })
        await("the account to register on its first server in the office", NETWORK_FAILOVER_MS) { onFirstNow() }

        // At home again, where the first server does not answer: on the backup at once, and it stays there.
        relay!!.silent = true
        val port = onMain { NetworkWatch.publish(home); engine.accounts.find(account(APP)!!.id)!!.params.serverAddress!!.port }
        assertTrue("Not on the backup at once: port $port", port != RELAY_PORT)
        await("the account to register on its backup at home", NETWORK_FAILOVER_MS) { onBackupNow() }
        never("the account to go back to its first server at home", QUIET_WINDOW_MS) { !onBackupNow() }
    }

    /** A call on the backup stays up while the first server answers again; the account returns after it. */
    @Test
    fun aCallStaysOnItsServerWhileTheOtherComesBack() {
        addRoutedAccount(timeoutMs = FAST_MS)
        relay!!.silent = true
        moveTo(network(1))
        await("the account to move to its backup server", NETWORK_FAILOVER_MS) { onBackupNow() }

        dial("2000") // echo
        awaitConnected("2000")
        awaitAppHearsAudio()
        relay!!.silent = false
        moveTo(network(2))
        never("the account to leave the backup during the call", CALL_WINDOW_MS) { !onBackupNow() }
        assertEquals(CallPhase.CONNECTED, call()?.phase)
        awaitAppHearsAudio()
        onMain { engine.calls.hangUp() }
        awaitNoCall()

        moveTo(network(3))
        await("the account to return to its first server after the call", NETWORK_FAILOVER_MS) { onFirstNow() }
    }

    /** A call out to a first server that fell silent unnoticed is placed again over the backup, once, on the same screen. */
    @Test
    fun aCallOutToASilentServerIsPlacedAgainOverTheBackup() {
        addRoutedAccount(timeoutMs = FAST_MS)
        val entries = engine.history.entries.value.size
        relay!!.silent = true
        val phases = ArrayList<CallPhase?>()
        val start = SystemClock.elapsedRealtime()
        dial("2000") // echo
        await("the call to be connected over the backup", RETRY_CONNECT_MS) {
            phases += call()?.phase
            call()?.phase == CallPhase.CONNECTED
        }
        report("Call out placed again over the backup: connected", SystemClock.elapsedRealtime() - start)
        assertTrue("The call screen ended or went away in between: $phases", phases.none { it == null || it == CallPhase.ENDED })
        assertTrue(account(APP)!!.onBackup)
        awaitAppHearsAudio()
        onMain { engine.calls.hangUp() }
        awaitNoCall()
        await("one entry in the history") { engine.history.entries.value.size == entries + 1 }
        never("a second entry in the history", QUIET_WINDOW_MS) { engine.history.entries.value.size > entries + 1 }
    }

    /** A call out to a first server that answers but takes a while to set it up is not placed again. */
    @Test
    fun aCallOutToASlowServerIsNotPlacedAgain() {
        addRoutedAccount(timeoutMs = FAST_MS)
        relay!!.delayMs = SMALL_DELAY_MS
        val entries = engine.history.entries.value.size
        // Answered after two seconds, with no ringing tone before (ConferencePacingTest): past the retry's look.
        dial("2400")
        awaitConnected("2400")
        awaitNoCall()
        assertFalse("Moved to the backup over a slow call", account(APP)!!.onBackup)
        await("one entry in the history") { engine.history.entries.value.size == entries + 1 }
        never("a second entry in the history", QUIET_WINDOW_MS) { engine.history.entries.value.size > entries + 1 }
    }

    /** What the user set before the call rang, the microphone off and the speaker, stays across the retry. */
    @Test
    fun aCallPlacedAgainKeepsItsMicrophoneAndRoute() {
        addRoutedAccount(timeoutMs = FAST_MS)
        relay!!.silent = true
        dial("2000") // echo
        await("the call to be on screen") { call()?.phase == CallPhase.OUTGOING }
        onMain {
            engine.calls.toggleMute()
            engine.calls.setRoute(AudioRoute.SPEAKER)
        }
        val connected = awaitConnected("2000")
        assertTrue(account(APP)!!.onBackup)
        assertTrue("The microphone is on again", connected.muted)
        await("the sound on the speaker") { call()?.route == AudioRoute.SPEAKER }
        onMain { engine.calls.hangUp() }
        awaitNoCall()
    }

    /**
     * How often a short fast timeout takes a first server that answers, through a path that loses
     * or delays packets, for out of reach. The checks run as the registration is refreshed, on the
     * network the account registered on: it leaves only after two silent checks in a row. One
     * check is silent when its three requests within the 500 ms, or their answers, are all lost:
     * q³ with q = 1 − (1 − p)² for a loss p each way; two in a row, q⁶: 1.8 % at 30 %, 0.2 % at
     * 20 %, below 0.01 % at 10 %. Over [LOSS_ROUNDS] checks one wrong move at most is allowed
     * (two or more at 30 %: about 1.4 %).
     */
    @Test
    fun aShortFastTimeoutRidesOutLossAndDelay() {
        addRoutedAccount(timeoutMs = FAST_MS)
        val id = account(APP)!!.id
        for ((loss, delay) in PATHS) {
            relay!!.loss = loss
            relay!!.delayMs = delay
            var moved = 0
            repeat(LOSS_ROUNDS) {
                val before = onMain { engine.accounts.servers(id)?.checkedAt }
                Thread.sleep(ServerChoice.MIN_CHECK_GAP_MS + SLACK_MS)
                onMain { engine.core.refreshRegisters() }
                // The check, the second one after a silence, and the registration.
                Thread.sleep(2 * ServerChoice.waitMs(FAST_MS) + SLACK_MS)
                assertTrue("The servers were not asked", onMain { engine.accounts.servers(id)?.checkedAt } != before)
                if (account(APP)?.onBackup == true) {
                    moved++
                    relay!!.loss = 0.0
                    relay!!.delayMs = 0
                    moveTo(network(20 + moved))
                    await("the account to return to its first server", NETWORK_FAILOVER_MS) { onFirstNow() }
                    relay!!.loss = loss
                    relay!!.delayMs = delay
                }
            }
            Log.i(TAG, "Fast timeout $FAST_MS ms, ${(loss * 100).toInt()} % loss and $delay ms delay each way: $moved of $LOSS_ROUNDS checks took the backup")
            assertTrue("$moved wrong moves at ${(loss * 100).toInt()} % loss, $delay ms delay", moved <= if (loss == 0.0) 0 else 1)
        }
    }

    private companion object {
        const val HOST = "127.0.0.1"
        const val SIP_PORT = 5060
        const val RELAY_PORT = 5099

        /** A port of the PBX host on which nothing answers. */
        const val DEAD_PORT = 5399

        /** The first server is asked about once a minute. */
        const val RETURN_TIMEOUT_MS = 150_000L

        const val TAG = "SipLink.Test"
        const val FAST_MS = 500
        const val SLACK_MS = 1_000L

        /** The fast timeout, the backup's probe and its registration. */
        const val FAST_FAILOVER_MS = 10_000L

        /** A probe of four seconds, and a registration on the backup. */
        const val NETWORK_FAILOVER_MS = 15_000L

        /** The SIP core's own timeout of 32 seconds, and a registration. */
        const val SIP_FAILOVER_MS = 90_000L

        /** The INVITE's two seconds, the probes, and the call over the backup. */
        const val RETRY_CONNECT_MS = 20_000L
        const val CALL_WINDOW_MS = 10_000L
        /** Loss each way and delay each way of the path to the first server. */
        val PATHS = listOf(0.0 to 0L, 0.0 to SMALL_DELAY_MS, 0.1 to 0L, 0.2 to 0L, 0.3 to 0L, 0.1 to SMALL_DELAY_MS)
        const val LOSS_ROUNDS = 10

        /** 200 ms there and back: well within the 500 ms. */
        const val SMALL_DELAY_MS = 100L
    }
}
