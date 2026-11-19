package com.sky.siplink.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.sky.siplink.SipLinkApp

/** Handles the Decline / Hang up buttons of the call notifications. */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val calls = (context.applicationContext as SipLinkApp).engine.calls
        when (intent.action) {
            CallNotifier.ACTION_DECLINE -> calls.decline()
            CallNotifier.ACTION_HANG_UP -> calls.hangUp()
        }
    }
}
