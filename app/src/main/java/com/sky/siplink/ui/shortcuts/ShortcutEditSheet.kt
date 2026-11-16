package com.sky.siplink.ui.shortcuts

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sky.siplink.core.logic.ShortcutLines
import com.sky.siplink.core.logic.ShortcutRules
import com.sky.siplink.core.model.DndMode
import com.sky.siplink.core.model.Shortcut
import com.sky.siplink.core.model.ShortcutAction
import com.sky.siplink.core.model.ShortcutIcon
import com.sky.siplink.core.model.SipAccount
import com.sky.siplink.engine
import com.sky.siplink.ui.components.AppBottomSheet
import com.sky.siplink.ui.components.DropdownField
import com.sky.siplink.ui.components.FormField
import com.sky.siplink.ui.components.LocalConfigLocked
import com.sky.siplink.ui.components.LocalMessenger
import com.sky.siplink.ui.components.ManagedLock
import com.sky.siplink.ui.components.pressScale

/** Offers to make a new key of a contact or a call log entry. */
@Composable
fun NewShortcutSheet(name: String, number: String, onDismiss: () -> Unit) {
    val draft = remember(name, number) {
        Shortcut(id = NEW_KEY, label = name.take(ShortcutRules.MAX_LABEL_LENGTH), number = number)
    }
    ShortcutEditSheet(draft, onDismiss)
}

/**
 * Creates a key ([NEW_KEY]) or changes / deletes an existing one. Laid out like the function
 * key table of a desk phone: name, value, type and line, each a plain field or a list.
 *
 * Where the configuration is locked it is for the user's own keys ([Shortcut.own]) and for new
 * ones, which become such. The screens do not open it for a key that was handed out; should
 * the configuration be locked while it shows one, it goes on showing the key and can no longer
 * save or delete it.
 */
@Composable
internal fun ShortcutEditSheet(shortcut: Shortcut, onDismiss: () -> Unit) {
    val engine = LocalContext.current.engine
    val manager = engine.shortcuts
    val all by manager.shortcuts.collectAsStateWithLifecycle()
    val accounts by engine.accounts.accounts.collectAsStateWithLifecycle()
    val settings by engine.settings.settings.collectAsStateWithLifecycle()
    val messenger = LocalMessenger.current
    val isNew = shortcut.id == NEW_KEY
    val locked = LocalConfigLocked.current && !isNew && !shortcut.own
    val categories = remember(all) { ShortcutRules.categories(all).map { it.first }.filter { it.isNotEmpty() } }

    var label by remember { mutableStateOf(shortcut.label) }
    var number by remember { mutableStateOf(shortcut.number) }
    var action by remember { mutableStateOf(shortcut.action) }
    var category by remember { mutableStateOf(shortcut.category) }
    // A category exists as long as a key is in it. One made here is offered until the sheet
    // closes, and stays if this key ends up in it.
    var madeCategories by remember { mutableStateOf(listOfNotNull(shortcut.category.ifEmpty { null })) }
    var makingCategory by remember { mutableStateOf(false) }
    var icon by remember { mutableStateOf(shortcut.icon) }
    var fixedLine by remember { mutableStateOf(shortcut.line.isNotEmpty()) }
    var line by remember { mutableStateOf(shortcut.line) }
    var lineChecked by remember { mutableStateOf(false) }
    var errors by remember { mutableStateOf(emptyList<ShortcutRules.Error>()) }
    var confirmDelete by remember { mutableStateOf(false) }

    val usesLine = ShortcutRules.usesLine(action, serverDnd = settings.dndMode == DndMode.SERVER)
    val lineAccount = remember(line, accounts) { ShortcutLines.resolve(line, accounts) }
    val lineError = when {
        lineAccount != null -> null
        // The account was removed, or the key came from an import that named one this phone lacks.
        line.isNotEmpty() -> "线路“$line”已不存在"
        lineChecked -> "必填"
        else -> null
    }

    fun error(vararg candidates: ShortcutRules.Error): String? = errors.firstOrNull { it in candidates }?.message

    fun save() {
        val keepsLine = usesLine && fixedLine
        if (keepsLine && lineAccount == null) {
            lineChecked = true
            return
        }
        val draft = shortcut.copy(
            label = label,
            number = number,
            action = action,
            category = category,
            line = if (keepsLine) line else "",
            icon = icon,
        )
        errors = if (isNew) manager.add(draft) else manager.update(draft)
        if (errors.isEmpty()) {
            messenger(if (isNew) "快捷键已添加" else "快捷键已保存")
            onDismiss()
        } else {
            // What has no field of the sheet to stand under.
            errors.firstOrNull { it == ShortcutRules.Error.MANAGED || it == ShortcutRules.Error.OWN_LIMIT_REACHED }?.let { messenger(it.full) }
        }
    }

    AppBottomSheet(onDismiss) {
        // The fields go by what this key is, not by the screen the sheet stands on.
        CompositionLocalProvider(LocalConfigLocked provides locked) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = SheetPadding),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (isNew) "新建快捷键" else "编辑快捷键", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    ManagedLock()
                }

                FormField(
                    value = label,
                    onValueChange = { label = it },
                    label = "名称",
                    error = error(ShortcutRules.Error.LABEL_TOO_LONG),
                )
                AnimatedVisibility(action.hasNumber) {
                    FormField(
                        value = number,
                        onValueChange = { number = it },
                        label = when (action) {
                            ShortcutAction.DTMF -> "按键"
                            ShortcutAction.FORWARD -> "前转到"
                            else -> "号码或功能码"
                        },
                        keyboardType = KeyboardType.Phone,
                        error = error(
                            ShortcutRules.Error.NUMBER_EMPTY,
                            ShortcutRules.Error.NUMBER_INVALID,
                            ShortcutRules.Error.NUMBER_TOO_LONG,
                            ShortcutRules.Error.DTMF_INVALID,
                            ShortcutRules.Error.LIMIT_REACHED,
                        ),
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    DropdownField(
                        value = action.label,
                        label = "类型",
                        options = ShortcutAction.entries,
                        optionLabel = { it.label },
                        onSelect = {
                            action = it
                            errors = emptyList()
                        },
                        modifier = Modifier.weight(1f),
                        selected = action,
                        leading = { Icon(shortcutIcon(it), contentDescription = null, modifier = Modifier.size(20.dp)) },
                    )
                    if (usesLine) {
                        DropdownField(
                            value = lineModeLabel(fixedLine),
                            label = "出局线路",
                            options = listOf(false, true),
                            optionLabel = ::lineModeLabel,
                            onSelect = {
                                fixedLine = it
                                lineChecked = false
                            },
                            modifier = Modifier.weight(1f),
                            selected = fixedLine,
                        )
                    }
                }
                AnimatedVisibility(usesLine && fixedLine) {
                    DropdownField(
                        value = lineAccount?.let(::lineLabel) ?: line,
                        label = "线路",
                        options = accounts,
                        optionLabel = ::lineLabel,
                        onSelect = { line = ShortcutLines.of(it) },
                        selected = lineAccount,
                        error = lineError,
                    )
                }

                Text("图标", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
                // The row of icons runs to the edges of the sheet, so that the one cut off at the
                // edge reads as "more to the side" rather than as something clipped by mistake.
                LazyHorizontalGrid(
                    rows = GridCells.Fixed(2),
                    modifier = Modifier
                        .bleed(SheetPadding)
                        .fillMaxWidth()
                        .height(IconChoiceSize * 2 + 8.dp),
                    contentPadding = PaddingValues(horizontal = SheetPadding),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // First the picture of the type, which is what a key shows when none is chosen.
                    item(key = "type") {
                        IconChoice(shortcutIcon(action), "默认（随类型）", selected = icon == null, enabled = !locked) { icon = null }
                    }
                    items(ShortcutIcon.entries, key = { it.name }) { choice ->
                        IconChoice(shortcutIcon(choice), choice.label, selected = icon == choice, enabled = !locked) { icon = choice }
                    }
                }

                // No category, the ones there are, and (null) the way to make another.
                DropdownField(
                    value = categoryLabel(category),
                    label = "分类",
                    options = listOf("") + (categories + madeCategories).distinct() + null,
                    optionLabel = ::categoryLabel,
                    onSelect = { if (it == null) makingCategory = true else category = it },
                    selected = category,
                    error = error(ShortcutRules.Error.CATEGORY_TOO_LONG),
                    leading = { Icon(categoryIcon(it), contentDescription = null, modifier = Modifier.size(20.dp)) },
                )

                if (!locked) {
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (!isNew) {
                            OutlinedButton(
                                onClick = {
                                    if (confirmDelete) {
                                        manager.remove(listOf(shortcut.id))
                                        messenger("快捷键已删除")
                                        onDismiss()
                                    } else {
                                        confirmDelete = true
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(if (confirmDelete) "再点一次确认删除" else "删除", color = MaterialTheme.colorScheme.error)
                            }
                        }
                        Button(onClick = ::save, modifier = Modifier.weight(1f)) { Text("保存") }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }

    if (makingCategory) {
        CategoryNameDialog(
            title = "新建分类",
            initial = "",
            taken = categories + madeCategories,
            confirm = "创建",
            onDismiss = { makingCategory = false },
            onConfirm = { name ->
                madeCategories = madeCategories + name
                category = name
                makingCategory = false
            },
        )
    }
}

private fun categoryLabel(category: String?): String = when {
    category == null -> "新建分类"
    category.isEmpty() -> "未分类"
    else -> category
}

private fun categoryIcon(category: String?): ImageVector = when {
    category == null -> Icons.Rounded.CreateNewFolder
    category.isEmpty() -> Icons.Rounded.FolderOff
    else -> Icons.Rounded.Folder
}

/** A key either asks for its line whenever it calls out, or keeps to one. */
private fun lineModeLabel(fixed: Boolean): String = if (fixed) "固定线路" else "拨出时选择"

private fun lineLabel(account: SipAccount): String {
    val name = if (account.name.isBlank()) account.identity else "${account.name} · ${account.identity}"
    return if (account.enabled) name else "$name · 已停用"
}

private val IconChoiceSize = 46.dp

/** The space between the edges of the sheet and its fields. */
private val SheetPadding = 24.dp

/** Widens an element by [by] on either side, into the padding of what holds it. Needs a bounded width. */
private fun Modifier.bleed(by: Dp): Modifier = layout { measurable, constraints ->
    val extra = if (constraints.hasBoundedWidth) by.roundToPx() * 2 else 0
    val placeable = measurable.measure(
        constraints.copy(minWidth = constraints.minWidth + extra, maxWidth = constraints.maxWidth + extra),
    )
    layout(placeable.width - extra, placeable.height) { placeable.place(-extra / 2, 0) }
}

/** One picture of the icon list; the chosen one is filled in the accent colour. */
@Composable
private fun IconChoice(icon: ImageVector, name: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val container by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
        label = "iconChoice",
    )
    val content by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "iconChoiceTint",
    )
    Box(
        Modifier
            .size(IconChoiceSize)
            .pressScale()
            .clip(CircleShape)
            .background(container)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = name, tint = content, modifier = Modifier.size(24.dp))
    }
}
