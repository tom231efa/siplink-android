package com.sky.siplink.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.sky.siplink.core.logic.FeatureDial
import com.sky.siplink.core.model.FeatureCodes
import com.sky.siplink.engine
import com.sky.siplink.ui.components.FormField
import com.sky.siplink.ui.components.LocalMessenger
import com.sky.siplink.ui.components.SectionCard
import com.sky.siplink.ui.components.SubScreen

private class CodeField(
    val label: String,
    val read: (FeatureCodes) -> String,
    val write: (FeatureCodes, String) -> FeatureCodes,
)

private val fields = listOf(
    // Dialled with the extension they act on behind them.
    CodeField("对讲", { it.intercom }) { c, v -> c.copy(intercom = v) },
    CodeField("监听", { it.listen }) { c, v -> c.copy(listen = v) },
    CodeField("耳语", { it.whisper }) { c, v -> c.copy(whisper = v) },
    CodeField("强插", { it.barge }) { c, v -> c.copy(barge = v) },
    CodeField("强拆", { it.forceHangUp }) { c, v -> c.copy(forceHangUp = v) },
    CodeField("指定代接", { it.pickup }) { c, v -> c.copy(pickup = v) },
    // Dialled as they are.
    CodeField("同组代接", { it.groupPickup }) { c, v -> c.copy(groupPickup = v) },
    CodeField("驻留", { it.park }) { c, v -> c.copy(park = v) },
    CodeField("语音信箱", { it.voicemail }) { c, v -> c.copy(voicemail = v) },
    CodeField("开启免打扰", { it.dndOn }) { c, v -> c.copy(dndOn = v) },
    CodeField("关闭免打扰", { it.dndOff }) { c, v -> c.copy(dndOff = v) },
)

/** Dial codes of the PBX features; they must match the PBX dial plan. */
@Composable
fun FeatureCodesScreen(onBack: () -> Unit) {
    val engine = LocalContext.current.engine
    val messenger = LocalMessenger.current
    // Edited locally: an invalid code is shown with its error but never stored.
    var codes by remember { mutableStateOf(engine.settings.current.features) }

    fun change(next: FeatureCodes) {
        codes = next
        engine.updateSettings { it.copy(features = validOnly(next, it.features)) }
    }

    SubScreen(
        title = "功能码",
        onBack = onBack,
        actions = {
            TextButton(onClick = {
                codes = FeatureCodes()
                engine.updateSettings { it.copy(features = FeatureCodes()) }
                messenger("已恢复默认功能码")
            }) { Text("恢复默认") }
        },
    ) {
        SectionCard(title = null) {
            Column(
                Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    "留空即停用该功能",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                fields.forEach { field ->
                    val value = field.read(codes)
                    FormField(
                        value = value,
                        onValueChange = { typed -> change(field.write(codes, typed.filterNot(Char::isWhitespace))) },
                        label = field.label,
                        error = "仅限 0-9 * #".takeIf { !FeatureDial.isValidCode(value) },
                        keyboardType = KeyboardType.Phone,
                    )
                }
            }
        }
    }
}

/** Takes every valid code from [edited] and keeps the stored one where the edit is invalid. */
private fun validOnly(edited: FeatureCodes, stored: FeatureCodes): FeatureCodes {
    fun pick(new: String, old: String) = if (FeatureDial.isValidCode(new)) new else old
    return FeatureCodes(
        park = pick(edited.park, stored.park),
        listen = pick(edited.listen, stored.listen),
        whisper = pick(edited.whisper, stored.whisper),
        barge = pick(edited.barge, stored.barge),
        forceHangUp = pick(edited.forceHangUp, stored.forceHangUp),
        pickup = pick(edited.pickup, stored.pickup),
        groupPickup = pick(edited.groupPickup, stored.groupPickup),
        voicemail = pick(edited.voicemail, stored.voicemail),
        intercom = pick(edited.intercom, stored.intercom),
        dndOn = pick(edited.dndOn, stored.dndOn),
        dndOff = pick(edited.dndOff, stored.dndOff),
    )
}
