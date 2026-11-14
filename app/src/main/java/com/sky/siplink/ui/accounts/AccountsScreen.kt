@file:OptIn(ExperimentalMaterial3Api::class)

package com.sky.siplink.ui.accounts

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.ManageAccounts
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sky.siplink.core.logic.AccountRules
import com.sky.siplink.core.model.RegState
import com.sky.siplink.core.model.SipAccount
import com.sky.siplink.core.model.SipTransport
import com.sky.siplink.engine
import com.sky.siplink.ui.components.Avatar
import com.sky.siplink.ui.components.EmptyState
import com.sky.siplink.ui.components.LocalMessenger
import com.sky.siplink.ui.components.StatusDot
import com.sky.siplink.ui.components.regStateColor
import com.sky.siplink.ui.components.regStateLabel

@Composable
fun AccountsScreen(onAdd: () -> Unit, onEdit: (String) -> Unit) {
    val context = LocalContext.current
    val manager = context.engine.accounts
    val accounts by manager.accounts.collectAsStateWithLifecycle()
    val messenger = LocalMessenger.current
    var query by rememberSaveable { mutableStateOf("") }
    var importOpen by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val fabExpanded by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }

    val filtered = remember(accounts, query) {
        if (query.isBlank()) {
            accounts
        } else {
            accounts.filter { it.identity.contains(query, true) || it.displayName.contains(query, true) }
        }
    }
    val full = !AccountRules.canAdd(accounts.size)

    fun add() {
        if (full) messenger(AccountRules.Error.LIMIT_REACHED.message) else onAdd()
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TopAppBar(
                title = { Text("SIP 账号") },
                actions = {
                    IconButton(onClick = { if (full) messenger(AccountRules.Error.LIMIT_REACHED.message) else importOpen = true }) {
                        Icon(Icons.Rounded.UploadFile, contentDescription = "批量导入账号")
                    }
                },
            )
            if (accounts.isEmpty()) {
                EmptyState(
                    icon = Icons.Rounded.ManageAccounts,
                    title = "添加第一个 SIP 账号",
                    subtitle = "最多可以配置 ${AccountRules.MAX_ACCOUNTS} 个账号，支持 UDP、TCP 和 TLS，也可以批量导入。",
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Button(onClick = ::add) { Text("添加账号") }
                }
            } else {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item(key = "summary") { SummaryCard(accounts) }
                    if (accounts.size > 6) {
                        item(key = "search") {
                            OutlinedTextField(
                                value = query,
                                onValueChange = { query = it },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = { Text("搜索账号") },
                                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                                trailingIcon = {
                                    if (query.isNotEmpty()) {
                                        IconButton(onClick = { query = "" }) {
                                            Icon(Icons.Rounded.Close, contentDescription = "清除")
                                        }
                                    }
                                },
                                singleLine = true,
                                shape = CircleShape,
                            )
                        }
                    }
                    items(filtered, key = { it.id }) { account ->
                        AccountRow(
                            account = account,
                            onClick = { onEdit(account.id) },
                            onToggle = { manager.setEnabled(account.id, it) },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
        }

        if (accounts.isNotEmpty()) {
            ExtendedFloatingActionButton(
                onClick = ::add,
                expanded = fabExpanded,
                icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                text = { Text("添加账号") },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(20.dp),
            )
        }
    }

    if (importOpen) {
        AccountImportSheet(onDismiss = { importOpen = false })
    }
}

@Composable
private fun SummaryCard(accounts: List<SipAccount>) {
    val registered = accounts.count { it.state == RegState.OK }
    val failed = accounts.count { it.state == RegState.FAILED }
    val disabled = accounts.count { !it.enabled }
    val progress by animateFloatAsState(
        targetValue = accounts.size / AccountRules.MAX_ACCOUNTS.toFloat(),
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 80f),
        label = "accountUsage",
    )
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primaryContainer) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    accounts.size.toString(),
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    " / ${AccountRules.MAX_ACCOUNTS} 个账号",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(CircleShape),
                trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.12f),
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SummaryStat(regStateColor(RegState.OK), "已注册 $registered")
                SummaryStat(regStateColor(RegState.FAILED), "失败 $failed")
                SummaryStat(regStateColor(RegState.DISABLED), "停用 $disabled")
            }
        }
    }
}

@Composable
private fun SummaryStat(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

@Composable
private fun AccountRow(
    account: SipAccount,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            Modifier
                .clickable(onClick = onClick)
                .padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                Avatar(account.label)
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .padding(2.dp),
                ) {
                    StatusDot(account.state, size = 11.dp)
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        account.label,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (account.isDefault) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            Icons.Rounded.Star,
                            contentDescription = "默认账号",
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
                Text(
                    account.identity,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (account.transport == SipTransport.TLS) {
                        Icon(
                            Icons.Rounded.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(12.dp),
                        )
                        Spacer(Modifier.width(3.dp))
                    }
                    Text(
                        "${account.transport.name} · ${account.error ?: regStateLabel(account.state)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = regStateColor(account.state),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Switch(checked = account.enabled, onCheckedChange = onToggle)
        }
    }
}
