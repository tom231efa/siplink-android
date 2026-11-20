package com.sky.siplink.ui.call

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitDragOrCancellation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.VideocamOff
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.sky.siplink.R
import com.sky.siplink.core.logic.PreviewDock
import com.sky.siplink.core.logic.PreviewDock.Area
import com.sky.siplink.core.logic.PreviewDock.Place
import com.sky.siplink.core.logic.VideoRules
import com.sky.siplink.core.model.CallVideo
import com.sky.siplink.core.model.DockSide
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * What the picture of a video call and the controls over it share: how the picture of the other
 * side is shown, whether the two pictures are swapped, and where the controls stand (the small
 * picture keeps clear of them).
 */
@Stable
internal class VideoStageState {
    /** What the user picked with the fill key; null until then. */
    var fillChosen by mutableStateOf<Boolean?>(null)

    /** How much of the other side's picture would be cut off if it filled the screen ([VideoRules.cutWhenFilled]). */
    var cut by mutableFloatStateOf(0f)

    /** The user swapped the pictures: the own one large ([VideoRules.swapped]). */
    var swapChosen by mutableStateOf(false)

    /** Where the row of buttons at the top ends, in pixels from the top of the window; 0 before it was laid out. */
    var topRowBottom by mutableFloatStateOf(0f)

    /** Where the controls of the call stand in the window; null before they were laid out. */
    var controls by mutableStateOf<Rect?>(null)

    val filled: Boolean get() = fillChosen ?: VideoRules.fillsByDefault(cut)

    fun swapped(video: CallVideo): Boolean = VideoRules.swapped(swapChosen, video.sending, video.received != null)

    /** What the user chose for a video ends with it. */
    fun reset() {
        fillChosen = null
        swapChosen = false
    }
}

/**
 * The bands the small picture may stand in ([PreviewDock]): [shown] while the controls are on
 * the screen, [hidden] while they are not. Both keep [margin] from the edges, the system bars and
 * the cut-out.
 *
 * @param wide the phone lies on its side: the controls stand at the start, not at the bottom (the
 *   left, or the right in a right-to-left language: the band keeps clear of the side they are on)
 */
internal fun dockAreas(width: Float, height: Float, safe: Rect, margin: Float, stage: VideoStageState, wide: Boolean): Pair<Area, Area> {
    val hidden = Area(safe.left + margin, safe.top + margin, width - safe.right - margin, height - safe.bottom - margin)
    val controls = stage.controls
    val top = maxOf(hidden.top, stage.topRowBottom + margin)
    val shown = when {
        controls == null -> hidden.copy(top = top)
        wide && controls.center.x > width / 2 -> hidden.copy(right = minOf(hidden.right, controls.left - margin), top = top)
        wide -> hidden.copy(left = maxOf(hidden.left, controls.right + margin), top = top)
        else -> hidden.copy(top = top, bottom = maxOf(top, minOf(hidden.bottom, controls.top - margin)))
    }
    return shown to hidden
}

/** Where the small picture is: where it was put, or under the finger while it is dragged. */
@Stable
internal class SmallPictureState {
    val x = Animatable(0f)
    val y = Animatable(0f)

    /** Where the finger has it; null while it is not dragged. */
    var dragged by mutableStateOf<Offset?>(null)
    var placed = false

    /** What a picture let go now is placed by; the stage keeps it up to date as it is laid out. */
    var frame: Frame? = null

    /**
     * @param size of the small picture, in pixels
     * @param shown the band while the controls are shown
     * @param now the band the picture is in now
     * @param fling from this speed on (pixels per second), a picture let go goes the way it was flung
     */
    data class Frame(val width: Float, val height: Float, val shown: Area, val now: Area, val place: Place, val fling: Float)

    val offset: IntOffset get() = (dragged ?: Offset(x.value, y.value)).let { IntOffset(it.x.roundToInt(), it.y.roundToInt()) }
}

/**
 * Keeps the small picture at its spot: it takes it at once the first time, and moves there in
 * step with the fade of the controls afterwards (no spring: it does not overshoot).
 */
@Composable
internal fun FollowSpot(state: SmallPictureState, spot: PreviewDock.Spot, controlsShown: Boolean) {
    LaunchedEffect(spot) {
        if (state.dragged != null) return@LaunchedEffect
        if (!state.placed) {
            state.x.snapTo(spot.x)
            state.y.snapTo(spot.y)
            state.placed = true
            return@LaunchedEffect
        }
        val spec = tween<Float>(if (controlsShown) SHOW_MS else HIDE_MS)
        launch { state.x.animateTo(spot.x, spec) }
        state.y.animateTo(spot.y, spec)
    }
}

/**
 * The small picture (placed at [SmallPictureState.offset] by the caller): dragged by a finger and
 * let go at a side edge, swapped with the large one by a tap. A tap on it is never a tap on the
 * picture behind (the controls stay as they are), and a drag is no tap.
 *
 * @param scope where its moves run
 * @param frame what it is placed by now
 * @param labels what a screen reader says of it
 * @param own the small picture is the own camera's (not swapped)
 * @param onPlace the user put it somewhere else
 */
internal fun Modifier.smallPicture(
    state: SmallPictureState,
    scope: CoroutineScope,
    frame: SmallPictureState.Frame,
    labels: SmallPictureLabels,
    own: Boolean,
    canSwap: Boolean,
    onSwap: () -> Unit,
    onPlace: (Place) -> Unit,
): Modifier {
    fun put(target: Place, by: SmallPictureState.Frame) {
        onPlace(target)
        val spot = PreviewDock.spot(target, by.width, by.height, by.shown, by.now)
        scope.launch {
            val spec = tween<Float>(SHOW_MS)
            launch { state.x.animateTo(spot.x, spec) }
            state.y.animateTo(spot.y, spec)
        }
    }
    val place = frame.place
    return this
        .semantics {
            contentDescription = if (own) labels.own else labels.other
            if (canSwap) {
                onClick(label = labels.swap) {
                    onSwap()
                    true
                }
            }
            customActions = listOfNotNull(
                CustomAccessibilityAction(labels.left) { put(place.copy(side = DockSide.LEFT), frame); true }.takeIf { place.side == DockSide.RIGHT },
                CustomAccessibilityAction(labels.right) { put(place.copy(side = DockSide.RIGHT), frame); true }.takeIf { place.side == DockSide.LEFT },
                CustomAccessibilityAction(labels.up) { put(PreviewDock.moved(place, up = true), frame); true }.takeIf { PreviewDock.canMove(place, up = true) },
                CustomAccessibilityAction(labels.down) { put(PreviewDock.moved(place, up = false), frame); true }.takeIf { PreviewDock.canMove(place, up = false) },
            )
        }
        // Taken here, the tap never reaches the picture behind: the controls stay as they are.
        .pointerInput(canSwap) { detectTapGestures(onTap = { if (canSwap) onSwap() }) }
        // Never started anew while a finger has it: what it is let go into is read when it is let go.
        .pointerInput(state) {
            // A finger has the picture: let go once, whichever way the drag ends.
            var holding = false
            fun letGo(velocityX: Float) {
                if (!holding) return
                holding = false
                val at = state.dragged ?: return
                val by = state.frame ?: return
                val target = PreviewDock.release(at.x, at.y, velocityX, by.width, by.height, by.shown, by.now, by.fling)
                scope.launch {
                    state.x.snapTo(at.x)
                    state.y.snapTo(at.y)
                    state.dragged = null
                    put(target, by)
                }
            }
            try {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var overSlop = Offset.Zero
                    val first = awaitTouchSlopOrCancellation(down.id) { change, over ->
                        change.consume()
                        overSlop = over
                    } ?: return@awaitEachGesture
                    // The picture follows the finger from here on: its own way across the screen is the
                    // finger's, every sample the touch screen reported (several may come in one go).
                    val tracker = VelocityTracker()
                    var at = Offset(state.x.value, state.y.value) + overSlop
                    state.dragged = at
                    holding = true
                    tracker.addPosition(first.uptimeMillis, at)
                    var last = first
                    while (true) {
                        val change = awaitDragOrCancellation(last.id) ?: break
                        if (change.changedToUpIgnoreConsumed()) {
                            // A finger that rested before it was lifted flung nothing (as Android's own velocity
                            // tracker has it), told by the times the touch screen gave, not by when the app got to them.
                            val rested = change.uptimeMillis - last.uptimeMillis > RESTED_MS
                            letGo(if (rested) 0f else tracker.calculateVelocity().x)
                            return@awaitEachGesture
                        }
                        val before = at
                        change.historical.forEach { tracker.addPosition(it.uptimeMillis, before + (it.position - change.previousPosition)) }
                        at = before + change.positionChange()
                        change.consume()
                        state.dragged = at
                        tracker.addPosition(change.uptimeMillis, at)
                        last = change
                    }
                    // Taken by something else (the system's back gesture, say): it still ends at an edge.
                    letGo(0f)
                }
            } finally {
                // A drag cut off (the picture left the screen under the finger) still ends at an edge.
                letGo(0f)
            }
        }
        .clip(RoundedCornerShape(16.dp))
        .background(Color(0xFF202328))
}

/** What a screen reader says of the small picture and offers to do with it. */
internal class SmallPictureLabels(
    val own: String,
    val other: String,
    val swap: String,
    val left: String,
    val right: String,
    val up: String,
    val down: String,
) {
    companion object {
        @Composable
        fun current() = SmallPictureLabels(
            own = stringResource(R.string.video_own_picture),
            other = stringResource(R.string.video_other_picture),
            swap = stringResource(R.string.video_swap),
            left = stringResource(R.string.video_move_left),
            right = stringResource(R.string.video_move_right),
            up = stringResource(R.string.video_move_up),
            down = stringResource(R.string.video_move_down),
        )
    }
}

/** The buttons of a video call in the row at the top, after the record button: fill or whole, switch camera, camera on or off. */
@Composable
internal fun VideoButtons(video: CallVideo, stage: VideoStageState, onSwitchCamera: () -> Unit, onToggleCamera: () -> Unit) {
    // While the own picture is large, it is shown whole, and the key is of no use.
    if (video.receiving && video.received != null && VideoRules.canFill(stage.cut) && !stage.swapped(video)) {
        val filled = stage.filled
        TopButton(
            if (filled) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
            stringResource(if (filled) R.string.video_show_whole else R.string.video_fill),
        ) { stage.fillChosen = !filled }
    }
    if (video.sending) TopButton(Icons.Rounded.Cameraswitch, stringResource(R.string.video_switch_camera), onSwitchCamera)
    TopButton(
        if (video.sending) Icons.Rounded.Videocam else Icons.Rounded.VideocamOff,
        stringResource(if (video.sending) R.string.video_camera_off else R.string.video_camera_on),
        onToggleCamera,
    )
}

@Composable
private fun TopButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.32f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(22.dp))
    }
}

/** A finger still for longer than this before it is lifted flung nothing. */
private const val RESTED_MS = 40L

/** The controls come in this long, and the small picture moves with them. */
private const val SHOW_MS = 180

/** They go in this long. */
private const val HIDE_MS = 160
