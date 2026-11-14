package com.sky.siplink.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Brings the SIP registrations back after a reboot or an app update. Creating the process
 * already starts the engine (see SipLinkApp); this only makes sure the service is running.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> SipService.sync(context)
        }
    }
}
