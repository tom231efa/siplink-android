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
import com.sky.siplink.core.logic.FeatureDial
import com.sky.siplink.core.model.FeatureCodes
import com.sky.siplink.core.model.SupervisionMode
import com.sky.siplink.engine
import com.sky.siplink.openCallScreen

/** Places outgoing calls, asking for the microphone permission first when needed. */
class CallLauncher(
    private val launch: (CallRequest) -> Unit,
    private val notify: (String) -> Unit,
    private val codes: () -> FeatureCodes,
) {
    fun call(target: String, accountId: String? = null) = launch(CallRequest(target, accountId))

    fun intercom(target: String, accountId: String? = null) =
        launch(CallRequest(target, accountId, intercom = true))

    /** Dials a PBX feature code (listen, whisper, barge, force hang up, pickup) for [target]. */
    fun feature(action: FeatureDial.Action, target: String, accountId: String? = null) {
        val number = FeatureDial.compose(action, target, codes())
        if (number == null) {
            notify("尚未配置“${action.label}”功能码，或号码为空")
        } else {
            launch(CallRequest(number, accountId, supervision = FeatureDial.supervisionOf(action)))
        }
    }

    /** Dials the group pickup code: answers a call ringing somewhere in our pickup group. */
    fun groupPickup(accountId: String? = null) = code(codes().groupPickup, "同组代接", accountId)

    /** Calls the voicemail box. */
    fun voicemail(accountId: String? = null) = code(codes().voicemail, "语音信箱", accountId)

    private fun code(code: String, label: String, accountId: String?) {
        if (code.isBlank()) notify("尚未配置“$label”功能码") else launch(CallRequest(code.trim(), accountId))
    }
}

data class CallRequest(
    val target: String,
    val accountId: String?,
    val intercom: Boolean = false,
    val supervision: SupervisionMode? = null,
)

@Composable
fun rememberCallLauncher(): CallLauncher {
    val context = LocalContext.current
    val messenger by rememberUpdatedState(LocalMessenger.current)
    var pending by remember { mutableStateOf<CallRequest?>(null) }

    fun start(request: CallRequest) {
        val error = context.engine.calls.dial(request.target, request.accountId, request.intercom, request.supervision)
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
        CallLauncher(
            launch = { request ->
                val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                if (granted) {
                    start(request)
                } else {
                    pending = request
                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
            notify = { messenger(it) },
            codes = { context.engine.settings.current.features },
        )
    }
}
