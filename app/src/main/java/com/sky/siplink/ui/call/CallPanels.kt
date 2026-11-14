@file:OptIn(ExperimentalMaterial3Api::class)

package com.sky.siplink.ui.call

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val dtmfKeys = listOf('1', '2', '3', '4', '5', '6', '7', '8', '9', '*', '0', '#')

/** In-call keypad sliding up from the bottom of the call screen. */
@Composable
internal fun DtmfPanel(onDigit: (Char) -> Unit, onClose: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    var typed by remember { mutableStateOf("") }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp))
            .background(Color(0xF51C1C1C))
            // Swallow touches so they do not reach the controls underneath.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
            .navigationBarsPadding()
            .padding(horizontal = 28.dp, vertical = 16.dp),
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
                Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "收起键盘", tint = Color.White)
            }
        }
        Spacer(Modifier.height(8.dp))
        dtmfKeys.chunked(3).forEach { row ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                row.forEach { digit ->
                    Box(
                        Modifier
                            .size(66.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.12f))
                            .clickable {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                typed += digit
                                onDigit(digit)
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(digit.toString(), color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** Asks for the transfer target and whether to transfer blindly or after consulting. */
@Composable
internal fun TransferSheet(
    canConsult: Boolean,
    onDismiss: () -> Unit,
    onBlind: (String) -> Unit,
    onConsult: (String) -> Unit,
) {
    var target by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("转接通话", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = target,
                onValueChange = { target = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("转接到") },
                placeholder = { Text("分机号或 SIP 地址") },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            )
            Text(
                "盲转：立即把对方交给目标号码。咨询转：先保留对方并呼叫目标，确认后再完成转接。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = { onConsult(target) },
                    enabled = target.isNotBlank() && canConsult,
                    modifier = Modifier.weight(1f),
                ) { Text("咨询转") }
                Button(
                    onClick = { onBlind(target) },
                    enabled = target.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) { Text("盲转") }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}
