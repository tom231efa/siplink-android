package com.sky.siplink.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sky.siplink.core.logic.ManagedConfig

/**
 * True where the configuration is managed and can only be looked at. The rows that edit a value
 * ([SwitchRow], [FormField], [DropdownField], [ChoiceRow]) read it themselves: they keep showing
 * the value and take no input. A screen reads it for what is its own, such as the buttons that
 * add, save or delete.
 */
val LocalConfigLocked = compositionLocalOf { false }

/** What stays the user's own while the configuration is managed: the rows in [content] are never locked. */
@Composable
fun Personal(content: @Composable () -> Unit) = CompositionLocalProvider(LocalConfigLocked provides false, content = content)

/** The line at the top of a screen that takes no changes, saying why; nothing where the configuration is not locked. */
@Composable
fun ManagedNotice(modifier: Modifier = Modifier) {
    if (!LocalConfigLocked.current) return
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Rounded.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(ManagedConfig.NOTICE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
