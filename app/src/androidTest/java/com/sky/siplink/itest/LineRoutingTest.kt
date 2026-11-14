package com.sky.siplink.itest

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sky.siplink.core.TlsManager
import com.sky.siplink.core.model.RegState
import com.sky.siplink.core.model.SipTransport
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/** An account with a backup server, and an account with a DNS server of its own. */
@RunWith(AndroidJUnit4::class)
class LineRoutingTest : PbxTestBase() {
    private var relay: Relay? = null

    @After
    fun closeRelay() {
        relay?.close()
    }

    /**
     * A second way to the PBX that the test can cut: a UDP relay on the device itself. To the app
     * it is a SIP server of its own, at 127.0.0.1.
     */
    private class Relay(private val target: InetSocketAddress, port: Int) : Closeable {
        private val front = DatagramSocket(InetSocketAddress(InetAddress.getByName(HOST), port))
        private val backs = ConcurrentHashMap<SocketAddress, DatagramSocket>()

        init {
            pump(front) { packet ->
                // One socket towards the PBX per sender, so that its answers find their way back.
                val back = backs.getOrPut(packet.socketAddress) { backFor(packet.socketAddress) }
                back.send(DatagramPacket(packet.data, packet.length, target))
            }
        }

        private fun backFor(client: SocketAddress): DatagramSocket {
            val socket = DatagramSocket()
            pump(socket) { packet -> front.send(DatagramPacket(packet.data, packet.length, client)) }
            return socket
        }

        private fun pump(socket: DatagramSocket, forward: (DatagramPacket) -> Unit) {
            thread(isDaemon = true) {
                val buffer = ByteArray(MAX_PACKET)
                try {
                    while (true) {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)
                        forward(packet)
                    }
                } catch (_: IOException) {
                    // Closed: the relay is switched off.
                }
            }
        }

        override fun close() {
            front.close()
            backs.values.forEach { it.close() }
        }
    }

    private fun startRelay() {
        relay = Relay(InetSocketAddress(env.pbxHost, SIP_PORT), RELAY_PORT)
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

    private companion object {
        const val HOST = "127.0.0.1"
        const val SIP_PORT = 5060
        const val RELAY_PORT = 5099

        /** A port of the PBX host on which nothing answers. */
        const val DEAD_PORT = 5399
        const val MAX_PACKET = 65_535

        /** The first server is asked about once a minute. */
        const val RETURN_TIMEOUT_MS = 150_000L
    }
}
