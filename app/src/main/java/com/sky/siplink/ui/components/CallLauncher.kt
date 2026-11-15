package com.sky.siplink.ui.components

import android.Manifest
import android.content.Context
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
import com.sky.siplink.core.SipEngine
import com.sky.siplink.core.logic.FeatureDial
import com.sky.siplink.core.model.DndMode
import com.sky.siplink.core.model.FeatureCodes
import com.sky.siplink.core.model.SupervisionMode
import com.sky.siplink.engine
import com.sky.siplink.openCallScreen

/** Places outgoing calls, asking for the microphone permission first when needed. */
class CallLauncher(
    private val launch: (CallRequest) -> Unit,
    private val notify: (String) -> Unit,
    private val engine: SipEngine,
) {
    private fun codes(): FeatureCodes = engine.settings.current.features

    /** @param video a video call; the camera permission is asked for first, refusing it leaves the video incoming only */
    fun call(target: String, accountId: String? = null, video: Boolean = false) = launch(CallRequest(target, accountId, video = video))

    /**
     * Switches do not disturb on or off. In [DndMode.SOFTWARE] that is a switch of this app; in
     * [DndMode.SERVER] the PBX is told by a call to its feature code, on [accountId].
     */
    fun doNotDisturb(on: Boolean, accountId: String? = null) {
        val settings = engine.settings.current
        if (settings.dndMode == DndMode.SOFTWARE) {
            engine.updateSettings { it.copy(doNotDisturb = on) }
            return
        }
        val code = (if (on) settings.features.dndOn else settings.features.dndOff).trim()
        if (code.isEmpty()) {
            notify("未配置“${if (on) "开启" else "关闭"}免打扰”功能码")
        } else {
            // The switch follows once the call to the code is on its way.
            launch(CallRequest(code, accountId, onStarted = { engine.updateSettings { it.copy(doNotDisturb = on) } }))
        }
    }

    /** Dials a PBX feature code (intercom, listen, whisper, barge, force hang up, pickup) for [target]. */
    fun feature(action: FeatureDial.Action, target: String, accountId: String? = null) {
        val number = FeatureDial.compose(action, target, codes())
        if (number == null) {
            notify("未配置“${action.label}”功能码")
        } else {
            launch(CallRequest(number, accountId, supervision = FeatureDial.supervisionOf(action)))
        }
    }

    /** Dials the group pickup code: answers a call ringing somewhere in our pickup group. */
    fun groupPickup(accountId: String? = null) = code(codes().groupPickup, "同组代接", accountId)

    /** Calls the voicemail box. */
    fun voicemail(accountId: String? = null) = code(codes().voicemail, "语音信箱", accountId)

    private fun code(code: String, label: String, accountId: String?) {
        if (code.isBlank()) notify("未配置“$label”功能码") else launch(CallRequest(code.trim(), accountId))
    }
}

/** @param onStarted called once the call is on its way */
class CallRequest(
    val target: String,
    val accountId: String?,
    val supervision: SupervisionMode? = null,
    val onStarted: (() -> Unit)? = null,
    val video: Boolean = false,
)

@Composable
fun rememberCallLauncher(): CallLauncher {
    val context = LocalContext.current
    val messenger by rememberUpdatedState(LocalMessenger.current)
    var pending by remember { mutableStateOf<CallRequest?>(null) }

    fun start(request: CallRequest) {
        val error = context.engine.calls.dial(request.target, request.accountId, request.supervision, request.video)
        if (error != null) {
            messenger(error)
        } else {
            request.onStarted?.invoke()
            context.openCallScreen()
        }
    }

    // The camera is optional: a video call goes ahead without it, as long as the microphone is there.
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val request = pending
        pending = null
        val microphone = result[Manifest.permission.RECORD_AUDIO] ?: granted(context, Manifest.permission.RECORD_AUDIO)
        when {
            request == null -> Unit
            microphone -> start(request)
            else -> messenger("需要麦克风权限")
        }
    }

    return remember {
        CallLauncher(
            launch = { request ->
                val missing = buildList {
                    add(Manifest.permission.RECORD_AUDIO)
                    if (request.video) add(Manifest.permission.CAMERA)
                }.filterNot { granted(context, it) }
                if (missing.isEmpty()) {
                    start(request)
                } else {
                    pending = request
                    permissionLauncher.launch(missing.toTypedArray())
                }
            },
            notify = { messenger(it) },
            engine = context.engine,
        )
    }
}

private fun granted(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
