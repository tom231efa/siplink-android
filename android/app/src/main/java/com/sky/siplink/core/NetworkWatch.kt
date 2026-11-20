package com.sky.siplink.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import com.sky.siplink.core.logic.NetAddress
import com.sky.siplink.core.model.NetType
import com.sky.siplink.core.model.NetworkFacts
import org.linphone.core.tools.AndroidPlatformHelper

/**
 * Watches the network the phone is on.
 *
 * It has the SIP stack look at the network again whenever Android lets the app back onto it.
 * Android keeps an app that was started in the background off the network until it counts as
 * running in the foreground (its service is up). The SDK looks at the network when it starts
 * and whenever the network itself changes, but not when that block is lifted: an app started
 * in the background, at boot or after an update, could find "no network" at its start and stay
 * unregistered although the network was fine all along.
 *
 * It also tells what it knows of the network ([facts]) to whoever listens ([listen]): the line
 * checks show it, and the adaptation of the lines keys what it learns by it.
 */
class NetworkWatch(context: Context) {
    private val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /** The network the facts are of: the default one, as the callbacks last named it. */
    private var current: Network? = null

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            lookAgain()
            update(network, null, null)
        }

        override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
            if (!blocked) lookAgain()
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = update(network, capabilities, null)

        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) = update(network, null, properties)

        override fun onLost(network: Network) {
            // A network that was the default one before may be lost after the new one came.
            if (network != current) return
            current = null
            publish(null)
        }
    }

    init {
        // On the main thread, which is the thread of the SIP core.
        connectivity.registerDefaultNetworkCallback(callback, Handler(Looper.getMainLooper()))
    }

    private fun lookAgain() {
        if (AndroidPlatformHelper.isReady()) AndroidPlatformHelper.instance().updateNetworkReachability()
    }

    /** A callback brings only what changed: the rest is asked for. */
    private fun update(network: Network, capabilities: NetworkCapabilities?, properties: LinkProperties?) {
        val caps = capabilities ?: connectivity.getNetworkCapabilities(network) ?: return
        val link = properties ?: connectivity.getLinkProperties(network) ?: return
        current = network
        publish(factsOf(caps, link))
    }

    companion object {
        private val listeners = ArrayList<(NetworkFacts?) -> Unit>()

        /** What is known of the network the phone is on now; null while it has none. Main thread only. */
        var facts: NetworkFacts? = null
            private set

        /** Has [listener] told of every change of [facts] from now on, on the main thread. */
        fun listen(listener: (NetworkFacts?) -> Unit) {
            listeners += listener
        }

        fun unlisten(listener: (NetworkFacts?) -> Unit) {
            listeners -= listener
        }

        /**
         * Tells every listener of [next] when it differs from [facts]. The integration tests feed
         * a change of network here, as Android's callbacks do; the phone's network stays as it is.
         */
        internal fun publish(next: NetworkFacts?) {
            if (next == facts) return
            facts = next
            listeners.toList().forEach { it(next) }
        }

        private fun ipv4First(address: String): Int = if (NetAddress.parse(address)?.size == 4) 0 else 1

        private fun factsOf(capabilities: NetworkCapabilities, link: LinkProperties): NetworkFacts {
            val type = when {
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetType.WIFI
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetType.CELLULAR
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetType.ETHERNET
                else -> NetType.OTHER
            }
            val linked = link.linkAddresses.mapNotNull { linkAddress ->
                linkAddress.address.hostAddress?.substringBefore('%')?.let { it to linkAddress.prefixLength }
            }.filter { (address, _) -> NetAddress.kind(address) != NetAddress.Kind.LOOPBACK }
            val addresses = linked.map { it.first }.filter { NetAddress.kind(it) != NetAddress.Kind.LINK_LOCAL }
            val gateways = link.routes.filter { it.isDefaultRoute }
                .mapNotNull { route -> route.gateway?.takeUnless { it.isAnyLocalAddress }?.hostAddress?.substringBefore('%') }
            return NetworkFacts(
                type = type,
                vpn = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
                // IPv4 first: it is the address shown.
                addresses = addresses.sortedBy(::ipv4First),
                subnets = linked.mapNotNull { (address, prefix) -> NetAddress.subnet(address, prefix, prefix) }.distinct().sortedBy(::ipv4First),
                gateways = gateways.distinct().sortedBy(::ipv4First),
                dns = link.dnsServers.mapNotNull { it.hostAddress?.substringBefore('%') }.sortedBy(::ipv4First),
                validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                captive = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL),
            )
        }
    }
}
