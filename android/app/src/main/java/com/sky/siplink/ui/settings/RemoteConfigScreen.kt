package com.sky.siplink.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sky.siplink.R
import com.sky.siplink.core.RemoteConfig
import com.sky.siplink.core.logic.RemoteAddress
import com.sky.siplink.core.logic.RemoteSync
import com.sky.siplink.engine
import com.sky.siplink.ui.components.FadingText
import com.sky.siplink.ui.components.FoldingCard
import com.sky.siplink.ui.components.FormField
import com.sky.siplink.ui.components.LocalMessenger
import com.sky.siplink.ui.components.NavRow
import com.sky.siplink.ui.components.Personal
import com.sky.siplink.ui.components.SectionCard
import com.sky.siplink.ui.components.SubScreen
import java.time.ZoneId

/**
 * The remote configuration: loaded by scanning the QR code of the management platform or typing
 * its address, then shown with where it comes from and how its syncs go. This screen is the
 * user's own under a managed configuration: it is where the configuration is ended.
 */
@Composable
fun RemoteConfigScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val remote = context.engine.remote
    val status by remote.status.collectAsStateWithLifecycle()
    val messenger = LocalMessenger.current
    var typing by rememberSaveable { mutableStateOf(false) }
    var scanning by rememberSaveable { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    var pending by remember { mutableStateOf<RemoteAddress.Enrollment?>(null) }

    /** Takes an address that was scanned or typed to the question whether to load it; returns what is wrong with it. */
    fun offer(text: String): String? = when (val parsed = remote.parse(text)) {
        is RemoteAddress.Parsed.Ok -> null.also { pending = parsed.enrollment }
        is RemoteAddress.Parsed.Invalid -> resources.getString(parsed.error.message)
    }

    // The camera is asked for only here, when the user wants to scan; typing works without it.
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scanning = true else messenger(resources.getString(R.string.remote_camera_denied))
    }
    val scan = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            scanning = true
        } else {
            camera.launch(Manifest.permission.CAMERA)
        }
    }

    Personal {
        SubScreen(title = stringResource(R.string.settings_remote_config), onBack = onBack) {
            if (status.enrolled) {
                Enrolled(status, onSync = remote::syncNow, onDelete = { deleting = true })
            } else {
                NotEnrolled(status, onScan = scan, onType = { typing = true })
            }
        }

        if (typing) {
            AddressDialog(offer = ::offer, onDismiss = { typing = false }, onTaken = { typing = false })
        }
    }

    if (scanning) {
        QrScanner(
            accepts = { remote.parse(it) is RemoteAddress.Parsed.Ok },
            onFound = { text ->
                scanning = false
                offer(text)
            },
            onClose = { scanning = false },
            onFailed = { message ->
                scanning = false
                messenger(message)
            },
        )
    }

    pending?.let { enrollment ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(stringResource(R.string.remote_load_title)) },
            text = {
                Text(stringResource(R.string.remote_load_text, enrollment.host))
            },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    remote.enroll(enrollment, messenger)
                }) { Text(stringResource(R.string.remote_load)) }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }

    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text(stringResource(R.string.remote_delete_title)) },
            text = {
                Text(stringResource(R.string.remote_delete_text))
            },
            confirmButton = {
                TextButton(onClick = {
                    deleting = false
                    remote.delete(messenger)
                }) { Text(stringResource(R.string.common_confirm_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

@Composable
private fun NotEnrolled(status: RemoteConfig.Status, onScan: () -> Unit, onType: () -> Unit) {
    SectionCard(title = null) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            // One above the other at full width: side by side, a narrow phone or a large font broke the labels.
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onScan, enabled = !status.busy, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.QrCodeScanner, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.remote_scan), maxLines = 1)
                }
                OutlinedButton(onClick = onType, enabled = !status.busy, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Link, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.remote_enter_address), maxLines = 1)
                }
            }
            if (status.busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(R.string.remote_loading), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (status.lastError.isNotEmpty()) {
                Text(status.lastError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun Enrolled(status: RemoteConfig.Status, onSync: () -> Unit, onDelete: () -> Unit) {
    val zone = remember { ZoneId.systemDefault() }
    var details by rememberSaveable { mutableStateOf(false) }
    SectionCard(title = stringResource(R.string.remote_section_platform)) {
        InfoRow(stringResource(R.string.remote_platform), status.platformName.ifEmpty { status.host })
        if (status.clientName.isNotEmpty()) InfoRow(stringResource(R.string.remote_client), status.clientName)
        if (status.region.isNotEmpty()) InfoRow(stringResource(R.string.remote_region), status.region)
    }
    SectionCard(title = stringResource(R.string.remote_section_sync)) {
        InfoRow(stringResource(R.string.remote_last_sync), if (status.lastSyncMs > 0) RemoteSync.shownTime(status.lastSyncMs, zone) else stringResource(R.string.remote_never_synced))
        if (status.generatedAt.isNotEmpty()) InfoRow(stringResource(R.string.remote_generated_at), RemoteSync.shownTime(status.generatedAt, zone))
        if (status.lastError.isNotEmpty()) InfoRow(stringResource(R.string.remote_sync_result), status.lastError, error = true)
        NavRow(
            title = stringResource(if (status.busy) R.string.remote_syncing else R.string.remote_sync_now),
            subtitle = pluralStringResource(R.plurals.remote_sync_interval, status.refreshMinutes, status.refreshMinutes.toString()),
            icon = Icons.Rounded.Sync,
            onClick = { if (!status.busy) onSync() },
        )
        if (status.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
    }
    FoldingCard(title = stringResource(R.string.remote_details), open = details, onToggle = { details = !details }) {
        InfoRow(stringResource(R.string.remote_address), status.host)
        InfoRow(stringResource(R.string.remote_version), status.version.take(VERSION_SHOWN).ifEmpty { "—" })
        InfoRow(stringResource(R.string.remote_certificate), stringResource(if (status.pinned) R.string.remote_certificate_pinned else R.string.remote_certificate_system))
    }
    OutlinedButton(onClick = onDelete, enabled = !status.busy, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.remote_delete), color = MaterialTheme.colorScheme.error)
    }
    Spacer(Modifier.height(8.dp))
}

/** A value to read, with what it is on its left; [error] is a status that wraps, any other value stays on one line. */
@Composable
private fun InfoRow(label: String, value: String, error: Boolean = false) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        if (error) {
            // An error is read in full.
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
        } else {
            // Names and addresses of the platform: one line, fading where it does not fit.
            FadingText(
                value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * Asks for the address of a remote configuration.
 *
 * @param offer takes the address on; returns what is wrong with it instead
 * @param onTaken called once [offer] took it
 */
@Composable
private fun AddressDialog(offer: (String) -> String?, onDismiss: () -> Unit, onTaken: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.remote_enter_address_title)) },
        text = {
            FormField(
                value = text,
                onValueChange = {
                    text = it
                    error = null
                },
                label = stringResource(R.string.remote_address_label),
                placeholder = "https://example.com/p/…",
                error = error,
                singleLine = false,
            )
        },
        confirmButton = {
            TextButton(onClick = { offer(text)?.let { error = it } ?: onTaken() }, enabled = text.isNotBlank()) { Text(stringResource(R.string.remote_next)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

/** How much of the version (a SHA-256 in hex) is shown: enough to tell two apart. */
private const val VERSION_SHOWN = 12
