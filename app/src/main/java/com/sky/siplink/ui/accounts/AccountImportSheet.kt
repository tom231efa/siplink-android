@file:OptIn(ExperimentalMaterial3Api::class)

package com.sky.siplink.ui.accounts

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.sky.siplink.core.logic.AccountImport
import com.sky.siplink.core.logic.AccountRules
import com.sky.siplink.engine
import com.sky.siplink.ui.components.LocalMessenger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MAX_IMPORT_BYTES = 512 * 1024

/** Bulk import of accounts from pasted text or a CSV file. */
@Composable
fun AccountImportSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val manager = context.engine.accounts
    val messenger = LocalMessenger.current
    val scope = rememberCoroutineScope()
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
            if (content == null) messenger("无法读取所选文件") else text = content
        }
    }

    fun import() {
        val existing = manager.accounts.value.map { AccountRules.identityKey(it.username, it.domain) }
        val result = AccountImport.parse(text, existing)
        val created = if (result.drafts.isEmpty()) 0 else manager.addAll(result.drafts)
        if (result.errors.isEmpty() && result.skippedForLimit == 0 && created > 0) {
            messenger("已导入 $created 个账号")
            onDismiss()
            return
        }
        report = buildString {
            append("已导入 $created 个账号")
            if (result.skippedForLimit > 0) {
                append("；${result.skippedForLimit} 个因超过 ${AccountRules.MAX_ACCOUNTS} 个上限被跳过")
            }
            if (result.errors.isNotEmpty()) {
                append("；${result.errors.size} 行有问题：")
                result.errors.take(8).forEach { append("\n第 ${it.line} 行：${it.reason}") }
                if (result.errors.size > 8) append("\n……")
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("批量导入账号", style = MaterialTheme.typography.titleLarge)
            Text(
                "每行一个账号，字段以英文逗号分隔；以 # 开头的行会被忽略。密码中含逗号时请用英文双引号包住。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Text(
                    AccountImport.FORMAT_HINT,
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
                placeholder = { Text("1001,password,pbx.example.com,tls,张三") },
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
                    Text("选择文件")
                }
                Button(onClick = ::import, enabled = text.isNotBlank(), modifier = Modifier.weight(1f)) {
                    Text("导入")
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}
