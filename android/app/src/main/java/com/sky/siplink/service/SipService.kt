package com.sky.siplink.service

import android.Manifest
import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.sky.siplink.SipLinkApp
import com.sky.siplink.core.model.RegState
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Foreground service with two jobs:
 *  - keep the process (and so the SIP registrations) alive in the background, and
 *  - hold the phone call / microphone foreground types while a call is running.
 */
class SipService : Service() {
    private val scope = MainScope()
    private val app: SipLinkApp get() = application as SipLinkApp

    override fun onCreate() {
        super.onCreate()
        // Keep the "n / m accounts registered" text fresh without flooding the notification manager.
        scope.launch {
            app.engine.accounts.accounts
                .map { accounts -> accounts.count { it.state == RegState.OK } to accounts.count { it.enabled } }
                .distinctUntilChanged()
                .conflate()
                .collect {
                    refresh()
                    delay(NOTIFICATION_UPDATE_INTERVAL_MS)
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        refresh()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun refresh() {
        val engine = app.engine
        val call = engine.calls.ongoingCall
        if (call != null) {
            enterForeground(app.notifier.ongoingCall(call), inCall = true)
            return
        }
        val accounts = engine.accounts.accounts.value
        val notification = app.notifier.keepAlive(
            registered = accounts.count { it.state == RegState.OK },
            enabled = accounts.count { it.enabled },
        )
        enterForeground(notification, inCall = false)
        if (!engine.settings.current.keepAlive) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun enterForeground(notification: Notification, inCall: Boolean) {
        val candidates = when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                listOf(ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST)
            inCall -> buildList {
                // The microphone type is refused when the app is not visible; fall back gracefully.
                if (hasMicrophonePermission()) {
                    add(ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                }
                add(ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
                add(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            }
            else -> listOf(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        }
        for (type in candidates) {
            try {
                ServiceCompat.startForeground(this, CallNotifier.ID_SERVICE, notification, type)
                return
            } catch (e: Exception) {
                Log.w(TAG, "startForeground refused for type $type: ${e.message}")
            }
        }
    }

    private fun hasMicrophonePermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "SipLink.Service"
        private const val NOTIFICATION_UPDATE_INTERVAL_MS = 1500L

        /** Starts, updates or stops the service so it matches the current engine state. */
        fun sync(context: Context) {
            val app = context.applicationContext as SipLinkApp
            val engine = app.engine
            val intent = Intent(app, SipService::class.java)
            if (engine.calls.hasOngoingCall || engine.settings.current.keepAlive) {
                try {
                    ContextCompat.startForegroundService(app, intent)
                } catch (e: Exception) {
                    // Android refuses background starts in some situations; retried on next sync.
                    Log.w(TAG, "Unable to start foreground service: ${e.message}")
                }
            } else {
                app.stopService(intent)
            }
        }
    }
}
