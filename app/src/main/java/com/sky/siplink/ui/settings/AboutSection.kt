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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sky.siplink.BuildConfig
import com.sky.siplink.core.logic.FoundRelease
import com.sky.siplink.core.logic.UpdateRules
import com.sky.siplink.engine
import com.sky.siplink.ui.components.AppBottomSheet
import com.sky.siplink.ui.components.LocalMessenger
import com.sky.siplink.ui.components.NavRow
import com.sky.siplink.ui.components.SectionCard
import com.sky.siplink.ui.components.SwitchRow

/**
 * The version of the app and its updates. A tap on the version checks for a newer one, or, once
 * one is known, shows it. Checking is an action and stays open under a managed configuration;
 * whether the app checks by itself is a setting and is locked there like the others.
 */
@Composable
fun AboutSection() {
    val context = LocalContext.current
    val engine = context.engine
    val settings by engine.settings.settings.collectAsStateWithLifecycle()
    val update by engine.updates.status.collectAsStateWithLifecycle()
    val installed = BuildConfig.VERSION_NAME
    val offered = UpdateRules.offered(update, installed)
    var showRelease by rememberSaveable { mutableStateOf(false) }

    SectionCard(title = "关于") {
        NavRow(
            title = "版本 $installed",
            subtitle = UpdateRules.status(update, installed),
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
    }

    if (showRelease && offered != null) {
        ReleaseSheet(offered, installed, onDismiss = { showRelease = false })
    }
}

/** The newer version, its release notes, and what the user can do about it. */
@Composable
private fun ReleaseSheet(release: FoundRelease, installed: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val messenger = LocalMessenger.current
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
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = {
                        context.engine.updates.skip()
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("跳过此版本") }
                FilledTonalButton(
                    onClick = {
                        // The browser downloads the package; installing it is up to the user and the system.
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, release.url.toUri())) }
                            .onFailure { messenger("无法打开浏览器") }
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("下载安装包") }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

/** The notes scroll inside this height, so that the buttons below them stay on the screen. */
private val NOTES_MAX_HEIGHT = 280.dp
