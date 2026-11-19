package com.sky.siplink.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重启后对 LDAP 生效") },
        text = {
            Text(
                "SIP 账号已启用新的证书设置。LDAP 通讯录的加密连接已在本次运行中建立，" +
                    "需重启应用后方可使用新的 CA 证书。",
            )
        },
        confirmButton = {
            TextButton(onClick = {
                if (context.engine.calls.call.value != null) {
                    onDismiss()
                    messenger("请在通话结束后重启应用")
                } else {
                    context.restartApp()
                }
            }) { Text("立即重启") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("稍后") } },
    )
}
