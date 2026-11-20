package com.sky.siplink.ui.recordings

import android.content.ContentResolver
import android.media.MediaPlayer
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sky.siplink.R
import com.sky.siplink.core.logic.Formatters
import com.sky.siplink.core.logic.RecordingPeriod
import com.sky.siplink.core.logic.RecordingRules
import com.sky.siplink.core.model.Recording
import com.sky.siplink.ui.components.FadingText
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The time filter: one compact row of chips; the last one picks a range of days. */
@Composable
internal fun PeriodRow(period: RecordingPeriod, range: Pair<LocalDate, LocalDate>?, onPeriod: (RecordingPeriod) -> Unit, onPickRange: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf(
            RecordingPeriod.ALL to stringResource(R.string.recording_period_all),
            RecordingPeriod.TODAY to stringResource(R.string.recording_period_today),
            RecordingPeriod.WEEK to pluralStringResource(R.plurals.recording_period_days, 7, "7"),
            RecordingPeriod.MONTH to pluralStringResource(R.plurals.recording_period_days, 30, "30"),
        )
            .forEach { (value, label) -> FilterChip(selected = period == value, onClick = { onPeriod(value) }, label = { Text(label) }) }
        FilterChip(
            selected = period == RecordingPeriod.RANGE,
            onClick = onPickRange,
            label = {
                Text(
                    range?.let { (from, to) -> stringResource(R.string.recording_period_range, DAY.format(from), DAY.format(to)) }
                        ?: stringResource(R.string.recording_period_custom),
                )
            },
        )
    }
}

/**
 * One recording: play or stop at the front, who the call was with and when, and its own menu.
 * In selection mode a tap selects it instead.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun RecordingRow(
    recording: Recording,
    zone: ZoneId,
    playing: Boolean,
    selecting: Boolean,
    selected: Boolean,
    onPlay: () -> Unit,
    onSelect: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val unknown = stringResource(R.string.recording_unknown_number)
    val title = recording.name.ifEmpty { recording.number }.ifEmpty { unknown }
    val time = TIME.format(Instant.ofEpochMilli(recording.startedAt).atZone(zone))
    val detail = stringResource(
        if (recording.incoming) R.string.recording_detail_incoming else R.string.recording_detail_outgoing,
        time,
        Formatters.duration(recording.durationMs / 1000),
        RecordingRules.sizeText(recording.bytes),
    )
    Box(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = if (selecting) onSelect else onPlay, onLongClick = onSelect)
                .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selecting) {
                Checkbox(checked = selected, onCheckedChange = { onSelect() })
            } else {
                FilledTonalIconButton(onClick = onPlay) {
                    Icon(if (playing) Icons.Rounded.Stop else Icons.Rounded.PlayArrow, contentDescription = stringResource(if (playing) R.string.recording_stop else R.string.recording_play))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                FadingText(title, style = MaterialTheme.typography.titleMedium)
                FadingText(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (recording.name.isNotEmpty() && recording.number.isNotEmpty() && recording.name != recording.number) {
                    FadingText(
                        recording.number,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    )
                }
            }
            if (!selecting) {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.common_more)) }
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, modifier = Modifier.align(Alignment.TopEnd)) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.recording_export)) },
                leadingIcon = { Icon(Icons.Rounded.FileDownload, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    onExport()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.common_delete)) },
                leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    onDelete()
                },
            )
        }
    }
}

/** Plays one recording at a time. */
internal class RecordingPlayer {
    private var player: MediaPlayer? = null

    /** Plays [file]; [onDone] is called when it has played to its end. False when it cannot be played. */
    fun play(file: File, onDone: () -> Unit): Boolean {
        stop()
        val next = MediaPlayer()
        return runCatching {
            next.setDataSource(file.path)
            next.setOnCompletionListener { onDone() }
            next.prepare()
            next.start()
            player = next
        }.onFailure { next.release() }.isSuccess
    }

    fun stop() {
        player?.let {
            runCatching { it.stop() }
            it.release()
        }
        player = null
    }
}

/**
 * Copies the files of [recordings] into the folder [tree] the user picked, each under its
 * export name. Off the main thread.
 *
 * @return how many were copied
 */
internal fun exportToFolder(resolver: ContentResolver, tree: Uri, recordings: List<Pair<Recording, File>>, zone: ZoneId): Int {
    val folder = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
    return recordings.count { (recording, file) ->
        runCatching {
            val target = DocumentsContract.createDocument(
                resolver,
                folder,
                RecordingRules.mimeType(recording.file),
                RecordingRules.exportName(recording, zone),
            ) ?: return@runCatching false
            copyTo(resolver, file, target)
        }.getOrDefault(false)
    }
}

/** Copies [file] into the document [target]; off the main thread. */
internal fun copyTo(resolver: ContentResolver, file: File, target: Uri): Boolean = runCatching {
    val output = resolver.openOutputStream(target) ?: return false
    output.use { out -> file.inputStream().use { it.copyTo(out) } }
    true
}.getOrDefault(false)

private val DAY = DateTimeFormatter.ofPattern("M/d")
private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
