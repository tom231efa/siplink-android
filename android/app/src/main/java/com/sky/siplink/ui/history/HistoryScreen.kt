@file:OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)

package com.sky.siplink.ui.history

import android.text.format.DateFormat
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallMade
import androidx.compose.material.icons.automirrored.rounded.CallMissed
import androidx.compose.material.icons.automirrored.rounded.CallReceived
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Apps
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sky.siplink.R
import com.sky.siplink.core.logic.Formatters
import com.sky.siplink.core.model.HistoryEntry
import com.sky.siplink.core.model.HistoryKind
import com.sky.siplink.engine
import com.sky.siplink.ui.components.Avatar
import com.sky.siplink.ui.components.EmptyState
import com.sky.siplink.ui.components.FadingText
import com.sky.siplink.ui.components.FeatureSheet
import com.sky.siplink.ui.components.rememberCallLauncher
import com.sky.siplink.ui.shortcuts.NewShortcutSheet
import com.sky.siplink.ui.theme.Brand
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DecimalStyle
import java.util.Locale

private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")

/** A line of the list: a day header ([entry] is null, [text] the day) or a call ([text] its time). */
private class HistoryListRow(val key: String, val text: String, val entry: HistoryEntry?)

/** How the day headers name the days that have a word of their own. */
private class DayWords(val today: String, val yesterday: String)

/**
 * [day] as a header of the list writes it in [locale], the language of the app: month and day,
 * and the year as well when it is not [today]'s ("11月21日", "2026年11月21日" in Chinese). The
 * pattern is the locale's own for the skeleton, as Android's DateFormat gives it; its digits are
 * Western in every language, like every other number of the app (DecimalStyle.STANDARD).
 */
internal fun historyDate(day: LocalDate, today: LocalDate, locale: Locale): String {
    val skeleton = if (day.year == today.year) "MMMd" else "yMMMd"
    val pattern = DateFormat.getBestDateTimePattern(locale, skeleton)
    return DateTimeFormatter.ofPattern(pattern, locale).withDecimalStyle(DecimalStyle.STANDARD).format(day)
}

private fun historyRows(entries: List<HistoryEntry>, zone: ZoneId, locale: Locale, words: DayWords): List<HistoryListRow> {
    val today = LocalDate.now(zone)
    val rows = ArrayList<HistoryListRow>(entries.size + 8)
    var previousDay: LocalDate? = null
    for (entry in entries) {
        val moment = Instant.ofEpochMilli(entry.startedAtMs).atZone(zone)
        val day = moment.toLocalDate()
        if (day != previousDay) {
            previousDay = day
            rows += HistoryListRow("day-$day", dayLabel(day, today, locale, words), null)
        }
        rows += HistoryListRow(entry.id, moment.format(timeFormat), entry)
    }
    return rows
}

@Composable
fun HistoryScreen() {
    val context = LocalContext.current
    val history = context.engine.history
    LaunchedEffect(Unit) { history.resolveNames() }
    val entries by history.entries.collectAsStateWithLifecycle()
    val launcher = rememberCallLauncher()
    var confirmClear by remember { mutableStateOf(false) }
    var featuresFor by remember { mutableStateOf<HistoryEntry?>(null) }
    var shortcutFor by remember { mutableStateOf<HistoryEntry?>(null) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.history_title)) },
            actions = {
                if (entries.isNotEmpty()) {
                    IconButton(onClick = { confirmClear = true }) {
                        Icon(Icons.Rounded.DeleteSweep, contentDescription = stringResource(R.string.history_clear))
                    }
                }
            },
        )
        if (entries.isEmpty()) {
            EmptyState(
                icon = Icons.Rounded.History,
                title = stringResource(R.string.history_empty),
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            // Day headers and clock times are worked out once per change of the list, not while scrolling.
            val locale = LocalConfiguration.current.locales[0]
            val words = DayWords(stringResource(R.string.history_today), stringResource(R.string.history_yesterday))
            val rows = remember(entries, locale, words.today, words.yesterday) {
                historyRows(entries, ZoneId.systemDefault(), locale, words)
            }
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                items(rows, key = { it.key }, contentType = { it.entry == null }) { row ->
                    val entry = row.entry
                    if (entry == null) {
                        Text(
                            row.text,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .animateItem()
                                .padding(start = 24.dp, top = 16.dp, bottom = 4.dp),
                        )
                    } else {
                        HistoryRow(
                            entry = entry,
                            time = row.text,
                            onCall = { launcher.call(entry.address) },
                            onAddShortcut = { shortcutFor = entry },
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
        FeatureSheet(
            launcher = launcher,
            onDismiss = { featuresFor = null },
            name = entry.name,
            target = entry.address,
            onAddShortcut = { shortcutFor = entry },
        )
    }
    shortcutFor?.let { entry ->
        NewShortcutSheet(name = entry.name, number = entry.address, onDismiss = { shortcutFor = null })
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.history_clear_title)) },
            text = { Text(pluralStringResource(R.plurals.history_clear_text, entries.size, entries.size.toString())) },
            confirmButton = {
                TextButton(onClick = {
                    history.clear()
                    confirmClear = false
                }) { Text(stringResource(R.string.history_clear_confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

private fun dayLabel(day: LocalDate, today: LocalDate, locale: Locale, words: DayWords): String = when {
    day == today -> words.today
    day == today.minusDays(1) -> words.yesterday
    else -> historyDate(day, today, locale)
}

@Composable
private fun HistoryRow(
    entry: HistoryEntry,
    time: String,
    onCall: () -> Unit,
    onAddShortcut: () -> Unit,
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
            stringResource(R.string.history_outgoing, Formatters.duration(entry.durationSec.toLong())),
        )
        HistoryKind.INCOMING -> Triple(
            Icons.AutoMirrored.Rounded.CallReceived,
            MaterialTheme.colorScheme.primary,
            stringResource(R.string.history_incoming, Formatters.duration(entry.durationSec.toLong())),
        )
        HistoryKind.MISSED -> Triple(Icons.AutoMirrored.Rounded.CallMissed, Brand.HangUpRed, stringResource(R.string.history_missed))
        HistoryKind.FAILED -> Triple(Icons.AutoMirrored.Rounded.CallMade, MaterialTheme.colorScheme.outline, stringResource(R.string.history_failed))
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
                FadingText(
                    entry.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (missed) Brand.HangUpRed else MaterialTheme.colorScheme.onSurface,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    FadingText(
                        stringResource(R.string.history_time_detail, time, detail),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FadingText(
                    entry.address,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                )
            }
            FilledTonalIconButton(onClick = onCall) {
                Icon(Icons.Rounded.Call, contentDescription = stringResource(R.string.history_call_back))
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.history_add_shortcut)) },
                leadingIcon = { Icon(Icons.Rounded.Apps, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    onAddShortcut()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.history_call_features)) },
                leadingIcon = { Icon(Icons.Rounded.SupportAgent, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    onMore()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.history_delete)) },
                leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    onDelete()
                },
            )
        }
    }
}
