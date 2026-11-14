package com.sky.siplink

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.PowerManager
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.sky.siplink.core.model.AudioRoute
import com.sky.siplink.core.model.CallInfo
import com.sky.siplink.core.model.CallPhase
import com.sky.siplink.core.model.ThemeMode
import com.sky.siplink.ui.call.CallScreen
import com.sky.siplink.ui.theme.SipLinkTheme
import kotlinx.coroutines.launch

/** Full-screen call UI, also shown over the lock screen for incoming calls. */
class CallActivity : ComponentActivity() {
    private var proximityLock: PowerManager.WakeLock? = null

    // Answer whatever the user decides: without the microphone the call is simply one-way.
    private val microphonePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { engine.calls.answer() }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        handleIntent(intent)
        setContent {
            SipLinkTheme(themeMode = ThemeMode.DARK, dynamicColor = false) {
                CallScreen(onAnswer = ::answer, onFinished = ::finishAndRemoveTask)
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                engine.calls.call.collect(::updateProximityLock)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        engine.calls.onCallUiVisible()
    }

    override fun onStop() {
        releaseProximityLock()
        super.onStop()
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == ACTION_ANSWER) {
            intent.action = null
            answer()
        }
    }

    private fun answer() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) engine.calls.answer() else microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    /** Turns the screen off against the ear while the earpiece is in use. */
    private fun updateProximityLock(info: CallInfo?) {
        val wanted = info != null && info.route == AudioRoute.EARPIECE &&
            (info.phase == CallPhase.OUTGOING || info.phase == CallPhase.CONNECTED || info.phase == CallPhase.HELD)
        if (!wanted) {
            releaseProximityLock()
            return
        }
        if (proximityLock?.isHeld == true) return
        val powerManager = getSystemService(PowerManager::class.java)
        if (!powerManager.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) return
        proximityLock = powerManager
            .newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, PROXIMITY_TAG)
            .also { it.acquire(MAX_PROXIMITY_HOLD_MS) }
    }

    private fun releaseProximityLock() {
        proximityLock?.takeIf { it.isHeld }?.release()
        proximityLock = null
    }

    companion object {
        const val ACTION_ANSWER = "com.sky.siplink.action.ANSWER"
        private const val PROXIMITY_TAG = "siplink:proximity"
        private const val MAX_PROXIMITY_HOLD_MS = 4 * 60 * 60 * 1000L

        fun intent(context: Context): Intent = Intent(context, CallActivity::class.java).asNewTask()
    }
}
