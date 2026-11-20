package com.sky.siplink.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Hearing
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.PhonePaused
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sky.siplink.R
import com.sky.siplink.core.logic.FeatureDial
import com.sky.siplink.core.logic.IncomingPolicy
import com.sky.siplink.core.logic.MediaAdapt
import com.sky.siplink.core.logic.RoomRules
import com.sky.siplink.core.logic.UserAgent
import com.sky.siplink.core.model.DndMode
import com.sky.siplink.core.model.DtmfMode
import com.sky.siplink.core.model.Phrase
import com.sky.siplink.core.render
import com.sky.siplink.engine
import com.sky.siplink.ui.components.ChoiceRow
import com.sky.siplink.ui.components.FormField
import com.sky.siplink.ui.components.LocalMessenger
import com.sky.siplink.ui.components.SectionCard
import com.sky.siplink.ui.components.SubScreen
import com.sky.siplink.ui.components.SwitchRow
import com.sky.siplink.ui.components.stringOf
import com.sky.siplink.ui.components.text

/**
 * What most people leave as it is: who keeps do not disturb, call waiting, call forwarding, the
 * size of the conference room, how the lines adapt to the network (with a STUN server), and a
 * few options of the SIP library (rate control, echo cancellation, IPv6, the User-Agent name).
 */
@Composable
fun AdvancedSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val engine = context.engine
    val messenger = LocalMessenger.current
    val settings by engine.settings.settings.collectAsStateWithLifecycle()
    // Leaving do not disturb to the PBX works through its two codes: without them it is not in use.
    val serverDnd = FeatureDial.serverDndConfigured(settings.features)
    // Typed text is kept locally so an out-of-range number can be shown with its error.
    var seconds by remember { mutableStateOf(settings.forwardNoAnswerSeconds.toString()) }
    val range = IncomingPolicy.MIN_NO_ANSWER_SECONDS..IncomingPolicy.MAX_NO_ANSWER_SECONDS
    var roomLimit by remember(settings.roomLimit) { mutableStateOf(settings.roomLimit.toString()) }
    val roomRange = RoomRules.MIN_LIMIT..RoomRules.LIMIT
    // Likewise the name, which is kept only once the rule takes it; a name set elsewhere (a remote configuration) replaces it.
    var userAgent by remember(settings.userAgent) { mutableStateOf(settings.userAgent) }
    var stunServer by remember(settings.stunServer) { mutableStateOf(settings.stunServer) }

    SubScreen(title = stringResource(R.string.advanced_title), onBack = onBack) {
        SectionCard(title = stringResource(R.string.advanced_section_incoming)) {
            Text(
                stringResource(R.string.advanced_dnd_mode),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 16.dp, top = 10.dp),
            )
            ChoiceRow(
                options = listOf(DndMode.SOFTWARE to stringResource(R.string.advanced_dnd_software), DndMode.SERVER to stringResource(R.string.advanced_dnd_server)),
                selected = settings.dndMode,
                onSelect = { mode ->
                    if (mode == DndMode.SERVER && !serverDnd) {
                        messenger(context.render(FeatureDial.missingCode(Phrase.of(R.string.advanced_dnd_codes, Phrase.of(R.string.feature_dnd_on), Phrase.of(R.string.feature_dnd_off)))))
                    } else {
                        // What the other side was last told says nothing about this one: start switched off.
                        engine.updateSettings { it.copy(dndMode = mode, doNotDisturb = false) }
                    }
                },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                dimmed = { it == DndMode.SERVER && !serverDnd },
            )
            SwitchRow(
                title = stringResource(R.string.advanced_call_waiting),
                icon = Icons.Rounded.PhonePaused,
                checked = settings.callWaiting,
                onCheckedChange = { value -> engine.updateSettings { it.copy(callWaiting = value) } },
            )
            SwitchRow(
                title = stringResource(R.string.advanced_reject_anonymous),
                icon = Icons.Rounded.Block,
                checked = settings.rejectAnonymous,
                onCheckedChange = { value -> engine.updateSettings { it.copy(rejectAnonymous = value) } },
            )
            Text(
                stringResource(R.string.advanced_dtmf_mode),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 16.dp, top = 10.dp),
            )
            ChoiceRow(
                options = listOf(DtmfMode.RFC2833 to "RFC 2833", DtmfMode.SIP_INFO to "SIP INFO"),
                selected = settings.dtmfMode,
                onSelect = { mode -> engine.updateSettings { it.copy(dtmfMode = mode) } },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        SectionCard(title = stringResource(R.string.advanced_section_forwarding)) {
            Column(
                Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                FormField(
                    value = settings.forwardAlways,
                    onValueChange = { value -> engine.updateSettings { it.copy(forwardAlways = value.trim()) } },
                    label = stringResource(R.string.advanced_forward_always),
                    keyboardType = KeyboardType.Email,
                )
                FormField(
                    value = settings.forwardBusy,
                    onValueChange = { value -> engine.updateSettings { it.copy(forwardBusy = value.trim()) } },
                    label = stringResource(R.string.advanced_forward_busy),
                    keyboardType = KeyboardType.Email,
                )
                FormField(
                    value = settings.forwardNoAnswer,
                    onValueChange = { value -> engine.updateSettings { it.copy(forwardNoAnswer = value.trim()) } },
                    label = stringResource(R.string.advanced_forward_no_answer),
                    keyboardType = KeyboardType.Email,
                )
                FormField(
                    value = seconds,
                    onValueChange = { value ->
                        seconds = value.filter(Char::isDigit).take(3)
                        val parsed = seconds.toIntOrNull()
                        if (parsed != null && parsed in range) {
                            engine.updateSettings { it.copy(forwardNoAnswerSeconds = parsed) }
                        }
                    },
                    label = stringResource(R.string.advanced_no_answer_seconds),
                    error = when {
                        seconds.isEmpty() -> stringResource(R.string.form_required)
                        seconds.toIntOrNull() !in range -> stringOf(R.string.advanced_range, range.first.toString(), range.last.toString())
                        else -> null
                    },
                    keyboardType = KeyboardType.Number,
                )
            }
        }

        SectionCard(title = stringResource(R.string.advanced_section_room)) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                FormField(
                    value = roomLimit,
                    onValueChange = { value ->
                        roomLimit = value.filter(Char::isDigit).take(2)
                        val parsed = roomLimit.toIntOrNull()
                        if (parsed != null && parsed in roomRange) engine.updateSettings { it.copy(roomLimit = parsed) }
                    },
                    label = stringResource(R.string.advanced_room_limit),
                    machineValue = true,
                    error = when {
                        roomLimit.isEmpty() -> stringResource(R.string.form_required)
                        roomLimit.toIntOrNull() !in roomRange -> stringOf(R.string.advanced_range, roomRange.first.toString(), roomRange.last.toString())
                        else -> null
                    },
                    keyboardType = KeyboardType.Number,
                )
            }
        }

        SectionCard(title = stringResource(R.string.advanced_section_media)) {
            SwitchRow(
                title = stringResource(R.string.advanced_rate_control),
                icon = Icons.Rounded.Speed,
                checked = settings.adaptiveRateControl,
                onCheckedChange = { value -> engine.updateSettings { it.copy(adaptiveRateControl = value) } },
            )
            SwitchRow(
                title = stringResource(R.string.advanced_echo_cancellation),
                icon = Icons.Rounded.Hearing,
                checked = settings.echoCancellation,
                onCheckedChange = { value -> engine.updateSettings { it.copy(echoCancellation = value) } },
            )
            SwitchRow(
                title = stringResource(R.string.advanced_ipv6),
                icon = Icons.Rounded.Lan,
                checked = settings.ipv6,
                onCheckedChange = { value -> engine.updateSettings { it.copy(ipv6 = value) } },
            )
            SwitchRow(
                title = stringResource(R.string.advanced_auto_network),
                icon = Icons.Rounded.NetworkCheck,
                checked = settings.autoNetwork,
                onCheckedChange = { value -> engine.updateSettings { it.copy(autoNetwork = value) } },
            )
            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                val usable = MediaAdapt.isValidStunServer(stunServer.trim())
                FormField(
                    value = stunServer,
                    onValueChange = { value ->
                        stunServer = value
                        if (MediaAdapt.isValidStunServer(value.trim())) engine.updateSettings { it.copy(stunServer = value.trim()) }
                    },
                    label = stringResource(R.string.advanced_stun_server),
                    placeholder = stringResource(R.string.advanced_host_port_hint),
                    error = if (usable) null else stringResource(R.string.form_host_port),
                    keyboardType = KeyboardType.Uri,
                )
            }
        }

        SectionCard(title = stringResource(R.string.advanced_section_identity)) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                val refused = UserAgent.error(userAgent)
                FormField(
                    value = userAgent,
                    onValueChange = { value ->
                        userAgent = value
                        if (UserAgent.error(value) == null) engine.updateSettings { it.copy(userAgent = value) }
                    },
                    label = stringResource(R.string.advanced_user_agent),
                    machineValue = true,
                    placeholder = engine.defaultUserAgent,
                    error = refused?.message?.text(),
                )
            }
        }
    }
}
