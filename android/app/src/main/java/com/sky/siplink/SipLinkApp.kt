package com.sky.siplink

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import androidx.core.content.ContextCompat
import com.sky.siplink.core.CallManager
import com.sky.siplink.core.CoreThread
import com.sky.siplink.core.SipEngine
import com.sky.siplink.core.model.CallInfo
import com.sky.siplink.service.CallNotifier
import com.sky.siplink.service.SipService

class SipLinkApp : Application(), CallManager.Host {
    lateinit var engine: SipEngine
        private set
    lateinit var notifier: CallNotifier
        private set

    /** Counted on the main thread; read by the core from its own ([isAppVisible]). */
    @Volatile
    private var startedActivities = 0

    /** The activities that exist, to be made anew in another language before Android 13 ([onLanguageChanged]). */
    private val activities = LinkedHashSet<Activity>()

    override val isAppVisible: Boolean get() = startedActivities > 0

    override val canUseCamera: Boolean
        get() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    override fun onCreate() {
        super.onCreate()
        // Made on its thread, and with its hooks in place before the core gets to run: a call
        // that comes in at once finds them. They are called on this thread, after onCreate.
        engine = CoreThread.wait("SipEngine") {
            SipEngine(this).also {
                it.calls.host = this
                it.onMissedCall = { name, number -> notifier.showMissedCall(name, number) }
                it.onKeepAliveChanged = { SipService.sync(this) }
            }
        }
        notifier = CallNotifier(this, engine.language)
        engine.language.onChanged = ::onLanguageChanged

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivities++
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivities = (startedActivities - 1).coerceAtLeast(0)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                activities += activity
            }

            override fun onActivityDestroyed(activity: Activity) {
                activities -= activity
            }

            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        })

        SipService.sync(this)
        engine.remote.start()
    }

    /**
     * The system changed the configuration: a change of the system's languages comes this way,
     * and from Android 13 on one of the app's language made on the system's page. The system
     * makes the activities anew, or hands the call screen the change (it keeps its views, see the
     * manifest); the texts rendered outside them follow (AppLanguage.refresh).
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        engine.language.refresh()
    }

    /**
     * Everything rendered outside the activities is rendered again in the language of the moment:
     * the snapshots of the core, the notification channels and the notification of the service.
     * Before Android 13 the app chose the language itself ([recreate]): its activities are made
     * anew in it, the call screen too (the call goes on; the screen shows it again).
     */
    private fun onLanguageChanged(recreate: Boolean) {
        engine.onLanguageChanged()
        notifier.onLanguageChanged()
        SipService.sync(this)
        if (recreate) activities.toList().forEach(Activity::recreate)
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
