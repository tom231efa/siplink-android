package com.sky.siplink.ui.call

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Dialpad
import androidx.compose.material.icons.rounded.Headset
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sky.siplink.core.CallManager
import com.sky.siplink.core.logic.Formatters
import com.sky.siplink.core.model.AudioRoute
import com.sky.siplink.core.model.CallInfo
import com.sky.siplink.core.model.CallPhase
import com.sky.siplink.engine
import com.sky.siplink.ui.components.Avatar
import com.sky.siplink.ui.components.PulseRings
import com.sky.siplink.ui.theme.Brand
import kotlinx.coroutines.delay

private enum class Controls { INCOMING, ACTIVE, ENDED }

private val dtmfKeys = listOf('1', '2', '3', '4', '5', '6', '7', '8', '9', '*', '0', '#')

/** Full-screen call UI: incoming, outgoing, established and ended states of the current call. */
@Composable
fun CallScreen(onAnswer: () -> Unit, onFinished: () -> Unit) {
    val calls = LocalContext.current.engine.calls
    val live by calls.call.collectAsStateWithLifecycle()
    // Keep showing the last known call while the screen animates away.
    var shown by remember { mutableStateOf(live) }
    var keypadOpen by remember { mutableStateOf(false) }

    LaunchedEffect(live) {
        if (live != null) {
            shown = live
        } else {
            delay(250)
            onFinished()
        }
    }

    val info = shown
    Box(Modifier.fillMaxSize()) {
        CallBackground(intercom = info?.intercom == true)
        if (info != null) {
            val controls = when (info.phase) {
                CallPhase.INCOMING -> Controls.INCOMING
                CallPhase.ENDED -> Controls.ENDED
                else -> Controls.ACTIVE
            }
            CallContent(info, controls, calls, onAnswer, onOpenKeypad = { keypadOpen = true })
            AnimatedVisibility(
                visible = keypadOpen && controls == Controls.ACTIVE,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically(spring(dampingRatio = 0.85f, stiffness = 300f)) { it } + fadeIn(),
                exit = slideOutVertically(tween(220)) { it } + fadeOut(),
            ) {
                DtmfPanel(onDigit = calls::sendDtmf, onClose = { keypadOpen = false })
            }
        }
    }
}

@Composable
private fun CallBackground(intercom: Boolean) {
    val transition = rememberInfiniteTransition(label = "callBackground")
    val drift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(11_000, easing = LinearEasing), RepeatMode.Reverse),
        label = "drift",
    )
    val colors = if (intercom) Brand.IntercomGradient else Brand.CallGradient
    Canvas(Modifier.fillMaxSize()) {
        drawRect(
            Brush.linearGradient(
                colors,
                start = Offset(size.width * drift * 0.5f, 0f),
                end = Offset(size.width * (1f - drift * 0.3f), size.height),
            ),
        )
        // Two soft glows drifting slowly give the background some depth.
        val radius = size.minDimension * 0.8f
        val first = Offset(size.width * (0.1f + 0.55f * drift), size.height * 0.2f)
        drawCircle(
            Brush.radialGradient(listOf(Brand.Violet.copy(alpha = 0.42f), Color.Transparent), first, radius),
            radius,
            first,
        )
        val second = Offset(size.width * (0.95f - 0.55f * drift), size.height * 0.82f)
        drawCircle(
            Brush.radialGradient(listOf(Brand.Teal.copy(alpha = 0.32f), Color.Transparent), second, radius),
            radius,
            second,
        )
    }
}

@Composable
private fun CallContent(
    info: CallInfo,
    controls: Controls,
    calls: CallManager,
    onAnswer: () -> Unit,
    onOpenKeypad: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (info.accountLabel.isNotEmpty()) Chip(text = info.accountLabel)
            if (info.intercom) Chip(text = "对讲", icon = Icons.Rounded.Campaign, highlighted = true)
        }

        Spacer(Modifier.weight(0.5f))
        Box(Modifier.size(232.dp), contentAlignment = Alignment.Center) {
            val ringing = info.phase == CallPhase.INCOMING || info.phase == CallPhase.OUTGOING
            androidx.compose.animation.AnimatedVisibility(ringing, enter = fadeIn(tween(400)), exit = fadeOut(tween(400))) {
                PulseRings(Color.White, Modifier.fillMaxSize())
            }
            Avatar(info.remoteName, size = 120.dp)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            info.remoteName,
            style = MaterialTheme.typography.headlineMedium,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (info.remoteAddress != info.remoteName) {
            Spacer(Modifier.height(4.dp))
            Text(
                info.remoteAddress,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(14.dp))
        StatusLine(info)

        Spacer(Modifier.weight(1f))
        AnimatedContent(
            targetState = controls,
            transitionSpec = {
                (fadeIn(tween(260)) + slideInVertically(tween(300)) { it / 4 }) togetherWith fadeOut(tween(160))
            },
            label = "callControls",
        ) { mode ->
            when (mode) {
                Controls.INCOMING -> IncomingControls(onAnswer = onAnswer, onDecline = calls::decline)
                Controls.ACTIVE -> ActiveControls(info, calls, onOpenKeypad)
                Controls.ENDED -> Spacer(
                    Modifier
                        .fillMaxWidth()
                        .height(210.dp),
                )
            }
        }
        Spacer(Modifier.height(28.dp))
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
            CallPhase.INCOMING -> if (info.intercom) "对讲呼叫" else "来电"
            CallPhase.OUTGOING -> if (info.intercom) "正在发起对讲…" else "正在呼叫…"
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

@Composable
private fun IncomingControls(onAnswer: () -> Unit, onDecline: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(210.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundAction(Brand.HangUpRed, Icons.Rounded.CallEnd, "拒绝", onDecline)

        val transition = rememberInfiniteTransition(label = "answer")
        val bounce by transition.animateFloat(
            initialValue = 0f,
            targetValue = -10f,
            animationSpec = infiniteRepeatable(tween(620, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "answerBounce",
        )
        Box(contentAlignment = Alignment.TopCenter) {
            PulseRings(Brand.CallGreen, Modifier.size(132.dp).graphicsLayer { translationY = -30.dp.toPx() }, durationMs = 1800)
            RoundAction(
                Brand.CallGreen,
                Icons.Rounded.Call,
                "接听",
                onAnswer,
                modifier = Modifier.graphicsLayer { translationY = bounce.dp.toPx() },
            )
        }
    }
}

@Composable
private fun ActiveControls(info: CallInfo, calls: CallManager, onOpenKeypad: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .height(210.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            ControlToggle(
                icon = if (info.muted) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                label = if (info.muted) "已静音" else "静音",
                active = info.muted,
                onClick = calls::toggleMute,
            )
            ControlToggle(Icons.Rounded.Dialpad, "键盘", active = false, onClick = onOpenKeypad)
            RouteToggle(info, calls)
            val established = info.phase == CallPhase.CONNECTED || info.phase == CallPhase.HELD
            ControlToggle(
                icon = if (info.phase == CallPhase.HELD) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                label = if (info.phase == CallPhase.HELD) "恢复" else "保留",
                active = info.phase == CallPhase.HELD,
                enabled = established,
                onClick = calls::toggleHold,
            )
        }
        RoundAction(Brand.HangUpRed, Icons.Rounded.CallEnd, "挂断", calls::hangUp, size = 76.dp)
    }
}

@Composable
private fun RouteToggle(info: CallInfo, calls: CallManager) {
    var menuOpen by remember { mutableStateOf(false) }
    val (icon, label) = when (info.route) {
        AudioRoute.BLUETOOTH -> Icons.Rounded.Bluetooth to "蓝牙"
        AudioRoute.HEADSET -> Icons.Rounded.Headset to "耳机"
        else -> Icons.AutoMirrored.Rounded.VolumeUp to "扬声器"
    }
    val simple = info.availableRoutes.all { it == AudioRoute.EARPIECE || it == AudioRoute.SPEAKER }
    Box {
        ControlToggle(
            icon = icon,
            label = label,
            active = info.route != AudioRoute.EARPIECE,
            onClick = {
                if (simple) {
                    calls.setRoute(if (info.route == AudioRoute.SPEAKER) AudioRoute.EARPIECE else AudioRoute.SPEAKER)
                } else {
                    menuOpen = true
                }
            },
        )
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            info.availableRoutes.forEach { route ->
                DropdownMenuItem(
                    text = { Text(routeName(route)) },
                    trailingIcon = {
                        if (route == info.route) Icon(Icons.Rounded.Check, contentDescription = "当前")
                    },
                    onClick = {
                        menuOpen = false
                        calls.setRoute(route)
                    },
                )
            }
        }
    }
}

private fun routeName(route: AudioRoute): String = when (route) {
    AudioRoute.EARPIECE -> "听筒"
    AudioRoute.SPEAKER -> "扬声器"
    AudioRoute.BLUETOOTH -> "蓝牙"
    AudioRoute.HEADSET -> "有线耳机"
}

@Composable
private fun ControlToggle(
    icon: ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.88f else 1f,
        animationSpec = spring(dampingRatio = 0.45f, stiffness = 600f),
        label = "toggleScale",
    )
    val container by animateColorAsState(if (active) Color.White else Color.White.copy(alpha = 0.16f), label = "toggleBg")
    val content by animateColorAsState(if (active) Color(0xFF1B1F4B) else Color.White, label = "toggleFg")
    val alpha by animateFloatAsState(if (enabled) 1f else 0.38f, label = "toggleAlpha")
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.graphicsLayer { this.alpha = alpha },
    ) {
        Box(
            Modifier
                .size(64.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .clip(CircleShape)
                .background(container)
                .clickable(
                    enabled = enabled,
                    interactionSource = interaction,
                    indication = LocalIndication.current,
                    onClick = onClick,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = label, tint = content, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.82f))
    }
}

@Composable
private fun RoundAction(
    color: Color,
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 72.dp,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.9f else 1f,
        animationSpec = spring(dampingRatio = 0.4f, stiffness = 500f),
        label = "actionScale",
    )
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(size)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .clip(CircleShape)
                .background(color)
                .clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(size * 0.45f))
        }
        Spacer(Modifier.height(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.9f))
    }
}

@Composable
private fun DtmfPanel(onDigit: (Char) -> Unit, onClose: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    var typed by remember { mutableStateOf("") }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp))
            .background(Color(0xF5171A36))
            // Swallow touches so they do not reach the controls underneath.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
            .navigationBarsPadding()
            .padding(horizontal = 28.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                typed.takeLast(24),
                modifier = Modifier.weight(1f),
                color = Color.White,
                fontSize = 24.sp,
                maxLines = 1,
                letterSpacing = 2.sp,
            )
            IconButton(onClick = onClose) {
                Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "收起键盘", tint = Color.White)
            }
        }
        Spacer(Modifier.height(8.dp))
        dtmfKeys.chunked(3).forEach { row ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                row.forEach { digit ->
                    Box(
                        Modifier
                            .size(66.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.12f))
                            .clickable {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                typed += digit
                                onDigit(digit)
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(digit.toString(), color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
