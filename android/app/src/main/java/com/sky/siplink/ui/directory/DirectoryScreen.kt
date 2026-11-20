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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sky.siplink.R
import com.sky.siplink.core.logic.ListKeys
import com.sky.siplink.core.model.DirectoryContact
import com.sky.siplink.engine
import com.sky.siplink.ui.components.Avatar
import com.sky.siplink.ui.components.EmptyState
import com.sky.siplink.ui.components.FadingText
import com.sky.siplink.ui.components.FeatureSheet
import com.sky.siplink.ui.components.FieldText
import com.sky.siplink.ui.components.pluralOf
import com.sky.siplink.ui.components.rememberCallLauncher
import com.sky.siplink.ui.components.stringOf
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

    val usable = remember(directories) { directories.filter { it.enabled } }
    val current = usable.firstOrNull { it.id == chosen } ?: usable.firstOrNull()
    // The line of the directory, as long as that account is still there.
    val callLine = current?.line?.takeIf { line -> accounts.any { it.id == line } }

    LaunchedEffect(query, current) {
        if (current == null) return@LaunchedEffect
        delay(SEARCH_DEBOUNCE_MS)
        directory.search(current.id, query)
    }
    // The directory answers one search at a time, and the sheet that adds members to a conference
    // room may have asked its own since this screen was last seen: back on screen, it asks again.
    var seen by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        if (seen && current != null) directory.search(current.id, query)
        seen = true
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.directory_title)) },
            actions = {
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Rounded.Tune, contentDescription = stringResource(R.string.directory_ldap_settings))
                }
            },
        )

        when {
            !directory.isAvailable -> EmptyState(
                icon = Icons.Rounded.Dns,
                title = stringResource(R.string.directory_no_ldap),
                modifier = Modifier.fillMaxSize(),
            )

            // The SIP stack completes directory numbers with the domain of the default account,
            // and drops every entry while there is none.
            accounts.isEmpty() -> EmptyState(
                icon = Icons.Rounded.Contacts,
                title = stringResource(R.string.directory_add_account_first),
                modifier = Modifier.fillMaxSize(),
            )

            current == null -> EmptyState(
                icon = Icons.Rounded.Dns,
                title = stringResource(if (directories.isEmpty()) R.string.directory_none_configured else R.string.directory_all_disabled),
                modifier = Modifier.fillMaxSize(),
            ) {
                Button(onClick = onOpenSettings) { Text(stringResource(R.string.directory_ldap_settings)) }
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
                                // A long name keeps the chip from taking the whole row.
                                label = { FadingText(item.title, modifier = Modifier.widthIn(max = 200.dp)) },
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
                    placeholder = { FieldText(stringResource(R.string.directory_search)) },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    trailingIcon = {
                        AnimatedVisibility(query.isNotEmpty(), enter = fadeIn(), exit = fadeOut()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.common_clear))
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
                // Two entries of the directory may have the same name and number. Made once per
                // answer, not each time the screen is composed again (as each letter is typed).
                val keys = remember(contacts) { ListKeys.distinct(contacts.map { "${it.callTarget}|${it.name}" }) }
                when {
                    contacts.isEmpty() && !state.searching && state.directory == current.id && state.query == trimmed -> EmptyState(
                        icon = Icons.Rounded.SearchOff,
                        title = stringResource(R.string.directory_no_match),
                        modifier = Modifier.fillMaxSize(),
                    )

                    else -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                        if (state.fromCache && contacts.isNotEmpty()) {
                            item(key = "cache", contentType = "note") {
                                Text(
                                    stringResource(if (state.searching) R.string.directory_cache_searching else R.string.directory_from_cache),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .animateItem()
                                        .padding(horizontal = 24.dp, vertical = 6.dp),
                                )
                            }
                        }
                        itemsIndexed(contacts, key = { index, _ -> keys[index] }, contentType = { _, _ -> "contact" }) { _, contact ->
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
                                    pluralOf(R.plurals.directory_first_only, contacts.size, contacts.size.toString()),
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
            onAddShortcut = { shortcutFor = contact },
        )
    }
    shortcutFor?.let { contact ->
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
            FadingText(contact.name, style = MaterialTheme.typography.titleMedium)
            FadingText(
                if (contact.organization.isEmpty()) {
                    contact.number
                } else {
                    stringOf(R.string.directory_number_organization, contact.number, contact.organization)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FilledIconButton(onClick = onCall) {
            Icon(Icons.Rounded.Call, contentDescription = stringOf(R.string.directory_call_contact, contact.name))
        }
        IconButton(onClick = onMore) {
            Icon(Icons.Rounded.MoreVert, contentDescription = stringOf(R.string.directory_more_for, contact.name))
        }
    }
}
