package com.sky.siplink.ui.shortcuts

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.grid.LazyGridItemInfo
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.toOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Dragging an item of a lazy grid to another place, after the drag and drop demo of Compose
 * foundation (LazyGridDragAndDropDemo). A long press picks up the item under the finger, which
 * then follows the finger; whenever its middle is over another item it takes that item's place
 * ([onHover]), and the grid scrolls while it is held near the top or the bottom edge. The order
 * only lives in the caller while the finger is down: [onDrop] is told once, when it is lifted.
 *
 * Items are told apart by their keys in the grid. The grid is to have no horizontal content
 * padding: the place of an item is not told from the side of the grid, but from that padding.
 */
@Stable
internal class GridReorderState(
    private val grid: LazyGridState,
    private val scope: CoroutineScope,
    private val haptics: HapticFeedback,
) {
    /** Whether the item with this key may be picked up. */
    var canPick: (Any) -> Boolean = { false }

    /** Moves the picked-up item (first key) to the place of the other one; false when it may not go there. */
    var onHover: (Any, Any) -> Boolean = { _, _ -> false }

    /** The picked-up item with this key was let go. */
    var onDrop: (Any) -> Unit = {}

    /** The key of the item under the finger; null while nothing is picked up. */
    var draggedKey by mutableStateOf<Any?>(null)
        private set

    /** How far the finger moved since the item was picked up. */
    private var dragged by mutableStateOf(Offset.Zero)

    /** Where the item was in the grid when it was picked up. */
    private var start by mutableStateOf(Offset.Zero)

    /** The item that was let go last, while it glides into its place. */
    var releasedKey by mutableStateOf<Any?>(null)
        private set
    val released = Animatable(Offset.Zero, Offset.VectorConverter)

    private val draggedItem: LazyGridItemInfo?
        get() = draggedKey?.let { key -> grid.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key } }

    /** How far the picked-up item is drawn away from its place in the grid. */
    val draggedOffset: Offset
        get() = draggedItem?.let { start + dragged - it.offset.toOffset() } ?: Offset.Zero

    private fun itemAt(position: Offset, except: Any? = null): LazyGridItemInfo? =
        grid.layoutInfo.visibleItemsInfo.firstOrNull { item ->
            item.key != except &&
                position.x >= item.offset.x && position.x <= item.offset.x + item.size.width &&
                position.y >= item.offset.y && position.y <= item.offset.y + item.size.height
        }

    /** @param position where the finger is, from the top left of the grid */
    internal fun pick(position: Offset) {
        // The grid tells where its items are from the end of its padding at the top.
        val item = itemAt(position - Offset(0f, grid.layoutInfo.beforeContentPadding.toFloat())) ?: return
        if (!canPick(item.key)) return
        draggedKey = item.key
        start = item.offset.toOffset()
        dragged = Offset.Zero
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    internal fun drag(amount: Offset) {
        if (draggedKey == null) return
        dragged += amount
        hover()
    }

    /** Lets the picked-up item take the place of the one under its middle. */
    internal fun hover() {
        val item = draggedItem ?: return
        val middle = item.offset.toOffset() + draggedOffset + Offset(item.size.width / 2f, item.size.height / 2f)
        val target = itemAt(middle, except = item.key) ?: return
        // The grid keeps its first visible item where it is when items change places; with the
        // picked-up item involved that would carry the view along with it. Keep it by position.
        val first = grid.firstVisibleItemIndex
        if (target.index == first || item.index == first) grid.requestScrollToItem(first, grid.firstVisibleItemScrollOffset)
        onHover(item.key, target.key)
    }

    /** How far to scroll in one frame: as far as the picked-up item reaches past the top or the bottom of the grid. */
    internal fun edgeScroll(): Float {
        val item = draggedItem ?: return 0f
        val top = item.offset.y + draggedOffset.y
        val bottom = top + item.size.height
        val info = grid.layoutInfo
        val past = when {
            dragged.y > 0 -> (bottom - info.viewportEndOffset).coerceAtLeast(0f)
            dragged.y < 0 -> (top - info.viewportStartOffset).coerceAtMost(0f)
            else -> 0f
        }
        return (past / EDGE_DIVISOR).coerceIn(-MAX_EDGE_STEP, MAX_EDGE_STEP)
    }

    internal fun release() {
        val key = draggedKey ?: return
        val offset = draggedOffset
        draggedKey = null
        dragged = Offset.Zero
        onDrop(key)
        releasedKey = key
        // Started at once, so that the item is not drawn in its place for a frame before it glides there.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            released.snapTo(offset)
            released.animateTo(Offset.Zero, spring(stiffness = Spring.StiffnessMedium, visibilityThreshold = Offset.VisibilityThreshold))
            if (releasedKey == key) releasedKey = null
        }
    }

    private companion object {
        /** An item reaching past the edge by this many pixels scrolls one pixel a frame. */
        const val EDGE_DIVISOR = 4f
        const val MAX_EDGE_STEP = 40f
    }
}

/**
 * A [GridReorderState] for [grid], with the grid scrolling by itself while an item is held near
 * one of its edges.
 */
@Composable
internal fun rememberGridReorder(
    grid: LazyGridState,
    canPick: (Any) -> Boolean,
    onHover: (Any, Any) -> Boolean,
    onDrop: (Any) -> Unit,
): GridReorderState {
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val state = remember(grid) { GridReorderState(grid, scope, haptics) }
    SideEffect {
        state.canPick = canPick
        state.onHover = onHover
        state.onDrop = onDrop
    }
    LaunchedEffect(state) {
        snapshotFlow { state.draggedKey != null }.collectLatest { dragging ->
            while (dragging) {
                withFrameNanos { }
                val step = state.edgeScroll()
                if (step != 0f) {
                    grid.scrollBy(step)
                    state.hover()
                }
            }
        }
    }
    return state
}

/** Picks up an item of the grid on a long press and drags it; to be set on the grid. Does nothing while [enabled] is false. */
internal fun Modifier.reorderable(state: GridReorderState, enabled: Boolean): Modifier =
    if (!enabled) {
        this
    } else {
        pointerInput(state) {
            detectDragGesturesAfterLongPress(
                onDragStart = state::pick,
                onDrag = { change, amount ->
                    if (state.draggedKey != null) change.consume()
                    state.drag(amount)
                },
                onDragEnd = state::release,
                onDragCancel = state::release,
            )
        }
    }
