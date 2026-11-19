package com.sky.siplink.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import org.linphone.core.tools.AndroidPlatformHelper

/**
 * Has the SIP stack look at the network again whenever Android lets the app back onto it.
 *
 * Android keeps an app that was started in the background off the network until it counts as
 * running in the foreground (its service is up). The SDK looks at the network when it starts
 * and whenever the network itself changes, but not when that block is lifted: an app started
 * in the background, at boot or after an update, could find "no network" at its start and stay
 * unregistered although the network was fine all along.
 */
class NetworkWatch(context: Context) {
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = lookAgain()

        override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
            if (!blocked) lookAgain()
        }
    }

    init {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        // On the main thread, which is the thread of the SIP core.
        connectivity.registerDefaultNetworkCallback(callback, Handler(Looper.getMainLooper()))
    }

    private fun lookAgain() {
        if (AndroidPlatformHelper.isReady()) AndroidPlatformHelper.instance().updateNetworkReachability()
    }
}
