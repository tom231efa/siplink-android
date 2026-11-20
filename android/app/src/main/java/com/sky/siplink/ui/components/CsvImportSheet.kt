package com.sky.siplink.ui.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.PluralsRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sky.siplink.R
import com.sky.siplink.core.logic.Csv
import com.sky.siplink.core.model.Phrase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MAX_IMPORT_BYTES = 512 * 1024

/** What an import did: finished without anything to report, or a report for the user to read. */
data class ImportOutcome(val finished: Boolean, val message: String)

/**
 * The report of an import: how many were taken, how many did not fit, which lines failed.
 *
 * @param imported the plural that says how many of what were taken ([R.plurals.csv_imported_accounts], …)
 */
fun importReport(
    @PluralsRes imported: Int,
    created: Int,
    skipped: Int,
    limit: Int,
    errors: List<Csv.LineError>,
    render: (Phrase) -> String,
): String = buildString {
    append(render(Phrase.count(imported, created)))
    if (skipped > 0) append(render(Phrase.count(R.plurals.csv_report_skipped, skipped, limit)))
    if (errors.isNotEmpty()) {
        append(render(Phrase.count(R.plurals.csv_report_errors, errors.size)))
        errors.take(8).forEach { append(render(Phrase.of(R.string.csv_report_line, it.line, it.reason))) }
        if (errors.size > 8) append(render(Phrase.of(R.string.csv_report_more)))
    }
}

/** Bulk import from pasted text or a CSV file: one item per line, fields separated by commas. */
@Composable
fun CsvImportSheet(
    title: String,
    formatHint: String,
    placeholder: String,
    onImport: (String) -> ImportOutcome,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val messenger = LocalMessenger.current
    val scope = rememberCoroutineScope()
    val unreadable = stringResource(R.string.csv_file_unreadable)
    var text by remember { mutableStateOf("") }
    var report by remember { mutableStateOf<String?>(null) }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val content = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        val buffer = ByteArray(MAX_IMPORT_BYTES)
                        var size = 0
                        while (size < buffer.size) {
                            val read = stream.read(buffer, size, buffer.size - size)
                            if (read < 0) break
                            size += read
                        }
                        String(buffer, 0, size, Charsets.UTF_8)
                    }
                }.getOrNull()
            }
            if (content == null) messenger(unreadable) else text = content
        }
    }

    fun import() {
        val outcome = onImport(text)
        if (outcome.finished) {
            messenger(outcome.message)
            onDismiss()
        } else {
            report = outcome.message
        }
    }

    AppBottomSheet(onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Text(
                    formatHint,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(12.dp),
                )
            }
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it
                    report = null
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 140.dp, max = 260.dp),
                placeholder = { Text(placeholder) },
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                shape = MaterialTheme.shapes.medium,
            )
            AnimatedVisibility(report != null) {
                Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.errorContainer) {
                    Text(
                        report.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { filePicker.launch(arrayOf("text/*", "application/csv")) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.FolderOpen, contentDescription = null)
                    Spacer(Modifier.padding(start = 8.dp))
                    Text(stringResource(R.string.csv_choose_file), textAlign = TextAlign.Center)
                }
                Button(onClick = ::import, enabled = text.isNotBlank(), modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.csv_import), textAlign = TextAlign.Center)
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}
