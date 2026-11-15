package com.sky.siplink.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/** Titled group of rows drawn on a rounded surface. */
@Composable
fun SectionCard(title: String?, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth()) {
        if (title != null) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 12.dp, bottom = 8.dp),
            )
        }
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(vertical = 6.dp), content = content)
        }
    }
}

/**
 * A card that shows only its title until it is opened: for what most people leave as it is.
 *
 * @param open whether its rows are shown; a tap on the title asks for the opposite with [onToggle]
 */
@Composable
fun FoldingCard(title: String, open: Boolean, onToggle: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    SectionCard(title = null) {
        val rotation by animateFloatAsState(if (open) 180f else 0f, label = "foldingChevron")
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Icon(
                Icons.Rounded.ExpandMore,
                contentDescription = if (open) "收起" else "展开",
                modifier = Modifier.graphicsLayer { rotationZ = rotation },
            )
        }
        AnimatedVisibility(open) { Column(content = content) }
    }
}

@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val usable = enabled && !LocalConfigLocked.current
    Row(
        modifier
            .fillMaxWidth()
            .clickable(enabled = usable) { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowIcon(icon)
        RowText(title, subtitle, Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = usable)
    }
}

@Composable
fun NavRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    trailing: String? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowIcon(icon)
        RowText(title, subtitle, Modifier.weight(1f))
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            Text(trailing, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(
            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RowIcon(icon: ImageVector?) {
    if (icon != null) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
    }
}

@Composable
private fun RowText(title: String, subtitle: String?, modifier: Modifier) {
    Column(modifier) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        if (subtitle != null) {
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Outlined text field with the app's rounded shape and an optional error line.
 *
 * Where the configuration is locked ([LocalConfigLocked]) it shows its value and takes no input;
 * the eye of a password field goes on working.
 *
 * @param onUnlock for a password field whose password is kept from the screen (a managed
 *   configuration with a password of its own, see [ConfigPasswordDialog]): the field then shows
 *   dots that say nothing about it, not even how long it is, and its eye calls this to ask for
 *   it. Null: the field shows [value].
 */
@Composable
fun FormField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    supporting: String? = null,
    error: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
    password: Boolean = false,
    onUnlock: (() -> Unit)? = null,
) {
    val locked = LocalConfigLocked.current
    val kept = password && onUnlock != null
    var revealed by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = if (kept) KEPT_PASSWORD else value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        enabled = !locked,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        supportingText = (error ?: supporting)?.let { { Text(it) } },
        isError = error != null,
        singleLine = singleLine,
        shape = MaterialTheme.shapes.medium,
        colors = lockedFieldColors(),
        visualTransformation = if (password && (kept || !revealed)) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (password) KeyboardType.Password else keyboardType,
            autoCorrectEnabled = false,
        ),
        trailingIcon = if (password) {
            {
                val shown = revealed && !kept
                IconButton(
                    onClick = {
                        if (kept) {
                            // Asked for, the password shows as soon as it is handed over.
                            revealed = true
                            onUnlock?.invoke()
                        } else {
                            revealed = !revealed
                        }
                    },
                ) {
                    Icon(
                        if (shown) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        contentDescription = if (shown) "隐藏密码" else "显示密码",
                    )
                }
            }
        } else {
            null
        },
    )
}

/** Stands in a password field whose password is kept from the screen: eight dots, whatever the password. */
private const val KEPT_PASSWORD = "········"

/** A field is only ever disabled where the configuration is locked. It is then there to be read: its value stays darker than the frame around it. */
@Composable
private fun lockedFieldColors() = OutlinedTextFieldDefaults.colors(
    disabledTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
    // The eye of a password field still works there.
    disabledTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
)

/**
 * A field that opens a list to choose from, in the shape of [FormField]. Where the configuration
 * is locked ([LocalConfigLocked]) it shows the choice made and does not open.
 *
 * @param value text of the choice made
 * @param selected the choice made, shown in the accent colour in the list
 * @param leading picture in front of an option
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> DropdownField(
    value: String,
    label: String,
    options: List<T>,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    selected: T? = null,
    error: String? = null,
    leading: (@Composable (T) -> Unit)? = null,
) {
    val locked = LocalConfigLocked.current
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded && !locked, onExpandedChange = { expanded = it && !locked }, modifier = modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = options.isNotEmpty() && !locked),
            enabled = !locked,
            readOnly = true,
            label = { Text(label, maxLines = 1) },
            supportingText = error?.let { { Text(it) } },
            isError = error != null,
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
            colors = lockedFieldColors(),
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
        )
        ExposedDropdownMenu(expanded = expanded && !locked, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            optionLabel(option),
                            color = if (option == selected) MaterialTheme.colorScheme.primary else Color.Unspecified,
                        )
                    },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                    leadingIcon = leading?.let { { it(option) } },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
}
