package com.sky.siplink.ui.components

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

/**
 * Whether an incoming call may open its screen over the lock screen. From Android 14 on that is
 * a special app access ("full screen notifications") which the user or the installing store can
 * switch off; without it a call that comes in while the phone is locked is only a notification.
 */
fun Context.canShowCallsOnLockScreen(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
        getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

/** Opens the system page where that access is granted; false if the system has no such page. */
fun Context.openLockScreenCallSettings(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
    val intent = Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.fromParts("package", packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return runCatching { startActivity(intent) }.isSuccess
}

/** The access as state, read again whenever the app comes back from the system settings. */
@Composable
fun rememberLockScreenCallAccess(): State<Boolean> {
    val context = LocalContext.current
    val allowed = remember { mutableStateOf(context.canShowCallsOnLockScreen()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { allowed.value = context.canShowCallsOnLockScreen() }
    return allowed
}
