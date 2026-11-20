package com.sky.siplink.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sky.siplink.R
import com.sky.siplink.core.logic.ManagedConfig
import com.sky.siplink.engine

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

/**
 * Asks for the password of a managed configuration that keeps its stored passwords from being
 * shown ([com.sky.siplink.core.ConfigLock.passwordsProtected]).
 *
 * @param onOpened called with what was typed once that is the password: the managers hand a
 *   stored password out for it
 */
@Composable
fun ConfigPasswordDialog(onDismiss: () -> Unit, onOpened: (String) -> Unit) {
    val lock = LocalContext.current.engine.lock
    var typed by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.managed_password_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.managed_password_text))
                // The dialog stands on a locked screen; what is typed into it changes no configuration.
                CompositionLocalProvider(LocalConfigLocked provides false) {
                    FormField(
                        value = typed,
                        onValueChange = {
                            typed = it
                            wrong = false
                        },
                        label = stringResource(R.string.managed_password_field),
                        password = true,
                        error = if (wrong) stringResource(R.string.managed_password_wrong) else null,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (lock.showsPasswords(typed)) onOpened(typed) else wrong = true }, enabled = typed.isNotEmpty()) {
                Text(stringResource(R.string.managed_password_view))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

/** The lock in the top bar of a screen that takes no changes; a tap says why. Nothing where the configuration is not locked. */
@Composable
fun ManagedLock() {
    if (!LocalConfigLocked.current) return
    val messenger = LocalMessenger.current
    val notice = ManagedConfig.NOTICE.text()
    IconButton(onClick = { messenger(notice) }) {
        Icon(
            Icons.Rounded.Lock,
            contentDescription = ManagedConfig.LOCKED.text(),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}
