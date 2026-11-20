package com.sky.siplink.ui.shortcuts

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import com.sky.siplink.core.logic.ShortcutRules
import com.sky.siplink.ui.components.text

/**
 * Asks for the name of a category: of a new one, or another name for one there is.
 *
 * @param initial the present name of the category, empty for a new one
 * @param taken the names of the other categories, none of which it can be called
 * @param onDelete given for a category there is: takes it away; its keys stay, without a category
 */
@Composable
internal fun CategoryNameDialog(
    title: String,
    initial: String,
    taken: Collection<String>,
    confirm: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    var name by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val reserved = ShortcutRules.RESERVED_CATEGORIES.map { stringResource(it) }.toSet()
    val problem = ShortcutRules.categoryNameError(name, taken, reserved)

    fun done() {
        if (problem == null) onConfirm(name.trim())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(ShortcutRules.MAX_CATEGORY_LENGTH) },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus),
                // An empty field says so by itself: the button stays off.
                isError = problem != null && name.isNotBlank(),
                supportingText = problem?.takeIf { name.isNotBlank() }?.let { { Text(it.message.text()) } },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { done() }),
            )
        },
        confirmButton = {
            Row {
                if (onDelete != null) TextButton(onClick = onDelete) { Text("删除分类", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("取消") }
                TextButton(onClick = ::done, enabled = problem == null) { Text(confirm) }
            }
        },
    )
}
