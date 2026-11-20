package com.sky.siplink.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.PhoneInTalk
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
import com.sky.siplink.core.logic.AutoAnswerRules
import com.sky.siplink.engine
import com.sky.siplink.ui.components.ChoiceRow
import com.sky.siplink.ui.components.SectionCard
import com.sky.siplink.ui.components.SubScreen
import com.sky.siplink.ui.components.SwitchRow
import com.sky.siplink.ui.components.pluralOf

/** Incoming calls answered by themselves: intercom calls at once, regular calls after a time of their own. */
@Composable
fun AutoAnswerScreen(onBack: () -> Unit) {
    val engine = LocalContext.current.engine
    val settings by engine.settings.settings.collectAsStateWithLifecycle()

    SubScreen(title = stringResource(R.string.settings_auto_answer), onBack = onBack) {
        SectionCard(title = stringResource(R.string.auto_answer_section_intercom)) {
            SwitchRow(
                title = stringResource(R.string.auto_answer_intercom),
                icon = Icons.Rounded.Campaign,
                checked = settings.intercomAutoAnswer,
                onCheckedChange = { value -> engine.updateSettings { it.copy(intercomAutoAnswer = value) } },
            )
            SwitchRow(
                title = stringResource(R.string.auto_answer_intercom_speaker),
                icon = Icons.AutoMirrored.Rounded.VolumeUp,
                checked = settings.intercomSpeaker,
                onCheckedChange = { value -> engine.updateSettings { it.copy(intercomSpeaker = value) } },
            )
        }

        SectionCard(title = stringResource(R.string.auto_answer_section_regular)) {
            SwitchRow(
                title = stringResource(R.string.auto_answer_regular),
                icon = Icons.Rounded.PhoneInTalk,
                checked = settings.autoAnswer,
                onCheckedChange = { value -> engine.updateSettings { it.copy(autoAnswer = value) } },
            )
            AnimatedVisibility(settings.autoAnswer) {
                Column {
                    Text(
                        stringResource(R.string.auto_answer_delay),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 16.dp, top = 10.dp),
                    )
                    ChoiceRow(
                        options = AutoAnswerRules.SECONDS_CHOICES.map { it to secondsLabel(it) },
                        selected = settings.autoAnswerSeconds,
                        onSelect = { seconds -> engine.updateSettings { it.copy(autoAnswerSeconds = seconds) } },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun secondsLabel(seconds: Int): String =
    if (seconds == 0) stringResource(R.string.auto_answer_now) else pluralOf(R.plurals.auto_answer_seconds, seconds, seconds.toString())
