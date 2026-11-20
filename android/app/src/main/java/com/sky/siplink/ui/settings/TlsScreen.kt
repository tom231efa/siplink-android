@file:OptIn(ExperimentalMaterial3Api::class)

package com.sky.siplink.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.GppMaybe
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sky.siplink.R
import com.sky.siplink.core.TlsManager
import com.sky.siplink.core.logic.ManagedConfig
import com.sky.siplink.core.model.CertInfo
import com.sky.siplink.core.model.MediaSecurity
import com.sky.siplink.core.model.Phrase
import com.sky.siplink.core.render
import com.sky.siplink.engine
import com.sky.siplink.ui.components.ChoiceRow
import com.sky.siplink.ui.components.FadingText
import com.sky.siplink.ui.components.LdapRestartDialog
import com.sky.siplink.ui.components.LocalConfigLocked
import com.sky.siplink.ui.components.LocalMessenger
import com.sky.siplink.ui.components.SectionCard
import com.sky.siplink.ui.components.SubScreen
import com.sky.siplink.ui.components.SwitchRow
import com.sky.siplink.ui.components.stringOf
import com.sky.siplink.ui.theme.Brand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val MAX_CERT_FILE_BYTES = 1024 * 1024
private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd")

/** TLS trust: user imported CA certificates, server verification and media encryption. */
@Composable
fun TlsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val engine = context.engine
    val settings by engine.settings.settings.collectAsStateWithLifecycle()
    val certificates by engine.tls.certificates.collectAsStateWithLifecycle()
    val messenger = LocalMessenger.current
    val locked = LocalConfigLocked.current
    val scope = rememberCoroutineScope()
    var pendingRemoval by remember { mutableStateOf<CertInfo?>(null) }
    var restartPrompt by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val data = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        // Read one byte more than the cap so oversized files can be told apart.
                        val buffer = ByteArray(MAX_CERT_FILE_BYTES + 1)
                        var size = 0
                        while (size < buffer.size) {
                            val read = stream.read(buffer, size, buffer.size - size)
                            if (read < 0) break
                            size += read
                        }
                        buffer.copyOf(size)
                    }
                }.getOrNull()
            }
            val message = when {
                data == null -> resources.getString(R.string.tls_read_failed)
                data.size > MAX_CERT_FILE_BYTES -> resources.getString(R.string.tls_file_too_large)
                else -> when (val result = engine.tls.import(data)) {
                    is TlsManager.ImportResult.Added -> {
                        restartPrompt = engine.directory.tlsChangeNeedsRestart
                        resources.render(Phrase.count(R.plurals.tls_imported, result.count))
                    }
                    TlsManager.ImportResult.AlreadyTrusted -> resources.getString(R.string.tls_already_trusted)
                    is TlsManager.ImportResult.Invalid -> resources.render(result.reason)
                    TlsManager.ImportResult.Managed -> resources.render(ManagedConfig.NOTICE)
                }
            }
            messenger(message)
        }
    }

    SubScreen(title = stringResource(R.string.settings_tls), onBack = onBack) {
        SectionCard(title = stringResource(R.string.tls_section_imported)) {
            if (certificates.isEmpty()) {
                Text(
                    stringResource(R.string.tls_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
            certificates.forEach { certificate ->
                CertificateRow(certificate, onRemove = if (locked) null else ({ pendingRemoval = certificate }))
            }
            if (!locked) {
                Button(
                    onClick = { picker.launch(arrayOf("*/*")) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Icon(Icons.Rounded.FileOpen, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.tls_import))
                }
            }
        }

        SectionCard(title = stringResource(R.string.tls_section_verification)) {
            SwitchRow(
                title = stringResource(R.string.tls_verify_certificate),
                icon = Icons.Rounded.VerifiedUser,
                checked = settings.verifyTlsCertificate,
                onCheckedChange = { value -> engine.updateSettings { it.copy(verifyTlsCertificate = value) } },
            )
            SwitchRow(
                title = stringResource(R.string.tls_verify_hostname),
                icon = Icons.Rounded.VerifiedUser,
                checked = settings.verifyTlsHostname,
                enabled = settings.verifyTlsCertificate,
                onCheckedChange = { value -> engine.updateSettings { it.copy(verifyTlsHostname = value) } },
            )
            AnimatedVisibility(!settings.verifyTlsCertificate || !settings.verifyTlsHostname) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.GppMaybe, contentDescription = null, tint = Brand.Amber)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        stringResource(R.string.tls_verification_off_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        SectionCard(title = stringResource(R.string.tls_section_srtp)) {
            val options = listOf(
                MediaSecurity.NONE to stringResource(R.string.tls_srtp_off),
                MediaSecurity.SRTP_OPTIONAL to stringResource(R.string.tls_srtp_preferred),
                MediaSecurity.SRTP_MANDATORY to stringResource(R.string.tls_srtp_mandatory),
            )
            ChoiceRow(
                options = options,
                selected = settings.mediaSecurity,
                onSelect = { value -> engine.updateSettings { it.copy(mediaSecurity = value) } },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
    }

    pendingRemoval?.let { certificate ->
        AlertDialog(
            onDismissRequest = { pendingRemoval = null },
            title = { Text(stringResource(R.string.tls_remove_title)) },
            text = { Text(stringOf(R.string.tls_remove_text, certificate.subject)) },
            confirmButton = {
                TextButton(onClick = {
                    engine.tls.remove(certificate.fingerprint)
                    pendingRemoval = null
                    restartPrompt = engine.directory.tlsChangeNeedsRestart
                }) { Text(stringResource(R.string.tls_remove_confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingRemoval = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }

    if (restartPrompt) LdapRestartDialog(onDismiss = { restartPrompt = false })
}

/** @param onRemove null where the certificate cannot be removed: the row then has no button for it */
@Composable
private fun CertificateRow(certificate: CertInfo, onRemove: (() -> Unit)?) {
    val zone = remember { ZoneId.systemDefault() }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = if (onRemove == null) 16.dp else 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.tertiaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.VerifiedUser, contentDescription = null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            FadingText(certificate.subject, style = MaterialTheme.typography.titleMedium)
            FadingText(
                stringOf(R.string.tls_issuer, certificate.issuer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val expiry = Instant.ofEpochMilli(certificate.notAfterMs).atZone(zone).format(dateFormat)
            Text(
                when {
                    certificate.expired -> stringOf(R.string.tls_expired, expiry)
                    !certificate.isCa -> stringOf(R.string.tls_valid_until_not_ca, expiry)
                    else -> stringOf(R.string.tls_valid_until, expiry)
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (certificate.expired) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // As much of the fingerprint as the row has room for, on one line.
            FadingText(
                "SHA-256 ${certificate.fingerprint}",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
            )
        }
        if (onRemove != null) {
            IconButton(onClick = onRemove) {
                Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.tls_remove))
            }
        }
    }
}
