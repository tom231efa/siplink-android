package com.sky.siplink.ui.shortcuts

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sky.siplink.core.logic.ShortcutRules
import com.sky.siplink.core.model.Shortcut
import com.sky.siplink.core.model.ShortcutAction
import com.sky.siplink.engine
import com.sky.siplink.ui.components.FadingText
import com.sky.siplink.ui.components.pressScale

/**
 * The board of keys with its search field and category chips. Used by the shortcut screen and,
 * during a call, by the sheet of the call screen.
 *
 * @param all every key, not empty
 * @param category the chosen category, null for all of them
 * @param editing marks the keys as editable; what a tap does is up to [onKey]
 * @param onMove where the keys can be put in order: in edit mode, while nothing is searched for,
 *   a key picked up by a long press and dropped on another one is to take its place
 *   ([ShortcutRules.move]). Under a managed configuration only the user's own keys move.
 */
@Composable
internal fun ShortcutBoard(
    all: List<Shortcut>,
    category: String?,
    onCategoryChange: (String?) -> Unit,
    editing: Boolean,
    onKey: (Shortcut) -> Unit,
    modifier: Modifier = Modifier,
    onMove: ((movedId: Long, targetId: Long) -> Unit)? = null,
) {
    val engine = LocalContext.current.engine
    val settings by engine.settings.settings.collectAsStateWithLifecycle()
    // Read from the engine rather than the screen: the board is also shown during a call.
    val managed by engine.lock.managed.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }

    // 2000 keys filter quickly, but not on every recomposition.
    val categories = remember(all) { ShortcutRules.categories(all) }
    val shown = remember(all, query, category) { ShortcutRules.filter(all, query, category) }
    LaunchedEffect(categories) {
        if (category != null && categories.none { it.first == category }) onCategoryChange(null)
    }

    // While a key is dragged the board shows the order it would have; it is stored once the key is dropped.
    var dragOrder by remember(shown) { mutableStateOf<List<Shortcut>?>(null) }
    val order = dragOrder ?: shown
    val grid = rememberLazyGridState()
    val keyShape = MaterialTheme.shapes.medium
    val reorder = rememberGridReorder(
        grid = grid,
        canPick = { key -> !managed || order.any { it.id == key && it.own } },
        onHover = { moved, target ->
            val reordered = ShortcutRules.move(order, moved as Long, target as Long, ownOnly = managed)
            if (reordered != null) dragOrder = reordered
            reordered != null
        },
        onDrop = { moved ->
            dragOrder?.let { after ->
                ShortcutRules.dropTarget(shown.map { it.id }, after.map { it.id }, moved as Long)
                    ?.let { target -> onMove?.invoke(moved, target) }
            }
            dragOrder = null
        },
    )

    Column(modifier) {
        SearchField(
            value = query,
            onValueChange = { query = it },
            placeholder = "搜索名称、号码、分类或类型",
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        )

        if (categories.size > 1 || categories.first().first.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(key = "all", contentType = "chip") {
                    FilterChip(
                        selected = category == null,
                        onClick = { onCategoryChange(null) },
                        label = { Text("全部 ${all.size}") },
                    )
                }
                items(categories, key = { "c:${it.first}" }, contentType = { "chip" }) { (name, count) ->
                    FilterChip(
                        selected = category == name,
                        onClick = { onCategoryChange(if (category == name) null else name) },
                        label = { Text("${name.ifEmpty { "未分类" }} $count") },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        } else {
            Spacer(Modifier.height(8.dp))
        }

        if (shown.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("无匹配的快捷键", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 96.dp),
                state = grid,
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding()
                    // Outside the content padding: dragging finds a key by its place counted from the side of the grid.
                    .padding(horizontal = 16.dp)
                    .reorderable(reorder, enabled = editing && onMove != null && query.isBlank()),
                contentPadding = PaddingValues(bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(order, key = { it.id }, contentType = { "key" }) { shortcut ->
                    val dragged = reorder.draggedKey == shortcut.id
                    val released = reorder.releasedKey == shortcut.id
                    ShortcutKey(
                        shortcut = shortcut,
                        editing = editing,
                        lit = when (shortcut.action) {
                            ShortcutAction.DND -> settings.doNotDisturb
                            ShortcutAction.FORWARD -> settings.forwardAlways == shortcut.number
                            else -> false
                        },
                        // Under a managed configuration forwarding belongs to it, and in edit mode so does
                        // every key that is not the user's own: such a key is grey, and a tap says why.
                        dimmed = managed && (shortcut.action == ShortcutAction.FORWARD || (editing && !shortcut.own)),
                        // A key that was picked up is not opened when it is let go.
                        onClick = { if (!dragged) onKey(shortcut) },
                        modifier = when {
                            dragged -> Modifier
                                .zIndex(1f)
                                .graphicsLayer {
                                    val offset = reorder.draggedOffset
                                    translationX = offset.x
                                    translationY = offset.y
                                    shadowElevation = 8.dp.toPx()
                                    shape = keyShape
                                }
                            released -> Modifier
                                .zIndex(1f)
                                .graphicsLayer {
                                    val offset = reorder.released.value
                                    translationX = offset.x
                                    translationY = offset.y
                                }
                            else -> Modifier.animateItem()
                        },
                    )
                }
            }
        }
    }
}

/**
 * One key of the board: its picture and the name the user gave it, nothing else. A lit key is a
 * switch that is currently on (do not disturb, forwarding).
 *
 * @param dimmed drawn grey although it can be tapped: the key has nothing to do, and the tap says why
 */
@Composable
private fun ShortcutKey(
    shortcut: Shortcut,
    editing: Boolean,
    lit: Boolean,
    dimmed: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container by animateColorAsState(
        if (lit) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        label = "keyColor",
    )
    Surface(
        modifier = modifier
            .height(84.dp)
            .pressScale(),
        shape = MaterialTheme.shapes.medium,
        color = container,
        border = if (editing) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null,
    ) {
        Box(Modifier.clickable(onClickLabel = shortcut.action.label, onClick = onClick)) {
            Column(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = if (dimmed) 0.38f else 1f }
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    shortcutIcon(shortcut),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp),
                )
                Spacer(Modifier.height(6.dp))
                FadingText(
                    shortcut.label,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 2,
                    textAlign = TextAlign.Center,
                )
            }
            if (editing) {
                Icon(
                    Icons.Rounded.Edit,
                    contentDescription = "编辑",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(14.dp),
                )
            }
        }
    }
}

/** The search field of the board: a compact pill, smaller than a form field, with a smaller text and icons. */
@Composable
private fun SearchField(value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.height(SEARCH_HEIGHT),
        singleLine = true,
        textStyle = textStyle,
        cursorBrush = SolidColor(colors.primary),
        decorationBox = { field ->
            Row(
                Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(colors.surfaceContainerHigh)
                    .padding(start = 14.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Search, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        FadingText(placeholder, style = textStyle, color = colors.onSurfaceVariant)
                    }
                    field()
                }
                if (value.isNotEmpty()) {
                    IconButton(onClick = { onValueChange("") }, modifier = Modifier.size(34.dp)) {
                        Icon(Icons.Rounded.Clear, contentDescription = "清除", modifier = Modifier.size(18.dp))
                    }
                }
            }
        },
    )
}

private val SEARCH_HEIGHT = 42.dp
