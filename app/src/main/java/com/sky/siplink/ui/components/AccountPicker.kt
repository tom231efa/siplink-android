package com.sky.siplink.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sky.siplink.core.model.RegState
import com.sky.siplink.core.model.SipAccount

/**
 * Bottom sheet for choosing the account a call goes out on: search by name, number or domain,
 * or pick from the list.
 *
 * @param selectedId the account shown as chosen; null marks the "default account" row
 * @param defaultTitle title of the row that stands for whatever the default account is; null
 *   leaves that row out, [onSelect] then always gets an account
 * @param pinnedId an account to list first: the one chosen on the dial pad
 * @param note why the list is shown, when that needs saying
 */
@Composable
fun AccountPickerSheet(
    accounts: List<SipAccount>,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
    title: String = "选择呼出账号",
    defaultTitle: String? = "跟随默认账号",
    pinnedId: String? = null,
    note: String? = null,
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(accounts, query, pinnedId) {
        accounts.filter { it.enabled && it.matches(query) }.sortedByDescending { it.id == pinnedId }
    }
    AppBottomSheet(onDismiss, expandFully = false) {
        Column {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            if (note != null) {
                Text(
                    note,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                placeholder = { FieldText("搜索名称、号码或域名") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                singleLine = true,
                shape = CircleShape,
            )
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                if (defaultTitle != null && query.isBlank()) {
                    item(key = "default", contentType = "row") {
                        val default = accounts.firstOrNull { it.isDefault }
                        PickerRow(
                            title = defaultTitle,
                            subtitle = default?.let { "${it.label} · ${it.identity}" } ?: "尚未设置",
                            state = default?.state ?: RegState.NONE,
                            selected = selectedId == null,
                            onClick = { onSelect(null) },
                        )
                    }
                }
                items(filtered, key = { it.id }, contentType = { "row" }) { account ->
                    PickerRow(
                        title = account.label,
                        subtitle = (if (account.id == pinnedId) "拨号页当前选择 · " else "") +
                            "${account.identity} · ${regStateLabel(account.state)}",
                        state = account.state,
                        selected = account.id == selectedId,
                        onClick = { onSelect(account.id) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun PickerRow(
    title: String,
    subtitle: String,
    state: RegState,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tint by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else Color.Transparent,
        label = "pickerRow",
    )
    val titleColor by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        label = "pickerTitle",
    )
    Row(
        modifier
            .fillMaxWidth()
            .background(tint)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(state, size = 12.dp)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = titleColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
