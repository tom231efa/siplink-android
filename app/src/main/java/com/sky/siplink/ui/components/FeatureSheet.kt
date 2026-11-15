package com.sky.siplink.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PhoneCallback
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.GroupAdd
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Hearing
import androidx.compose.material.icons.rounded.PhoneDisabled
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Voicemail
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sky.siplink.core.logic.FeatureDial
import com.sky.siplink.engine

/**
 * Bottom sheet with the PBX features that act on an extension: intercom, listen, whisper,
 * barge, force hang up and pickup, plus group pickup and voicemail. A feature whose code is
 * left empty is not in use: its row is grey, and a tap says that the code is missing.
 *
 * @param name contact the sheet was opened for; null lets the user type the extension
 * @param target number or SIP address of that contact, or the initial value of the input
 * @param accountId account to dial with, null for the default account
 * @param onAddShortcut offers to make a shortcut key of the contact; null hides the entry
 */
@Composable
fun FeatureSheet(
    launcher: CallLauncher,
    onDismiss: () -> Unit,
    name: String? = null,
    target: String = "",
    accountId: String? = null,
    onAddShortcut: (() -> Unit)? = null,
) {
    var extension by remember { mutableStateOf(FeatureDial.extensionOf(target)) }
    var confirmForce by remember { mutableStateOf(false) }
    val hasTarget = extension.isNotBlank()
    val settings by LocalContext.current.engine.settings.settings.collectAsStateWithLifecycle()
    val codes = settings.features
    val forceConfigured = FeatureDial.isConfigured(FeatureDial.Action.FORCE_HANG_UP, codes)

    fun run(action: () -> Unit) {
        onDismiss()
        action()
    }

    /** A feature that is dialled as its code followed by the extension. */
    @Composable
    fun DialRow(action: FeatureDial.Action, icon: ImageVector) {
        // The launcher answers a feature without a code with the notice, whatever the target.
        val configured = FeatureDial.isConfigured(action, codes)
        FeatureRow(icon, action.label, enabled = hasTarget || !configured, dimmed = !configured) {
            run { launcher.feature(action, extension, accountId) }
        }
    }

    AppBottomSheet(onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            Column(Modifier.padding(horizontal = 24.dp)) {
                Text(name ?: "话务功能", style = MaterialTheme.typography.titleLarge)
                if (name != null) {
                    Text(
                        "分机 $extension",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = extension,
                        onValueChange = {
                            extension = it.filterNot(Char::isWhitespace)
                            confirmForce = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("目标分机") },
                        placeholder = { Text("例如 1002") },
                        singleLine = true,
                        shape = MaterialTheme.shapes.medium,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))

            if (name != null) {
                FeatureRow(Icons.Rounded.Call, "呼叫", hasTarget) { run { launcher.call(target, accountId) } }
                if (onAddShortcut != null) {
                    FeatureRow(Icons.Rounded.Apps, "添加到快捷键", hasTarget) { run(onAddShortcut) }
                }
            }
            DialRow(FeatureDial.Action.INTERCOM, Icons.Rounded.Campaign)
            DialRow(FeatureDial.Action.LISTEN, Icons.Rounded.Hearing)
            DialRow(FeatureDial.Action.WHISPER, Icons.Rounded.RecordVoiceOver)
            DialRow(FeatureDial.Action.BARGE, Icons.Rounded.GroupAdd)
            FeatureRow(
                icon = Icons.Rounded.PhoneDisabled,
                title = if (confirmForce) "再点一次确认强拆 $extension" else FeatureDial.Action.FORCE_HANG_UP.label,
                enabled = hasTarget || !forceConfigured,
                destructive = true,
                dimmed = !forceConfigured,
            ) {
                // Cutting someone's call off cannot be undone, so it takes two taps.
                if (confirmForce || !forceConfigured) {
                    run { launcher.feature(FeatureDial.Action.FORCE_HANG_UP, extension, accountId) }
                } else {
                    confirmForce = true
                }
            }
            DialRow(FeatureDial.Action.PICKUP, Icons.AutoMirrored.Rounded.PhoneCallback)

            HorizontalDivider(Modifier.padding(horizontal = 24.dp, vertical = 6.dp))
            FeatureRow(Icons.Rounded.Groups, "同组代接", true, dimmed = codes.groupPickup.isBlank()) {
                run { launcher.groupPickup(accountId) }
            }
            FeatureRow(Icons.Rounded.Voicemail, "语音信箱", true, dimmed = codes.voicemail.isBlank()) {
                run { launcher.voicemail(accountId) }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

/** @param dimmed drawn grey although it can be tapped: the feature has no code, the tap says so */
@Composable
private fun FeatureRow(
    icon: ImageVector,
    title: String,
    enabled: Boolean,
    destructive: Boolean = false,
    dimmed: Boolean = false,
    onClick: () -> Unit,
) {
    val accent by animateColorAsState(
        if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        label = "featureAccent",
    )
    Row(
        Modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = if (enabled && !dimmed) 1f else 0.38f }
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(16.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}
