package com.sky.siplink.ui.shortcuts

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sky.siplink.core.logic.ShortcutRules
import com.sky.siplink.core.model.Shortcut
import com.sky.siplink.core.model.ShortcutAction
import com.sky.siplink.engine
import com.sky.siplink.ui.components.pressScale

/**
 * The board of keys with its search field and category chips. Used by the shortcut screen and,
 * during a call, by the sheet of the call screen.
 *
 * @param all every key, not empty
 * @param category the chosen category, null for all of them
 * @param editing marks the keys as editable; what a tap does is up to [onKey]
 */
@Composable
internal fun ShortcutBoard(
    all: List<Shortcut>,
    category: String?,
    onCategoryChange: (String?) -> Unit,
    editing: Boolean,
    onKey: (Shortcut) -> Unit,
    modifier: Modifier = Modifier,
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

    Column(modifier) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            placeholder = { Text("搜索名称、号码、分类或类型") },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Clear, contentDescription = "清除") }
                }
            },
            singleLine = true,
            shape = CircleShape,
        )

        if (categories.size > 1 || categories.first().first.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
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
            Spacer(Modifier.height(12.dp))
        }

        if (shown.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("没有匹配的快捷键", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 96.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(shown, key = { it.id }, contentType = { "key" }) { shortcut ->
                    ShortcutKey(
                        shortcut = shortcut,
                        editing = editing,
                        lit = when (shortcut.action) {
                            ShortcutAction.DND -> settings.doNotDisturb
                            ShortcutAction.FORWARD -> settings.forwardAlways == shortcut.number
                            else -> false
                        },
                        // Forwarding belongs to a managed configuration: the key is grey, and a tap says why.
                        dimmed = managed && shortcut.action == ShortcutAction.FORWARD,
                        onClick = { onKey(shortcut) },
                        modifier = Modifier.animateItem(),
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
                Text(
                    shortcut.label,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
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
