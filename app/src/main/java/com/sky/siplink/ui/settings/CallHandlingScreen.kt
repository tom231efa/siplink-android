package com.sky.siplink.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DoNotDisturbOn
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
import com.sky.siplink.core.logic.IncomingPolicy
import com.sky.siplink.engine
import com.sky.siplink.ui.components.FormField
import com.sky.siplink.ui.components.SectionCard
import com.sky.siplink.ui.components.SubScreen
import com.sky.siplink.ui.components.SwitchRow

/** Do not disturb, call waiting and call forwarding. */
@Composable
fun CallHandlingScreen(onBack: () -> Unit) {
    val engine = LocalContext.current.engine
    val settings by engine.settings.settings.collectAsStateWithLifecycle()
    // Typed text is kept locally so an out-of-range number can be shown with its error.
    var seconds by remember { mutableStateOf(settings.forwardNoAnswerSeconds.toString()) }
    val range = IncomingPolicy.MIN_NO_ANSWER_SECONDS..IncomingPolicy.MAX_NO_ANSWER_SECONDS

    SubScreen(title = "呼叫处理", onBack = onBack) {
        SectionCard(title = "来电") {
            SwitchRow(
                title = "免打扰",
                subtitle = "拒接所有来电，优先于呼叫前转",
                icon = Icons.Rounded.DoNotDisturbOn,
                checked = settings.doNotDisturb,
                onCheckedChange = { value -> engine.updateSettings { it.copy(doNotDisturb = value) } },
            )
            SwitchRow(
                title = "呼叫等待",
                subtitle = "通话中有新来电时提示，可保留当前通话后接听；关闭后新来电按遇忙处理",
                icon = Icons.Rounded.PhonePaused,
                checked = settings.callWaiting,
                onCheckedChange = { value -> engine.updateSettings { it.copy(callWaiting = value) } },
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
                    placeholder = "留空表示不前转",
                    supporting = "所有来电直接转到该号码",
                    keyboardType = KeyboardType.Email,
                )
                FormField(
                    value = settings.forwardBusy,
                    onValueChange = { value -> engine.updateSettings { it.copy(forwardBusy = value.trim()) } },
                    label = "遇忙前转",
                    placeholder = "留空表示不前转",
                    supporting = "通话中的新来电转到该号码（优先于呼叫等待）",
                    keyboardType = KeyboardType.Email,
                )
                FormField(
                    value = settings.forwardNoAnswer,
                    onValueChange = { value -> engine.updateSettings { it.copy(forwardNoAnswer = value.trim()) } },
                    label = "无应答前转",
                    placeholder = "留空表示不前转",
                    supporting = "振铃超时未接听时转到该号码",
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
                    error = "请输入 ${range.first}–${range.last} 之间的秒数".takeIf { seconds.toIntOrNull() !in range },
                    keyboardType = KeyboardType.Number,
                )
            }
        }

        Text(
            "前转由本机以 SIP 302 应答完成，只在 SipLink 在线时生效；" +
                "需要离线也生效的前转请在 PBX 上配置。号码可以是分机号或完整的 SIP 地址。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
    }
}
