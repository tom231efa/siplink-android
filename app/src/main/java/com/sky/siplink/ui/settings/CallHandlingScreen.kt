package com.sky.siplink.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.PhonePaused
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sky.siplink.core.logic.FeatureDial
import com.sky.siplink.core.logic.IncomingPolicy
import com.sky.siplink.core.model.DndMode
import com.sky.siplink.core.model.DtmfMode
import com.sky.siplink.engine
import com.sky.siplink.ui.components.ChoiceRow
import com.sky.siplink.ui.components.FormField
import com.sky.siplink.ui.components.LocalMessenger
import com.sky.siplink.ui.components.SectionCard
import com.sky.siplink.ui.components.SubScreen
import com.sky.siplink.ui.components.SwitchRow

/** Who keeps do not disturb, call waiting and call forwarding. */
@Composable
fun CallHandlingScreen(onBack: () -> Unit) {
    val engine = LocalContext.current.engine
    val messenger = LocalMessenger.current
    val settings by engine.settings.settings.collectAsStateWithLifecycle()
    // Leaving do not disturb to the PBX works through its two codes: without them it is not in use.
    val serverDnd = FeatureDial.serverDndConfigured(settings.features)
    // Typed text is kept locally so an out-of-range number can be shown with its error.
    var seconds by remember { mutableStateOf(settings.forwardNoAnswerSeconds.toString()) }
    val range = IncomingPolicy.MIN_NO_ANSWER_SECONDS..IncomingPolicy.MAX_NO_ANSWER_SECONDS

    SubScreen(title = "呼叫处理", onBack = onBack) {
        SectionCard(title = "来电") {
            Text(
                "免打扰方式",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 16.dp, top = 10.dp),
            )
            ChoiceRow(
                options = listOf(DndMode.SOFTWARE to "软件", DndMode.SERVER to "服务器"),
                selected = settings.dndMode,
                onSelect = { mode ->
                    if (mode == DndMode.SERVER && !serverDnd) {
                        messenger(FeatureDial.missingCode("开启免打扰 / 关闭免打扰"))
                    } else {
                        // What the other side was last told says nothing about this one: start switched off.
                        engine.updateSettings { it.copy(dndMode = mode, doNotDisturb = false) }
                    }
                },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                dimmed = { it == DndMode.SERVER && !serverDnd },
            )
            SwitchRow(
                title = "呼叫等待",
                icon = Icons.Rounded.PhonePaused,
                checked = settings.callWaiting,
                onCheckedChange = { value -> engine.updateSettings { it.copy(callWaiting = value) } },
            )
            SwitchRow(
                title = "拒接匿名来电",
                icon = Icons.Rounded.Block,
                checked = settings.rejectAnonymous,
                onCheckedChange = { value -> engine.updateSettings { it.copy(rejectAnonymous = value) } },
            )
            Text(
                "DTMF 方式",
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

        SectionCard(title = "呼叫前转") {
            Column(
                Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                FormField(
                    value = settings.forwardAlways,
                    onValueChange = { value -> engine.updateSettings { it.copy(forwardAlways = value.trim()) } },
                    label = "无条件前转",
                    keyboardType = KeyboardType.Email,
                )
                FormField(
                    value = settings.forwardBusy,
                    onValueChange = { value -> engine.updateSettings { it.copy(forwardBusy = value.trim()) } },
                    label = "遇忙前转",
                    keyboardType = KeyboardType.Email,
                )
                FormField(
                    value = settings.forwardNoAnswer,
                    onValueChange = { value -> engine.updateSettings { it.copy(forwardNoAnswer = value.trim()) } },
                    label = "无应答前转",
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
                    label = "无应答等待时间（秒）",
                    error = when {
                        seconds.isEmpty() -> "必填"
                        seconds.toIntOrNull() !in range -> "应为 ${range.first}–${range.last}"
                        else -> null
                    },
                    keyboardType = KeyboardType.Number,
                )
            }
        }
    }
}
