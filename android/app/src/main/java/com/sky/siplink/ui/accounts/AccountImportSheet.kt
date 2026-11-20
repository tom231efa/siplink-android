package com.sky.siplink.ui.accounts

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.sky.siplink.R
import com.sky.siplink.core.logic.AccountImport
import com.sky.siplink.core.logic.AccountRules
import com.sky.siplink.core.render
import com.sky.siplink.engine
import com.sky.siplink.ui.components.CsvImportSheet
import com.sky.siplink.ui.components.ImportOutcome
import com.sky.siplink.ui.components.importReport

/** Bulk import of accounts from pasted text or a CSV file. */
@Composable
fun AccountImportSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val manager = context.engine.accounts
    CsvImportSheet(
        title = stringResource(R.string.account_import_title),
        formatHint = context.render(AccountImport.FORMAT),
        placeholder = stringResource(R.string.account_import_example),
        onImport = { text ->
            // The accounts screen does not offer the import where the configuration is locked; the
            // manager refuses it all the same and then reports that it created none.
            val existing = manager.accounts.value.map { AccountRules.identityKey(it.username, it.domain) }
            val result = AccountImport.parse(text, existing, manager.accounts.value.map { it.name })
            val created = if (result.drafts.isEmpty()) 0 else manager.addAll(result.drafts)
            ImportOutcome(
                finished = result.errors.isEmpty() && result.skippedForLimit == 0 && created > 0,
                message = importReport(R.plurals.csv_imported_accounts, created, result.skippedForLimit, AccountRules.MAX_ACCOUNTS, result.errors, context::render),
            )
        },
        onDismiss = onDismiss,
    )
}
