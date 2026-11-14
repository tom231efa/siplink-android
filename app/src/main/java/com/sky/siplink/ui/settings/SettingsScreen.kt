@file:OptIn(ExperimentalMaterial3Api::class)

package com.sky.siplink.ui.settings

import android.content.Intent
import android.os.Build
import android.provider.Settings as SystemSettings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.BatterySaver
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sky.siplink.BuildConfig
import com.sky.siplink.core.model.IntercomHeaderMode
import com.sky.siplink.core.model.ThemeMode
import com.sky.siplink.engine
import com.sky.siplink.ui.components.LocalMessenger
import com.sky.siplink.ui.components.NavRow
import com.sky.siplink.ui.components.SectionCard
import com.sky.siplink.ui.components.SwitchRow

@Composable
fun SettingsScreen(onOpenTls: () -> Unit, onOpenLdap: () -> Unit) {
    val context = LocalContext.current
    val engine = context.engine
    val settings by engine.settings.settings.collectAsStateWithLifecycle()
    val certificates by engine.tls.certificates.collectAsStateWithLifecycle()
    val messenger = LocalMessenger.current

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("设置") })
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionCard(title = "对讲") {
                SwitchRow(
                    title = "自动接听对讲呼叫",
                    subtitle = "来电带有自动应答标记时免按键接通。任何能呼叫本机的人都可能借此开启麦克风，请只在可信网络中开启",
                    icon = Icons.Rounded.Campaign,
                    checked = settings.intercomAutoAnswer,
                    onCheckedChange = { value -> engine.updateSettings { it.copy(intercomAutoAnswer = value) } },
                )
                SwitchRow(
                    title = "对讲使用扬声器",
                    subtitle = "对讲接通后自动切换到免提",
                    icon = Icons.AutoMirrored.Rounded.VolumeUp,
                    checked = settings.intercomSpeaker,
                    onCheckedChange = { value -> engine.updateSettings { it.copy(intercomSpeaker = value) } },
                )
                Text(
                    "呼出对讲时附加的自动应答头",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 16.dp, top = 10.dp),
                )
                val modes = listOf(
                    IntercomHeaderMode.CALL_INFO to "Call-Info",
                    IntercomHeaderMode.ALERT_INFO to "Alert-Info",
                    IntercomHeaderMode.ANSWER_MODE to "Answer-Mode",
                    IntercomHeaderMode.ALL to "全部",
                )
                SingleChoiceSegmentedButtonRow(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    modes.forEachIndexed { index, (mode, label) ->
                        SegmentedButton(
                            selected = settings.intercomHeaderMode == mode,
                            onClick = { engine.updateSettings { it.copy(intercomHeaderMode = mode) } },
                            shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                            icon = {},
                        ) { Text(label, maxLines = 1, style = MaterialTheme.typography.labelMedium) }
                    }
                }
                Text(
                    "不同品牌话机识别的头不同：多数话机与 PBX 识别 Call-Info（answer-after=0）；如对端不自动接听，可尝试其他选项或“全部”。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
                )
            }

            SectionCard(title = "安全与通讯录") {
                NavRow(
                    title = "TLS 与 CA 证书",
                    subtitle = if (certificates.isEmpty()) "导入 CA 证书以启用私有 TLS 加密" else "已导入 ${certificates.size} 个证书",
                    icon = Icons.Rounded.VerifiedUser,
                    onClick = onOpenTls,
                )
                NavRow(
                    title = "LDAP 通讯录",
                    subtitle = "配置企业目录服务器",
                    icon = Icons.Rounded.Dns,
                    onClick = onOpenLdap,
                )
            }

            SectionCard(title = "后台运行") {
                SwitchRow(
                    title = "后台保持在线",
                    subtitle = "通过常驻通知保持账号注册，退出界面后仍可接听来电",
                    icon = Icons.Rounded.Sync,
                    checked = settings.keepAlive,
                    onCheckedChange = { value -> engine.updateSettings { it.copy(keepAlive = value) } },
                )
                AnimatedVisibility(settings.keepAlive) {
                    NavRow(
                        title = "电池优化",
                        subtitle = "将 SipLink 设为“不受限制”可避免系统在待机时中断注册",
                        icon = Icons.Rounded.BatterySaver,
                        onClick = {
                            val intent = Intent(SystemSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            runCatching { context.startActivity(intent) }
                                .onFailure { messenger("无法打开系统的电池优化设置") }
                        },
                    )
                }
            }

            SectionCard(title = "外观") {
                Text(
                    "主题",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 16.dp, top = 10.dp),
                )
                val themes = listOf(ThemeMode.SYSTEM to "跟随系统", ThemeMode.LIGHT to "浅色", ThemeMode.DARK to "深色")
                SingleChoiceSegmentedButtonRow(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    themes.forEachIndexed { index, (mode, label) ->
                        SegmentedButton(
                            selected = settings.themeMode == mode,
                            onClick = { engine.updateSettings { it.copy(themeMode = mode) } },
                            shape = SegmentedButtonDefaults.itemShape(index, themes.size),
                        ) { Text(label) }
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    SwitchRow(
                        title = "动态配色",
                        subtitle = "界面颜色跟随系统壁纸",
                        icon = Icons.Rounded.Palette,
                        checked = settings.dynamicColor,
                        onCheckedChange = { value -> engine.updateSettings { it.copy(dynamicColor = value) } },
                    )
                }
            }

            Column(Modifier.padding(horizontal = 12.dp)) {
                Text(
                    "SipLink ${BuildConfig.VERSION_NAME} · Linphone SDK ${engine.sdkVersion}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "SIP 协议栈由 Belledonne Communications 的 Linphone SDK 提供，以 GNU GPLv3 许可发布。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
