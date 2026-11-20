package com.sky.siplink.ui.settings

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Update
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sky.siplink.BuildConfig
import com.sky.siplink.core.logic.DownloadState
import com.sky.siplink.core.logic.FoundRelease
import com.sky.siplink.core.logic.UpdateRules
import com.sky.siplink.engine
import com.sky.siplink.ui.components.AppBottomSheet
import com.sky.siplink.ui.components.LocalMessenger
import com.sky.siplink.ui.components.NavRow
import com.sky.siplink.ui.components.SwitchRow
import com.sky.siplink.ui.components.text

/**
 * The rows of the version and its updates on the About page. A tap on the version checks for a
 * newer one, or, once one is known, shows it with its download and installation. Checking is an
 * action and stays open under a managed configuration; whether the app checks by itself is a
 * setting and is locked there like the others.
 */
@Composable
internal fun UpdateRows() {
    val context = LocalContext.current
    val engine = context.engine
    val settings by engine.settings.settings.collectAsStateWithLifecycle()
    val update by engine.updates.status.collectAsStateWithLifecycle()
    val download by engine.updates.download.collectAsStateWithLifecycle()
    val installed = BuildConfig.VERSION_NAME
    val offered = UpdateRules.offered(update, installed)
    var showRelease by rememberSaveable { mutableStateOf(false) }
    // The user chose to install, and was taken to the system setting that allows it first.
    var installAfterPermission by rememberSaveable { mutableStateOf<String?>(null) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val path = installAfterPermission ?: return@LifecycleEventEffect
        installAfterPermission = null
        if (UpdateInstaller.allowed(context)) UpdateInstaller.install(context, path)
    }

    NavRow(
        title = "版本 $installed",
        subtitle = UpdateRules.status(update, installed, download).text(),
        icon = Icons.Rounded.SystemUpdate,
        onClick = {
            when {
                offered != null -> showRelease = true
                !update.checking -> engine.updates.checkNow()
            }
        },
    )
    SwitchRow(
        title = "自动检查更新",
        icon = Icons.Rounded.Update,
        checked = settings.autoUpdateCheck,
        onCheckedChange = { value -> engine.updateSettings { it.copy(autoUpdateCheck = value) } },
    )

    if (showRelease && offered != null) {
        ReleaseSheet(
            release = offered,
            installed = installed,
            download = download,
            onInstall = { path ->
                if (UpdateInstaller.allowed(context)) {
                    UpdateInstaller.install(context, path)
                } else {
                    installAfterPermission = path
                    UpdateInstaller.openPermission(context)
                }
            },
            onDismiss = { showRelease = false },
        )
    }
}

/**
 * The newer version, its release notes, and what the user can do about it: skip it, or download
 * it (with progress, cancellable) and hand it to the system's installer. Without an APK for this
 * device the release page opens in the browser instead.
 */
@Composable
private fun ReleaseSheet(release: FoundRelease, installed: String, download: DownloadState, onInstall: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val updates = context.engine.updates
    val messenger = LocalMessenger.current
    val mine = download.takeIf {
        when (it) {
            is DownloadState.Running -> it.version == release.version
            is DownloadState.Ready -> it.version == release.version
            is DownloadState.Failed -> it.version == release.version
            DownloadState.Idle -> false
        }
    } ?: DownloadState.Idle
    AppBottomSheet(onDismiss = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("新版本 ${release.version}", style = MaterialTheme.typography.titleLarge)
            Text(
                "当前版本 $installed",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (release.notes.isNotBlank()) {
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Text(
                        release.notes,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = NOTES_MAX_HEIGHT)
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp),
                    )
                }
            }
            when (mine) {
                is DownloadState.Running -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (mine.total > 0) {
                        LinearProgressIndicator(progress = { mine.done.toFloat() / mine.total }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    Text(
                        "正在下载 ${UpdateRules.percent(mine)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                is DownloadState.Failed -> Text("下载失败", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                else -> Unit
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = {
                        updates.cancelDownload()
                        updates.skip()
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("跳过此版本") }
                val apk = release.apk
                FilledTonalButton(
                    onClick = {
                        when {
                            apk == null -> {
                                // No package for this device: the release page, in the browser.
                                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, release.pageUrl.toUri())) }
                                    .onFailure { messenger("无法打开浏览器") }
                                onDismiss()
                            }
                            mine is DownloadState.Running -> updates.cancelDownload()
                            mine is DownloadState.Ready -> onInstall(mine.path)
                            else -> updates.startDownload(release)
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        when {
                            apk == null -> "前往下载页"
                            mine is DownloadState.Running -> "取消下载"
                            mine is DownloadState.Ready -> "安装"
                            mine is DownloadState.Failed -> "重新下载"
                            else -> "下载并安装"
                        },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

/** The notes scroll inside this height, so that the buttons below them stay on the screen. */
private val NOTES_MAX_HEIGHT = 280.dp
