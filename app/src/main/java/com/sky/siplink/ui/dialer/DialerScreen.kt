@file:OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)

package com.sky.siplink.ui.dialer

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Dialpad
import androidx.compose.material.icons.rounded.DoNotDisturbOn
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.PersonOff
import androidx.compose.material.icons.rounded.SupportAgent
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.Voicemail
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sky.siplink.core.model.SipAccount
import com.sky.siplink.engine
import com.sky.siplink.ui.components.FeatureSheet
import com.sky.siplink.ui.components.FieldText
import com.sky.siplink.ui.components.AccountPickerSheet
import com.sky.siplink.ui.components.LocalConfigLocked
import com.sky.siplink.ui.components.LocalMessenger
import com.sky.siplink.ui.components.StatusDot
import com.sky.siplink.ui.components.pressScale
import com.sky.siplink.ui.components.rememberCallLauncher
import com.sky.siplink.ui.theme.Brand

private val dialKeys = listOf(
    '1' to "", '2' to "ABC", '3' to "DEF",
    '4' to "GHI", '5' to "JKL", '6' to "MNO",
    '7' to "PQRS", '8' to "TUV", '9' to "WXYZ",
    '*' to "", '0' to "+", '#' to "",
)

@Composable
fun DialerScreen(onAddAccount: () -> Unit, onOpenShortcuts: (accountId: String?) -> Unit) {
    val context = LocalContext.current
    val engine = context.engine
    val accounts by engine.accounts.accounts.collectAsStateWithLifecycle()
    val settings by engine.settings.settings.collectAsStateWithLifecycle()
    val voicemail by engine.voicemail.collectAsStateWithLifecycle()
    val launcher = rememberCallLauncher()
    val messenger = LocalMessenger.current
    val haptics = LocalHapticFeedback.current

    var number by rememberSaveable { mutableStateOf("") }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var keyboardMode by rememberSaveable { mutableStateOf(false) }
    var pickerOpen by remember { mutableStateOf(false) }
    var featuresOpen by remember { mutableStateOf(false) }

    val selected = accounts.firstOrNull { it.id == selectedId }
    val active = selected ?: accounts.firstOrNull { it.isDefault } ?: accounts.firstOrNull()

    fun place(video: Boolean = false) {
        when {
            active == null -> onAddAccount()
            number.isBlank() -> messenger("请输入号码")
            else -> launcher.call(number, selected?.id, video)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (active == null) onAddAccount() else featuresOpen = true }) {
                Icon(Icons.Rounded.SupportAgent, contentDescription = "话务功能")
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                AccountChip(active, canAdd = !LocalConfigLocked.current, onClick = { if (active == null) onAddAccount() else pickerOpen = true })
            }
            IconButton(onClick = { keyboardMode = !keyboardMode }) {
                AnimatedContent(keyboardMode, label = "inputMode") { keyboard ->
                    Icon(
                        if (keyboard) Icons.Rounded.Dialpad else Icons.Rounded.Keyboard,
                        contentDescription = if (keyboard) "切换到拨号盘" else "切换到键盘输入",
                    )
                }
            }
        }

        StatusChips(
            doNotDisturb = settings.doNotDisturb,
            voicemail = voicemail,
            voicemailConfigured = settings.features.voicemail.isNotBlank(),
            onDisableDnd = { launcher.doNotDisturb(false, selected?.id) },
            onVoicemail = { launcher.voicemail(selected?.id) },
        )

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            NumberDisplay(number)
        }

        AnimatedContent(
            targetState = keyboardMode,
            modifier = Modifier
                .weight(2.7f)
                .heightIn(max = 380.dp)
                .fillMaxWidth(),
            transitionSpec = {
                (fadeIn(tween(220)) + slideInVertically(tween(260)) { it / 6 }) togetherWith fadeOut(tween(140))
            },
            label = "dialInput",
        ) { keyboard ->
            if (keyboard) {
                AddressInput(number, onChange = { number = it }, onCall = { place() })
            } else {
                DialPad(
                    onDigit = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        number += it
                    },
                    onLongZero = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        number += '+'
                    },
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Left: the shortcut keys, or a video call while a number is typed and video calls are
            // on. Right: delete, there only while a number is being typed (both keep their place,
            // so the call button stays in the middle).
            val videoKey = settings.videoCalls && number.isNotBlank() && active != null
            AnimatedContent(
                targetState = videoKey,
                transitionSpec = { fadeIn(tween(140)) togetherWith fadeOut(tween(100)) },
                label = "leftKey",
            ) { video ->
                if (video) {
                    SideAction(onClick = { place(video = true) }) {
                        Icon(Icons.Rounded.Videocam, contentDescription = "视频呼叫")
                    }
                } else {
                    SideAction(onClick = { onOpenShortcuts(selected?.id) }) {
                        Icon(Icons.Rounded.Apps, contentDescription = "快捷键")
                    }
                }
            }
            CallButton(enabled = number.isNotBlank() || active == null, onClick = { place() })
            Box(Modifier.size(SideActionSize)) {
                androidx.compose.animation.AnimatedVisibility(
                    visible = number.isNotEmpty(),
                    enter = fadeIn(tween(120)) + scaleIn(spring(dampingRatio = 0.6f, stiffness = 600f), initialScale = 0.7f),
                    exit = fadeOut(tween(90)) + scaleOut(tween(120), targetScale = 0.7f),
                ) {
                    SideAction(
                        onClick = { number = number.dropLast(1) },
                        onLongClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            number = ""
                        },
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.Backspace, contentDescription = "删除")
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }

    if (featuresOpen) {
        FeatureSheet(
            launcher = launcher,
            onDismiss = { featuresOpen = false },
            target = number,
            accountId = selected?.id,
        )
    }

    if (pickerOpen) {
        AccountPickerSheet(
            accounts = accounts,
            selectedId = selectedId,
            onSelect = {
                selectedId = it
                pickerOpen = false
            },
            onDismiss = { pickerOpen = false },
        )
    }
}

/** @param canAdd without an account the chip offers to add one; false where the configuration is locked, it then only says there is none */
@Composable
private fun AccountChip(account: SipAccount?, canAdd: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            Modifier.padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (account == null) {
                Icon(if (canAdd) Icons.Rounded.PersonAdd else Icons.Rounded.PersonOff, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (canAdd) "添加 SIP 账号" else "没有 SIP 账号", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.width(8.dp))
            } else {
                StatusDot(account.state)
                Spacer(Modifier.width(8.dp))
                Text(
                    account.name.ifBlank { account.identity },
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.sizeIn(maxWidth = 200.dp),
                )
                Icon(Icons.Rounded.ArrowDropDown, contentDescription = "选择呼出账号")
            }
        }
    }
}

/**
 * Reminders shown under the account chip: do not disturb is on, new voicemail is waiting.
 *
 * @param voicemailConfigured the voicemail code is set; without it the reminder is grey and a tap says so
 */
@Composable
private fun StatusChips(
    doNotDisturb: Boolean,
    voicemail: Int,
    voicemailConfigured: Boolean,
    onDisableDnd: () -> Unit,
    onVoicemail: () -> Unit,
) {
    AnimatedVisibility(doNotDisturb || voicemail > 0, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (doNotDisturb) {
                AssistChip(
                    onClick = onDisableDnd,
                    label = { Text("免打扰已开启") },
                    leadingIcon = {
                        Icon(Icons.Rounded.DoNotDisturbOn, contentDescription = null, tint = Brand.HangUpRed, modifier = Modifier.size(18.dp))
                    },
                )
            }
            if (voicemail > 0) {
                AssistChip(
                    onClick = onVoicemail,
                    modifier = Modifier.alpha(if (voicemailConfigured) 1f else 0.38f),
                    label = { Text("$voicemail 条新留言") },
                    leadingIcon = { Icon(Icons.Rounded.Voicemail, contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
            }
        }
    }
}

@Composable
private fun NumberDisplay(number: String) {
    val fontSize by animateFloatAsState(
        targetValue = when {
            number.length <= 11 -> 40f
            number.length <= 16 -> 31f
            number.length <= 24 -> 23f
            else -> 18f
        },
        animationSpec = spring(stiffness = 300f),
        label = "numberSize",
    )
    if (number.isEmpty()) {
        Text(
            "输入号码或 SIP 地址",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        )
    } else {
        Text(
            text = number,
            fontSize = fontSize.sp,
            lineHeight = (fontSize * 1.2f).sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun DialPad(onDigit: (Char) -> Unit, onLongZero: () -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        dialKeys.chunked(3).forEach { row ->
            Row(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                row.forEach { (digit, letters) ->
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        contentAlignment = Alignment.Center,
                    ) {
                        DialKey(
                            digit = digit,
                            letters = letters,
                            onClick = { onDigit(digit) },
                            onLongClick = if (digit == '0') onLongZero else null,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DialKey(digit: Char, letters: String, onClick: () -> Unit, onLongClick: (() -> Unit)?) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.86f else 1f,
        animationSpec = spring(dampingRatio = 0.42f, stiffness = 700f),
        label = "keyScale",
    )
    val container by animateColorAsState(
        targetValue = if (pressed) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        label = "keyColor",
    )
    Box(
        modifier = Modifier
            .sizeIn(maxWidth = 84.dp, maxHeight = 84.dp)
            .fillMaxHeight()
            .aspectRatio(1f, matchHeightConstraintsFirst = true)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(container)
            .combinedClickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(digit.toString(), fontSize = 28.sp, fontWeight = FontWeight.Medium)
            if (letters.isNotEmpty()) {
                Text(
                    letters,
                    fontSize = 10.sp,
                    lineHeight = 12.sp,
                    letterSpacing = 1.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AddressInput(value: String, onChange: (String) -> Unit, onCall: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(top = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth(),
            label = { FieldText("号码或 SIP 地址") },
            placeholder = { FieldText("例如 1001 或 alice@example.com") },
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { onCall() }),
        )
    }
}

@Composable
private fun CallButton(enabled: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.9f else 1f,
        animationSpec = spring(dampingRatio = 0.4f, stiffness = 500f),
        label = "callScale",
    )
    val alpha by animateFloatAsState(if (enabled) 1f else 0.45f, label = "callAlpha")
    Box(
        modifier = Modifier
            .size(76.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
            }
            .shadow(if (enabled) 6.dp else 0.dp, CircleShape)
            .clip(CircleShape)
            .background(Brand.CallGreen)
            .clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.Call, contentDescription = "呼叫", tint = Color.White, modifier = Modifier.size(34.dp))
    }
}

/** Round key next to the call button; what it does is said by its icon alone. */
@Composable
private fun SideAction(onClick: () -> Unit, onLongClick: (() -> Unit)? = null, icon: @Composable () -> Unit) {
    Box(
        Modifier
            .size(SideActionSize)
            .pressScale()
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center,
    ) {
        icon()
    }
}

private val SideActionSize = 56.dp
