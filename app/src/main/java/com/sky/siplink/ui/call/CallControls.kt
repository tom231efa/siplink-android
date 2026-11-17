@file:OptIn(ExperimentalMaterial3Api::class)

package com.sky.siplink.ui.call

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallMerge
import androidx.compose.material.icons.automirrored.rounded.PhoneForwarded
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.Dialpad
import androidx.compose.material.icons.rounded.Headset
import androidx.compose.material.icons.rounded.LocalParking
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SwapCalls
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sky.siplink.core.CallManager
import com.sky.siplink.core.model.AudioRoute
import com.sky.siplink.core.model.CallInfo
import com.sky.siplink.core.model.CallPhase
import com.sky.siplink.core.model.SupervisionMode
import com.sky.siplink.engine
import com.sky.siplink.ui.components.ChoiceRow
import com.sky.siplink.ui.theme.Brand

/** @param videoOffered the caller offers video: a switch above the slider says whether to answer with it */
@Composable
internal fun IncomingControls(videoOffered: Boolean, onAnswer: (video: Boolean) -> Unit, onDecline: () -> Unit) {
    var withVideo by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .height(150.dp)
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (videoOffered) {
            AnswerWithVideoToggle(on = withVideo, onChange = { withVideo = it })
            Spacer(Modifier.height(14.dp))
        }
        SlideToAnswer(onAnswer = { onAnswer(videoOffered && withVideo) }, onDecline = onDecline)
    }
}

@Composable
internal fun ActiveControls(
    info: CallInfo,
    calls: CallManager,
    onOpenKeypad: () -> Unit,
    onOpenTransfer: () -> Unit,
    onOpenShortcuts: () -> Unit,
    onToggleVideo: () -> Unit,
    onNotice: (String) -> Unit,
) {
    val established = info.phase == CallPhase.CONNECTED || info.phase == CallPhase.HELD
    val twoCalls = info.heldCall != null
    val settings by LocalContext.current.engine.settings.settings.collectAsStateWithLifecycle()
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        AnimatedVisibility(info.supervision != null) {
            SupervisionSwitch(info.supervision ?: SupervisionMode.LISTEN, calls::setSupervisionMode)
        }
        AnimatedVisibility(info.consulting && twoCalls) {
            Button(
                onClick = { calls.completeTransfer()?.let(onNotice) },
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Brand.CallSurface),
            ) {
                Icon(Icons.AutoMirrored.Rounded.PhoneForwarded, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("完成转接：将 ${info.heldCall?.name.orEmpty()} 转给对方")
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            ControlToggle(
                icon = if (info.muted) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                label = if (info.muted) "已静音" else "静音",
                active = info.muted,
                onClick = calls::toggleMute,
            )
            ControlToggle(Icons.Rounded.Dialpad, "键盘", active = false, onClick = onOpenKeypad)
            RouteToggle(info, calls)
            ControlToggle(
                icon = if (info.phase == CallPhase.HELD) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                label = if (info.phase == CallPhase.HELD) "恢复" else "保留",
                active = info.phase == CallPhase.HELD,
                enabled = established && !info.conference,
                onClick = calls::toggleHold,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            ControlToggle(
                Icons.AutoMirrored.Rounded.PhoneForwarded,
                "转接",
                active = false,
                enabled = established && !info.conference,
                onClick = onOpenTransfer,
            )
            ControlToggle(
                Icons.Rounded.LocalParking,
                "驻留",
                active = false,
                enabled = established && !twoCalls,
                // Without a parking code the key is grey, and park() answers the tap with the notice.
                dimmed = settings.features.park.isBlank(),
                onClick = { calls.park()?.let(onNotice) },
            )
            ControlToggle(
                Icons.Rounded.SwapCalls,
                "切换",
                active = false,
                enabled = twoCalls && !info.conference,
                onClick = calls::swap,
            )
            ControlToggle(
                Icons.AutoMirrored.Rounded.CallMerge,
                "合并",
                active = info.conference,
                enabled = twoCalls && !info.conference && info.phase == CallPhase.CONNECTED,
                onClick = { calls.merge()?.let(onNotice) },
            )
        }
        // The shortcut keys sit next to the hang up button, which stays in the middle.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                ControlToggle(Icons.Rounded.Apps, "快捷键", active = false, onClick = onOpenShortcuts)
            }
            RoundAction(Brand.HangUpRed, Icons.Rounded.CallEnd, if (info.conference) "结束全部" else "挂断", calls::hangUp, size = 72.dp)
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                // Nothing of video is shown while video calls are switched off.
                if (info.videoAllowed || info.video != null) {
                    ControlToggle(
                        Icons.Rounded.Videocam,
                        "视频",
                        active = info.video != null,
                        enabled = info.phase == CallPhase.CONNECTED && info.videoAllowed && !info.videoOffer,
                        onClick = onToggleVideo,
                    )
                }
            }
        }
    }
}

/** Listen / whisper / barge switch of a supervisor call. */
@Composable
private fun SupervisionSwitch(mode: SupervisionMode, onSelect: (SupervisionMode) -> Unit) {
    val options = listOf(
        SupervisionMode.LISTEN to "监听",
        SupervisionMode.WHISPER to "耳语",
        SupervisionMode.BARGE to "强插",
    )
    ChoiceRow(
        options = options,
        selected = mode,
        onSelect = onSelect,
        modifier = Modifier
            .padding(horizontal = 24.dp)
            .widthIn(max = 320.dp),
        track = Color.White.copy(alpha = 0.14f),
        thumb = Color.White,
        content = Color.White,
        selectedContent = Color(0xFF1B1E23),
    )
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
                    // The route in use is the one in the accent colour.
                    text = {
                        Text(
                            routeName(route),
                            color = if (route == info.route) MaterialTheme.colorScheme.primary else Color.Unspecified,
                        )
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

/** @param dimmed drawn like a disabled key although it can be tapped (the tap explains why it does nothing) */
@Composable
internal fun ControlToggle(
    icon: ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
    dimmed: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.88f else 1f,
        animationSpec = spring(dampingRatio = 0.45f, stiffness = 600f),
        label = "toggleScale",
    )
    val container by animateColorAsState(if (active) Color.White else Color.White.copy(alpha = 0.16f), label = "toggleBg")
    val content by animateColorAsState(if (active) Brand.CallSurface else Color.White, label = "toggleFg")
    val alpha by animateFloatAsState(if (enabled && !dimmed) 1f else 0.34f, label = "toggleAlpha")
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.graphicsLayer { this.alpha = alpha },
    ) {
        Box(
            Modifier
                .size(58.dp)
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
            Icon(icon, contentDescription = label, tint = content, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.82f))
    }
}

@Composable
internal fun RoundAction(
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
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.9f))
    }
}
