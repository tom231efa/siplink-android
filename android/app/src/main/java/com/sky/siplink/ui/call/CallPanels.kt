package com.sky.siplink.ui.call

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sky.siplink.R
import com.sky.siplink.engine
import com.sky.siplink.ui.components.AppBottomSheet
import com.sky.siplink.ui.components.FieldText
import com.sky.siplink.ui.components.LocalMessenger
import com.sky.siplink.ui.shortcuts.ShortcutBoard
import com.sky.siplink.ui.shortcuts.rememberShortcutRunner

private val dtmfKeys = listOf('1', '2', '3', '4', '5', '6', '7', '8', '9', '*', '0', '#')

/**
 * In-call keypad, over the controls: it slides up from the bottom of the call screen, or, with
 * the phone on its side ([sideways]), in from the start, where the controls stand beside the
 * name. The keys are as large as the height allows, never smaller than a finger needs; where even
 * that does not fit, the panel scrolls.
 */
@Composable
internal fun DtmfPanel(onDigit: (Char) -> Unit, onClose: () -> Unit, sideways: Boolean) {
    val haptics = LocalHapticFeedback.current
    var typed by remember { mutableStateOf("") }
    val density = LocalDensity.current
    val insets = WindowInsets.safeDrawing
    BoxWithConstraints {
        val bars = with(density) { (insets.getTop(density) + insets.getBottom(density)).toDp() }
        val keySize = if (sideways) sideKeySize(maxHeight - bars) else FullKey
        Column(
            (if (sideways) Modifier.fillMaxHeight() else Modifier.fillMaxWidth())
                .clip(if (sideways) RoundedCornerShape(topEnd = 32.dp, bottomEnd = 32.dp) else RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp))
                // Solid: the controls underneath must not show between the keys.
                .background(MaterialTheme.colorScheme.surfaceContainer)
                // Swallow touches so they do not reach the controls underneath.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
                // On its side, as wide as the controls stand from the edge (after the system bars, as
                // they do), the cut-out kept clear inside.
                .then(if (sideways) Modifier.windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Start)).width(SidePaneWidth) else Modifier)
                .windowInsetsPadding(insets.only(if (sideways) WindowInsetsSides.Vertical + WindowInsetsSides.Start else WindowInsetsSides.Bottom))
                .verticalScroll(rememberScrollState())
                .padding(horizontal = if (sideways) 20.dp else 28.dp, vertical = if (sideways) SidePadding else 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    typed.takeLast(24),
                    modifier = Modifier.weight(1f),
                    color = Color.White,
                    fontSize = 24.sp,
                    maxLines = 1,
                    letterSpacing = 2.sp,
                )
                IconButton(onClick = onClose) {
                    // It goes back the way it came.
                    val icon = if (sideways) Icons.AutoMirrored.Rounded.KeyboardArrowLeft else Icons.Rounded.KeyboardArrowDown
                    Icon(icon, contentDescription = stringResource(R.string.call_keypad_close), tint = Color.White)
                }
            }
            if (!sideways) Spacer(Modifier.height(8.dp))
            dtmfKeys.chunked(3).forEach { row ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = if (sideways) SideRowGap else 6.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    row.forEach { digit ->
                        Box(
                            Modifier
                                .size(keySize)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.12f))
                                .clickable {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    typed += digit
                                    onDigit(digit)
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(digit.toString(), color = Color.White, fontSize = if (keySize < FullKey) 22.sp else 26.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
            if (!sideways) Spacer(Modifier.height(8.dp))
        }
    }
}

/** The keys in the place of the controls: four rows of them, the row on top and the gaps in [height]. */
private fun sideKeySize(height: Dp): Dp = ((height - HeaderHeight - SidePadding * 2) / 4 - SideRowGap * 2).coerceIn(MinKey, FullKey)

private val FullKey = 66.dp

/** What a finger needs. */
private val MinKey = 48.dp
private val HeaderHeight = 48.dp
private val SidePadding = 8.dp
private val SideRowGap = 4.dp

/** Asks for the transfer target and whether to transfer blindly or after consulting. */
@Composable
internal fun TransferSheet(
    canConsult: Boolean,
    onDismiss: () -> Unit,
    onBlind: (String) -> Unit,
    onConsult: (String) -> Unit,
) {
    var target by remember { mutableStateOf("") }
    AppBottomSheet(onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.call_transfer_title), style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = target,
                onValueChange = { target = it },
                modifier = Modifier.fillMaxWidth(),
                label = { FieldText(stringResource(R.string.call_transfer_to)) },
                placeholder = { FieldText(stringResource(R.string.call_transfer_hint)) },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = { onConsult(target) },
                    enabled = target.isNotBlank() && canConsult,
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.call_transfer_attended)) }
                Button(
                    onClick = { onBlind(target) },
                    enabled = target.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.call_transfer_blind)) }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

/**
 * The shortcut keys during a call: transfer, park and DTMF keys act on the call in progress,
 * a speed dial key starts a consultation call next to it.
 */
@Composable
internal fun ShortcutsSheet(onDismiss: () -> Unit, onNotice: (String) -> Unit) {
    val all by LocalContext.current.engine.shortcuts.shortcuts.collectAsStateWithLifecycle()
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    // This screen has no snackbar: what the keys have to say goes to its notice line.
    CompositionLocalProvider(LocalMessenger provides onNotice) {
        val runner = rememberShortcutRunner(accountId = null) { outcome ->
            onDismiss()
            outcome.notice?.let(onNotice)
        }
        AppBottomSheet(onDismiss) {
            if (all.isEmpty()) {
                Text(
                    stringResource(R.string.call_shortcuts_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 32.dp),
                )
            } else {
                ShortcutBoard(
                    all = all,
                    category = category,
                    onCategoryChange = { category = it },
                    editing = false,
                    onKey = runner,
                    modifier = Modifier.fillMaxHeight(0.72f),
                )
            }
        }
    }
}
