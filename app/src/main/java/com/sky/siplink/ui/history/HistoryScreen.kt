@file:OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)

package com.sky.siplink.ui.history

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallMade
import androidx.compose.material.icons.automirrored.rounded.CallMissed
import androidx.compose.material.icons.automirrored.rounded.CallReceived
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.SupportAgent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sky.siplink.core.logic.Formatters
import com.sky.siplink.core.model.HistoryEntry
import com.sky.siplink.core.model.HistoryKind
import com.sky.siplink.engine
import com.sky.siplink.ui.components.Avatar
import com.sky.siplink.ui.components.EmptyState
import com.sky.siplink.ui.components.FeatureSheet
import com.sky.siplink.ui.components.rememberCallLauncher
import com.sky.siplink.ui.theme.Brand
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")
private val dayFormat = DateTimeFormatter.ofPattern("M月d日")
private val dayWithYearFormat = DateTimeFormatter.ofPattern("yyyy年M月d日")

@Composable
fun HistoryScreen() {
    val context = LocalContext.current
    val history = context.engine.history
    val entries by history.entries.collectAsStateWithLifecycle()
    val launcher = rememberCallLauncher()
    var confirmClear by remember { mutableStateOf(false) }
    var featuresFor by remember { mutableStateOf<HistoryEntry?>(null) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("通话记录") },
            actions = {
                if (entries.isNotEmpty()) {
                    IconButton(onClick = { confirmClear = true }) {
                        Icon(Icons.Rounded.DeleteSweep, contentDescription = "清空通话记录")
                    }
                }
            },
        )
        if (entries.isEmpty()) {
            EmptyState(
                icon = Icons.Rounded.History,
                title = "还没有通话记录",
                subtitle = "拨出或接听的通话会显示在这里。",
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            val zone = remember { ZoneId.systemDefault() }
            val today = remember(entries) { LocalDate.now(zone) }
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                var previousDay: LocalDate? = null
                entries.forEach { entry ->
                    val day = Instant.ofEpochMilli(entry.startedAtMs).atZone(zone).toLocalDate()
                    if (day != previousDay) {
                        previousDay = day
                        item(key = "day-$day") {
                            Text(
                                dayLabel(day, today),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .animateItem()
                                    .padding(start = 24.dp, top = 16.dp, bottom = 4.dp),
                            )
                        }
                    }
                    item(key = entry.id) {
                        HistoryRow(
                            entry = entry,
                            time = Instant.ofEpochMilli(entry.startedAtMs).atZone(zone).format(timeFormat),
                            onCall = { launcher.call(entry.address) },
                            onIntercom = { launcher.intercom(entry.address) },
                            onMore = { featuresFor = entry },
                            onDelete = { history.remove(entry.id) },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
        }
    }

    featuresFor?.let { entry ->
        FeatureSheet(launcher = launcher, onDismiss = { featuresFor = null }, name = entry.name, target = entry.address)
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空通话记录？") },
            text = { Text("全部 ${entries.size} 条记录将被删除，且无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    history.clear()
                    confirmClear = false
                }) { Text("清空", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } },
        )
    }
}

private fun dayLabel(day: LocalDate, today: LocalDate): String = when {
    day == today -> "今天"
    day == today.minusDays(1) -> "昨天"
    day.year == today.year -> day.format(dayFormat)
    else -> day.format(dayWithYearFormat)
}

@Composable
private fun HistoryRow(
    entry: HistoryEntry,
    time: String,
    onCall: () -> Unit,
    onIntercom: () -> Unit,
    onMore: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val missed = entry.kind == HistoryKind.MISSED
    val (icon, tint, detail) = when (entry.kind) {
        HistoryKind.OUTGOING -> Triple(
            Icons.AutoMirrored.Rounded.CallMade,
            Brand.CallGreen,
            "呼出 · ${Formatters.duration(entry.durationSec.toLong())}",
        )
        HistoryKind.INCOMING -> Triple(
            Icons.AutoMirrored.Rounded.CallReceived,
            MaterialTheme.colorScheme.primary,
            "呼入 · ${Formatters.duration(entry.durationSec.toLong())}",
        )
        HistoryKind.MISSED -> Triple(Icons.AutoMirrored.Rounded.CallMissed, Brand.HangUpRed, "未接来电")
        HistoryKind.FAILED -> Triple(Icons.AutoMirrored.Rounded.CallMade, MaterialTheme.colorScheme.outline, "未接通")
    }
    Box(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onCall, onLongClick = { menuOpen = true })
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(entry.name)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    entry.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (missed) Brand.HangUpRed else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "$time · $detail",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    entry.address,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            FilledTonalIconButton(onClick = onCall) {
                Icon(Icons.Rounded.Call, contentDescription = "回拨")
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("对讲呼叫") },
                leadingIcon = { Icon(Icons.Rounded.Campaign, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    onIntercom()
                },
            )
            DropdownMenuItem(
                text = { Text("企业功能…") },
                leadingIcon = { Icon(Icons.Rounded.SupportAgent, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    onMore()
                },
            )
            DropdownMenuItem(
                text = { Text("删除记录") },
                leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    onDelete()
                },
            )
        }
    }
}
