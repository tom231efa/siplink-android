@file:OptIn(ExperimentalMaterial3Api::class)

package com.sky.siplink.ui.accounts

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.Power
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.Voicemail
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sky.siplink.R
import com.sky.siplink.core.logic.AccountRoute
import com.sky.siplink.core.logic.AccountRules
import com.sky.siplink.core.logic.ShortcutLines
import com.sky.siplink.core.model.AccountDraft
import com.sky.siplink.core.model.SipTransport
import com.sky.siplink.core.render
import com.sky.siplink.engine
import com.sky.siplink.ui.components.ChoiceRow
import com.sky.siplink.ui.components.ConfigPasswordDialog
import com.sky.siplink.ui.components.FoldingCard
import com.sky.siplink.ui.components.FormField
import com.sky.siplink.ui.components.LocalConfigLocked
import com.sky.siplink.ui.components.LocalMessenger
import com.sky.siplink.ui.components.NavRow
import com.sky.siplink.ui.components.SectionCard
import com.sky.siplink.ui.components.SubScreen
import com.sky.siplink.ui.components.SwitchRow
import com.sky.siplink.ui.components.pluralOf
import com.sky.siplink.ui.components.stringOf

/** The errors of the fields under "advanced settings". */
private val advancedErrors = setOf(
    AccountRules.Error.SERVER_INVALID,
    AccountRules.Error.BACKUP_INVALID,
    AccountRules.Error.BACKUP_SAME,
    AccountRules.Error.TIMEOUT_INVALID,
    AccountRules.Error.DNS_INVALID,
    AccountRules.Error.EXPIRES_OUT_OF_RANGE,
)

private val PORT_FIELD_WIDTH = 124.dp

private fun defaultPort(transport: SipTransport): String = AccountRoute.defaultPort(transport).toString()

/**
 * Adds a new account ([accountId] == null) or edits an existing one. Where the configuration is
 * locked the account is only shown: its fields take no input, and nothing saves, deletes or
 * makes it the default one.
 */
@Composable
fun AccountEditScreen(accountId: String?, onBack: () -> Unit, onOpenTls: () -> Unit, onOpenLine: (String) -> Unit = {}) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val manager = context.engine.accounts
    val messenger = LocalMessenger.current
    val locked = LocalConfigLocked.current
    val defaultSet = stringResource(R.string.account_default_set)
    val existing = remember(accountId) { accountId?.let { id -> manager.accounts.value.firstOrNull { it.id == id } } }
    // Normalized, the draft has the port of the account apart from its domain.
    val initial = existing?.toDraft()?.let(AccountRules::normalize) ?: AccountDraft()

    var name by rememberSaveable { mutableStateOf(initial.name) }
    var username by rememberSaveable { mutableStateOf(initial.username) }
    // The saved password stands in the field, to look at or to change.
    var password by rememberSaveable { mutableStateOf(existing?.let { manager.passwordOf(it.id) }.orEmpty()) }
    // A managed configuration with a password of its own keeps the saved one back until that is typed in.
    val passwordsProtected by context.engine.lock.passwordsProtected.collectAsStateWithLifecycle()
    var passwordOpened by rememberSaveable { mutableStateOf(false) }
    var askConfigPassword by remember { mutableStateOf(false) }
    var domain by rememberSaveable { mutableStateOf(initial.domain) }
    // Without a port of its own the account shows the one its protocol has by itself.
    var port by rememberSaveable { mutableStateOf(initial.port.ifEmpty { defaultPort(initial.transport) }) }
    var displayName by rememberSaveable { mutableStateOf(initial.displayName) }
    var transport by rememberSaveable { mutableStateOf(initial.transport) }
    var authUser by rememberSaveable { mutableStateOf(initial.authUser) }
    var server by rememberSaveable { mutableStateOf(initial.server) }
    var backupServer by rememberSaveable { mutableStateOf(initial.backupServer) }
    var dnsServer by rememberSaveable { mutableStateOf(initial.dnsServer) }
    var serverTimeout by rememberSaveable { mutableStateOf(initial.serverTimeoutMs.takeIf { it > 0 }?.toString().orEmpty()) }
    var expires by rememberSaveable { mutableStateOf(initial.expires.toString()) }
    var subscribeMwi by rememberSaveable { mutableStateOf(initial.subscribeMwi) }
    var enabled by rememberSaveable { mutableStateOf(initial.enabled) }
    var advancedOpen by rememberSaveable {
        mutableStateOf(
            initial.authUser.isNotEmpty() || initial.server.isNotEmpty() || initial.backupServer.isNotEmpty() ||
                initial.dnsServer.isNotEmpty() || initial.subscribeMwi,
        )
    }
    var errors by remember { mutableStateOf(emptyList<AccountRules.Error>()) }
    var confirmDelete by remember { mutableStateOf(false) }

    fun error(vararg candidates: AccountRules.Error): String? = errors.firstOrNull { it in candidates }?.message?.let(context::render)

    fun save() {
        val draft = AccountDraft(
            name = name,
            username = username,
            password = password,
            domain = domain,
            // The port the protocol has by itself is not written into the address, so that a domain
            // whose DNS records name another one is still found; an account that has it there keeps it.
            port = port.trim().takeUnless { it == defaultPort(transport) && it != initial.port }.orEmpty(),
            displayName = displayName,
            authUser = authUser,
            server = server,
            backupServer = backupServer,
            dnsServer = dnsServer,
            // Empty is off; what is not a number is refused as out of range.
            serverTimeoutMs = serverTimeout.trim().let { if (it.isEmpty()) 0 else it.toIntOrNull() ?: -1 },
            transport = transport,
            // An unparseable value becomes 0 so validation reports it as out of range.
            expires = expires.trim().toIntOrNull() ?: 0,
            subscribeMwi = subscribeMwi,
            enabled = enabled,
        )
        errors = if (existing == null) manager.add(draft) else manager.update(existing.id, draft)
        // An error in a field that is folded away would go unseen.
        if (errors.any { it in advancedErrors }) advancedOpen = true
        if (errors.isEmpty()) {
            messenger(resources.getString(if (existing == null) R.string.account_added else R.string.account_saved))
            onBack()
        } else {
            messenger(resources.render(errors.first().full))
        }
    }

    SubScreen(
        title = when {
            existing == null -> stringResource(R.string.account_edit_title_new)
            locked -> stringResource(R.string.account_edit_title_view)
            else -> stringResource(R.string.account_edit_title_edit)
        },
        onBack = onBack,
        actions = { TextButton(onClick = ::save) { Text(stringResource(R.string.common_save)) } },
    ) {
        SectionCard(title = stringResource(R.string.account_section_info)) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FormField(
                    value = name,
                    onValueChange = { name = it },
                    label = stringResource(R.string.account_field_name),
                    placeholder = stringResource(R.string.account_field_name_hint),
                    error = error(AccountRules.Error.NAME_TOO_LONG, AccountRules.Error.NAME_TAKEN),
                )
                // The two fields stay aligned at their tops when one of them shows an error line.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                    FormField(
                        value = username,
                        onValueChange = { username = it },
                        label = stringResource(R.string.account_field_username),
                        modifier = Modifier.weight(1f),
                        placeholder = stringResource(R.string.account_field_username_hint),
                        keyboardType = KeyboardType.Email,
                        error = error(
                            AccountRules.Error.USERNAME_EMPTY,
                            AccountRules.Error.USERNAME_INVALID,
                            AccountRules.Error.DUPLICATE,
                            AccountRules.Error.LIMIT_REACHED,
                        ),
                    )
                    FormField(
                        value = port,
                        onValueChange = { port = it },
                        label = stringResource(R.string.account_field_port),
                        modifier = Modifier.width(PORT_FIELD_WIDTH),
                        keyboardType = KeyboardType.Number,
                        error = error(AccountRules.Error.PORT_INVALID),
                    )
                }
                FormField(
                    value = domain,
                    onValueChange = { domain = it },
                    label = stringResource(R.string.account_field_domain),
                    placeholder = stringResource(R.string.account_field_domain_hint),
                    keyboardType = KeyboardType.Uri,
                    error = error(
                        AccountRules.Error.DOMAIN_EMPTY,
                        AccountRules.Error.DOMAIN_INVALID,
                        AccountRules.Error.ADDRESS_REJECTED,
                    ),
                )
                FormField(
                    value = password,
                    onValueChange = { password = it },
                    label = stringResource(R.string.account_field_password),
                    placeholder = if (existing != null && !locked) stringResource(R.string.account_field_password_keep) else null,
                    password = true,
                    error = error(AccountRules.Error.PASSWORD_REQUIRED),
                    onUnlock = if (existing != null && passwordsProtected && !passwordOpened) ({ askConfigPassword = true }) else null,
                )
                FormField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = stringResource(R.string.account_field_display_name),
                )
            }
        }

        SectionCard(title = stringResource(R.string.account_section_transport)) {
            ChoiceRow(
                options = SipTransport.entries.map { it to it.name },
                selected = transport,
                onSelect = {
                    // The port follows the protocol for as long as it is the one the protocol has by itself.
                    if (port.isBlank() || port.trim() == defaultPort(transport)) port = defaultPort(it)
                    transport = it
                },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )
            AnimatedVisibility(transport == SipTransport.TLS) {
                NavRow(
                    title = stringResource(R.string.account_tls),
                    icon = Icons.Rounded.VerifiedUser,
                    onClick = onOpenTls,
                )
            }
        }

        FoldingCard(title = stringResource(R.string.account_section_advanced), open = advancedOpen, onToggle = { advancedOpen = !advancedOpen }) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FormField(
                    value = authUser,
                    onValueChange = { authUser = it },
                    label = stringResource(R.string.account_field_auth_user),
                    machineValue = true,
                )
                FormField(
                    value = server,
                    onValueChange = { server = it },
                    label = stringResource(R.string.account_field_proxy),
                    placeholder = stringResource(R.string.form_placeholder_host_port),
                    keyboardType = KeyboardType.Uri,
                    error = error(AccountRules.Error.SERVER_INVALID),
                )
                FormField(
                    value = backupServer,
                    onValueChange = { backupServer = it },
                    label = stringResource(R.string.account_field_backup_proxy),
                    placeholder = stringResource(R.string.form_placeholder_host_port),
                    keyboardType = KeyboardType.Uri,
                    error = error(AccountRules.Error.BACKUP_INVALID, AccountRules.Error.BACKUP_SAME),
                )
                AnimatedVisibility(backupServer.isNotBlank()) {
                    FormField(
                        value = serverTimeout,
                        onValueChange = { serverTimeout = it },
                        label = stringResource(R.string.account_field_fast_timeout),
                        keyboardType = KeyboardType.Number,
                        error = error(AccountRules.Error.TIMEOUT_INVALID),
                    )
                }
                FormField(
                    value = dnsServer,
                    onValueChange = { dnsServer = it },
                    label = stringResource(R.string.account_field_dns),
                    placeholder = stringResource(R.string.account_field_dns_hint),
                    keyboardType = KeyboardType.Uri,
                    error = error(AccountRules.Error.DNS_INVALID),
                )
                FormField(
                    value = expires,
                    onValueChange = { expires = it },
                    label = stringResource(R.string.account_field_expires),
                    keyboardType = KeyboardType.Number,
                    error = error(AccountRules.Error.EXPIRES_OUT_OF_RANGE),
                )
            }
            SwitchRow(
                title = stringResource(R.string.account_subscribe_mwi),
                icon = Icons.Rounded.Voicemail,
                checked = subscribeMwi,
                onCheckedChange = { subscribeMwi = it },
            )
        }

        if (existing != null) {
            // Also under a managed configuration: the checks only look.
            SectionCard(title = null) {
                NavRow(
                    title = stringResource(R.string.account_line_diagnosis),
                    icon = Icons.Rounded.NetworkCheck,
                    onClick = { onOpenLine(existing.id) },
                )
            }
        }

        SectionCard(title = null) {
            SwitchRow(
                title = stringResource(R.string.account_enable),
                icon = Icons.Rounded.Power,
                checked = enabled,
                onCheckedChange = { enabled = it },
            )
        }

        if (existing != null && !locked) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(
                    onClick = {
                        manager.setDefault(existing.id)
                        messenger(defaultSet)
                    },
                    enabled = !existing.isDefault,
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(if (existing.isDefault) R.string.account_is_default else R.string.account_set_default)) }
                OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.account_delete), color = MaterialTheme.colorScheme.error)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    if (askConfigPassword && existing != null) {
        ConfigPasswordDialog(onDismiss = { askConfigPassword = false }) { typed ->
            password = manager.passwordOf(existing.id, typed)
            passwordOpened = true
            askConfigPassword = false
        }
    }

    if (confirmDelete && existing != null && !locked) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.account_delete_title)) },
            text = {
                val shortcuts by context.engine.shortcuts.shortcuts.collectAsStateWithLifecycle()
                val keys = ShortcutLines.fixedTo(existing, shortcuts)
                Text(
                    stringOf(R.string.account_delete_text, existing.identity) +
                        if (keys > 0) pluralOf(R.plurals.account_delete_keys, keys, keys.toString()) else "",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    manager.remove(existing.id)
                    confirmDelete = false
                    onBack()
                }) { Text(stringResource(R.string.common_confirm_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}
