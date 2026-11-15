@file:OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)

package com.sky.siplink.ui.directory

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Contacts
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sky.siplink.core.model.DirectoryContact
import com.sky.siplink.engine
import com.sky.siplink.ui.components.Avatar
import com.sky.siplink.ui.components.EmptyState
import com.sky.siplink.ui.components.FeatureSheet
import com.sky.siplink.ui.components.LocalConfigLocked
import com.sky.siplink.ui.components.rememberCallLauncher
import com.sky.siplink.ui.shortcuts.NewShortcutSheet
import kotlinx.coroutines.delay

/** Gathers the letters of a quick typist into one question to the server. */
private const val SEARCH_DEBOUNCE_MS = 250L

/**
 * The LDAP directories: search one of them as you type, then call a contact with one tap.
 * Without a search it lists what the directory has, as far as the server lets it.
 */
@Composable
fun DirectoryScreen(onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val directory = context.engine.directory
    val directories by directory.directories.collectAsStateWithLifecycle()
    val state by directory.state.collectAsStateWithLifecycle()
    val accounts by context.engine.accounts.accounts.collectAsStateWithLifecycle()
    val launcher = rememberCallLauncher()
    var query by rememberSaveable { mutableStateOf("") }
    var chosen by rememberSaveable { mutableStateOf("") }
    var featuresFor by remember { mutableStateOf<DirectoryContact?>(null) }
    var shortcutFor by remember { mutableStateOf<DirectoryContact?>(null) }
    // A managed configuration takes no new shortcut key.
    val canAddShortcut = !LocalConfigLocked.current

    val usable = remember(directories) { directories.filter { it.enabled } }
    val current = usable.firstOrNull { it.id == chosen } ?: usable.firstOrNull()
    // The line of the directory, as long as that account is still there.
    val callLine = current?.line?.takeIf { line -> accounts.any { it.id == line } }

    LaunchedEffect(query, current) {
        if (current == null) return@LaunchedEffect
        delay(SEARCH_DEBOUNCE_MS)
        directory.search(current.id, query)
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("通讯录") },
            actions = {
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Rounded.Tune, contentDescription = "LDAP 设置")
                }
            },
        )

        when {
            !directory.isAvailable -> EmptyState(
                icon = Icons.Rounded.Dns,
                title = "当前 SDK 不支持 LDAP",
                modifier = Modifier.fillMaxSize(),
            )

            // The SIP stack completes directory numbers with the domain of the default account,
            // and drops every entry while there is none.
            accounts.isEmpty() -> EmptyState(
                icon = Icons.Rounded.Contacts,
                title = "请先添加 SIP 账号",
                modifier = Modifier.fillMaxSize(),
            )

            current == null -> EmptyState(
                icon = Icons.Rounded.Dns,
                title = if (directories.isEmpty()) "尚未配置 LDAP 通讯录" else "LDAP 通讯录已停用",
                modifier = Modifier.fillMaxSize(),
            ) {
                Button(onClick = onOpenSettings) { Text("LDAP 设置") }
            }

            else -> {
                if (usable.size > 1) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(bottom = 8.dp),
                    ) {
                        items(usable, key = { it.id }, contentType = { "chip" }) { item ->
                            FilterChip(
                                selected = item.id == current.id,
                                onClick = { chosen = item.id },
                                label = { Text(item.title, maxLines = 1) },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    placeholder = { Text("搜索姓名或号码") },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    trailingIcon = {
                        AnimatedVisibility(query.isNotEmpty(), enter = fadeIn(), exit = fadeOut()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Rounded.Close, contentDescription = "清除")
                            }
                        }
                    },
                    singleLine = true,
                    shape = CircleShape,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(12.dp)
                        .padding(horizontal = 32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.animation.AnimatedVisibility(state.searching, enter = fadeIn(), exit = fadeOut()) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }

                val trimmed = query.trim()
                // What another directory found is not shown as this one's.
                val contacts = if (state.directory == current.id) state.contacts else emptyList()
                when {
                    contacts.isEmpty() && !state.searching && state.directory == current.id && state.query == trimmed -> EmptyState(
                        icon = Icons.Rounded.SearchOff,
                        title = "没有匹配的联系人",
                        modifier = Modifier.fillMaxSize(),
                    )

                    else -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                        if (state.fromCache && contacts.isNotEmpty()) {
                            item(key = "cache", contentType = "note") {
                                Text(
                                    if (state.searching) "本地缓存，正在查询服务器…" else "来自本地缓存",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .animateItem()
                                        .padding(horizontal = 24.dp, vertical = 6.dp),
                                )
                            }
                        }
                        items(contacts, key = { "${it.callTarget}|${it.name}" }, contentType = { "contact" }) { contact ->
                            ContactRow(
                                contact = contact,
                                onCall = { launcher.call(contact.callTarget, callLine) },
                                onMore = { featuresFor = contact },
                                modifier = Modifier.animateItem(),
                            )
                        }
                        if (state.limitReached) {
                            item(key = "limit") {
                                Text(
                                    "仅显示前 ${contacts.size} 条",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    featuresFor?.let { contact ->
        FeatureSheet(
            launcher = launcher,
            onDismiss = { featuresFor = null },
            name = contact.name,
            target = contact.callTarget,
            onAddShortcut = if (canAddShortcut) ({ shortcutFor = contact }) else null,
        )
    }
    shortcutFor?.takeIf { canAddShortcut }?.let { contact ->
        NewShortcutSheet(name = contact.name, number = contact.callTarget, onDismiss = { shortcutFor = null })
    }
}

@Composable
private fun ContactRow(
    contact: DirectoryContact,
    onCall: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onCall, onLongClick = onMore)
            .padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(contact.name)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(contact.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (contact.organization.isEmpty()) contact.number else "${contact.number} · ${contact.organization}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        FilledIconButton(onClick = onCall) {
            Icon(Icons.Rounded.Call, contentDescription = "呼叫 ${contact.name}")
        }
        IconButton(onClick = onMore) {
            Icon(Icons.Rounded.MoreVert, contentDescription = "${contact.name} 的更多功能")
        }
    }
}
