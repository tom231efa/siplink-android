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
    val hint: String,
    val read: (FeatureCodes) -> String,
    val write: (FeatureCodes, String) -> FeatureCodes,
)

private val supervisorFields = listOf(
    CodeField("监听", "功能码 + 分机号，只听不说", { it.listen }) { c, v -> c.copy(listen = v) },
    CodeField("耳语", "功能码 + 分机号，只有该分机能听到你", { it.whisper }) { c, v -> c.copy(whisper = v) },
    CodeField("强插", "功能码 + 分机号，加入对方通话", { it.barge }) { c, v -> c.copy(barge = v) },
    CodeField("强拆", "功能码 + 分机号，强制挂断对方通话", { it.forceHangUp }) { c, v -> c.copy(forceHangUp = v) },
)

private val callFields = listOf(
    CodeField("驻留", "通话中点“驻留”时盲转到的号码", { it.park }) { c, v -> c.copy(park = v) },
    CodeField("指定代接", "功能码 + 分机号，接起该分机的振铃来电", { it.pickup }) { c, v -> c.copy(pickup = v) },
    CodeField("同组代接", "接起代接组内的振铃来电", { it.groupPickup }) { c, v -> c.copy(groupPickup = v) },
    CodeField("语音信箱", "收听留言时拨打的号码", { it.voicemail }) { c, v -> c.copy(voicemail = v) },
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
        Text(
            "功能码是 PBX 拨号计划中定义的号码，SipLink 只负责替你拨出，请向 PBX 管理员确认后填写。" +
                "留空表示不使用该功能。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        CodeSection("班长功能", supervisorFields, codes, ::change)
        CodeSection("通话功能", callFields, codes, ::change)
        Text(
            "监听中可在通话界面切换 监听 / 耳语 / 强插，对应向 PBX 发送按键 4 / 5 / 6（Asterisk ChanSpy 的约定）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
    }
}

@Composable
private fun CodeSection(
    title: String,
    fields: List<CodeField>,
    codes: FeatureCodes,
    onChange: (FeatureCodes) -> Unit,
) {
    SectionCard(title = title) {
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            fields.forEach { field ->
                val value = field.read(codes)
                FormField(
                    value = value,
                    onValueChange = { typed -> onChange(field.write(codes, typed.filterNot(Char::isWhitespace))) },
                    label = field.label,
                    supporting = field.hint,
                    error = "只能包含数字、* 和 #".takeIf { !FeatureDial.isValidCode(value) },
                    keyboardType = KeyboardType.Phone,
                )
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
    )
}
