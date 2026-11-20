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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sky.siplink.R
import com.sky.siplink.core.CallManager
import com.sky.siplink.core.model.AudioRoute
import com.sky.siplink.core.model.CallInfo
import com.sky.siplink.core.model.CallPhase
import com.sky.siplink.core.model.SupervisionMode
import com.sky.siplink.engine
import com.sky.siplink.ui.components.ChoiceRow
import com.sky.siplink.ui.components.stringOf
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

/** @param compact closed up, for a low screen: less room between the rows and a smaller hang up button */
@Composable
internal fun ActiveControls(
    info: CallInfo,
    calls: CallManager,
    onOpenKeypad: () -> Unit,
    onOpenTransfer: () -> Unit,
    onOpenShortcuts: () -> Unit,
    onToggleVideo: () -> Unit,
    onOpenRoom: () -> Unit,
    onNotice: (String) -> Unit,
    compact: Boolean,
) {
    val established = info.phase == CallPhase.CONNECTED || info.phase == CallPhase.HELD
    val twoCalls = info.heldCall != null
    val settings by LocalContext.current.engine.settings.settings.collectAsStateWithLifecycle()
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 14.dp),
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
                Text(stringOf(R.string.call_complete_transfer, info.heldCall?.name.orEmpty()))
            }
        }

        KeyRow(keys = 4) {
            ControlToggle(
                icon = if (info.muted) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                label = stringResource(if (info.muted) R.string.call_muted else R.string.call_mute),
                active = info.muted,
                onClick = calls::toggleMute,
            )
            ControlToggle(Icons.Rounded.Dialpad, stringResource(R.string.call_keypad), active = false, onClick = onOpenKeypad)
            RouteToggle(info, calls)
            ControlToggle(
                icon = if (info.phase == CallPhase.HELD) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                label = stringResource(if (info.phase == CallPhase.HELD) R.string.call_resume else R.string.call_hold),
                active = info.phase == CallPhase.HELD,
                enabled = established,
                onClick = calls::toggleHold,
            )
        }
        KeyRow(keys = 4) {
            ControlToggle(
                Icons.AutoMirrored.Rounded.PhoneForwarded,
                stringResource(R.string.call_transfer),
                active = false,
                enabled = established,
                onClick = onOpenTransfer,
            )
            ControlToggle(
                Icons.Rounded.LocalParking,
                stringResource(R.string.call_park),
                active = false,
                enabled = established && !twoCalls,
                // Without a parking code the key is grey, and park() answers the tap with the notice.
                dimmed = settings.features.park.isBlank(),
                onClick = { calls.park()?.let(onNotice) },
            )
            ControlToggle(
                Icons.Rounded.SwapCalls,
                stringResource(R.string.call_swap),
                active = false,
                enabled = twoCalls,
                onClick = calls::swap,
            )
            // Merging the two calls opens the conference room with them.
            ControlToggle(
                Icons.AutoMirrored.Rounded.CallMerge,
                stringResource(R.string.call_merge),
                active = false,
                enabled = twoCalls && info.phase == CallPhase.CONNECTED,
                onClick = onOpenRoom,
            )
        }
        // The shortcut keys sit next to the hang up button, which stays in the middle.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                ControlToggle(Icons.Rounded.Apps, stringResource(R.string.call_shortcuts), active = false, onClick = onOpenShortcuts)
            }
            RoundAction(Brand.HangUpRed, Icons.Rounded.CallEnd, stringResource(R.string.call_hang_up), calls::hangUp, size = if (compact) 64.dp else 72.dp)
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                // Nothing of video is shown while video calls are switched off.
                if (info.videoAllowed || info.video != null) {
                    ControlToggle(
                        Icons.Rounded.Videocam,
                        stringResource(R.string.call_video),
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
        SupervisionMode.LISTEN to stringResource(R.string.call_supervision_listen),
        SupervisionMode.WHISPER to stringResource(R.string.call_supervision_whisper),
        SupervisionMode.BARGE to stringResource(R.string.call_supervision_barge),
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
internal fun RouteToggle(info: CallInfo, calls: CallManager) {
    var menuOpen by remember { mutableStateOf(false) }
    // An open menu keeps the controls of a video call on screen.
    if (menuOpen) KeepControlsShown()
    val (icon, label) = when (info.route) {
        AudioRoute.BLUETOOTH -> Icons.Rounded.Bluetooth to stringResource(R.string.call_route_bluetooth)
        AudioRoute.HEADSET -> Icons.Rounded.Headset to stringResource(R.string.call_route_headset)
        else -> Icons.AutoMirrored.Rounded.VolumeUp to stringResource(R.string.call_route_speaker)
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

@Composable
private fun routeName(route: AudioRoute): String = stringResource(
    when (route) {
        AudioRoute.EARPIECE -> R.string.call_route_earpiece
        AudioRoute.SPEAKER -> R.string.call_route_speaker
        AudioRoute.BLUETOOTH -> R.string.call_route_bluetooth
        AudioRoute.HEADSET -> R.string.call_route_wired_headset
    },
)

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
        // As wide as its share of the row ([KeyRow]): a longer label (other languages) takes a second line.
        Text(
            label,
            modifier = Modifier.widthIn(max = LocalKeyLabelWidth.current),
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = 0.82f),
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The widest a label under a key ([ControlToggle]) gets: four keys of a row on a phone 360 dp wide; a [KeyRow] narrows it to its share. */
private val LocalKeyLabelWidth = staticCompositionLocalOf { KeyLabelWidth }

/** The widest a label of a key outside a [KeyRow] gets. */
private val KeyLabelWidth = 86.dp

/**
 * A row of [keys] keys spread evenly over the width, whose labels keep to their share of it: a
 * row that cannot hold [keys] labels of the full width (a narrow screen) narrows them, so a long
 * word takes the label's second line instead of running into its neighbour or off the screen.
 */
@Composable
internal fun KeyRow(keys: Int, content: @Composable RowScope.() -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        CompositionLocalProvider(LocalKeyLabelWidth provides minOf(KeyLabelWidth, maxWidth / keys)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, content = content)
        }
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
