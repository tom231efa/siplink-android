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
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sky.siplink.core.CallManager
import com.sky.siplink.core.CallSupport
import com.sky.siplink.core.logic.CallRules
import com.sky.siplink.core.logic.Formatters
import com.sky.siplink.core.model.CallInfo
import com.sky.siplink.core.model.CallPhase
import com.sky.siplink.core.model.TransferStatus
import com.sky.siplink.engine
import com.sky.siplink.ui.components.FadingText
import com.sky.siplink.ui.theme.Brand
import kotlinx.coroutines.delay

internal enum class Controls { INCOMING, ACTIVE, ENDED }

/**
 * Full-screen call UI: incoming, outgoing, established and ended states of the current call.
 *
 * @param glass the window is see through and what is behind it is blurred, so the background
 *   may let it shine through; without the blur it covers it whole
 * @param activityNotice a notice of the activity (why answering from the notification did not
 *   work), shown like the screen's own; [onActivityNoticeShown] once it is
 */
@Composable
fun CallScreen(
    onAnswer: (video: Boolean) -> Unit,
    onFinished: () -> Unit,
    glass: Boolean = false,
    endRoomAsked: Boolean = false,
    onEndRoomAsked: () -> Unit = {},
    activityNotice: String? = null,
    onActivityNoticeShown: () -> Unit = {},
) {
    val context = LocalContext.current
    val calls = context.engine.calls
    val live by calls.call.collectAsStateWithLifecycle()
    // Keep showing the last known call while the screen animates away.
    var shown by remember { mutableStateOf(live) }
    var keypadOpen by remember { mutableStateOf(false) }
    var transferOpen by remember { mutableStateOf(false) }
    var shortcutsOpen by remember { mutableStateOf(false) }
    var addMembersOpen by remember { mutableStateOf(false) }
    var confirmRoom by remember { mutableStateOf(false) }
    var confirmRoomEnd by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    // Over video the controls go by themselves, and with a tap on the picture.
    val controls = remember { ControlsVisibility() }
    val stage = remember { VideoStageState() }

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
    LaunchedEffect(activityNotice) {
        if (activityNotice == null) return@LaunchedEffect
        notice = activityNotice
        onActivityNoticeShown()
    }
    // What reaches beyond the call waits until the phone is unlocked; locked again, it closes.
    val unlocked = rememberUnlocked {
        transferOpen = false
        shortcutsOpen = false
        addMembersOpen = false
    }

    val info = shown
    val video = info?.video?.takeIf { info.phase == CallPhase.CONNECTED }
    val established = info != null && (info.phase == CallPhase.CONNECTED || info.phase == CallPhase.HELD)
    // The record button stands in the top end corner, ahead of the buttons of a video call.
    val recordable = established && info.recordable
    val record: @Composable () -> Unit = { RecordControl(info?.recordingSince, onToggle = calls::toggleRecording) }
    val room = info?.room?.takeIf { info.phase != CallPhase.ENDED }
    // The room takes the place of the call, or the call that of the room: what was open over the other closes.
    LaunchedEffect(room != null) {
        keypadOpen = false
        transferOpen = false
        shortcutsOpen = false
        addMembersOpen = false
    }
    // What the user chose for a video (whole or filled, swapped) ends with it.
    LaunchedEffect(video == null) { if (video == null) stage.reset() }
    // The hang up key of the notification asks here before a room ends for everyone.
    LaunchedEffect(endRoomAsked) {
        if (!endRoomAsked) return@LaunchedEffect
        onEndRoomAsked()
        val present = calls.call.value?.room?.present ?: return@LaunchedEffect
        if (CallRules.confirmRoomEnd(present)) confirmRoomEnd = true else calls.hangUp()
    }
    // From a video call the room is opened once the user knows that the video ends.
    val openRoom: () -> Unit = {
        if (info?.video != null) confirmRoom = true else calls.openRoom()?.let { notice = it }
    }
    AutoHideControls(
        controls,
        canHide = video?.received != null && !keypadOpen && !transferOpen && !shortcutsOpen && notice == null &&
            info.waitingCall == null && !info.videoOffer && transferLabel(info) == null && !touchExplorationOn(),
    )
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = presence.value }
            .pictureTaps(controls, enabled = video != null),
    ) {
        CallBackground(ringingIn = info?.phase == CallPhase.INCOMING, glass = glass)
        if (video != null) VideoStage(video = video, stage = stage, chrome = !controls.hidden)
        if (info != null) {
            val mode = when (info.phase) {
                CallPhase.INCOMING -> Controls.INCOMING
                CallPhase.ENDED -> Controls.ENDED
                else -> Controls.ACTIVE
            }
            CompositionLocalProvider(LocalControlsVisibility provides controls) {
                AnimatedVisibility(!controls.hidden || video == null, enter = fadeIn(tween(180)), exit = fadeOut(tween(160))) {
                    if (room != null) {
                        RoomContent(
                            info, room, calls, notice,
                            onAddMembers = { unlocked { addMembersOpen = true } },
                            onNotice = { notice = it },
                            confirmEnd = confirmRoomEnd,
                            onConfirmEnd = { confirmRoomEnd = it },
                        )
                    } else {
                        CallContent(
                            info = info,
                            controls = mode,
                            calls = calls,
                            notice = notice,
                            videoMode = video != null,
                            topBar = {
                                CallTopBar(
                                    roomButton = established && mode == Controls.ACTIVE,
                                    onOpenRoom = openRoom,
                                    record = record.takeIf { recordable },
                                    videoButtons = video?.let { shownVideo ->
                                        {
                                            VideoButtons(
                                                shownVideo,
                                                stage,
                                                onSwitchCamera = calls::switchCamera,
                                                onToggleCamera = { withCamera { calls.toggleCamera()?.let { notice = it } } },
                                            )
                                        }
                                    },
                                    onPlaced = { stage.topRowBottom = it },
                                )
                            },
                            onControlsPlaced = { stage.controls = it },
                            // The call activity asks for the microphone and, to answer with video, the camera.
                            onAnswer = onAnswer,
                            onToggleVideo = {
                                val toggle = { calls.toggleVideo()?.let { notice = it } }
                                // Ending video needs no camera.
                                if (info.video != null) toggle() else withCamera { toggle() }
                            },
                            onAcceptVideo = { withCamera { calls.answerVideoRequest(true) } },
                            onOpenKeypad = { keypadOpen = true },
                            onOpenTransfer = { unlocked { transferOpen = true } },
                            onOpenShortcuts = { unlocked { shortcutsOpen = true } },
                            onOpenRoom = openRoom,
                            onNotice = { notice = it },
                        )
                    }
                }
            }
            // The keypad takes the place of the controls, under the name upright and beside it with
            // the phone on its side: the name, the notices, the top bar and the pictures stay in sight.
            val sideways = LocalWindowInfo.current.containerSize.let { it.width > it.height }
            AnimatedVisibility(
                visible = keypadOpen && mode == Controls.ACTIVE && room == null,
                modifier = Modifier.align(if (sideways) Alignment.CenterStart else Alignment.BottomCenter),
                enter = if (sideways) {
                    slideInHorizontally(spring(dampingRatio = 0.85f, stiffness = 300f)) { -it } + fadeIn()
                } else {
                    slideInVertically(spring(dampingRatio = 0.85f, stiffness = 300f)) { it } + fadeIn()
                },
                exit = if (sideways) slideOutHorizontally(tween(220)) { -it } + fadeOut() else slideOutVertically(tween(220)) { it } + fadeOut(),
            ) {
                DtmfPanel(onDigit = calls::sendDtmf, onClose = { keypadOpen = false }, sideways = sideways)
            }
            if (transferOpen && mode == Controls.ACTIVE && room == null) {
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
            if (shortcutsOpen && mode == Controls.ACTIVE && room == null) {
                ShortcutsSheet(onDismiss = { shortcutsOpen = false }, onNotice = { notice = it })
            }
            if (addMembersOpen && room != null) {
                RoomAddSheet(room, calls, onDismiss = { addMembersOpen = false })
            }
            if (confirmRoom) {
                AlertDialog(
                    onDismissRequest = { confirmRoom = false },
                    title = { Text("开启会议室？") },
                    text = { Text("会议室仅支持语音，视频将关闭。") },
                    confirmButton = {
                        TextButton(onClick = {
                            confirmRoom = false
                            calls.openRoom()?.let { notice = it }
                        }) { Text("开启") }
                    },
                    dismissButton = { TextButton(onClick = { confirmRoom = false }) { Text("取消") } },
                )
            }
        }
    }
}

/**
 * Dark, with one faint blue highlight for depth. A call that only rings lets what is behind the
 * window shine through, like frosted glass, where the system blurs it; once it is dialled or
 * answered the call has the screen to itself, and the glass turns solid. Without the blur the
 * background is solid from the start: a page seen sharp behind the caller's name only confuses.
 *
 * It does not move otherwise: a call can last for hours, and a background that animates would
 * redraw the whole screen all that time.
 */
@Composable
private fun CallBackground(ringingIn: Boolean, glass: Boolean) {
    val tint by animateFloatAsState(
        targetValue = if (ringingIn && glass) GLASS_TINT else 1f,
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

/** How much of what is behind a ringing call its dark tint covers where the system blurs it. */
private const val GLASS_TINT = 0.72f

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
    topBar: @Composable () -> Unit,
    onControlsPlaced: (Rect) -> Unit,
    onAnswer: (video: Boolean) -> Unit,
    onToggleVideo: () -> Unit,
    onAcceptVideo: () -> Unit,
    onOpenKeypad: () -> Unit,
    onOpenTransfer: () -> Unit,
    onOpenShortcuts: () -> Unit,
    onOpenRoom: () -> Unit,
    onNotice: (String) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth > maxHeight
        // A phone on its side is too low for the controls and the avatar as they are upright: they close up.
        val compact = wide && maxHeight < CompactBelow
        // The in-call controls take two rows, so the avatar shrinks on short screens.
        val roomy = maxHeight >= 760.dp && controls != Controls.ACTIVE || maxHeight >= 860.dp
        val ringSize = when {
            roomy -> 224.dp
            compact -> 104.dp
            else -> 148.dp
        }
        val avatarSize = when {
            roomy -> 120.dp
            compact -> 60.dp
            else -> 84.dp
        }
        val controlsArea: @Composable (Modifier) -> Unit = { modifier ->
            AnimatedContent(
                targetState = controls,
                modifier = modifier,
                transitionSpec = {
                    (fadeIn(tween(260)) + slideInVertically(tween(300)) { it / 4 }) togetherWith fadeOut(tween(160))
                },
                label = "callControls",
            ) { mode ->
                when (mode) {
                    Controls.INCOMING -> IncomingControls(videoOffered = info.videoOffer, onAnswer = onAnswer, onDecline = calls::decline)
                    Controls.ACTIVE -> ActiveControls(info, calls, onOpenKeypad, onOpenTransfer, onOpenShortcuts, onToggleVideo, onOpenRoom, onNotice, compact)
                    Controls.ENDED -> Spacer(Modifier.fillMaxWidth())
                }
            }
        }

        // The top bar is part of the flow, so that nothing below it can run under its buttons,
        // whatever the size of the screen or of the font.
        if (wide) {
            // The phone lies on its side: too low for the controls under the name, so they stand
            // beside it, from top to bottom, and the top bar over the name only. The far corner
            // stays free for the picture of the own camera.
            Row(
                Modifier
                    .fillMaxSize()
                    .systemBarsPadding(),
            ) {
                Box(
                    Modifier
                        .padding(start = 24.dp)
                        .width(SideControlsWidth)
                        .fillMaxHeight()
                        .onGloballyPositioned { onControlsPlaced(it.boundsInRoot()) }
                        .verticalScroll(rememberScrollState()),
                    contentAlignment = Alignment.Center,
                ) {
                    controlsArea(Modifier.padding(vertical = 8.dp))
                }
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                ) {
                    topBar()
                    // With the avatar, what waits beside the call (banners, a notice) may not fit
                    // under the name: it scrolls. Not over video: a scroll there would take the
                    // touches of the small picture under it.
                    BoxWithConstraints(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    ) {
                        Column(
                            Modifier
                                .then(if (videoMode) Modifier else Modifier.verticalScroll(rememberScrollState()))
                                .heightIn(min = maxHeight)
                                .fillMaxWidth()
                                .padding(start = 24.dp, end = 24.dp + if (videoMode) SideCornerWidth else 0.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Spacer(Modifier.height(8.dp))
                            CallHead(info, calls, notice, videoMode, avatarSize, ringSize, onAcceptVideo)
                            if (!videoMode) Spacer(Modifier.weight(0.6f))
                        }
                    }
                }
            }
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .systemBarsPadding(),
            ) {
                topBar()
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.height(16.dp))
                    CallHead(info, calls, notice, videoMode, avatarSize, ringSize, onAcceptVideo)
                    Spacer(Modifier.weight(1f))
                    controlsArea(
                        Modifier
                            .heightIn(min = 250.dp)
                            .onGloballyPositioned { onControlsPlaced(it.boundsInRoot()) },
                    )
                    Spacer(Modifier.height(20.dp))
                }
            }
        }
    }
}

/**
 * The buttons at the top of a call that is up: the conference room at the start, recording at
 * the end, and over video the buttons of the picture after it ([videoButtons]), all in one row.
 *
 * @param onPlaced where the row ends, in pixels from the top of the window (the small picture of a video call stays under it)
 */
@Composable
private fun CallTopBar(
    roomButton: Boolean,
    onOpenRoom: () -> Unit,
    record: (@Composable () -> Unit)?,
    videoButtons: (@Composable () -> Unit)?,
    onPlaced: (Float) -> Unit,
) {
    AnimatedVisibility(
        visible = roomButton || record != null || videoButtons != null,
        enter = expandVertically(tween(220)) + fadeIn(tween(180)),
        exit = shrinkVertically(tween(200)) + fadeOut(tween(140)),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .onGloballyPositioned { onPlaced(it.boundsInRoot().bottom) }
                .padding(start = 16.dp, end = 16.dp, top = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // A long label (a large font) gives way to the buttons and fades.
            if (roomButton) RoomButton(onOpenRoom, Modifier.weight(1f, fill = false)) else Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                record?.invoke()
                videoButtons?.invoke()
            }
        }
    }
}

/** Opens the conference room with the call that is up. */
@Composable
private fun RoomButton(onClick: () -> Unit, modifier: Modifier) {
    Row(
        modifier
            .heightIn(min = 40.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.32f))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = 12.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Groups, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        FadingText("会议室", style = MaterialTheme.typography.labelLarge, color = Color.White, modifier = Modifier.weight(1f, fill = false))
    }
}

/** Who the call is with and how it stands: the line, what waits beside it, the name, the time. */
@Composable
private fun ColumnScope.CallHead(
    info: CallInfo,
    calls: CallManager,
    notice: String?,
    videoMode: Boolean,
    avatarSize: Dp,
    ringSize: Dp,
    onAcceptVideo: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        // Over video the corner belongs to the own picture: the line is left out there.
        // A long line name gives way to the chips beside it.
        if (info.accountLabel.isNotEmpty() && !videoMode) Chip(text = info.accountLabel, modifier = Modifier.weight(1f, fill = false))
        if (info.intercom) Chip(text = "对讲", icon = Icons.Rounded.Campaign, highlighted = true)
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
    FadingText(
        info.remoteName,
        style = MaterialTheme.typography.headlineMedium,
        color = Color.White,
    )
    if (info.remoteAddress != info.remoteName && !videoMode) {
        Spacer(Modifier.height(2.dp))
        FadingText(
            info.remoteAddress,
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White.copy(alpha = 0.7f),
        )
    }
    Spacer(Modifier.height(10.dp))
    StatusLine(info)
    NoticeLine(notice ?: transferLabel(info) ?: noSoundLabel(info))
}

/** With the phone on its side: the width of the controls, and what the own picture needs of the far corner. */
internal val SideControlsWidth = 380.dp
private val SideCornerWidth = 172.dp

/** The controls with the margin before them: what the keypad takes the place of with the phone on its side. */
internal val SidePaneWidth = SideControlsWidth + 24.dp

/** A screen on its side lower than this has the controls closed up. */
private val CompactBelow = 420.dp

private fun transferLabel(info: CallInfo): String? = when (info.transfer) {
    TransferStatus.IN_PROGRESS -> "正在转接…"
    TransferStatus.SUCCEEDED -> if (info.parked) "已驻留，请收听 PBX 播报的车位号" else "转接成功"
    TransferStatus.FAILED -> "转接失败，通话仍处于保留状态"
    null -> null
}

private fun noSoundLabel(info: CallInfo): String? = CallSupport.NO_SOUND.takeIf { info.noSound && info.phase == CallPhase.CONNECTED }

@Composable
internal fun NoticeLine(text: String?) {
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
private fun Chip(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, highlighted: Boolean = false) {
    Row(
        modifier
            .clip(CircleShape)
            .background(if (highlighted) Brand.Amber.copy(alpha = 0.9f) else Color.White.copy(alpha = 0.14f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
        }
        FadingText(text, style = MaterialTheme.typography.labelMedium, color = Color.White, modifier = Modifier.weight(1f, fill = false))
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
internal fun callTimer(connectedAt: Long?): String {
    if (connectedAt == null) return "已接通"
    val now by produceState(SystemClock.elapsedRealtime(), connectedAt) {
        while (true) {
            value = SystemClock.elapsedRealtime()
            delay(500)
        }
    }
    return Formatters.duration((now - connectedAt) / 1000)
}
