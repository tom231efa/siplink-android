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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sky.siplink.core.RemoteConfig
import com.sky.siplink.core.logic.RemoteAddress
import com.sky.siplink.core.logic.RemoteSync
import com.sky.siplink.engine
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
        is RemoteAddress.Parsed.Invalid -> parsed.error.message
    }

    // The camera is asked for only here, when the user wants to scan; typing works without it.
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scanning = true else messenger("未获得相机权限，可改用输入地址")
    }
    val scan = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            scanning = true
        } else {
            camera.launch(Manifest.permission.CAMERA)
        }
    }

    Personal {
        SubScreen(title = "远程配置", onBack = onBack) {
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
            title = { Text("载入远程配置？") },
            text = {
                Text(
                    "来自 ${enrollment.host}。载入后，本机的账号、通讯录、证书、编码和快捷键等配置将被远程配置替换，" +
                        "并且不能在本机修改。通话记录、免打扰和外观设置不受影响。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    remote.enroll(enrollment, messenger)
                }) { Text("载入") }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("取消") } },
        )
    }

    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("删除远程配置？") },
            text = {
                Text(
                    "将通知平台解除绑定，并删除远程下发的账号、通讯录、证书和快捷键，以及自建的快捷键。" +
                        "通话记录和外观设置保留。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    deleting = false
                    remote.delete(messenger)
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("取消") } },
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
                    Text("扫描二维码", maxLines = 1)
                }
                OutlinedButton(onClick = onType, enabled = !status.busy, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Link, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("输入地址", maxLines = 1)
                }
            }
            if (status.busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("正在载入…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    SectionCard(title = "平台") {
        InfoRow("平台", status.platformName.ifEmpty { status.host })
        if (status.clientName.isNotEmpty()) InfoRow("客户端", status.clientName)
        if (status.region.isNotEmpty()) InfoRow("区域", status.region)
    }
    SectionCard(title = "同步") {
        InfoRow("上次同步", if (status.lastSyncMs > 0) RemoteSync.shownTime(status.lastSyncMs, zone) else "尚未成功")
        if (status.generatedAt.isNotEmpty()) InfoRow("配置更新于", RemoteSync.shownTime(status.generatedAt, zone))
        if (status.lastError.isNotEmpty()) InfoRow("同步结果", status.lastError, error = true)
        NavRow(
            title = if (status.busy) "正在同步…" else "立即同步",
            subtitle = "每 ${status.refreshMinutes} 分钟自动同步",
            icon = Icons.Rounded.Sync,
            onClick = { if (!status.busy) onSync() },
        )
        if (status.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
    }
    FoldingCard(title = "详细信息", open = details, onToggle = { details = !details }) {
        InfoRow("地址", status.host)
        InfoRow("配置版本", status.version.take(VERSION_SHOWN).ifEmpty { "—" })
        InfoRow("平台证书", if (status.pinned) "扫码时已信任平台 CA" else "由系统 CA 校验")
    }
    OutlinedButton(onClick = onDelete, enabled = !status.busy, modifier = Modifier.fillMaxWidth()) {
        Text("删除远程配置", color = MaterialTheme.colorScheme.error)
    }
    Spacer(Modifier.height(8.dp))
}

/** A value to read, with what it is on its left. */
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
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
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
        title = { Text("输入地址") },
        text = {
            FormField(
                value = text,
                onValueChange = {
                    text = it
                    error = null
                },
                label = "配置地址",
                placeholder = "https://example.com/p/…",
                error = error,
                singleLine = false,
            )
        },
        confirmButton = {
            TextButton(onClick = { offer(text)?.let { error = it } ?: onTaken() }, enabled = text.isNotBlank()) { Text("下一步") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** How much of the version (a SHA-256 in hex) is shown: enough to tell two apart. */
private const val VERSION_SHOWN = 12
