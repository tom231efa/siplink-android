@file:OptIn(ExperimentalMaterial3Api::class)

package com.sky.siplink.ui.recordings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sky.siplink.core.logic.RecordingPeriod
import com.sky.siplink.core.logic.RecordingRules
import com.sky.siplink.core.model.Recording
import com.sky.siplink.engine
import com.sky.siplink.ui.components.EmptyState
import com.sky.siplink.ui.components.LocalMessenger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The call recordings, newest first: narrowed by time, played in place, exported (one under a
 * name of the user's choice, several into a folder) and deleted. They are the user's own data,
 * like the call history, and the page works the same under a managed configuration.
 */
@Composable
fun RecordingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = context.engine.recordings
    val all by store.recordings.collectAsStateWithLifecycle()
    val messenger = LocalMessenger.current
    val scope = rememberCoroutineScope()
    val zone = remember { ZoneId.systemDefault() }

    var period by rememberSaveable { mutableStateOf(RecordingPeriod.ALL) }
    var range by remember { mutableStateOf<Pair<LocalDate, LocalDate>?>(null) }
    var pickingRange by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var confirmDelete by remember { mutableStateOf<List<Recording>?>(null) }
    var exportOne by remember { mutableStateOf<Recording?>(null) }
    var exportMany by remember { mutableStateOf<List<Recording>>(emptyList()) }
    var playing by remember { mutableStateOf<String?>(null) }
    val player = remember { RecordingPlayer() }
    DisposableEffect(Unit) { onDispose { player.stop() } }

    val shown = remember(all, period, range) {
        val window = RecordingRules.window(period, System.currentTimeMillis(), zone, range?.first, range?.second)
        RecordingRules.shown(all, window)
    }
    val selecting = selected.isNotEmpty()
    // What the actions of the top bar work on: the selection, else what the filter shows.
    val target = if (selecting) shown.filter { it.id in selected } else shown

    fun exported(count: Int, of: Int) = messenger(if (count == of) "已导出 $count 条录音" else "导出失败")

    val saveOne = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/mp4")) { uri ->
        val recording = exportOne ?: return@rememberLauncherForActivityResult
        exportOne = null
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) { copyTo(context.contentResolver, store.fileOf(recording), uri) }
            exported(if (ok) 1 else 0, 1)
        }
    }
    val saveMany = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        val chosen = exportMany
        exportMany = emptyList()
        if (tree == null || chosen.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val files = chosen.map { it to store.fileOf(it) }
            val count = withContext(Dispatchers.IO) { exportToFolder(context.contentResolver, tree, files, zone) }
            exported(count, chosen.size)
            selected = emptySet()
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(if (selecting) "已选 ${selected.size}" else "通话录音") },
            navigationIcon = {
                if (selecting) {
                    IconButton(onClick = { selected = emptySet() }) { Icon(Icons.Rounded.Close, contentDescription = "取消选择") }
                } else {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回") }
                }
            },
            actions = {
                IconButton(
                    onClick = {
                        exportMany = target
                        saveMany.launch(null)
                    },
                    enabled = target.isNotEmpty(),
                ) { Icon(Icons.Rounded.FileDownload, contentDescription = "导出") }
                if (selecting) {
                    IconButton(onClick = { confirmDelete = target }) { Icon(Icons.Rounded.Delete, contentDescription = "删除") }
                }
            },
        )
        PeriodRow(
            period = period,
            range = range,
            onPeriod = {
                period = it
                selected = emptySet()
            },
            onPickRange = { pickingRange = true },
        )
        Text(
            "${shown.size} 条 · ${RecordingRules.sizeText(shown.sumOf { it.bytes })}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 24.dp, top = 8.dp, bottom = 4.dp),
        )
        if (shown.isEmpty()) {
            EmptyState(icon = Icons.Rounded.GraphicEq, title = "暂无录音", modifier = Modifier.fillMaxSize())
        } else {
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                items(shown, key = { it.id }) { recording ->
                    RecordingRow(
                        recording = recording,
                        zone = zone,
                        playing = playing == recording.id,
                        selecting = selecting,
                        selected = recording.id in selected,
                        onPlay = {
                            if (playing == recording.id) {
                                player.stop()
                                playing = null
                            } else if (player.play(store.fileOf(recording)) { playing = null }) {
                                playing = recording.id
                            } else {
                                playing = null
                                messenger("无法播放")
                            }
                        },
                        onSelect = { selected = if (recording.id in selected) selected - recording.id else selected + recording.id },
                        onExport = {
                            exportOne = recording
                            saveOne.launch(RecordingRules.exportName(recording, zone))
                        },
                        onDelete = { confirmDelete = listOf(recording) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }

    confirmDelete?.let { doomed ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("删除 ${doomed.size} 条录音？") },
            confirmButton = {
                TextButton(onClick = {
                    if (doomed.any { it.id == playing }) {
                        player.stop()
                        playing = null
                    }
                    store.remove(doomed)
                    selected = selected - doomed.mapTo(HashSet()) { it.id }
                    confirmDelete = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("取消") } },
        )
    }

    if (pickingRange) {
        val picker = rememberDateRangePickerState()
        DatePickerDialog(
            onDismissRequest = { pickingRange = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        val from = picker.selectedStartDateMillis
                        val to = picker.selectedEndDateMillis ?: from
                        if (from != null && to != null) {
                            // The picker counts days in UTC.
                            range = day(from) to day(to)
                            period = RecordingPeriod.RANGE
                            selected = emptySet()
                        }
                        pickingRange = false
                    },
                    enabled = picker.selectedStartDateMillis != null,
                ) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { pickingRange = false }) { Text("取消") } },
        ) {
            DateRangePicker(state = picker, modifier = Modifier.weight(1f))
        }
    }
}

private fun day(utcMillis: Long): LocalDate = Instant.ofEpochMilli(utcMillis).atZone(ZoneOffset.UTC).toLocalDate()
