@file:OptIn(ExperimentalMaterial3Api::class)

package com.sky.siplink.ui.directory

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Power
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sky.siplink.core.logic.LdapRules
import com.sky.siplink.core.model.LdapCertCheck
import com.sky.siplink.core.model.LdapConfig
import com.sky.siplink.engine
import com.sky.siplink.ui.components.ChoiceRow
import com.sky.siplink.ui.components.FormField
import com.sky.siplink.ui.components.LocalMessenger
import com.sky.siplink.ui.components.NavRow
import com.sky.siplink.ui.components.SectionCard
import com.sky.siplink.ui.components.SubScreen
import com.sky.siplink.ui.components.SwitchRow
import java.util.Locale

@Composable
fun LdapSettingsScreen(onBack: () -> Unit, onOpenTls: () -> Unit) {
    val context = LocalContext.current
    val directory = context.engine.directory
    val messenger = LocalMessenger.current
    val stored = remember { directory.config.value }
    val initial = stored ?: LdapConfig()
    val cache by directory.cacheStats.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { directory.refreshCacheStats() }

    var enabled by rememberSaveable { mutableStateOf(initial.enabled) }
    var server by rememberSaveable { mutableStateOf(initial.server) }
    var startTls by rememberSaveable { mutableStateOf(initial.useStartTls) }
    var certCheck by rememberSaveable { mutableStateOf(initial.certCheck) }
    var bindDn by rememberSaveable { mutableStateOf(initial.bindDn) }
    var password by rememberSaveable { mutableStateOf("") }
    var baseDn by rememberSaveable { mutableStateOf(initial.baseDn) }
    var filter by rememberSaveable { mutableStateOf(initial.filter) }
    var nameAttributes by rememberSaveable { mutableStateOf(initial.nameAttributes) }
    var sipAttributes by rememberSaveable { mutableStateOf(initial.sipAttributes) }
    var sipDomain by rememberSaveable { mutableStateOf(initial.sipDomain) }
    var maxResults by rememberSaveable { mutableStateOf(initial.maxResults.toString()) }
    var minChars by rememberSaveable { mutableStateOf(initial.minChars.toString()) }
    var timeout by rememberSaveable { mutableStateOf(initial.timeoutSec.toString()) }
    var delay by rememberSaveable { mutableStateOf(initial.delayMs.toString()) }
    var errors by remember { mutableStateOf(emptyList<LdapRules.Error>()) }
    var confirmDelete by remember { mutableStateOf(false) }

    fun error(vararg candidates: LdapRules.Error): String? = errors.firstOrNull { it in candidates }?.message

    fun save() {
        val config = LdapConfig(
            enabled = enabled,
            server = server,
            useStartTls = startTls,
            certCheck = certCheck,
            bindDn = bindDn,
            password = password,
            baseDn = baseDn,
            filter = filter,
            nameAttributes = nameAttributes,
            sipAttributes = sipAttributes,
            sipDomain = sipDomain,
            // Unparseable numbers become -1 so validation reports them as out of range.
            maxResults = maxResults.trim().toIntOrNull() ?: -1,
            minChars = minChars.trim().toIntOrNull() ?: -1,
            timeoutSec = timeout.trim().toIntOrNull() ?: -1,
            delayMs = delay.trim().toIntOrNull() ?: -1,
        )
        errors = directory.save(config)
        if (errors.isEmpty()) {
            messenger("LDAP 设置已保存")
            onBack()
        } else {
            messenger(errors.first().full)
        }
    }

    SubScreen(
        title = "LDAP 通讯录",
        onBack = onBack,
        actions = { TextButton(onClick = ::save) { Text("保存") } },
    ) {
        SectionCard(title = null) {
            SwitchRow(
                title = "启用 LDAP 通讯录",
                icon = Icons.Rounded.Power,
                checked = enabled,
                onCheckedChange = { enabled = it },
            )
        }

        SectionCard(title = "服务器") {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FormField(
                    value = server,
                    onValueChange = { server = it },
                    label = "服务器地址",
                    placeholder = "ldap://ldap.example.com:389",
                    keyboardType = KeyboardType.Uri,
                    error = error(
                        LdapRules.Error.SERVER_EMPTY,
                        LdapRules.Error.SERVER_INVALID,
                        LdapRules.Error.SERVER_LDAPS,
                        LdapRules.Error.STARTTLS_NEEDS_HOST_NAME,
                    ),
                )
                FormField(
                    value = bindDn,
                    onValueChange = { bindDn = it },
                    label = "绑定 DN（留空为匿名）",
                    placeholder = "cn=reader,dc=example,dc=com",
                    error = error(LdapRules.Error.PASSWORD_WITHOUT_BIND_DN),
                )
                FormField(
                    value = password,
                    onValueChange = { password = it },
                    label = "密码",
                    placeholder = if (stored != null && stored.bindDn.isNotEmpty()) "留空则保持已保存的密码" else null,
                    password = true,
                )
            }
        }

        SectionCard(title = "加密") {
            SwitchRow(
                title = "StartTLS 加密",
                icon = Icons.Rounded.Lock,
                checked = startTls,
                onCheckedChange = { startTls = it },
            )
            AnimatedVisibility(startTls) {
                Column {
                    Text(
                        "服务器证书校验",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 16.dp, top = 8.dp),
                    )
                    ChoiceRow(
                        options = listOf(
                            LdapCertCheck.DEFAULT to "跟随全局",
                            LdapCertCheck.ENABLED to "始终校验",
                            LdapCertCheck.DISABLED to "不校验",
                        ),
                        selected = certCheck,
                        onSelect = { certCheck = it },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    NavRow(
                        title = "CA 证书",
                        icon = Icons.Rounded.VerifiedUser,
                        onClick = onOpenTls,
                    )
                }
            }
        }

        SectionCard(title = "搜索") {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FormField(
                    value = baseDn,
                    onValueChange = { baseDn = it },
                    label = "搜索基准 DN",
                    placeholder = "ou=people,dc=example,dc=com",
                    error = error(LdapRules.Error.BASE_DN_EMPTY),
                )
                FormField(
                    value = filter,
                    onValueChange = { filter = it },
                    label = "过滤器",
                    singleLine = false,
                    error = error(LdapRules.Error.FILTER_EMPTY, LdapRules.Error.FILTER_NO_PLACEHOLDER),
                )
                FormField(
                    value = nameAttributes,
                    onValueChange = { nameAttributes = it },
                    label = "姓名属性",
                    error = error(LdapRules.Error.NAME_ATTRIBUTES_EMPTY),
                )
                FormField(
                    value = sipAttributes,
                    onValueChange = { sipAttributes = it },
                    label = "号码属性",
                    error = error(LdapRules.Error.SIP_ATTRIBUTES_EMPTY),
                )
                FormField(
                    value = sipDomain,
                    onValueChange = { sipDomain = it },
                    label = "SIP 域（可选）",
                    keyboardType = KeyboardType.Uri,
                )
            }
        }

        SectionCard(title = "高级") {
            val numberError = error(LdapRules.Error.NUMBER_OUT_OF_RANGE)

            // The error is one for all four values, so each field adds its own range to it.
            fun rangeError(range: String): String? = numberError?.let { "应为 $range" }

            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FormField(
                        value = maxResults,
                        onValueChange = { maxResults = it },
                        label = "最多结果数",
                        keyboardType = KeyboardType.Number,
                        error = rangeError("0–1000"),
                        modifier = Modifier.weight(1f),
                    )
                    FormField(
                        value = minChars,
                        onValueChange = { minChars = it },
                        label = "最少字符数",
                        keyboardType = KeyboardType.Number,
                        error = rangeError("0–20"),
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FormField(
                        value = timeout,
                        onValueChange = { timeout = it },
                        label = "超时（秒）",
                        keyboardType = KeyboardType.Number,
                        error = rangeError("1–60"),
                        modifier = Modifier.weight(1f),
                    )
                    FormField(
                        value = delay,
                        onValueChange = { delay = it },
                        label = "查询间隔（毫秒）",
                        keyboardType = KeyboardType.Number,
                        error = rangeError("0–10000"),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        if (stored != null) {
            SectionCard(title = "本地缓存") {
                Text(
                    "已缓存 ${cache.entries} 条，占用 ${megabytes(cache.bytes)} / ${megabytes(cache.maxBytes)} MB",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp),
                )
                TextButton(
                    onClick = {
                        directory.clearCache()
                        messenger("已清空通讯录缓存")
                    },
                    enabled = cache.entries > 0,
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) { Text("清空缓存") }
            }
            OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
                Text("删除 LDAP 配置", color = MaterialTheme.colorScheme.error)
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除 LDAP 配置？") },
            text = { Text("服务器地址、绑定账号等设置和本机缓存的联系人都会被移除。") },
            confirmButton = {
                TextButton(onClick = {
                    directory.remove()
                    confirmDelete = false
                    onBack()
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        )
    }
}

/** A size in bytes as megabytes with one decimal. */
private fun megabytes(bytes: Long): String = String.format(Locale.US, "%.1f", bytes / (1024.0 * 1024.0))
