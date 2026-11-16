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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.BatterySaver
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.Dialpad
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.DoNotDisturbOn
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.ManageAccounts
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.ScreenLockPortrait
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sky.siplink.BuildConfig
import com.sky.siplink.core.model.BannerPosition
import com.sky.siplink.core.model.RegState
import com.sky.siplink.core.model.ThemeMode
import com.sky.siplink.engine
import com.sky.siplink.ui.components.ChoiceRow
import com.sky.siplink.ui.components.LocalMessenger
import com.sky.siplink.ui.components.ManagedLock
import com.sky.siplink.ui.components.NavRow
import com.sky.siplink.ui.components.Personal
import com.sky.siplink.ui.components.SectionCard
import com.sky.siplink.ui.components.SwitchRow
import com.sky.siplink.ui.components.openLockScreenCallSettings
import com.sky.siplink.ui.components.rememberCallLauncher
import com.sky.siplink.ui.components.rememberLockScreenCallAccess

@Composable
fun SettingsScreen(
    onOpenAccounts: () -> Unit,
    onOpenTls: () -> Unit,
    onOpenLdap: () -> Unit,
    onOpenAdvanced: () -> Unit,
    onOpenFeatureCodes: () -> Unit,
    onOpenCodecs: () -> Unit,
    onOpenRemoteConfig: () -> Unit,
) {
    val context = LocalContext.current
    val engine = context.engine
    val settings by engine.settings.settings.collectAsStateWithLifecycle()
    val certificates by engine.tls.certificates.collectAsStateWithLifecycle()
    val accounts by engine.accounts.accounts.collectAsStateWithLifecycle()
    val directories by engine.directory.directories.collectAsStateWithLifecycle()
    val remote by engine.remote.status.collectAsStateWithLifecycle()
    val messenger = LocalMessenger.current
    val launcher = rememberCallLauncher()

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("设置") }, actions = { ManagedLock() })
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionCard(title = "账号") {
                // Like the profiles of a proxy client: scanned or typed in, then in force until it is deleted here.
                NavRow(
                    title = "远程配置",
                    subtitle = if (remote.enrolled) remote.clientName.ifEmpty { remote.platformName.ifEmpty { remote.host } } else null,
                    icon = Icons.Rounded.CloudSync,
                    onClick = onOpenRemoteConfig,
                )
                NavRow(
                    title = "SIP 账号",
                    // Registered / all.
                    trailing = "${accounts.count { it.state == RegState.OK }}/${accounts.size}".takeIf { accounts.isNotEmpty() },
                    icon = Icons.Rounded.ManageAccounts,
                    onClick = onOpenAccounts,
                )
            }

            SectionCard(title = "呼叫与话务功能") {
                // Whether to be disturbed stays the user's own switch under a managed configuration.
                Personal {
                    SwitchRow(
                        title = "免打扰",
                        icon = Icons.Rounded.DoNotDisturbOn,
                        checked = settings.doNotDisturb,
                        onCheckedChange = { value -> launcher.doNotDisturb(value) },
                    )
                }
                SwitchRow(
                    title = "视频通话",
                    icon = Icons.Rounded.Videocam,
                    checked = settings.videoCalls,
                    onCheckedChange = { value -> engine.updateSettings { it.copy(videoCalls = value) } },
                )
                NavRow(title = "高级设置", icon = Icons.Rounded.Tune, onClick = onOpenAdvanced)
                NavRow(title = "功能码", icon = Icons.Rounded.Dialpad, onClick = onOpenFeatureCodes)
                NavRow(title = if (settings.videoCalls) "编码" else "音频编码", icon = Icons.Rounded.GraphicEq, onClick = onOpenCodecs)
            }

            SectionCard(title = "对讲来电") {
                SwitchRow(
                    title = "自动接听对讲呼叫",
                    icon = Icons.Rounded.Campaign,
                    checked = settings.intercomAutoAnswer,
                    onCheckedChange = { value -> engine.updateSettings { it.copy(intercomAutoAnswer = value) } },
                )
                SwitchRow(
                    title = "对讲使用扬声器",
                    icon = Icons.AutoMirrored.Rounded.VolumeUp,
                    checked = settings.intercomSpeaker,
                    onCheckedChange = { value -> engine.updateSettings { it.copy(intercomSpeaker = value) } },
                )
            }

            SectionCard(title = "安全与通讯录") {
                NavRow(
                    title = "TLS 与 CA 证书",
                    trailing = certificates.size.toString().takeIf { certificates.isNotEmpty() },
                    icon = Icons.Rounded.VerifiedUser,
                    onClick = onOpenTls,
                )
                NavRow(
                    title = "LDAP 通讯录",
                    trailing = directories.size.toString().takeIf { directories.isNotEmpty() },
                    icon = Icons.Rounded.Dns,
                    onClick = onOpenLdap,
                )
            }

            SectionCard(title = "后台运行") {
                SwitchRow(
                    title = "后台保持在线",
                    icon = Icons.Rounded.Sync,
                    checked = settings.keepAlive,
                    onCheckedChange = { value -> engine.updateSettings { it.copy(keepAlive = value) } },
                )
                AnimatedVisibility(settings.keepAlive) {
                    NavRow(
                        title = "电池优化",
                        icon = Icons.Rounded.BatterySaver,
                        onClick = {
                            val intent = Intent(SystemSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            runCatching { context.startActivity(intent) }
                                .onFailure { messenger("无法打开电池优化设置") }
                        },
                    )
                }
                // Before Android 14 the system has no switch for this: it is always allowed.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    val lockScreenCalls by rememberLockScreenCallAccess()
                    NavRow(
                        title = "锁屏显示来电",
                        trailing = if (lockScreenCalls) "已允许" else "未允许",
                        icon = Icons.Rounded.ScreenLockPortrait,
                        onClick = { if (!context.openLockScreenCallSettings()) messenger("无法打开全屏通知设置") },
                    )
                }
            }

            // The look of the app stays the user's own under a managed configuration.
            Personal {
                SectionCard(title = "外观") {
                    Text(
                        "主题",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 16.dp, top = 10.dp),
                    )
                    val themes = listOf(ThemeMode.SYSTEM to "跟随系统", ThemeMode.LIGHT to "浅色", ThemeMode.DARK to "深色")
                    ChoiceRow(
                        options = themes,
                        selected = settings.themeMode,
                        onSelect = { mode -> engine.updateSettings { it.copy(themeMode = mode) } },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    Text(
                        "通话横幅位置",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 16.dp, top = 10.dp),
                    )
                    val positions = listOf(BannerPosition.TOP to "顶部", BannerPosition.BOTTOM to "底部")
                    ChoiceRow(
                        options = positions,
                        selected = settings.callBannerPosition,
                        onSelect = { position -> engine.updateSettings { it.copy(callBannerPosition = position) } },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        SwitchRow(
                            title = "动态配色",
                            icon = Icons.Rounded.Palette,
                            checked = settings.dynamicColor,
                            onCheckedChange = { value -> engine.updateSettings { it.copy(dynamicColor = value) } },
                        )
                    }
                }
            }

            AboutSection()

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
