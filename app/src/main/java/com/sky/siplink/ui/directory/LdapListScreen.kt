package com.sky.siplink.ui.directory

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sky.siplink.core.logic.LdapRules
import com.sky.siplink.core.model.LdapDirectory
import com.sky.siplink.engine
import com.sky.siplink.ui.components.EmptyState
import com.sky.siplink.ui.components.LocalConfigLocked
import com.sky.siplink.ui.components.LocalMessenger
import com.sky.siplink.ui.components.NavRow
import com.sky.siplink.ui.components.SectionCard
import com.sky.siplink.ui.components.SubScreen
import java.util.Locale

/**
 * The LDAP directories, and the local copy of what their servers returned. Where the configuration
 * is locked none can be added; the local copy can be emptied all the same.
 */
@Composable
fun LdapListScreen(onBack: () -> Unit, onAdd: () -> Unit, onEdit: (String) -> Unit) {
    val manager = LocalContext.current.engine.directory
    val messenger = LocalMessenger.current
    val locked = LocalConfigLocked.current
    val directories by manager.directories.collectAsStateWithLifecycle()
    val cache by manager.cacheStats.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { manager.refreshCacheStats() }

    SubScreen(
        title = "LDAP 通讯录",
        onBack = onBack,
        actions = {
            if (directories.size < LdapRules.MAX_DIRECTORIES) {
                IconButton(onClick = onAdd) { Icon(Icons.Rounded.Add, contentDescription = "添加通讯录") }
            }
        },
    ) {
        if (directories.isEmpty()) {
            EmptyState(
                icon = Icons.Rounded.Dns,
                title = "尚未配置 LDAP 通讯录",
                modifier = Modifier.padding(vertical = 48.dp),
                action = if (locked) null else ({ Button(onClick = onAdd) { Text("添加通讯录") } }),
            )
        } else {
            SectionCard(title = "${directories.size} / ${LdapRules.MAX_DIRECTORIES} 个通讯录") {
                directories.forEach { directory ->
                    NavRow(
                        title = directory.title,
                        subtitle = summary(directory),
                        icon = Icons.Rounded.Dns,
                        onClick = { onEdit(directory.id) },
                    )
                }
            }

            SectionCard(title = "本地缓存") {
                Text(
                    "已缓存 ${cache.entries} 条，占用 ${megabytes(cache.bytes)} / ${megabytes(cache.maxBytes)} MB",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp),
                )
                TextButton(
                    onClick = {
                        manager.clearCache()
                        messenger("已清空通讯录缓存")
                    },
                    enabled = cache.entries > 0,
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) { Text("清空缓存") }
            }
        }
    }
}

/** Where the directory is, and that it is switched off when it is. */
private fun summary(directory: LdapDirectory): String {
    val server = LdapRules.serverText(directory)
    return if (directory.enabled) server else "$server · 已停用"
}

/** A size in bytes as megabytes with one decimal. */
private fun megabytes(bytes: Long): String = String.format(Locale.US, "%.1f", bytes / (1024.0 * 1024.0))
