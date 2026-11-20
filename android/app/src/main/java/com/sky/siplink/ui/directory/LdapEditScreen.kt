package com.sky.siplink.ui.directory

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallMade
import androidx.compose.material.icons.automirrored.rounded.CallReceived
import androidx.compose.material.icons.rounded.Power
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sky.siplink.R
import com.sky.siplink.core.logic.LdapRules
import com.sky.siplink.core.model.LdapDirectory
import com.sky.siplink.core.model.LdapSecurity
import com.sky.siplink.core.model.SipAccount
import com.sky.siplink.core.render
import com.sky.siplink.engine
import com.sky.siplink.ui.components.ChoiceRow
import com.sky.siplink.ui.components.ConfigPasswordDialog
import com.sky.siplink.ui.components.DropdownField
import com.sky.siplink.ui.components.FoldingCard
import com.sky.siplink.ui.components.FormField
import com.sky.siplink.ui.components.LocalConfigLocked
import com.sky.siplink.ui.components.LocalMessenger
import com.sky.siplink.ui.components.NavRow
import com.sky.siplink.ui.components.SectionCard
import com.sky.siplink.ui.components.SubScreen
import com.sky.siplink.ui.components.SwitchRow

/** What is wrong with something that is folded away: the advanced settings open to show it. */
private val advancedErrors = setOf(
    LdapRules.Error.NAME_ATTRIBUTES_EMPTY,
    LdapRules.Error.NUMBER_ATTRIBUTES_EMPTY,
    LdapRules.Error.MAX_RESULTS_OUT_OF_RANGE,
    LdapRules.Error.NAME_FILTER_NO_PLACEHOLDER,
    LdapRules.Error.NUMBER_FILTER_NO_PLACEHOLDER,
)

/**
 * One LDAP directory. The server, the log-in and what the directory does for calls come first;
 * what has a default that fits most servers (attributes, filters, result limit) is folded away.
 * Where the configuration is locked the directory is only shown: its fields take no input, and
 * nothing saves or deletes it.
 *
 * @param directoryId the directory to edit, null for a new one
 */
@Composable
fun LdapEditScreen(directoryId: String?, onBack: () -> Unit, onOpenTls: () -> Unit) {
    val context = LocalContext.current
    val engine = context.engine
    val manager = engine.directory
    val messenger = LocalMessenger.current
    val locked = LocalConfigLocked.current
    val accounts by engine.accounts.accounts.collectAsStateWithLifecycle()
    val stored = remember { manager.directories.value.firstOrNull { it.id == directoryId } }
    val initial = stored ?: LdapDirectory()

    var name by rememberSaveable { mutableStateOf(initial.name) }
    var server by rememberSaveable { mutableStateOf(LdapRules.serverText(initial)) }
    var baseDn by rememberSaveable { mutableStateOf(initial.baseDn) }
    var security by rememberSaveable { mutableStateOf(initial.security) }
    // Most servers want a user; a directory that is stored says for itself whether it has one.
    var logsIn by rememberSaveable { mutableStateOf(stored == null || stored.bindDn.isNotEmpty()) }
    var bindDn by rememberSaveable { mutableStateOf(initial.bindDn) }
    // The saved password stands in the field, to look at or to change.
    var password by rememberSaveable { mutableStateOf(stored?.let { manager.passwordOf(it.id) }.orEmpty()) }
    // A managed configuration with a password of its own keeps the saved one back until that is typed in.
    val passwordsProtected by engine.lock.passwordsProtected.collectAsStateWithLifecycle()
    var passwordOpened by rememberSaveable { mutableStateOf(false) }
    var askConfigPassword by remember { mutableStateOf(false) }
    var line by rememberSaveable { mutableStateOf(initial.line) }
    var lookupIncoming by rememberSaveable { mutableStateOf(initial.lookupIncoming) }
    var lookupOutgoing by rememberSaveable { mutableStateOf(initial.lookupOutgoing) }
    var nameAttributes by rememberSaveable { mutableStateOf(initial.nameAttributes) }
    var numberAttributes by rememberSaveable { mutableStateOf(initial.numberAttributes) }
    var maxResults by rememberSaveable { mutableStateOf(initial.maxResults.toString()) }
    var ownFilters by rememberSaveable { mutableStateOf(initial.nameFilter.isNotEmpty() || initial.numberFilter.isNotEmpty()) }
    var nameFilter by rememberSaveable { mutableStateOf(initial.nameFilter) }
    var numberFilter by rememberSaveable { mutableStateOf(initial.numberFilter) }
    var enabled by rememberSaveable { mutableStateOf(initial.enabled) }
    var advancedOpen by rememberSaveable { mutableStateOf(false) }
    var errors by remember { mutableStateOf(emptyList<LdapRules.Error>()) }
    var userMissing by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    fun error(vararg candidates: LdapRules.Error): String? = errors.firstOrNull { it in candidates }?.message?.let(context::render)

    val userRequired = stringResource(R.string.directory_user_required)
    val saved = stringResource(R.string.directory_saved)
    fun save() {
        userMissing = logsIn && bindDn.isBlank()
        if (userMissing) {
            messenger(userRequired)
            return
        }
        val directory = LdapDirectory(
            id = stored?.id.orEmpty(),
            name = name,
            enabled = enabled,
            // A port typed behind the host is taken from there.
            host = server,
            security = security,
            bindDn = if (logsIn) bindDn else "",
            password = if (logsIn) password else "",
            baseDn = baseDn,
            line = line,
            lookupIncoming = lookupIncoming,
            lookupOutgoing = lookupOutgoing,
            // An unparseable number becomes -1, which validation reports as out of range.
            maxResults = maxResults.trim().toIntOrNull() ?: -1,
            nameAttributes = nameAttributes,
            numberAttributes = numberAttributes,
            nameFilter = if (ownFilters) nameFilter else "",
            numberFilter = if (ownFilters) numberFilter else "",
        )
        errors = manager.save(directory)
        if (errors.isEmpty()) {
            messenger(saved)
            onBack()
        } else {
            if (errors.any { it in advancedErrors }) advancedOpen = true
            messenger(context.render(errors.first().full))
        }
    }

    SubScreen(
        title = when {
            stored == null -> stringResource(R.string.directory_add_title)
            locked -> stringResource(R.string.directory_details_title)
            else -> stringResource(R.string.directory_edit_title)
        },
        onBack = onBack,
        actions = { TextButton(onClick = ::save) { Text(stringResource(R.string.common_save)) } },
    ) {
        SectionCard(title = stringResource(R.string.directory_section_server)) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FormField(
                    value = name,
                    onValueChange = { name = it.take(LdapRules.MAX_NAME_LENGTH) },
                    label = stringResource(R.string.directory_name_optional),
                    error = error(LdapRules.Error.NAME_TAKEN, LdapRules.Error.NAME_TOO_LONG),
                )
                FormField(
                    value = server,
                    onValueChange = { server = it },
                    label = stringResource(R.string.directory_server_address),
                    placeholder = stringResource(R.string.directory_server_placeholder),
                    keyboardType = KeyboardType.Uri,
                    error = error(
                        LdapRules.Error.HOST_EMPTY,
                        LdapRules.Error.HOST_INVALID,
                        LdapRules.Error.HOST_LDAPS,
                        LdapRules.Error.STARTTLS_NEEDS_HOST_NAME,
                        LdapRules.Error.PORT_OUT_OF_RANGE,
                    ),
                )
                FormField(
                    value = baseDn,
                    onValueChange = { baseDn = it },
                    label = stringResource(R.string.directory_base_dn),
                    placeholder = "dc=example,dc=com",
                    error = error(LdapRules.Error.BASE_DN_EMPTY),
                )
                Text(stringResource(R.string.directory_encryption), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
                ChoiceRow(
                    options = listOf(LdapSecurity.NONE to stringResource(R.string.directory_encryption_none), LdapSecurity.START_TLS to "StartTLS"),
                    selected = security,
                    onSelect = { security = it },
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
            AnimatedVisibility(security == LdapSecurity.START_TLS) {
                NavRow(title = stringResource(R.string.directory_ca_certificates), icon = Icons.Rounded.VerifiedUser, onClick = onOpenTls)
            }
        }

        SectionCard(title = stringResource(R.string.directory_section_sign_in)) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ChoiceRow(
                    options = listOf(
                        true to stringResource(R.string.directory_sign_in_account),
                        false to stringResource(R.string.directory_sign_in_anonymous),
                    ),
                    selected = logsIn,
                    onSelect = { logsIn = it },
                    modifier = Modifier.padding(vertical = 4.dp),
                )
                AnimatedVisibility(logsIn) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        FormField(
                            value = bindDn,
                            onValueChange = {
                                bindDn = it
                                userMissing = false
                            },
                            label = stringResource(R.string.directory_user_name),
                            placeholder = "cn=reader,dc=example,dc=com",
                            error = if (userMissing) stringResource(R.string.form_required) else null,
                        )
                        FormField(
                            value = password,
                            onValueChange = { password = it },
                            label = stringResource(R.string.directory_password),
                            placeholder = if (stored != null && stored.bindDn.isNotEmpty() && !locked) {
                                stringResource(R.string.directory_password_keep)
                            } else {
                                null
                            },
                            password = true,
                            onUnlock = if (stored != null && passwordsProtected && !passwordOpened) ({ askConfigPassword = true }) else null,
                        )
                    }
                }
            }
        }

        SectionCard(title = stringResource(R.string.directory_section_calls)) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                // "" stands for every line.
                val lines = remember(accounts) { listOf("") + accounts.map { it.id } }
                val allLines = stringResource(R.string.directory_all_lines)
                val namedLine = stringResource(R.string.directory_line_named)
                val lineName = { id: String -> lineLabel(accounts.firstOrNull { it.id == id }, allLines, namedLine) }
                DropdownField(
                    value = lineName(line),
                    label = stringResource(R.string.directory_line),
                    options = lines,
                    optionLabel = lineName,
                    onSelect = { line = it },
                    selected = line,
                )
            }
            SwitchRow(
                title = stringResource(R.string.directory_lookup_incoming),
                icon = Icons.AutoMirrored.Rounded.CallReceived,
                checked = lookupIncoming,
                onCheckedChange = { lookupIncoming = it },
            )
            SwitchRow(
                title = stringResource(R.string.directory_lookup_outgoing),
                icon = Icons.AutoMirrored.Rounded.CallMade,
                checked = lookupOutgoing,
                onCheckedChange = { lookupOutgoing = it },
            )
        }

        FoldingCard(title = stringResource(R.string.directory_advanced), open = advancedOpen, onToggle = { advancedOpen = !advancedOpen }) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FormField(
                    value = nameAttributes,
                    onValueChange = { nameAttributes = it },
                    label = stringResource(R.string.directory_name_attributes),
                    error = error(LdapRules.Error.NAME_ATTRIBUTES_EMPTY),
                )
                FormField(
                    value = numberAttributes,
                    onValueChange = { numberAttributes = it },
                    label = stringResource(R.string.directory_number_attributes),
                    error = error(LdapRules.Error.NUMBER_ATTRIBUTES_EMPTY),
                )
                FormField(
                    value = maxResults,
                    onValueChange = { maxResults = it },
                    label = stringResource(R.string.directory_max_results),
                    keyboardType = KeyboardType.Number,
                    error = error(LdapRules.Error.MAX_RESULTS_OUT_OF_RANGE),
                )
                Text(stringResource(R.string.directory_filter), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
                ChoiceRow(
                    options = listOf(
                        false to stringResource(R.string.directory_filter_auto),
                        true to stringResource(R.string.directory_filter_custom),
                    ),
                    selected = ownFilters,
                    onSelect = { own ->
                        // Start from what the attributes make, which is what was in use until now.
                        if (own && nameFilter.isBlank()) nameFilter = LdapRules.filterOver(LdapRules.attributes(nameAttributes))
                        if (own && numberFilter.isBlank()) numberFilter = LdapRules.filterOver(LdapRules.attributes(numberAttributes))
                        ownFilters = own
                    },
                    modifier = Modifier.padding(vertical = 4.dp),
                )
                AnimatedVisibility(ownFilters) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        FormField(
                            value = nameFilter,
                            onValueChange = { nameFilter = it },
                            label = stringResource(R.string.directory_name_filter),
                            singleLine = false,
                            error = error(LdapRules.Error.NAME_FILTER_NO_PLACEHOLDER),
                        )
                        FormField(
                            value = numberFilter,
                            onValueChange = { numberFilter = it },
                            label = stringResource(R.string.directory_number_filter),
                            singleLine = false,
                            error = error(LdapRules.Error.NUMBER_FILTER_NO_PLACEHOLDER),
                        )
                    }
                }
            }
        }

        SectionCard(title = null) {
            SwitchRow(
                title = stringResource(R.string.directory_enable),
                icon = Icons.Rounded.Power,
                checked = enabled,
                onCheckedChange = { enabled = it },
            )
        }

        if (stored != null && !locked) {
            OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.directory_delete), color = MaterialTheme.colorScheme.error)
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    if (askConfigPassword && stored != null) {
        ConfigPasswordDialog(onDismiss = { askConfigPassword = false }) { typed ->
            password = manager.passwordOf(stored.id, typed)
            passwordOpened = true
            askConfigPassword = false
        }
    }

    if (confirmDelete && stored != null && !locked) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.directory_delete_title, stored.title)) },
            text = { Text(stringResource(R.string.directory_delete_text)) },
            confirmButton = {
                TextButton(onClick = {
                    manager.remove(stored.id)
                    confirmDelete = false
                    onBack()
                }) { Text(stringResource(R.string.common_confirm_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

/**
 * An account as the line of a directory; none means the directory is for every line, [allLines].
 * [named] is the pattern of an account with a name: the name, then the identity.
 */
private fun lineLabel(account: SipAccount?, allLines: String, named: String): String = when {
    account == null -> allLines
    account.name.isBlank() -> account.identity
    else -> named.format(account.name, account.identity)
}
