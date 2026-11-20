package com.sky.siplink.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sky.siplink.R
import com.sky.siplink.engine
import com.sky.siplink.ui.components.LocalMessenger
import com.sky.siplink.ui.components.openLockScreenCallSettings
import com.sky.siplink.ui.components.rememberLockScreenCallAccess
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Asks once per launch for the runtime permissions a softphone needs and, once those dialogs are
 * out of the way, for the access that lets a call show on the lock screen.
 */
@Composable
internal fun StartupPermissions() {
    val context = LocalContext.current
    var runtimeAsked by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        runtimeAsked = true
    }
    LaunchedEffect(Unit) {
        val wanted = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        val missing = wanted.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) runtimeAsked = true else launcher.launch(missing.toTypedArray())
    }
    if (runtimeAsked) LockScreenCallPrompt()
}

/**
 * Shown when the app starts while the system keeps calls off the lock screen; switching the
 * access off later, from the settings screen, does not bring it up. Leaving the dialog hides it
 * until the next launch; "不再提醒" hides it for good, and the settings screen keeps the way in.
 */
@Composable
private fun LockScreenCallPrompt() {
    val context = LocalContext.current
    val engine = context.engine
    val messenger = LocalMessenger.current
    val allowed by rememberLockScreenCallAccess()
    val declined by remember(engine) {
        engine.settings.settings.map { it.lockScreenPromptDeclined }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(engine.settings.current.lockScreenPromptDeclined)
    var open by remember { mutableStateOf(!allowed) }
    // Once granted the question is settled for this launch, whatever happens to the access later.
    LaunchedEffect(allowed) { if (allowed) open = false }
    if (!open || allowed || declined) return
    val openFailed = stringResource(R.string.main_lock_screen_settings_failed)

    AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(stringResource(R.string.main_lock_screen_title)) },
        text = { Text(stringResource(R.string.main_lock_screen_text)) },
        confirmButton = {
            TextButton(onClick = {
                if (!context.openLockScreenCallSettings()) {
                    open = false
                    messenger(openFailed)
                }
            }) { Text(stringResource(R.string.main_lock_screen_open_settings)) }
        },
        dismissButton = {
            TextButton(onClick = { engine.updateSettings { it.copy(lockScreenPromptDeclined = true) } }) { Text(stringResource(R.string.main_lock_screen_never)) }
        },
    )
}
