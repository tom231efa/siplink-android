@file:OptIn(ExperimentalMaterial3Api::class)

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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.GroupAdd
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Hearing
import androidx.compose.material.icons.rounded.PhoneCallback
import androidx.compose.material.icons.rounded.PhoneDisabled
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Voicemail
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.sky.siplink.core.logic.FeatureDial

/**
 * Bottom sheet with the PBX features that act on an extension: listen, whisper, barge, force
 * hang up and pickup, plus group pickup and voicemail.
 *
 * @param name contact the sheet was opened for; null lets the user type the extension
 * @param target number or SIP address of that contact, or the initial value of the input
 * @param accountId account to dial with, null for the default account
 */
@Composable
fun FeatureSheet(
    launcher: CallLauncher,
    onDismiss: () -> Unit,
    name: String? = null,
    target: String = "",
    accountId: String? = null,
) {
    var extension by remember { mutableStateOf(FeatureDial.extensionOf(target)) }
    var confirmForce by remember { mutableStateOf(false) }
    val hasTarget = extension.isNotBlank()

    fun run(action: () -> Unit) {
        onDismiss()
        action()
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding(),
        ) {
            Column(Modifier.padding(horizontal = 24.dp)) {
                Text(name ?: "企业功能", style = MaterialTheme.typography.titleLarge)
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
                FeatureRow(Icons.Rounded.Call, "呼叫", "普通语音呼叫", hasTarget) { run { launcher.call(target, accountId) } }
                FeatureRow(Icons.Rounded.Campaign, "对讲", "对方免按键自动接听", hasTarget) {
                    run { launcher.intercom(target, accountId) }
                }
            }
            SupervisorRow(FeatureDial.Action.LISTEN, Icons.Rounded.Hearing, hasTarget) {
                run { launcher.feature(FeatureDial.Action.LISTEN, extension, accountId) }
            }
            SupervisorRow(FeatureDial.Action.WHISPER, Icons.Rounded.RecordVoiceOver, hasTarget) {
                run { launcher.feature(FeatureDial.Action.WHISPER, extension, accountId) }
            }
            SupervisorRow(FeatureDial.Action.BARGE, Icons.Rounded.GroupAdd, hasTarget) {
                run { launcher.feature(FeatureDial.Action.BARGE, extension, accountId) }
            }
            FeatureRow(
                icon = Icons.Rounded.PhoneDisabled,
                title = if (confirmForce) "再点一次确认强拆 $extension" else FeatureDial.Action.FORCE_HANG_UP.label,
                subtitle = FeatureDial.Action.FORCE_HANG_UP.description,
                enabled = hasTarget,
                destructive = true,
            ) {
                // Cutting someone's call off cannot be undone, so it takes two taps.
                if (confirmForce) {
                    run { launcher.feature(FeatureDial.Action.FORCE_HANG_UP, extension, accountId) }
                } else {
                    confirmForce = true
                }
            }
            SupervisorRow(FeatureDial.Action.PICKUP, Icons.Rounded.PhoneCallback, hasTarget) {
                run { launcher.feature(FeatureDial.Action.PICKUP, extension, accountId) }
            }

            HorizontalDivider(Modifier.padding(horizontal = 24.dp, vertical = 6.dp))
            FeatureRow(Icons.Rounded.Groups, "同组代接", "接起本代接组内正在振铃的来电", true) {
                run { launcher.groupPickup(accountId) }
            }
            FeatureRow(Icons.Rounded.Voicemail, "语音信箱", "收听语音留言", true) {
                run { launcher.voicemail(accountId) }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun SupervisorRow(action: FeatureDial.Action, icon: ImageVector, enabled: Boolean, onClick: () -> Unit) {
    FeatureRow(icon, action.label, action.description, enabled, onClick = onClick)
}

@Composable
private fun FeatureRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    enabled: Boolean,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val accent by animateColorAsState(
        if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        label = "featureAccent",
    )
    Row(
        Modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = if (enabled) 1f else 0.38f }
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
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
