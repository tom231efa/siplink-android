package com.sky.siplink.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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
        title = { Text("查看密码") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("此配置的密码受保护，输入配置密码后才能查看。")
                // The dialog stands on a locked screen; what is typed into it changes no configuration.
                CompositionLocalProvider(LocalConfigLocked provides false) {
                    FormField(
                        value = typed,
                        onValueChange = {
                            typed = it
                            wrong = false
                        },
                        label = "配置密码",
                        password = true,
                        error = if (wrong) "密码不正确" else null,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (lock.showsPasswords(typed)) onOpened(typed) else wrong = true }, enabled = typed.isNotEmpty()) {
                Text("查看")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/**
 * The line at the top of a screen that takes no changes, saying why; nothing where the configuration is not locked.
 *
 * @param text what it says, for a screen where not everything is locked
 */
@Composable
fun ManagedNotice(modifier: Modifier = Modifier, text: String = ManagedConfig.NOTICE) {
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
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
