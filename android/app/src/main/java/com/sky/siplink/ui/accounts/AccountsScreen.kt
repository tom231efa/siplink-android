@file:OptIn(ExperimentalMaterial3Api::class)

package com.sky.siplink.ui.accounts

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sky.siplink.core.logic.AccountRules
import com.sky.siplink.core.logic.LineAdapt
import com.sky.siplink.core.model.RegState
import com.sky.siplink.core.model.SipAccount
import com.sky.siplink.core.model.SipTransport
import com.sky.siplink.engine
import com.sky.siplink.ui.Motion
import com.sky.siplink.ui.components.Avatar
import com.sky.siplink.ui.components.EmptyState
import com.sky.siplink.ui.components.FadingText
import com.sky.siplink.ui.components.FieldText
import com.sky.siplink.ui.components.LocalConfigLocked
import com.sky.siplink.ui.components.LocalMessenger
import com.sky.siplink.ui.components.ManagedLock
import com.sky.siplink.ui.components.StatusDot
import com.sky.siplink.ui.components.regStateColor
import com.sky.siplink.ui.components.regStateLabel

/** The accounts and their registrations. Where the configuration is locked they are only listed: nothing adds, imports or switches one. */
@Composable
fun AccountsScreen(onBack: () -> Unit, onAdd: () -> Unit, onEdit: (String) -> Unit) {
    val context = LocalContext.current
    val manager = context.engine.accounts
    val accounts by manager.accounts.collectAsStateWithLifecycle()
    val adapted by manager.adaptedTransports.collectAsStateWithLifecycle()
    val messenger = LocalMessenger.current
    val locked = LocalConfigLocked.current
    val focusManager = LocalFocusManager.current
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    // Only a tap on the search button focuses the field: coming back from an account with the
    // search still open must not bring the keyboard up again.
    var focusSearch by remember { mutableStateOf(false) }
    var importOpen by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val fabExpanded by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }

    val filtered = remember(accounts, query) {
        if (query.isBlank()) accounts else accounts.filter { it.matches(query) }
    }
    val full = !AccountRules.canAdd(accounts.size)
    // Without accounts the empty state takes the screen, and there is nothing to search in.
    val searchOpen = searching && accounts.isNotEmpty()

    fun add() {
        if (full) messenger(AccountRules.Error.LIMIT_REACHED.message) else onAdd()
    }

    fun openSearch() {
        searching = true
        focusSearch = true
    }

    fun closeSearch() {
        searching = false
        focusSearch = false
        query = ""
        focusManager.clearFocus()
    }
    // Back first puts the search away, like its close button.
    BackHandler(enabled = searchOpen, onBack = ::closeSearch)

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TopAppBar(
                title = { Text("SIP 账号") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回") }
                },
                actions = {
                    ManagedLock()
                    if (!locked) {
                        IconButton(onClick = { if (full) messenger(AccountRules.Error.LIMIT_REACHED.message) else importOpen = true }) {
                            Icon(Icons.Rounded.UploadFile, contentDescription = "批量导入账号")
                        }
                    }
                    IconButton(
                        onClick = { if (searching) closeSearch() else openSearch() },
                        enabled = accounts.isNotEmpty(),
                    ) {
                        Icon(Icons.Rounded.Search, contentDescription = "搜索账号")
                    }
                },
            )
            AnimatedVisibility(
                visible = searchOpen,
                enter = expandVertically(Motion.spatial()) + fadeIn(Motion.fade(160)),
                exit = shrinkVertically(Motion.spatial()) + fadeOut(Motion.fade(100)),
            ) {
                val focusRequester = remember { FocusRequester() }
                LaunchedEffect(focusSearch) {
                    if (focusSearch) {
                        focusRequester.requestFocus()
                        focusSearch = false
                    }
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 6.dp)
                        .focusRequester(focusRequester),
                    placeholder = { FieldText("搜索账号") },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    trailingIcon = {
                        IconButton(onClick = ::closeSearch) {
                            Icon(Icons.Rounded.Close, contentDescription = "关闭搜索")
                        }
                    },
                    singleLine = true,
                    shape = CircleShape,
                )
            }
            if (accounts.isEmpty()) {
                EmptyState(
                    icon = Icons.Rounded.ManageAccounts,
                    title = "暂无 SIP 账号",
                    modifier = Modifier.fillMaxSize(),
                    action = if (locked) null else ({ Button(onClick = ::add) { Text("添加账号") } }),
                )
            } else {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item(key = "summary") { SummaryRow(accounts) }
                    if (filtered.isEmpty()) {
                        item(key = "noMatch") {
                            Text(
                                "无匹配的账号",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 24.dp),
                            )
                        }
                    }
                    items(filtered, key = { it.id }) { account ->
                        AccountRow(
                            account = account,
                            adapted = adapted[account.id],
                            onClick = { onEdit(account.id) },
                            onToggle = { manager.setEnabled(account.id, it) },
                            canToggle = !locked,
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
        }

        if (accounts.isNotEmpty() && !locked) {
            ExtendedFloatingActionButton(
                onClick = ::add,
                expanded = fabExpanded,
                icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                text = { Text("添加账号") },
                // Neutral like the rest of the list; the accent is kept for what is switched on.
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(20.dp),
            )
        }
    }

    if (importOpen && !locked) {
        AccountImportSheet(onDismiss = { importOpen = false })
    }
}

/** One slim line with the totals; it only wraps when the screen is too narrow for it. */
@Composable
private fun SummaryRow(accounts: List<SipAccount>) {
    val registered = accounts.count { it.state == RegState.OK }
    val failed = accounts.count { it.state == RegState.FAILED }
    val disabled = accounts.count { !it.enabled }
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${accounts.size} / ${AccountRules.MAX_ACCOUNTS} 个账号",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            SummaryStat(regStateColor(RegState.OK), "已注册 $registered")
            SummaryStat(regStateColor(RegState.FAILED), "失败 $failed")
            SummaryStat(regStateColor(RegState.DISABLED), "停用 $disabled")
        }
    }
}

@Composable
private fun SummaryStat(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}

/** @param adapted the transport the line runs on instead of the account's own; null: its own */
@Composable
private fun AccountRow(
    account: SipAccount,
    adapted: SipTransport?,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
    canToggle: Boolean,
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
                    FadingText(
                        account.label,
                        style = MaterialTheme.typography.titleMedium,
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
                FadingText(
                    account.identity,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if ((adapted ?: account.transport) == SipTransport.TLS) {
                        Icon(
                            Icons.Rounded.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(12.dp),
                        )
                        Spacer(Modifier.width(3.dp))
                    }
                    FadingText(
                        "${adapted?.let(LineAdapt::label) ?: account.transport.name} · ${account.error ?: regStateLabel(account.state)}" +
                            if (account.onBackup) " · 备用服务器" else "",
                        style = MaterialTheme.typography.labelMedium,
                        color = regStateColor(account.state),
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Switch(checked = account.enabled, onCheckedChange = onToggle, enabled = canToggle)
        }
    }
}
