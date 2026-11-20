package com.sky.siplink.ui.shortcuts

import android.content.res.Resources
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sky.siplink.R
import com.sky.siplink.core.logic.ShortcutLines
import com.sky.siplink.core.logic.ShortcutRules
import com.sky.siplink.core.model.DndMode
import com.sky.siplink.core.model.Shortcut
import com.sky.siplink.core.model.ShortcutAction
import com.sky.siplink.core.model.ShortcutIcon
import com.sky.siplink.core.model.SipAccount
import com.sky.siplink.core.render
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
    val context = LocalContext.current
    val resources = LocalResources.current
    val engine = context.engine
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
        line.isNotEmpty() -> stringResource(R.string.shortcut_line_gone, line)
        lineChecked -> stringResource(R.string.form_required)
        else -> null
    }

    fun error(vararg candidates: ShortcutRules.Error): String? = errors.firstOrNull { it in candidates }?.message?.let(context::render)

    val added = stringResource(R.string.shortcut_added)
    val saved = stringResource(R.string.shortcut_saved)
    val deleted = stringResource(R.string.shortcut_deleted)
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
            messenger(if (isNew) added else saved)
            onDismiss()
        } else {
            // What has no field of the sheet to stand under.
            errors.firstOrNull { it == ShortcutRules.Error.MANAGED || it == ShortcutRules.Error.OWN_LIMIT_REACHED }?.let { messenger(context.render(it.full)) }
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
                    Text(
                        stringResource(if (isNew) R.string.shortcut_new_title else R.string.shortcut_edit_title),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    ManagedLock()
                }

                FormField(
                    value = label,
                    onValueChange = { label = it },
                    label = stringResource(R.string.shortcut_field_name),
                    error = error(ShortcutRules.Error.LABEL_TOO_LONG),
                )
                AnimatedVisibility(action.hasNumber) {
                    FormField(
                        value = number,
                        onValueChange = { number = it },
                        label = when (action) {
                            ShortcutAction.DTMF -> stringResource(R.string.shortcut_field_keys)
                            ShortcutAction.FORWARD -> stringResource(R.string.shortcut_field_forward_to)
                            else -> stringResource(R.string.shortcut_field_number)
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
                        value = stringResource(action.label),
                        label = stringResource(R.string.shortcut_field_type),
                        options = ShortcutAction.entries,
                        optionLabel = { resources.getString(it.label) },
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
                            value = lineModeLabel(resources, fixedLine),
                            label = stringResource(R.string.shortcut_field_line_mode),
                            options = listOf(false, true),
                            optionLabel = { lineModeLabel(resources, it) },
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
                        value = lineAccount?.let { lineLabel(resources, it) } ?: line,
                        label = stringResource(R.string.shortcut_field_line),
                        options = accounts,
                        optionLabel = { lineLabel(resources, it) },
                        onSelect = { line = ShortcutLines.of(it) },
                        selected = lineAccount,
                        error = lineError,
                    )
                }

                Text(stringResource(R.string.shortcut_field_icon), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
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
                        IconChoice(shortcutIcon(action), stringResource(R.string.shortcut_icon_default), selected = icon == null, enabled = !locked) {
                            icon = null
                        }
                    }
                    items(ShortcutIcon.entries, key = { it.name }) { choice ->
                        IconChoice(shortcutIcon(choice), stringResource(choice.label), selected = icon == choice, enabled = !locked) { icon = choice }
                    }
                }

                // No category, the ones there are, and (null) the way to make another.
                DropdownField(
                    value = categoryLabel(resources, category),
                    label = stringResource(R.string.shortcut_field_category),
                    options = listOf("") + (categories + madeCategories).distinct() + null,
                    optionLabel = { categoryLabel(resources, it) },
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
                                        messenger(deleted)
                                        onDismiss()
                                    } else {
                                        confirmDelete = true
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(
                                    stringResource(if (confirmDelete) R.string.common_confirm_delete else R.string.common_delete),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                        Button(onClick = ::save, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.common_save)) }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }

    if (makingCategory) {
        CategoryNameDialog(
            title = stringResource(R.string.shortcut_new_category_title),
            initial = "",
            taken = categories + madeCategories,
            confirm = stringResource(R.string.shortcut_create),
            onDismiss = { makingCategory = false },
            onConfirm = { name ->
                madeCategories = madeCategories + name
                category = name
                makingCategory = false
            },
        )
    }
}

/** A choice of the category list: a category, none (empty), or the way to make another (null). */
private fun categoryLabel(resources: Resources, category: String?): String = when {
    category == null -> resources.getString(R.string.shortcut_category_new)
    category.isEmpty() -> resources.getString(R.string.shortcut_category_none)
    else -> category
}

private fun categoryIcon(category: String?): ImageVector = when {
    category == null -> Icons.Rounded.CreateNewFolder
    category.isEmpty() -> Icons.Rounded.FolderOff
    else -> Icons.Rounded.Folder
}

/** A key either asks for its line whenever it calls out, or keeps to one. */
private fun lineModeLabel(resources: Resources, fixed: Boolean): String =
    resources.getString(if (fixed) R.string.shortcut_line_fixed else R.string.shortcut_line_ask)

private fun lineLabel(resources: Resources, account: SipAccount): String {
    val name = if (account.name.isBlank()) account.identity else resources.getString(R.string.shortcut_line_named, account.name, account.identity)
    return if (account.enabled) name else resources.getString(R.string.shortcut_line_disabled, name)
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
