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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sky.siplink.core.logic.ManagedConfig
import com.sky.siplink.core.logic.ShortcutRules
import com.sky.siplink.core.model.Shortcut
import com.sky.siplink.core.render
import com.sky.siplink.engine
import com.sky.siplink.openCallScreen
import com.sky.siplink.ui.components.CsvImportSheet
import com.sky.siplink.ui.components.EmptyState
import com.sky.siplink.ui.components.ImportOutcome
import com.sky.siplink.ui.components.LocalConfigLocked
import com.sky.siplink.ui.components.LocalMessenger
import com.sky.siplink.ui.components.importReport
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
            messenger(context.render(ShortcutRules.Error.OWN_LIMIT_REACHED.full))
        } else {
            edited = Shortcut(id = NEW_KEY, label = "", category = category.orEmpty())
        }
    }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = manager.export()
        scope.launch {
            val written = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } }.getOrNull()
            }
            messenger(if (written == null) "导出失败" else "已导出 ${all.size} 个快捷键")
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text("快捷键")
                    // Under a managed configuration what counts is how many keys of their own the user has.
                    Text(
                        if (locked) "$ownCount / ${ShortcutRules.MAX_OWN}" else "${all.size} / ${ShortcutRules.MAX_SHORTCUTS}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回") }
            },
            actions = {
                TextButton(onClick = { editing = !editing }, enabled = editable || editing) {
                    AnimatedContent(editing, label = "editMode") { on -> Text(if (on) "完成" else "编辑") }
                }
                IconButton(onClick = newKey, enabled = loaded) {
                    Icon(Icons.Rounded.Add, contentDescription = "新建快捷键")
                }
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "更多") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (!locked) {
                        DropdownMenuItem(
                            text = { Text("批量导入") },
                            enabled = loaded,
                            onClick = {
                                menuOpen = false
                                importOpen = true
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("导出为 CSV") },
                        enabled = all.isNotEmpty(),
                        onClick = {
                            menuOpen = false
                            exporter.launch("siplink-shortcuts.csv")
                        },
                    )
                    val selected = category
                    if (!selected.isNullOrEmpty() && (!locked || ShortcutRules.ownsCategory(all, selected))) {
                        DropdownMenuItem(
                            text = { Text("编辑分类“$selected”") },
                            onClick = {
                                menuOpen = false
                                renaming = selected
                            },
                        )
                    }
                }
            },
        )

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
                        locked && !key.own -> messenger(context.render(ManagedConfig.NOTICE))
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
            title = "批量导入快捷键",
            formatHint = context.render(ShortcutRules.FORMAT) +
                "\n类型：" + ShortcutRules.typeWords.joinToString(" / ") +
                "\n线路：账号名称或 user@domain；留空则拨出时选择" +
                "\n图标：" + ShortcutRules.iconWords.joinToString(" / ") + "；留空则随类型",
            placeholder = "前台,1001,speed,常用,,reception\n对讲仓库,*801005,speed,对讲,,warehouse\n开门,#9,dtmf,门禁,,door\n转总机,1000,bxfer,转接",
            onImport = { text ->
                val result = manager.import(text)
                if (result == null) {
                    ImportOutcome(finished = false, message = context.render(ManagedConfig.NOTICE))
                } else {
                    ImportOutcome(
                        finished = result.errors.isEmpty() && result.skippedForLimit == 0 && result.shortcuts.isNotEmpty(),
                        message = importReport(
                            result.shortcuts.size,
                            "快捷键",
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
            title = "分类",
            initial = old,
            taken = remember(all, old) { ShortcutRules.categories(all).map { it.first }.filter { it != old } },
            confirm = "确定",
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
    EmptyState(icon = Icons.Rounded.Apps, title = "暂无快捷键", modifier = Modifier.fillMaxSize()) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (canImport) OutlinedButton(onClick = onImport) { Text("批量导入") }
            Button(onClick = onAdd) { Text("新建快捷键") }
        }
    }
}

/** Id of a key that does not exist yet (the editor then adds it instead of changing one). */
internal const val NEW_KEY = 0L
