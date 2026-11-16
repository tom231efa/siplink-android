package com.sky.siplink

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.core.content.ContextCompat
import com.sky.siplink.core.CallManager
import com.sky.siplink.core.SipEngine
import com.sky.siplink.core.model.CallInfo
import com.sky.siplink.service.CallNotifier
import com.sky.siplink.service.SipService

class SipLinkApp : Application(), CallManager.Host {
    lateinit var engine: SipEngine
        private set
    lateinit var notifier: CallNotifier
        private set

    private var startedActivities = 0

    override val isAppVisible: Boolean get() = startedActivities > 0

    override val canUseCamera: Boolean
        get() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    override fun onCreate() {
        super.onCreate()
        notifier = CallNotifier(this)
        engine = SipEngine(this)
        engine.calls.host = this
        engine.onMissedCall = notifier::showMissedCall
        engine.onKeepAliveChanged = { SipService.sync(this) }

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivities++
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivities = (startedActivities - 1).coerceAtLeast(0)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })

        SipService.sync(this)
        engine.remote.start()
    }

    override fun showIncomingCall(info: CallInfo) {
        if (isAppVisible) {
            startActivity(CallActivity.intent(this))
        } else {
            // Background: only a full-screen-intent notification may bring up the call screen.
            notifier.showIncomingCall(info)
        }
    }

    override fun updateIncomingCall(info: CallInfo) = notifier.updateIncomingCall(info)

    override fun dismissIncomingCall() = notifier.cancelIncomingCall()

    override fun onCallActivityChanged() = SipService.sync(this)
}

val Context.engine: SipEngine get() = (applicationContext as SipLinkApp).engine

fun Context.openCallScreen() = startActivity(CallActivity.intent(this))

fun Intent.asNewTask(): Intent = addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

/**
 * Restarts the app process. Needed for the few settings a native library only reads once per
 * process (the CA certificates of the LDAP library).
 */
fun Context.restartApp() {
    val launch = packageManager.getLaunchIntentForPackage(packageName)?.component ?: return
    engine.core.config.sync()
    startActivity(Intent.makeRestartActivityTask(launch))
    Runtime.getRuntime().exit(0)
}
