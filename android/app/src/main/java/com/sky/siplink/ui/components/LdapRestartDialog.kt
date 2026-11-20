package com.sky.siplink.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.sky.siplink.R
import com.sky.siplink.engine
import com.sky.siplink.restartApp

/**
 * Asks to restart the app after the trusted CAs changed: the LDAP library loads them once per
 * process, at its first TLS connection ([com.sky.siplink.core.DirectoryManager.tlsChangeNeedsRestart]).
 * A call in progress is not cut off for it.
 */
@Composable
fun LdapRestartDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val messenger = LocalMessenger.current
    val afterCall = stringResource(R.string.tls_restart_after_call)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tls_restart_ldap_title)) },
        text = { Text(stringResource(R.string.tls_restart_ldap_text)) },
        confirmButton = {
            TextButton(onClick = {
                if (context.engine.calls.call.value != null) {
                    onDismiss()
                    messenger(afterCall)
                } else {
                    context.restartApp()
                }
            }) { Text(stringResource(R.string.tls_restart_now)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.tls_restart_later)) } },
    )
}
