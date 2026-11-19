package com.sky.siplink

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.sky.siplink.core.logic.VideoRules
import com.sky.siplink.core.model.CallInfo
import com.sky.siplink.core.model.ThemeMode
import com.sky.siplink.ui.call.CallScreen
import com.sky.siplink.ui.theme.SipLinkTheme
import java.util.function.Consumer
import kotlinx.coroutines.launch

/**
 * Full-screen call UI, also shown over the lock screen for incoming calls. Its window is see
 * through: where the system blurs what is behind it, a ringing call lets what was on screen
 * before show through, like frosted glass.
 */
class CallActivity : ComponentActivity() {
    private var proximityLock: PowerManager.WakeLock? = null

    /**
     * Whether the system blurs what is behind this window right now. It cannot on Android 11 and
     * older, and stops when the user saves battery; the screen then covers what is behind whole.
     */
    private var blurred by mutableStateOf(false)

    /** The hang up key of a room's notification was pressed: the call screen asks before the room ends. */
    private var endRoomAsked by mutableStateOf(false)
    private val onBlurChanged = Consumer<Boolean> { blurred = it }

    /** Answering with video waits for the permissions; [answerWithVideo] remembers which way it was. */
    private var answerWithVideo = false

    // Answer whatever the user decides: without the microphone the call is simply one-way, and
    // without the camera the video only comes in.
    private val answerPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            engine.calls.answer(answerWithVideo)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        blurBehind()
        // The screen fades in and out by itself (see CallScreen): no animation of the system on top of that.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        }

        handleIntent(intent)
        setContent {
            SipLinkTheme(themeMode = ThemeMode.DARK, dynamicColor = false) {
                CallScreen(
                    onAnswer = ::answer,
                    onFinished = ::finishAndRemoveTask,
                    glass = blurred,
                    endRoomAsked = endRoomAsked,
                    onEndRoomAsked = { endRoomAsked = false },
                )
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

    override fun onStart() {
        super.onStart()
        engine.calls.onCallScreenShown(true)
    }

    override fun onResume() {
        super.onResume()
        engine.calls.onCallUiVisible()
    }

    override fun onStop() {
        releaseProximityLock()
        engine.calls.onCallScreenShown(false)
        super.onStop()
    }

    private fun blurBehind() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        window.attributes = window.attributes.also {
            it.blurBehindRadius = (BLUR_RADIUS_DP * resources.displayMetrics.density).toInt()
        }
        // The listener may only be registered while the window is attached; it reports the current state at once.
        window.decorView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = windowManager.addCrossWindowBlurEnabledListener(onBlurChanged)

            override fun onViewDetachedFromWindow(view: View) = windowManager.removeCrossWindowBlurEnabledListener(onBlurChanged)
        })
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            ACTION_ANSWER -> {
                intent.action = null
                // From the notification: audio-only, the camera is only started from the call screen.
                answer(video = false)
            }
            // The hang up key of the notification of a room: the call screen asks first.
            ACTION_END_ROOM -> {
                intent.action = null
                endRoomAsked = true
            }
        }
    }

    /** @param video answer with the video the caller offers, the own camera included */
    private fun answer(video: Boolean) {
        val missing = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (video) add(Manifest.permission.CAMERA)
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) {
            engine.calls.answer(video)
        } else {
            answerWithVideo = video
            answerPermissions.launch(missing.toTypedArray())
        }
    }

    /** Turns the screen off against the ear while the earpiece is in use, and never over video. */
    private fun updateProximityLock(info: CallInfo?) {
        val wanted = info != null && VideoRules.proximityWanted(info.phase, info.route, info.video != null)
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
        const val ACTION_END_ROOM = "com.sky.siplink.action.END_ROOM"
        private const val PROXIMITY_TAG = "siplink:proximity"
        private const val MAX_PROXIMITY_HOLD_MS = 4 * 60 * 60 * 1000L
        private const val BLUR_RADIUS_DP = 28

        fun intent(context: Context): Intent = Intent(context, CallActivity::class.java).asNewTask()
    }
}
