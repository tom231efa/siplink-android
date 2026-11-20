package com.sky.siplink.ui.call

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.sky.siplink.R
import com.sky.siplink.ui.components.LeftToRight
import com.sky.siplink.ui.theme.Brand
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val TrackHeight = 78.dp
private val DotSize = 62.dp
private val TrackPadding = 8.dp
private val MaxTrackWidth = 320.dp
private val EndIconSize = 30.dp

/** How far the dot has to be dragged (share of the way) for the choice to count. */
private const val COMMIT_AT = 0.6f

/** A quick flick counts from this much of the way on ("quick" being the velocity below, in px per second). */
private const val FLICK_FROM = 0.2f
private const val FLICK_VELOCITY = 1500f

/**
 * Incoming call: a white dot on a glass track, with "hang up" at its left end and "answer" at
 * its right end, in a right-to-left language too. Sliding the dot to one of them does that; the dot and the track take on the
 * colour of where it is heading on the way. Nothing moves before it is touched.
 */
@Composable
internal fun SlideToAnswer(onAnswer: () -> Unit, onDecline: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val answer by rememberUpdatedState(onAnswer)
    val decline by rememberUpdatedState(onDecline)

    /**
     * Position of the dot in pixels from the middle; negative is towards "hang up". The finger
     * sets it as it moves, at once: when it lifts, the dot is where the finger left it. (Set
     * through a coroutine it once came in after the lifting, the last movement of a finger that
     * was still moving, and stopped the dot on its way to the end: the call was never answered.)
     */
    var position by remember { mutableFloatStateOf(0f) }
    var decided by remember { mutableStateOf(false) }

    /** The dot moving by itself: on to the end it was let go towards, or back to the middle. */
    var gliding by remember { mutableStateOf<Job?>(null) }
    fun glide(target: Float, spec: AnimationSpec<Float>): Job {
        gliding?.cancel()
        return scope.launch { animate(position, target, animationSpec = spec) { value, _ -> position = value } }.also { gliding = it }
    }

    // An answer can be held up (microphone permission refused): give the dot back then.
    LaunchedEffect(decided) {
        if (decided) {
            delay(1500)
            glide(0f, tween(160)).join()
            decided = false
        }
    }

    val description = stringResource(R.string.call_slide_description)
    val answerLabel = stringResource(R.string.call_slide_answer)
    val declineLabel = stringResource(R.string.call_slide_decline)
    // Right answers and left hangs up in every language, as the text of the slide says.
    LeftToRight {
        BoxWithConstraints(
            modifier
                .widthIn(max = MaxTrackWidth)
                .fillMaxWidth()
                .height(TrackHeight),
        ) {
            val reach = with(LocalDensity.current) { ((maxWidth - DotSize) / 2 - TrackPadding).toPx() }

            /** -1 (hang up) .. 0 (at rest) .. 1 (answer). Read while drawing, so dragging does not recompose. */
            val progress = { (position / reach).coerceIn(-1f, 1f) }

            Box(
                Modifier
                    .fillMaxSize()
                    .drawBehind {
                        val p = progress()
                        val towards = if (p >= 0f) Brand.CallGreen else Brand.HangUpRed
                        drawRoundRect(
                            lerp(Color.White.copy(alpha = 0.14f), towards.copy(alpha = 0.34f), abs(p)),
                            cornerRadius = CornerRadius(size.height / 2),
                        )
                    }
                    .semantics(mergeDescendants = true) {
                        contentDescription = description
                        customActions = listOf(
                            CustomAccessibilityAction(answerLabel) {
                                answer()
                                true
                            },
                            CustomAccessibilityAction(declineLabel) {
                                decline()
                                true
                            },
                        )
                    }
                    .draggable(
                        orientation = Orientation.Horizontal,
                        enabled = !decided,
                        // The finger belongs to the slide from the moment it is down. Waiting for it
                        // to have moved sideways first leaves the start of the movement to whatever
                        // scrolls around the slide (the controls of a phone on its side do), and a
                        // finger that also goes up or down a little then never moves the dot.
                        startDragImmediately = true,
                        onDragStarted = { gliding?.cancel() },
                        state = rememberDraggableState { delta -> position = (position + delta).coerceIn(-reach, reach) },
                        onDragStopped = { velocity ->
                            val p = progress()
                            val flicked = abs(velocity) > FLICK_VELOCITY && abs(p) > FLICK_FROM && velocity * p > 0f
                            if (abs(p) >= COMMIT_AT || flicked) {
                                decided = true
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                // At once: the call does not wait for the dot to arrive.
                                if (p > 0f) answer() else decline()
                                glide(if (p > 0f) reach else -reach, tween(110))
                            } else {
                                // Straight back, without a bounce.
                                glide(0f, spring(dampingRatio = 1f, stiffness = 700f))
                            }
                        },
                    ),
            ) {
                End(Icons.Rounded.CallEnd, Modifier.align(Alignment.CenterStart)) { -progress() }
                End(Icons.Rounded.Call, Modifier.align(Alignment.CenterEnd)) { progress() }
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .offset { IntOffset(position.roundToInt(), 0) }
                        .size(DotSize)
                        .drawBehind {
                            val p = progress()
                            drawCircle(lerp(Color.White, if (p >= 0f) Brand.CallGreen else Brand.HangUpRed, abs(p)))
                        },
                )
            }
        }
    }
}

/**
 * The icon at one end of the track. It grows clearer as the dot comes towards it and fades as
 * the dot goes the other way; [approach] is 1 when the dot has arrived and -1 at the far end.
 */
@Composable
private fun End(icon: ImageVector, modifier: Modifier, approach: () -> Float) {
    Icon(
        icon,
        contentDescription = null,
        tint = Color.White,
        modifier = modifier
            .padding(horizontal = 24.dp)
            .size(EndIconSize)
            .graphicsLayer { alpha = (0.7f + approach() * 0.5f).coerceIn(0.2f, 1f) },
    )
}
