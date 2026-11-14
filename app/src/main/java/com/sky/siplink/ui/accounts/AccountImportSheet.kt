package com.sky.siplink.ui.accounts

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.sky.siplink.core.logic.AccountImport
import com.sky.siplink.core.logic.AccountRules
import com.sky.siplink.engine
import com.sky.siplink.ui.components.CsvImportSheet
import com.sky.siplink.ui.components.ImportOutcome
import com.sky.siplink.ui.components.importReport

/** Bulk import of accounts from pasted text or a CSV file. */
@Composable
fun AccountImportSheet(onDismiss: () -> Unit) {
    val manager = LocalContext.current.engine.accounts
    CsvImportSheet(
        title = "批量导入账号",
        formatHint = AccountImport.FORMAT_HINT,
        placeholder = "1001,password,pbx.example.com,tls,张三,,前台",
        onImport = { text ->
            val existing = manager.accounts.value.map { AccountRules.identityKey(it.username, it.domain) }
            val result = AccountImport.parse(text, existing, manager.accounts.value.map { it.name })
            val created = if (result.drafts.isEmpty()) 0 else manager.addAll(result.drafts)
            ImportOutcome(
                finished = result.errors.isEmpty() && result.skippedForLimit == 0 && created > 0,
                message = importReport(created, "账号", result.skippedForLimit, AccountRules.MAX_ACCOUNTS, result.errors),
            )
        },
        onDismiss = onDismiss,
    )
}
