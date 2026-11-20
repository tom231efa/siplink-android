package com.sky.siplink.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sky.siplink.R
import com.sky.siplink.core.logic.RecordingRules
import com.sky.siplink.engine
import com.sky.siplink.ui.components.ChoiceRow
import com.sky.siplink.ui.components.NavRow
import com.sky.siplink.ui.components.Personal
import com.sky.siplink.ui.components.SectionCard
import com.sky.siplink.ui.components.SubScreen
import com.sky.siplink.ui.components.SwitchRow

/** Whether and how calls are recorded, and the way to the recordings themselves. */
@Composable
fun RecordingSettingsScreen(onBack: () -> Unit, onOpenRecordings: () -> Unit) {
    val engine = LocalContext.current.engine
    val settings by engine.settings.settings.collectAsStateWithLifecycle()
    val recordings by engine.recordings.recordings.collectAsStateWithLifecycle()

    SubScreen(title = stringResource(R.string.settings_recording), onBack = onBack) {
        SectionCard(title = null) {
            SwitchRow(
                title = stringResource(R.string.settings_recording_calls),
                icon = Icons.Rounded.Mic,
                checked = settings.callRecording,
                onCheckedChange = { value -> engine.updateSettings { it.copy(callRecording = value) } },
            )
            AnimatedVisibility(settings.callRecording) {
                SwitchRow(
                    title = stringResource(R.string.settings_recording_by_default),
                    icon = Icons.Rounded.FiberManualRecord,
                    checked = settings.recordByDefault,
                    onCheckedChange = { value -> engine.updateSettings { it.copy(recordByDefault = value) } },
                )
            }
            // How many recordings are kept is the user's own, like the recordings themselves.
            Personal {
                Text(
                    stringResource(R.string.settings_recording_kept),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 16.dp, top = 10.dp),
                )
                ChoiceRow(
                    options = RecordingRules.KEEP_CHOICES.map { it to "$it" },
                    selected = settings.recordingsKept,
                    onSelect = { kept -> engine.updateSettings { it.copy(recordingsKept = kept) } },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            NavRow(
                title = stringResource(R.string.settings_recording_files),
                trailing = recordings.size.toString().takeIf { recordings.isNotEmpty() },
                icon = Icons.Rounded.LibraryMusic,
                onClick = onOpenRecordings,
            )
        }
    }
}
