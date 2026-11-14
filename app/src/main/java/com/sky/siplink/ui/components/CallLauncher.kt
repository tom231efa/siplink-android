package com.sky.siplink.ui.components

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.sky.siplink.engine
import com.sky.siplink.openCallScreen

/** Places outgoing calls, asking for the microphone permission first when needed. */
class CallLauncher(private val launch: (CallRequest) -> Unit) {
    fun call(target: String, accountId: String? = null) = launch(CallRequest(target, accountId, false))
    fun intercom(target: String, accountId: String? = null) = launch(CallRequest(target, accountId, true))
}

data class CallRequest(val target: String, val accountId: String?, val intercom: Boolean)

@Composable
fun rememberCallLauncher(): CallLauncher {
    val context = LocalContext.current
    val messenger by rememberUpdatedState(LocalMessenger.current)
    var pending by remember { mutableStateOf<CallRequest?>(null) }

    fun start(request: CallRequest) {
        val error = context.engine.calls.dial(request.target, request.accountId, request.intercom)
        if (error != null) messenger(error) else context.openCallScreen()
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val request = pending
        pending = null
        when {
            request == null -> Unit
            granted -> start(request)
            else -> messenger("需要麦克风权限才能通话")
        }
    }

    return remember {
        CallLauncher { request ->
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            if (granted) {
                start(request)
            } else {
                pending = request
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }
}
