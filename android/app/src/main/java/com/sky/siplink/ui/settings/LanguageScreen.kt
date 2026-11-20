package com.sky.siplink.ui.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.sky.siplink.R
import com.sky.siplink.core.AppLanguage
import com.sky.siplink.core.model.Language
import com.sky.siplink.engine
import com.sky.siplink.ui.components.Personal
import com.sky.siplink.ui.components.SectionCard
import com.sky.siplink.ui.components.SubScreen

/** The language of the app: the system's, or one of [Language], each by its own name. Never managed by a remote configuration. */
@Composable
fun LanguageScreen(onBack: () -> Unit) {
    val language = LocalContext.current.engine.language
    // A change of the language comes as a change of the configuration: read again with it.
    LocalConfiguration.current
    val chosen = language.chosen
    Personal {
        SubScreen(title = stringResource(R.string.settings_language), onBack = onBack) {
            SectionCard(title = null, modifier = Modifier.selectableGroup()) {
                LanguageRow(stringResource(R.string.settings_language_system), selected = language.followsSystem) { language.choose(null) }
                Language.entries.forEach { entry ->
                    LanguageRow(entry.ownName, selected = entry == chosen) { language.choose(entry) }
                }
            }
        }
    }
}

/** What the language row of the settings says: the language chosen by its own name, or that the system's is followed. */
@Composable
fun languageLabel(language: AppLanguage): String? {
    LocalConfiguration.current
    return language.chosen?.ownName ?: stringResource(R.string.settings_language_system).takeIf { language.followsSystem }
}

@Composable
private fun LanguageRow(name: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (selected) Icon(Icons.Rounded.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
    }
}
