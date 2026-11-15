package com.sky.siplink.ui.call

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.SwapCalls
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sky.siplink.core.CallManager
import com.sky.siplink.core.logic.Formatters
import com.sky.siplink.core.model.CallInfo
import com.sky.siplink.core.model.CallPhase
import com.sky.siplink.core.model.SideCall
import com.sky.siplink.core.model.TransferStatus
import com.sky.siplink.engine
import com.sky.siplink.ui.theme.Brand
import kotlinx.coroutines.delay

internal enum class Controls { INCOMING, ACTIVE, ENDED }

/**
 * Full-screen call UI: incoming, outgoing, established and ended states of the current call.
 *
 * @param glass the window is see through and what is behind it is blurred, so the background
 *   may let it shine through; without the blur it has to cover more to keep the text readable
 */
@Composable
fun CallScreen(onAnswer: (video: Boolean) -> Unit, onFinished: () -> Unit, glass: Boolean = false) {
    val context = LocalContext.current
    val calls = context.engine.calls
    val live by calls.call.collectAsStateWithLifecycle()
    // Keep showing the last known call while the screen animates away.
    var shown by remember { mutableStateOf(live) }
    var keypadOpen by remember { mutableStateOf(false) }
    var transferOpen by remember { mutableStateOf(false) }
    var shortcutsOpen by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    // Hidden by tapping the video, so that the picture is seen whole.
    var chromeHidden by remember { mutableStateOf(false) }

    // The camera permission is asked for where video is first wanted. Whatever the answer, the
    // action goes on: without the camera the video is only received (the call manager decides).
    var afterCameraPermission by remember { mutableStateOf<(() -> Unit)?>(null) }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        afterCameraPermission?.invoke()
        afterCameraPermission = null
    }
    fun withCamera(action: () -> Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            action()
        } else {
            afterCameraPermission = action
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    // The screen fades in over what was there before, and out again once the call is gone.
    val presence = remember { Animatable(0f) }
    LaunchedEffect(live) {
        if (live != null) {
            shown = live
            presence.animateTo(1f, tween(APPEAR_MS))
        } else {
            presence.animateTo(0f, tween(DISAPPEAR_MS))
            onFinished()
        }
    }
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(3500)
            notice = null
        }
    }

    val info = shown
    val video = info?.video?.takeIf { info.phase == CallPhase.CONNECTED }
    LaunchedEffect(video == null) { chromeHidden = false }
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = presence.value },
    ) {
        CallBackground(ringingIn = info?.phase == CallPhase.INCOMING, glass = glass)
        if (video != null) {
            VideoStage(
                video = video,
                chrome = !chromeHidden,
                onTap = { chromeHidden = !chromeHidden },
                onSwitchCamera = calls::switchCamera,
                onToggleCamera = { withCamera { calls.toggleCamera()?.let { notice = it } } },
            )
        }
        if (info != null) {
            val controls = when (info.phase) {
                CallPhase.INCOMING -> Controls.INCOMING
                CallPhase.ENDED -> Controls.ENDED
                else -> Controls.ACTIVE
            }
            AnimatedVisibility(!chromeHidden || video == null, enter = fadeIn(tween(180)), exit = fadeOut(tween(160))) {
                CallContent(
                    info = info,
                    controls = controls,
                    calls = calls,
                    notice = notice,
                    videoMode = video != null,
                    // The call activity asks for the microphone and, to answer with video, the camera.
                    onAnswer = onAnswer,
                    onToggleVideo = {
                        val toggle = { calls.toggleVideo()?.let { notice = it } }
                        // Ending video needs no camera.
                        if (info.video != null) toggle() else withCamera { toggle() }
                    },
                    onAcceptVideo = { withCamera { calls.answerVideoRequest(true) } },
                    onOpenKeypad = { keypadOpen = true },
                    onOpenTransfer = { transferOpen = true },
                    onOpenShortcuts = { shortcutsOpen = true },
                    onNotice = { notice = it },
                )
            }
            AnimatedVisibility(
                visible = keypadOpen && controls == Controls.ACTIVE,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically(spring(dampingRatio = 0.85f, stiffness = 300f)) { it } + fadeIn(),
                exit = slideOutVertically(tween(220)) { it } + fadeOut(),
            ) {
                DtmfPanel(onDigit = calls::sendDtmf, onClose = { keypadOpen = false })
            }
            if (transferOpen && controls == Controls.ACTIVE) {
                TransferSheet(
                    canConsult = info.heldCall == null && info.waitingCall == null,
                    onDismiss = { transferOpen = false },
                    onBlind = { target ->
                        transferOpen = false
                        calls.blindTransfer(target)?.let { notice = it }
                    },
                    onConsult = { target ->
                        transferOpen = false
                        calls.startConsultation(target)?.let { notice = it }
                    },
                )
            }
            if (shortcutsOpen && controls == Controls.ACTIVE) {
                ShortcutsSheet(onDismiss = { shortcutsOpen = false }, onNotice = { notice = it })
            }
        }
    }
}

/**
 * Dark, with one faint blue highlight for depth. A call that only rings lets what is behind the
 * window shine through, like glass; once it is dialled or answered the call has the screen to
 * itself, and the glass turns solid.
 *
 * It does not move otherwise: a call can last for hours, and a background that animates would
 * redraw the whole screen all that time.
 */
@Composable
private fun CallBackground(ringingIn: Boolean, glass: Boolean) {
    val tint by animateFloatAsState(
        targetValue = when {
            !ringingIn -> 1f
            glass -> GLASS_TINT
            else -> PLAIN_TINT
        },
        animationSpec = tween(SOLIDIFY_MS, easing = FastOutSlowInEasing),
        label = "callTint",
    )
    Canvas(Modifier.fillMaxSize()) {
        drawRect(Brand.CallSurface.copy(alpha = tint))
        val radius = size.minDimension * 0.9f
        val center = Offset(size.width * 0.5f, size.height * 0.18f)
        drawCircle(
            Brush.radialGradient(listOf(Brand.Blue.copy(alpha = 0.13f), Color.Transparent), center, radius),
            radius,
            center,
        )
    }
}

/** How much of what is behind a ringing call its dark tint covers: over a blur, and over a sharp picture. */
private const val GLASS_TINT = 0.72f
private const val PLAIN_TINT = 0.95f

private const val APPEAR_MS = 280
private const val DISAPPEAR_MS = 220
private const val SOLIDIFY_MS = 450

@Composable
private fun CallContent(
    info: CallInfo,
    controls: Controls,
    calls: CallManager,
    notice: String?,
    videoMode: Boolean,
    onAnswer: (video: Boolean) -> Unit,
    onToggleVideo: () -> Unit,
    onAcceptVideo: () -> Unit,
    onOpenKeypad: () -> Unit,
    onOpenTransfer: () -> Unit,
    onOpenShortcuts: () -> Unit,
    onNotice: (String) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // The in-call controls take two rows, so the avatar shrinks on short screens.
        val roomy = maxHeight >= 760.dp && controls != Controls.ACTIVE || maxHeight >= 860.dp
        val ringSize = if (roomy) 224.dp else 148.dp
        val avatarSize = if (roomy) 120.dp else 84.dp

        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                // Over video the corner belongs to the own picture: the line is left out there.
                if (info.accountLabel.isNotEmpty() && !videoMode) Chip(text = info.accountLabel)
                if (info.intercom) Chip(text = "对讲", icon = Icons.Rounded.Campaign, highlighted = true)
                if (info.conference) Chip(text = "三方通话", icon = Icons.Rounded.Groups, highlighted = true)
            }

            AnimatedVisibility(
                visible = info.waitingCall != null,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                WaitingCallBanner(info.waitingCall, onAnswer = calls::answer, onDecline = calls::decline)
            }
            AnimatedVisibility(
                visible = info.heldCall != null,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                HeldCallBanner(info, onSwap = calls::swap)
            }
            AnimatedVisibility(
                visible = info.videoOffer && info.phase != CallPhase.INCOMING,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                VideoRequestBanner(onAccept = onAcceptVideo, onDecline = { calls.answerVideoRequest(false) })
            }

            // Over video the picture is what matters: the name and the time go to the top.
            if (videoMode) {
                Spacer(Modifier.height(12.dp))
            } else {
                Spacer(Modifier.weight(0.4f))
                CallAvatar(
                    name = info.remoteName,
                    beating = info.phase == CallPhase.INCOMING || info.phase == CallPhase.OUTGOING,
                    size = avatarSize,
                    haloSize = ringSize,
                )
                Spacer(Modifier.height(4.dp))
            }
            Text(
                info.remoteName,
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (info.remoteAddress != info.remoteName && !videoMode) {
                Spacer(Modifier.height(2.dp))
                Text(
                    info.remoteAddress,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(10.dp))
            StatusLine(info)
            NoticeLine(notice ?: transferLabel(info))

            Spacer(Modifier.weight(1f))
            AnimatedContent(
                targetState = controls,
                modifier = Modifier.heightIn(min = 250.dp),
                transitionSpec = {
                    (fadeIn(tween(260)) + slideInVertically(tween(300)) { it / 4 }) togetherWith fadeOut(tween(160))
                },
                label = "callControls",
            ) { mode ->
                when (mode) {
                    Controls.INCOMING -> IncomingControls(videoOffered = info.videoOffer, onAnswer = onAnswer, onDecline = calls::decline)
                    Controls.ACTIVE -> ActiveControls(info, calls, onOpenKeypad, onOpenTransfer, onOpenShortcuts, onToggleVideo, onNotice)
                    Controls.ENDED -> Spacer(Modifier.fillMaxWidth())
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

private fun transferLabel(info: CallInfo): String? = when (info.transfer) {
    TransferStatus.IN_PROGRESS -> "正在转接…"
    TransferStatus.SUCCEEDED -> if (info.parked) "已驻留，请听 PBX 播报的车位号" else "转接成功"
    TransferStatus.FAILED -> "转接失败，通话仍然保留"
    null -> null
}

@Composable
private fun NoticeLine(text: String?) {
    AnimatedVisibility(text != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        Text(
            text.orEmpty(),
            style = MaterialTheme.typography.bodyMedium,
            color = Brand.Amber,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun Chip(text: String, icon: ImageVector? = null, highlighted: Boolean = false) {
    Row(
        Modifier
            .clip(CircleShape)
            .background(if (highlighted) Brand.Amber.copy(alpha = 0.9f) else Color.White.copy(alpha = 0.14f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(text, style = MaterialTheme.typography.labelMedium, color = Color.White, maxLines = 1)
    }
}

/** Second call ringing on top of the current one. */
@Composable
private fun WaitingCallBanner(waiting: SideCall?, onAnswer: () -> Unit, onDecline: () -> Unit) {
    Row(
        Modifier
            .padding(top = 12.dp)
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(Color.White.copy(alpha = 0.16f))
            .padding(start = 16.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("新来电", style = MaterialTheme.typography.labelMedium, color = Brand.Amber)
            Text(
                waiting?.name.orEmpty(),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        SmallRound(Brand.HangUpRed, Icons.Rounded.CallEnd, "拒绝新来电", onDecline)
        Spacer(Modifier.width(10.dp))
        SmallRound(Brand.CallGreen, Icons.Rounded.Call, "保留当前通话并接听", onAnswer)
    }
}

/** The other call, parked on hold; tapping swaps the two calls. */
@Composable
private fun HeldCallBanner(info: CallInfo, onSwap: () -> Unit) {
    val held = info.heldCall
    Row(
        Modifier
            .padding(top = 12.dp)
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(Color.White.copy(alpha = 0.12f))
            .clickable(enabled = !info.conference, onClick = onSwap)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                when {
                    info.conference -> "三方通话成员"
                    info.consulting -> "等待转接（保留中）"
                    else -> "保留中"
                },
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.7f),
            )
            Text(
                held?.name.orEmpty(),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!info.conference) {
            Icon(Icons.Rounded.SwapCalls, contentDescription = "切换通话", tint = Color.White)
        }
    }
}

@Composable
private fun SmallRound(color: Color, icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(color)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun StatusLine(info: CallInfo) {
    AnimatedContent(
        targetState = info.phase,
        transitionSpec = {
            (slideInVertically(tween(260)) { it / 2 } + fadeIn(tween(260))) togetherWith
                (slideOutVertically(tween(200)) { -it / 2 } + fadeOut(tween(160)))
        },
        label = "callStatus",
    ) { phase ->
        val text = when (phase) {
            CallPhase.INCOMING -> when {
                info.intercom -> "对讲呼叫"
                info.videoOffer -> "视频来电"
                else -> "来电"
            }
            CallPhase.OUTGOING -> "正在呼叫…"
            CallPhase.CONNECTED -> callTimer(info.connectedAt)
            CallPhase.HELD -> "通话已保留"
            CallPhase.ENDED -> info.endReason ?: "通话结束"
        }
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            color = Color.White.copy(alpha = 0.88f),
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun callTimer(connectedAt: Long?): String {
    if (connectedAt == null) return "已接通"
    val now by produceState(SystemClock.elapsedRealtime(), connectedAt) {
        while (true) {
            value = SystemClock.elapsedRealtime()
            delay(500)
        }
    }
    return Formatters.duration((now - connectedAt) / 1000)
}
