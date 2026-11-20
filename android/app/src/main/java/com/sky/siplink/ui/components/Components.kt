package com.sky.siplink.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sky.siplink.R
import com.sky.siplink.core.logic.Formatters
import com.sky.siplink.core.model.RegState
import com.sky.siplink.ui.theme.Brand

/** Shows a transient message (snackbar) from anywhere below the app root. */
val LocalMessenger = staticCompositionLocalOf<(String) -> Unit> { {} }

/** Round avatar showing the initials of a name. */
@Composable
fun Avatar(name: String, modifier: Modifier = Modifier, size: Dp = 44.dp) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = Formatters.initials(name),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
            fontSize = (size.value * 0.36f).sp,
            maxLines = 1,
        )
    }
}

fun regStateColor(state: RegState): Color = when (state) {
    RegState.OK -> Brand.CallGreen
    RegState.PROGRESS -> Brand.Amber
    RegState.FAILED -> Brand.HangUpRed
    RegState.NONE, RegState.DISABLED -> Color(0xFF9E9E9E)
}

@Composable
fun regStateLabel(state: RegState): String = stringResource(
    when (state) {
        RegState.OK -> R.string.account_state_registered
        RegState.PROGRESS -> R.string.account_state_registering
        RegState.FAILED -> R.string.account_state_failed
        RegState.NONE -> R.string.account_state_none
        RegState.DISABLED -> R.string.account_state_disabled
    },
)

/** Registration indicator; breathes while a registration is in progress. */
@Composable
fun StatusDot(state: RegState, modifier: Modifier = Modifier, size: Dp = 10.dp) {
    val color = regStateColor(state)
    // The animated value is only read while drawing, so a breathing dot redraws without
    // recomposing its row: with many accounts registering at once that adds up.
    val breathing = if (state == RegState.PROGRESS) {
        rememberInfiniteTransition(label = "statusDot").animateFloat(
            initialValue = 1f,
            targetValue = 0.25f,
            animationSpec = infiniteRepeatable(tween(650, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "statusDotAlpha",
        )
    } else {
        null
    }
    Box(
        modifier
            .size(size)
            .graphicsLayer { alpha = breathing?.value ?: 1f }
            .clip(CircleShape)
            .background(color),
    )
}

/** Centered placeholder for empty lists; fades and rises in on first composition. */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val progress by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 120f),
        label = "emptyState",
    )
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 40.dp)
            .graphicsLayer {
                alpha = progress
                translationY = (1f - progress) * 48f
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(44.dp),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(24.dp))
            action()
        }
    }
}

/**
 * Makes an element give way a little while it is pressed and spring back when released. It only
 * watches the touch; the click itself is handled by whatever comes after it.
 */
fun Modifier.pressScale(pressed: Float = 0.94f): Modifier = composed {
    var down by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (down) pressed else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 700f),
        label = "pressScale",
    )
    graphicsLayer {
        scaleX = scale
        scaleY = scale
    }.pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            down = true
            waitForUpOrCancellation()
            down = false
        }
    }
}
