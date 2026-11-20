@file:OptIn(ExperimentalFoundationApi::class)

package com.sky.siplink.ui.dialer

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sky.siplink.R
import com.sky.siplink.ui.components.pressScale
import com.sky.siplink.ui.theme.Brand

private val dialKeys = listOf(
    '1' to "", '2' to "ABC", '3' to "DEF",
    '4' to "GHI", '5' to "JKL", '6' to "MNO",
    '7' to "PQRS", '8' to "TUV", '9' to "WXYZ",
    '*' to "", '0' to "+", '#' to "",
)

/** Space between two rows of keys; where the rows would get too low, the smaller one. */
private val RowGap = 10.dp
private val TightRowGap = 4.dp

/** Keys grow with the room they have up to this size... */
private val LargestKey = 84.dp

/** ...and never shrink below a comfortable touch target: where even that does not fit, the pad scrolls. */
private val SmallestKey = 48.dp

/** In a compact pad each key sits in a cell this much wider than itself. */
private const val COMPACT_CELL = 1.4f

/** The height the four rows need with the smallest keys. */
internal val PadMinHeight = SmallestKey * 4 + TightRowGap * 3

/** Height of the row with the call button. */
internal val CallRowHeight = 76.dp

/**
 * The twelve keys, round and as large as the room allows.
 *
 * @param compact the keys keep together in a block sized from the height (next to the other
 *   controls); otherwise they spread over the whole width (below them)
 */
@Composable
internal fun DialPad(onDigit: (Char) -> Unit, onLongZero: () -> Unit, modifier: Modifier = Modifier, compact: Boolean = false) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val cell = if (compact) COMPACT_CELL else 1f
        val gap = if ((maxHeight - RowGap * 3) / 4 >= SmallestKey) RowGap else TightRowGap
        val rowHeight = (maxHeight - gap * 3) / 4
        val fits = rowHeight >= SmallestKey
        val key = minOf(rowHeight, maxWidth / (3 * cell), LargestKey).coerceAtLeast(SmallestKey)
        val height = when {
            !fits -> Modifier.verticalScroll(rememberScrollState()).height(PadMinHeight)
            compact -> Modifier.height(key * 4 + gap * 3)
            else -> Modifier.fillMaxHeight()
        }
        Column(
            (if (compact) Modifier.width(key * 3 * cell) else Modifier.fillMaxWidth()).then(height),
            verticalArrangement = Arrangement.spacedBy(gap),
        ) {
            dialKeys.chunked(3).forEach { row ->
                Row(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    row.forEach { (digit, letters) ->
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            contentAlignment = Alignment.Center,
                        ) {
                            DialKey(
                                digit = digit,
                                letters = letters,
                                size = key,
                                onClick = { onDigit(digit) },
                                onLongClick = if (digit == '0') onLongZero else null,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DialKey(digit: Char, letters: String, size: Dp, onClick: () -> Unit, onLongClick: (() -> Unit)?) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.86f else 1f,
        animationSpec = spring(dampingRatio = 0.42f, stiffness = 700f),
        label = "keyScale",
    )
    val container by animateColorAsState(
        targetValue = if (pressed) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        label = "keyColor",
    )
    // The figures follow the font size the user chose, but only as far as the key holds them.
    val density = LocalDensity.current
    val digitSize = with(density) { minOf(28.sp.toDp(), size * 0.42f).toSp() }
    val letterSize = with(density) { minOf(10.sp.toDp(), size * 0.15f).toSp() }
    Box(
        modifier = Modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(container)
            .combinedClickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(digit.toString(), fontSize = digitSize, lineHeight = digitSize * (6f / 7f), fontWeight = FontWeight.Medium)
            if (letters.isNotEmpty()) {
                Text(
                    letters,
                    fontSize = letterSize,
                    lineHeight = letterSize * 1.2f,
                    letterSpacing = letterSize * 0.15f,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The call button between two round keys. Left: the shortcut keys, or a video call while a number
 * is typed and video calls are on. Right: delete, there only while a number is being typed (both
 * keep their place, so the call button stays in the middle).
 */
@Composable
internal fun CallRow(
    videoKey: Boolean,
    canCall: Boolean,
    canDelete: Boolean,
    onCall: () -> Unit,
    onVideoCall: () -> Unit,
    onShortcuts: () -> Unit,
    onDelete: () -> Unit,
    onClear: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedContent(
            targetState = videoKey,
            transitionSpec = { fadeIn(tween(140)) togetherWith fadeOut(tween(100)) },
            label = "leftKey",
        ) { video ->
            if (video) {
                SideAction(onClick = onVideoCall) {
                    Icon(Icons.Rounded.Videocam, contentDescription = stringResource(R.string.dialer_video_call))
                }
            } else {
                SideAction(onClick = onShortcuts) {
                    Icon(Icons.Rounded.Apps, contentDescription = stringResource(R.string.dialer_shortcuts))
                }
            }
        }
        CallButton(enabled = canCall, onClick = onCall)
        Box(Modifier.size(SideActionSize)) {
            // Spelled out: inside the row the name would mean the row's own variant.
            androidx.compose.animation.AnimatedVisibility(
                visible = canDelete,
                enter = fadeIn(tween(120)) + scaleIn(spring(dampingRatio = 0.6f, stiffness = 600f), initialScale = 0.7f),
                exit = fadeOut(tween(90)) + scaleOut(tween(120), targetScale = 0.7f),
            ) {
                SideAction(onClick = onDelete, onLongClick = onClear) {
                    Icon(Icons.AutoMirrored.Rounded.Backspace, contentDescription = stringResource(R.string.dialer_backspace))
                }
            }
        }
    }
}

@Composable
private fun CallButton(enabled: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.9f else 1f,
        animationSpec = spring(dampingRatio = 0.4f, stiffness = 500f),
        label = "callScale",
    )
    val alpha by animateFloatAsState(if (enabled) 1f else 0.45f, label = "callAlpha")
    Box(
        modifier = Modifier
            .size(CallRowHeight)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
            }
            .shadow(if (enabled) 6.dp else 0.dp, CircleShape)
            .clip(CircleShape)
            .background(Brand.CallGreen)
            .clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.Call, contentDescription = stringResource(R.string.dialer_call), tint = Color.White, modifier = Modifier.size(34.dp))
    }
}

/** Round key next to the call button; what it does is said by its icon alone. */
@Composable
private fun SideAction(onClick: () -> Unit, onLongClick: (() -> Unit)? = null, icon: @Composable () -> Unit) {
    Box(
        Modifier
            .size(SideActionSize)
            .pressScale()
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center,
    ) {
        icon()
    }
}

private val SideActionSize = 56.dp
