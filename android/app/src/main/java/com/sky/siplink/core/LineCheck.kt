package com.sky.siplink.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import com.sky.siplink.core.logic.AccountRoute
import com.sky.siplink.core.logic.LineAdapt
import com.sky.siplink.core.logic.LineVerdict
import com.sky.siplink.core.logic.MediaAdapt
import com.sky.siplink.core.logic.NetworkKey
import com.sky.siplink.core.model.LineReport
import com.sky.siplink.core.model.NatKind
import com.sky.siplink.core.model.SipTransport
import org.linphone.core.Core
import java.util.concurrent.Executors

/**
 * The line checks of one account: what the network, the way to the server and the
 * registration look like from here. Only asks, changes nothing. Main thread only; the probes
 * run on a thread of their own.
 */
class LineCheck(private val context: Context, private val core: Core, private val accounts: AccountManager) {
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    /**
     * Checks the line of account [id] and hands the report to [onDone] on the main thread.
     *
     * @return false when there is no such account
     */
    fun run(id: String, onDone: (LineReport) -> Unit): Boolean {
        val account = accounts.find(id) ?: return false
        val shown = accounts.accounts.value.firstOrNull { it.id == id } ?: return false
        val params = account.params
        val server = params.serverAddress ?: return false
        val transport = transportOf(server)
        val host = connectHostOf(server)
        val port = server.port.takeIf { it > 0 }
        val target = hostPortOf(server)
        val from = fromOf(params.identityAddress)
        val userAgent = core.userAgent
        val stun = accounts.stunServer
        val facts = NetworkWatch.facts
        val adaptation = accounts.adaptation(id)
        val pbxUnreachable = accounts.pbxUnreachable(id)
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val battery = power?.isIgnoringBatteryOptimizations(context.packageName)
        val contact = account.contactAddress?.asStringUriOnly()
        val expires = params.expires
        val lastCall = accounts.lastCall(id)
        worker.execute {
            val addresses = LineNetwork.lookup(host)
            val address = addresses.firstOrNull()
            // The port of the account counts for the transport it names; the others are tried on theirs.
            val sipPort = port?.takeIf { transport != SipTransport.TLS } ?: AccountRoute.defaultPort(SipTransport.UDP)
            val tlsPort = port?.takeIf { transport == SipTransport.TLS } ?: AccountRoute.defaultPort(SipTransport.TLS)
            val stunAddress = MediaAdapt.stunServer(stun)?.let { (stunHost, stunPort) -> LineNetwork.socketAddress(stunHost, stunPort) }
            val udp = address?.let { LineNetwork.probeUdp(it, sipPort, target, from, userAgent, stun = stunAddress) }
            val answer = udp?.answer
            val large = if (address != null && answer != null) {
                LineNetwork.probeUdp(address, sipPort, target, from, userAgent, size = LineAdapt.LARGE_PACKET).answer != null
            } else {
                null
            }
            val tcpMs = address?.let { LineNetwork.connectMs(it, sipPort) }
            val tlsMs = address?.let { LineNetwork.connectMs(it, tlsPort) }
            val seen = udp?.from?.let { LineVerdict.seen(answer, it) }
            val nat = if (facts == null) NatKind.UNKNOWN else LineVerdict.nat(facts.addresses, seen, udp?.stun)
            val report = LineReport(
                network = facts,
                localAddress = facts?.let(NetworkKey::localAddress),
                publicAddress = udp?.stun ?: seen,
                nat = nat,
                batteryUnrestricted = battery,
                server = target,
                serverAddresses = addresses,
                udp = answer,
                udpLarge = large,
                tcpMs = tcpMs,
                tlsMs = tlsMs,
                algSuspected = LineVerdict.algSuspected(answer),
                registration = shown.state,
                error = shown.error,
                transport = transport,
                contact = contact,
                expires = expires,
                adaptedTransport = adaptation?.transport,
                adaptedExpires = adaptation?.expires,
                adaptedIce = adaptation?.ice == true,
                pbxUnreachable = pbxUnreachable,
                lastCall = lastCall,
            )
            handler.post { onDone(report) }
        }
        return true
    }

    /** Stops the probe thread once nothing more is to be checked. */
    fun close() = worker.shutdown()
}
