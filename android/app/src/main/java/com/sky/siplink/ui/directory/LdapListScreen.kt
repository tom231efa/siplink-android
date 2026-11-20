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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sky.siplink.R
import com.sky.siplink.core.logic.LdapRules
import com.sky.siplink.core.model.LdapDirectory
import com.sky.siplink.engine
import com.sky.siplink.ui.components.EmptyState
import com.sky.siplink.ui.components.LocalConfigLocked
import com.sky.siplink.ui.components.LocalMessenger
import com.sky.siplink.ui.components.NavRow
import com.sky.siplink.ui.components.SectionCard
import com.sky.siplink.ui.components.SubScreen
import com.sky.siplink.ui.components.pluralOf
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
        title = stringResource(R.string.directory_ldap_title),
        onBack = onBack,
        actions = {
            if (directories.size < LdapRules.MAX_DIRECTORIES) {
                IconButton(onClick = onAdd) { Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.directory_add)) }
            }
        },
    ) {
        if (directories.isEmpty()) {
            EmptyState(
                icon = Icons.Rounded.Dns,
                title = stringResource(R.string.directory_none_configured),
                modifier = Modifier.padding(vertical = 48.dp),
                action = if (locked) null else ({ Button(onClick = onAdd) { Text(stringResource(R.string.directory_add)) } }),
            )
        } else {
            SectionCard(
                title = pluralOf(
                    R.plurals.directory_count,
                    LdapRules.MAX_DIRECTORIES,
                    directories.size.toString(),
                    LdapRules.MAX_DIRECTORIES.toString(),
                ),
            ) {
                val disabled = stringResource(R.string.directory_disabled_summary)
                directories.forEach { directory ->
                    NavRow(
                        title = directory.title,
                        subtitle = summary(directory, disabled),
                        icon = Icons.Rounded.Dns,
                        onClick = { onEdit(directory.id) },
                    )
                }
            }

            SectionCard(title = stringResource(R.string.directory_cache_title)) {
                Text(
                    pluralOf(
                        R.plurals.directory_cache_usage,
                        cache.entries.toPluralCount(),
                        cache.entries.toString(),
                        megabytes(cache.bytes),
                        megabytes(cache.maxBytes),
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp),
                )
                val cleared = stringResource(R.string.directory_cache_cleared)
                TextButton(
                    onClick = {
                        manager.clearCache()
                        messenger(cleared)
                    },
                    enabled = cache.entries > 0,
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) { Text(stringResource(R.string.directory_cache_clear)) }
            }
        }
    }
}

/**
 * Where the directory is, and that it is switched off when it is: said first, so that a long host
 * cannot fade it out. [disabled] is the pattern of a switched-off one, the server as its argument.
 */
private fun summary(directory: LdapDirectory, disabled: String): String {
    val server = LdapRules.serverText(directory)
    return if (directory.enabled) server else disabled.format(server)
}

/** The count that picks the plural form of a number of entries. */
private fun Long.toPluralCount(): Int = coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

/** A size in bytes as megabytes with one decimal. */
private fun megabytes(bytes: Long): String = String.format(Locale.ROOT, "%.1f", bytes / (1024.0 * 1024.0))
