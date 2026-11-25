package com.sky.siplink.ui.shortcuts

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sky.siplink.R
import com.sky.siplink.core.logic.ManagedConfig
import com.sky.siplink.core.logic.ShortcutRules
import com.sky.siplink.core.model.Phrase
import com.sky.siplink.core.model.Shortcut
import com.sky.siplink.core.render
import com.sky.siplink.engine
import com.sky.siplink.openCallScreen
import com.sky.siplink.ui.components.CsvImportSheet
import com.sky.siplink.ui.components.EmptyState
import com.sky.siplink.ui.components.ImportOutcome
import com.sky.siplink.ui.components.LocalConfigLocked
import com.sky.siplink.ui.components.LocalMessenger
import com.sky.siplink.ui.components.PageTopBar
import com.sky.siplink.ui.components.importReport
import com.sky.siplink.ui.components.stringOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The shortcut keys, reached from the dial pad: a searchable, grouped board of programmable
 * keys like the function keys of a desk phone. Tapping a key runs it; in edit mode it opens the
 * key instead. Where the configuration is locked the keys that were handed out only run (and
 * can be exported); the user makes, changes and removes keys of their own next to them
 * ([Shortcut.own]), nothing is imported, and only a category of their own keys is renamed.
 *
 * @param accountId the account chosen on the dial pad, offered first when a key asks for its line
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShortcutsScreen(accountId: String?, onBack: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val manager = context.engine.shortcuts
    val all by manager.shortcuts.collectAsStateWithLifecycle()
    val loaded by manager.loaded.collectAsStateWithLifecycle()
    val messenger = LocalMessenger.current
    val locked = LocalConfigLocked.current
    val scope = rememberCoroutineScope()
    val runner = rememberShortcutRunner(accountId) { outcome ->
        if (outcome.showCall) context.openCallScreen()
        outcome.notice?.let(messenger)
    }

    var category by rememberSaveable { mutableStateOf<String?>(null) }
    var editing by rememberSaveable { mutableStateOf(false) }
    val ownCount = remember(all) { all.count { it.own } }
    // What edit mode has to work on: every key, or under a managed configuration the user's own ones.
    val editable = if (locked) ownCount > 0 else all.isNotEmpty()
    var edited by remember { mutableStateOf<Shortcut?>(null) }
    var importOpen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<String?>(null) }
    val newKey = {
        if (locked && !ShortcutRules.canAddOwn(all)) {
            messenger(resources.render(ShortcutRules.Error.OWN_LIMIT_REACHED.full))
        } else {
            edited = Shortcut(id = NEW_KEY, label = "", category = category.orEmpty())
        }
    }

    val exportFailed = stringResource(R.string.shortcut_export_failed)
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = manager.export()
        scope.launch {
            val written = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } }.getOrNull()
            }
            messenger(
                if (written == null) exportFailed else resources.render(Phrase.count(R.plurals.shortcut_exported, all.size)),
            )
        }
    }

    Column(Modifier.fillMaxSize()) {
        PageTopBar {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.shortcut_title))
                        // Under a managed configuration what counts is how many keys of their own the user has.
                        Text(
                            if (locked) {
                                stringOf(R.string.shortcut_count_of, ownCount.toString(), ShortcutRules.MAX_OWN.toString())
                            } else {
                                stringOf(R.string.shortcut_count_of, all.size.toString(), ShortcutRules.MAX_SHORTCUTS.toString())
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.common_back)) }
                },
                actions = {
                    TextButton(onClick = { editing = !editing }, enabled = editable || editing) {
                        AnimatedContent(editing, label = "editMode") { on -> Text(stringResource(if (on) R.string.common_done else R.string.common_edit)) }
                    }
                    IconButton(onClick = newKey, enabled = loaded) {
                        Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.shortcut_new))
                    }
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.common_more)) }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (!locked) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.shortcut_bulk_import)) },
                                enabled = loaded,
                                onClick = {
                                    menuOpen = false
                                    importOpen = true
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.shortcut_export_csv)) },
                            enabled = all.isNotEmpty(),
                            onClick = {
                                menuOpen = false
                                exporter.launch("siplink-shortcuts.csv")
                            },
                        )
                        val selected = category
                        if (!selected.isNullOrEmpty() && (!locked || ShortcutRules.ownsCategory(all, selected))) {
                            DropdownMenuItem(
                                text = { Text(stringOf(R.string.shortcut_edit_category, selected)) },
                                onClick = {
                                    menuOpen = false
                                    renaming = selected
                                },
                            )
                        }
                    }
                },
            )
        }

        when {
            !loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            all.isEmpty() -> EmptyBoard(
                canImport = !locked,
                onAdd = { edited = Shortcut(id = NEW_KEY, label = "") },
                onImport = { importOpen = true },
            )
            else -> ShortcutBoard(
                all = all,
                category = category,
                onCategoryChange = { category = it },
                editing = editing,
                onKey = { key ->
                    when {
                        !editing -> runner(key)
                        // A key that was handed out is not the user's to open.
                        locked && !key.own -> messenger(resources.render(ManagedConfig.NOTICE))
                        else -> edited = key
                    }
                },
                modifier = Modifier.fillMaxSize(),
                onMove = { moved, target -> manager.move(moved, target) },
            )
        }
    }

    edited?.let { shortcut -> ShortcutEditSheet(shortcut, onDismiss = { edited = null }) }

    if (importOpen && !locked) {
        CsvImportSheet(
            title = stringResource(R.string.shortcut_import_title),
            // The words of the types and icons are data: what the CSV format takes.
            formatHint = stringOf(
                R.string.shortcut_import_hint,
                resources.render(ShortcutRules.FORMAT),
                ShortcutRules.typeWords.joinToString(" / "),
                ShortcutRules.iconWords.joinToString(" / "),
            ),
            placeholder = stringResource(R.string.shortcut_import_example),
            onImport = { text ->
                val result = manager.import(text)
                if (result == null) {
                    ImportOutcome(finished = false, message = resources.render(ManagedConfig.NOTICE))
                } else {
                    ImportOutcome(
                        finished = result.errors.isEmpty() && result.skippedForLimit == 0 && result.shortcuts.isNotEmpty(),
                        message = importReport(
                            R.plurals.csv_imported_shortcuts,
                            result.shortcuts.size,
                            result.skippedForLimit,
                            ShortcutRules.MAX_SHORTCUTS,
                            result.errors,
                            context::render,
                        ),
                    )
                }
            },
            onDismiss = { importOpen = false },
        )
    }

    renaming?.takeIf { !locked || ShortcutRules.ownsCategory(all, it) }?.let { old ->
        CategoryNameDialog(
            title = stringResource(R.string.shortcut_category_title),
            initial = old,
            taken = remember(all, old) { ShortcutRules.categories(all).map { it.first }.filter { it != old } },
            confirm = stringResource(R.string.common_ok),
            onDismiss = { renaming = null },
            onConfirm = { name ->
                manager.renameCategory(old, name)
                category = name
                renaming = null
            },
            onDelete = {
                manager.renameCategory(old, "")
                category = null
                renaming = null
            },
        )
    }
}

/** @param canImport false where the configuration is locked: keys are then made one by one, as the user's own */
@Composable
private fun EmptyBoard(canImport: Boolean, onAdd: () -> Unit, onImport: () -> Unit) {
    EmptyState(icon = Icons.Rounded.Apps, title = stringResource(R.string.shortcut_empty), modifier = Modifier.fillMaxSize()) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (canImport) OutlinedButton(onClick = onImport) { Text(stringResource(R.string.shortcut_bulk_import)) }
            Button(onClick = onAdd) { Text(stringResource(R.string.shortcut_new)) }
        }
    }
}

/** Id of a key that does not exist yet (the editor then adds it instead of changing one). */
internal const val NEW_KEY = 0L
