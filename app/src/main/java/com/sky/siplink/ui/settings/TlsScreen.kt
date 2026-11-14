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
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sky.siplink.core.TlsManager
import com.sky.siplink.core.model.CertInfo
import com.sky.siplink.core.model.MediaSecurity
import com.sky.siplink.engine
import com.sky.siplink.restartApp
import com.sky.siplink.ui.components.LocalMessenger
import com.sky.siplink.ui.components.SectionCard
import com.sky.siplink.ui.components.SubScreen
import com.sky.siplink.ui.components.SwitchRow
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
    val engine = context.engine
    val settings by engine.settings.settings.collectAsStateWithLifecycle()
    val certificates by engine.tls.certificates.collectAsStateWithLifecycle()
    val messenger = LocalMessenger.current
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
                data == null -> "无法读取所选文件"
                data.size > MAX_CERT_FILE_BYTES -> "文件过大，不像是证书文件"
                else -> when (val result = engine.tls.import(data)) {
                    is TlsManager.ImportResult.Added -> {
                        restartPrompt = engine.directory.tlsChangeNeedsRestart
                        "已导入 ${result.count} 个证书，正在重新注册账号"
                    }
                    TlsManager.ImportResult.AlreadyTrusted -> "这些证书已经导入过了"
                    is TlsManager.ImportResult.Invalid -> result.reason
                }
            }
            messenger(message)
        }
    }

    SubScreen(title = "TLS 与 CA 证书", onBack = onBack) {
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.tertiaryContainer) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Shield, contentDescription = null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
                Spacer(Modifier.width(12.dp))
                Text(
                    "将账号的传输协议设为 TLS 即可加密 SIP 信令。服务器证书由私有 CA 签发或为自签名时，在此导入对应的 CA 证书；它同时用于 LDAP 的 StartTLS。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
        }

        SectionCard(title = "已导入的 CA 证书") {
            if (certificates.isEmpty()) {
                Text(
                    "尚未导入证书，当前仅信任内置的公共根证书。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
            certificates.forEach { certificate ->
                CertificateRow(certificate, onRemove = { pendingRemoval = certificate })
            }
            Button(
                onClick = { picker.launch(arrayOf("*/*")) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Icon(Icons.Rounded.FileOpen, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("导入 CA 证书")
            }
            Text(
                "支持 PEM / CRT / CER / DER 格式，一个文件可包含多个证书。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
            )
        }

        SectionCard(title = "服务器证书校验") {
            SwitchRow(
                title = "校验服务器证书",
                subtitle = "确认证书由受信任的 CA 签发",
                icon = Icons.Rounded.VerifiedUser,
                checked = settings.verifyTlsCertificate,
                onCheckedChange = { value -> engine.updateSettings { it.copy(verifyTlsCertificate = value) } },
            )
            SwitchRow(
                title = "校验主机名",
                subtitle = "确认证书中的名称与服务器地址一致",
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
                        "关闭校验后连接仍会加密，但无法确认对方身份，存在被中间人窃听的风险。建议导入 CA 证书而不是关闭校验。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        SectionCard(title = "媒体加密（SRTP）") {
            val options = listOf(
                MediaSecurity.NONE to "关闭",
                MediaSecurity.SRTP_OPTIONAL to "优先",
                MediaSecurity.SRTP_MANDATORY to "强制",
            )
            SingleChoiceSegmentedButtonRow(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                options.forEachIndexed { index, (value, label) ->
                    SegmentedButton(
                        selected = settings.mediaSecurity == value,
                        onClick = { engine.updateSettings { it.copy(mediaSecurity = value) } },
                        shape = SegmentedButtonDefaults.itemShape(index, options.size),
                    ) { Text(label) }
                }
            }
            Text(
                "TLS 只加密信令。开启 SRTP 后通话语音也会加密；“强制”模式下对端不支持 SRTP 时通话将无法建立。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
    }

    pendingRemoval?.let { certificate ->
        AlertDialog(
            onDismissRequest = { pendingRemoval = null },
            title = { Text("移除证书？") },
            text = { Text("将不再信任“${certificate.subject}”签发的服务器证书，依赖它的 TLS 账号会注册失败。") },
            confirmButton = {
                TextButton(onClick = {
                    engine.tls.remove(certificate.fingerprint)
                    pendingRemoval = null
                    restartPrompt = engine.directory.tlsChangeNeedsRestart
                }) { Text("移除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingRemoval = null }) { Text("取消") } },
        )
    }

    if (restartPrompt) {
        AlertDialog(
            onDismissRequest = { restartPrompt = false },
            title = { Text("重启后对 LDAP 生效") },
            text = {
                Text(
                    "SIP 账号已经使用新的证书设置。LDAP 通讯录的加密连接在本次运行中已经建立过，" +
                        "它要到应用重启后才会改用新的 CA 证书。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (engine.calls.call.value != null) {
                        restartPrompt = false
                        messenger("通话结束后再重启应用")
                    } else {
                        context.restartApp()
                    }
                }) { Text("立即重启") }
            },
            dismissButton = { TextButton(onClick = { restartPrompt = false }) { Text("稍后") } },
        )
    }
}

@Composable
private fun CertificateRow(certificate: CertInfo, onRemove: () -> Unit) {
    val zone = remember { ZoneId.systemDefault() }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
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
            Text(certificate.subject, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "签发者：${certificate.issuer}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val expiry = Instant.ofEpochMilli(certificate.notAfterMs).atZone(zone).format(dateFormat)
            Text(
                when {
                    certificate.expired -> "已于 $expiry 过期"
                    !certificate.isCa -> "有效期至 $expiry · 非 CA 证书"
                    else -> "有效期至 $expiry"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (certificate.expired) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "SHA-256 ${certificate.fingerprint.take(29)}…",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                maxLines = 1,
            )
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Rounded.Delete, contentDescription = "移除证书")
        }
    }
}
