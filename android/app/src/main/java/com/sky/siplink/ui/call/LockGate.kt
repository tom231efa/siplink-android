package com.sky.siplink.ui.call

import android.app.Activity
import android.app.KeyguardManager
import androidx.activity.compose.LocalActivity
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

/**
 * The call screen shows over the lock screen ([com.sky.siplink.CallActivity]). What reaches beyond
 * the call that is up (the call history, the directories and the speed dial keys of its sheets,
 * dialling any number) waits until the phone is unlocked: the system asks the user to unlock it,
 * and the sheet opens once that is done.
 */
internal object LockGate {
    /** What the lock screen is asked. */
    interface Keyguard {
        fun isLocked(activity: Activity): Boolean

        /** Asks the system to let the user unlock the phone; [onUnlocked] runs once that is done. */
        fun requestUnlock(activity: Activity, onUnlocked: () -> Unit)
    }

    /** The phone's own; tests put one of theirs in its place (the test phone has no lock screen). */
    @VisibleForTesting
    var keyguard: Keyguard = SystemKeyguard

    private object SystemKeyguard : Keyguard {
        override fun isLocked(activity: Activity): Boolean = activity.getSystemService(KeyguardManager::class.java).isKeyguardLocked

        override fun requestUnlock(activity: Activity, onUnlocked: () -> Unit) {
            activity.getSystemService(KeyguardManager::class.java).requestDismissKeyguard(
                activity,
                object : KeyguardManager.KeyguardDismissCallback() {
                    override fun onDismissSucceeded() = onUnlocked()
                },
            )
        }
    }
}

/**
 * What runs the action it is given at once while the phone is unlocked, and only once the user
 * has unlocked it while it is locked ([LockGate]). [onLocked] runs when the screen comes back
 * with the phone locked (it was switched off, say): the sheets that were open close then.
 */
@Composable
internal fun rememberUnlocked(onLocked: () -> Unit): (action: () -> Unit) -> Unit {
    val activity = LocalActivity.current
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        if (activity != null && LockGate.keyguard.isLocked(activity)) onLocked()
    }
    return remember(activity) {
        { action ->
            if (activity == null || !LockGate.keyguard.isLocked(activity)) action() else LockGate.keyguard.requestUnlock(activity, action)
        }
    }
}
