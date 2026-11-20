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
import androidx.compose.material.icons.rounded.BatterySaver
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.Dialpad
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.DoNotDisturbOn
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.ManageAccounts
import androidx.compose.material.icons.rounded.Mic
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sky.siplink.BuildConfig
import com.sky.siplink.R
import com.sky.siplink.core.logic.UpdateRules
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
    onOpenRecordingSettings: () -> Unit,
    onOpenAutoAnswer: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenLanguage: () -> Unit,
) {
    val context = LocalContext.current
    val engine = context.engine
    val settings by engine.settings.settings.collectAsStateWithLifecycle()
    val certificates by engine.tls.certificates.collectAsStateWithLifecycle()
    val accounts by engine.accounts.accounts.collectAsStateWithLifecycle()
    val directories by engine.directory.directories.collectAsStateWithLifecycle()
    val remote by engine.remote.status.collectAsStateWithLifecycle()
    val recordings by engine.recordings.recordings.collectAsStateWithLifecycle()
    val update by engine.updates.status.collectAsStateWithLifecycle()
    val messenger = LocalMessenger.current
    val launcher = rememberCallLauncher()

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text(stringResource(R.string.settings_title)) }, actions = { ManagedLock() })
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionCard(title = stringResource(R.string.settings_section_accounts)) {
                NavRow(
                    title = stringResource(R.string.settings_sip_accounts),
                    // Registered / all.
                    trailing = "${accounts.count { it.state == RegState.OK }}/${accounts.size}".takeIf { accounts.isNotEmpty() },
                    icon = Icons.Rounded.ManageAccounts,
                    onClick = onOpenAccounts,
                )
            }

            SectionCard(title = stringResource(R.string.settings_section_call_features)) {
                // Whether to be disturbed stays the user's own switch under a managed configuration.
                Personal {
                    SwitchRow(
                        title = stringResource(R.string.settings_dnd),
                        icon = Icons.Rounded.DoNotDisturbOn,
                        checked = settings.doNotDisturb,
                        onCheckedChange = { value -> launcher.doNotDisturb(value) },
                    )
                }
                SwitchRow(
                    title = stringResource(R.string.settings_video_calls),
                    icon = Icons.Rounded.Videocam,
                    checked = settings.videoCalls,
                    onCheckedChange = { value -> engine.updateSettings { it.copy(videoCalls = value) } },
                )
                NavRow(
                    title = stringResource(R.string.settings_recording),
                    trailing = recordings.size.toString().takeIf { recordings.isNotEmpty() },
                    icon = Icons.Rounded.Mic,
                    onClick = onOpenRecordingSettings,
                )
                NavRow(title = stringResource(R.string.settings_auto_answer), icon = Icons.Rounded.Campaign, onClick = onOpenAutoAnswer)
                NavRow(
                    title = stringResource(R.string.settings_ldap),
                    trailing = directories.size.toString().takeIf { directories.isNotEmpty() },
                    icon = Icons.Rounded.Dns,
                    onClick = onOpenLdap,
                )
                NavRow(title = stringResource(R.string.settings_feature_codes), icon = Icons.Rounded.Dialpad, onClick = onOpenFeatureCodes)
                NavRow(title = stringResource(if (settings.videoCalls) R.string.settings_codecs else R.string.settings_audio_codecs), icon = Icons.Rounded.GraphicEq, onClick = onOpenCodecs)
                NavRow(title = stringResource(R.string.settings_advanced), icon = Icons.Rounded.Tune, onClick = onOpenAdvanced)
            }

            SectionCard(title = stringResource(R.string.settings_section_security)) {
                NavRow(
                    title = stringResource(R.string.settings_tls),
                    trailing = certificates.size.toString().takeIf { certificates.isNotEmpty() },
                    icon = Icons.Rounded.VerifiedUser,
                    onClick = onOpenTls,
                )
            }

            SectionCard(title = stringResource(R.string.settings_section_battery)) {
                SwitchRow(
                    title = stringResource(R.string.settings_keep_alive),
                    icon = Icons.Rounded.Sync,
                    checked = settings.keepAlive,
                    onCheckedChange = { value -> engine.updateSettings { it.copy(keepAlive = value) } },
                )
                AnimatedVisibility(settings.keepAlive) {
                    val batteryFailed = stringResource(R.string.settings_battery_open_failed)
                    NavRow(
                        title = stringResource(R.string.settings_battery_optimization),
                        icon = Icons.Rounded.BatterySaver,
                        onClick = {
                            val intent = Intent(SystemSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            runCatching { context.startActivity(intent) }
                                .onFailure { messenger(batteryFailed) }
                        },
                    )
                }
                // Before Android 14 the system has no switch for this: it is always allowed.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    val lockScreenCalls by rememberLockScreenCallAccess()
                    val lockScreenFailed = stringResource(R.string.settings_lock_screen_open_failed)
                    NavRow(
                        title = stringResource(R.string.settings_lock_screen_calls),
                        trailing = stringResource(if (lockScreenCalls) R.string.settings_lock_screen_allowed else R.string.settings_lock_screen_not_allowed),
                        icon = Icons.Rounded.ScreenLockPortrait,
                        onClick = { if (!context.openLockScreenCallSettings()) messenger(lockScreenFailed) },
                    )
                }
            }

            // The look of the app stays the user's own under a managed configuration.
            Personal {
                SectionCard(title = stringResource(R.string.settings_section_appearance)) {
                    NavRow(
                        title = stringResource(R.string.settings_language),
                        trailing = languageLabel(engine.language),
                        icon = Icons.Rounded.Language,
                        onClick = onOpenLanguage,
                    )
                    Text(
                        stringResource(R.string.settings_theme),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 16.dp, top = 10.dp),
                    )
                    val themes = listOf(
                        ThemeMode.SYSTEM to stringResource(R.string.settings_theme_system),
                        ThemeMode.LIGHT to stringResource(R.string.settings_theme_light),
                        ThemeMode.DARK to stringResource(R.string.settings_theme_dark),
                    )
                    ChoiceRow(
                        options = themes,
                        selected = settings.themeMode,
                        onSelect = { mode -> engine.updateSettings { it.copy(themeMode = mode) } },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    Text(
                        stringResource(R.string.settings_banner_position),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 16.dp, top = 10.dp),
                    )
                    val positions = listOf(
                        BannerPosition.TOP to stringResource(R.string.settings_banner_top),
                        BannerPosition.BOTTOM to stringResource(R.string.settings_banner_bottom),
                    )
                    ChoiceRow(
                        options = positions,
                        selected = settings.callBannerPosition,
                        onSelect = { position -> engine.updateSettings { it.copy(callBannerPosition = position) } },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        SwitchRow(
                            title = stringResource(R.string.settings_dynamic_color),
                            icon = Icons.Rounded.Palette,
                            checked = settings.dynamicColor,
                            onCheckedChange = { value -> engine.updateSettings { it.copy(dynamicColor = value) } },
                        )
                    }
                }
            }

            SectionCard(title = null) {
                // Like the profiles of a proxy client: scanned or typed in, then in force until it is deleted here.
                NavRow(
                    title = stringResource(R.string.settings_remote_config),
                    subtitle = if (remote.enrolled) remote.clientName.ifEmpty { remote.platformName.ifEmpty { remote.host } } else null,
                    icon = Icons.Rounded.CloudSync,
                    onClick = onOpenRemoteConfig,
                )
                NavRow(
                    title = stringResource(R.string.settings_about),
                    // A newer version is worth a word here; how a check failed is only told on the page itself.
                    trailing = if (UpdateRules.offered(update, BuildConfig.VERSION_NAME) != null) stringResource(R.string.settings_update_available) else BuildConfig.VERSION_NAME,
                    icon = Icons.Rounded.Info,
                    onClick = onOpenAbout,
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
