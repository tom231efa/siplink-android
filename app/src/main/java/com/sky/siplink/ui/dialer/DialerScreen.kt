@file:OptIn(ExperimentalMaterial3Api::class)

package com.sky.siplink.ui.dialer

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Dialpad
import androidx.compose.material.icons.rounded.DoNotDisturbOn
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.PersonOff
import androidx.compose.material.icons.rounded.SupportAgent
import androidx.compose.material.icons.rounded.Voicemail
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
import com.sky.siplink.ui.components.AccountPickerSheet
import com.sky.siplink.ui.components.FadingText
import com.sky.siplink.ui.components.FeatureSheet
import com.sky.siplink.ui.components.FieldText
import com.sky.siplink.ui.components.LocalConfigLocked
import com.sky.siplink.ui.components.LocalMessenger
import com.sky.siplink.ui.components.StatusDot
import com.sky.siplink.ui.components.rememberCallLauncher
import com.sky.siplink.ui.theme.Brand

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

    val parts = DialerParts(
        chipsShown = settings.doNotDisturb || voicemail > 0,
        keyboardMode = keyboardMode,
        top = {
            TopRow(
                active = active,
                keyboardMode = keyboardMode,
                onFeatures = { if (active == null) onAddAccount() else featuresOpen = true },
                onAccount = { if (active == null) onAddAccount() else pickerOpen = true },
                onToggleInput = { keyboardMode = !keyboardMode },
            )
        },
        chips = {
            StatusChips(
                doNotDisturb = settings.doNotDisturb,
                voicemail = voicemail,
                voicemailConfigured = settings.features.voicemail.isNotBlank(),
                onDisableDnd = { launcher.doNotDisturb(false, selected?.id) },
                onVoicemail = { launcher.voicemail(selected?.id) },
            )
        },
        display = { NumberDisplay(number) },
        input = { modifier, compact ->
            InputArea(
                keyboardMode = keyboardMode,
                compact = compact,
                number = number,
                onNumber = { number = it },
                onCall = { place() },
                onDigit = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    number += it
                },
                onLongZero = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    number += '+'
                },
                modifier = modifier,
            )
        },
        callRow = {
            CallRow(
                videoKey = settings.videoCalls && number.isNotBlank() && active != null,
                canCall = number.isNotBlank() || active == null,
                canDelete = number.isNotEmpty(),
                onCall = { place() },
                onVideoCall = { place(video = true) },
                onShortcuts = { onOpenShortcuts(selected?.id) },
                onDelete = { number = number.dropLast(1) },
                onClear = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    number = ""
                },
            )
        },
    )

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)),
    ) {
        // Decided on the room without the soft keyboard, so that opening it never rearranges the
        // screen (which would take the focus away from the field and close the keyboard again).
        val twoPanes = maxWidth > maxHeight && maxWidth >= TwoPanesMinWidth && maxHeight < TwoPanesBelowHeight
        Box(
            Modifier
                .fillMaxSize()
                .imePadding(),
            contentAlignment = Alignment.TopCenter,
        ) {
            if (twoPanes) TwoPanes(parts) else Stacked(parts)
        }
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

/**
 * The pieces of the dial screen, which [Stacked] and [TwoPanes] arrange.
 *
 * @param input the dial pad or the address field, in the space the modifier gives it; compact
 *   when it stands beside the other pieces
 */
private class DialerParts(
    val chipsShown: Boolean,
    val keyboardMode: Boolean,
    val top: @Composable () -> Unit,
    val chips: @Composable () -> Unit,
    val display: @Composable () -> Unit,
    val input: @Composable (modifier: Modifier, compact: Boolean) -> Unit,
    val callRow: @Composable () -> Unit,
)

/** Wider than high and lower than this: the number on one side, the keys on the other. */
private val TwoPanesBelowHeight = 560.dp

/** Narrower than this, two panes would not leave either of them enough room. */
private val TwoPanesMinWidth = 480.dp

/** Wider rooms keep the stacked layout together in the middle instead of spreading the keys apart. */
private val StackedMaxWidth = 440.dp

/** Height of the top row: one icon button. */
private val TopRowHeight = 48.dp

/** Height of the reminders under the top row: the gap above them and a chip's touch target. */
private val ChipsHeight = 58.dp

/** Space above and below the number beside the keys, so that it never touches the call button. */
private val NumberGap = 6.dp

/** What the address field takes at the least (its top gap included), also with larger fonts. */
private val InputMinHeight = 72.dp

private const val NUMBER_WEIGHT = 1f
private const val INPUT_WEIGHT = 2.7f

/** Portrait: everything one above the other, the keys below the number. Too low a window scrolls. */
@Composable
private fun Stacked(parts: DialerParts) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val fixed = 12.dp + TopRowHeight + 12.dp + CallRowHeight + 16.dp + if (parts.chipsShown) ChipsHeight else 0.dp
        // The number and the input share what is left; below this the keys would get too small.
        val needed = fixed + (if (parts.keyboardMode) InputMinHeight else PadMinHeight) * ((NUMBER_WEIGHT + INPUT_WEIGHT) / INPUT_WEIGHT)
        Column(
            Modifier
                .widthIn(max = StackedMaxWidth)
                .fillMaxWidth()
                .then(if (maxHeight < needed) Modifier.verticalScroll(rememberScrollState()).height(needed) else Modifier.fillMaxHeight())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(12.dp))
            parts.top()
            parts.chips()
            Box(
                Modifier
                    .weight(NUMBER_WEIGHT)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                parts.display()
            }
            parts.input(
                Modifier
                    .weight(INPUT_WEIGHT)
                    .heightIn(max = 380.dp)
                    .fillMaxWidth(),
                false,
            )
            Spacer(Modifier.height(12.dp))
            parts.callRow()
            Spacer(Modifier.height(16.dp))
        }
    }
}

/**
 * Landscape, or any window wide and low: account, number and call button on one side, the keys
 * (or the address field) on the other. The keys take their size from the height.
 */
@Composable
private fun TwoPanes(parts: DialerParts) {
    Row(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BoxWithConstraints(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                // Where the room above the call button is short (a low window, the soft keyboard
                // open), the reminders give way first, so that the number keeps a line, then the
                // gap above the top row; should even the top row not fit, it scrolls. The call
                // button stays in view.
                val numberRoom = with(LocalDensity.current) { (SMALLEST_NUMBER_SIZE * 1.2f).sp.toDp() } + NumberGap * 2
                val topGap = if (maxHeight >= 8.dp + TopRowHeight) 8.dp else 0.dp
                val chipsFit = maxHeight >= topGap + TopRowHeight + ChipsHeight + numberRoom
                Column(
                    Modifier
                        .fillMaxWidth()
                        .then(
                            if (maxHeight < TopRowHeight) {
                                Modifier.verticalScroll(rememberScrollState()).height(TopRowHeight)
                            } else {
                                Modifier.fillMaxHeight()
                            },
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.height(topGap))
                    parts.top()
                    if (chipsFit) parts.chips()
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(vertical = NumberGap),
                        contentAlignment = Alignment.Center,
                    ) {
                        parts.display()
                    }
                }
            }
            parts.callRow()
            Spacer(Modifier.height(12.dp))
        }
        parts.input(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(vertical = 12.dp),
            true,
        )
    }
}

@Composable
private fun TopRow(
    active: SipAccount?,
    keyboardMode: Boolean,
    onFeatures: () -> Unit,
    onAccount: () -> Unit,
    onToggleInput: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onFeatures) {
            Icon(Icons.Rounded.SupportAgent, contentDescription = "话务功能")
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            AccountChip(active, canAdd = !LocalConfigLocked.current, onClick = onAccount)
        }
        IconButton(onClick = onToggleInput) {
            AnimatedContent(keyboardMode, label = "inputMode") { keyboard ->
                Icon(
                    if (keyboard) Icons.Rounded.Dialpad else Icons.Rounded.Keyboard,
                    contentDescription = if (keyboard) "切换到拨号盘" else "切换到键盘输入",
                )
            }
        }
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
                Text(if (canAdd) "添加 SIP 账号" else "暂无 SIP 账号", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.width(8.dp))
            } else {
                StatusDot(account.state)
                Spacer(Modifier.width(8.dp))
                FadingText(
                    account.name.ifBlank { account.identity },
                    style = MaterialTheme.typography.labelLarge,
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
                    label = { Text("免打扰已开启", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = {
                        Icon(Icons.Rounded.DoNotDisturbOn, contentDescription = null, tint = Brand.HangUpRed, modifier = Modifier.size(18.dp))
                    },
                )
            }
            if (voicemail > 0) {
                AssistChip(
                    onClick = onVoicemail,
                    modifier = Modifier.alpha(if (voicemailConfigured) 1f else 0.38f),
                    label = { Text("$voicemail 条新留言", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { Icon(Icons.Rounded.Voicemail, contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
            }
        }
    }
}

/** The number display does not go below this font size (sp); a room too low even for that shows nothing. */
private const val SMALLEST_NUMBER_SIZE = 18f

/** The number typed so far, in as many lines (two at most) as the room holds; none where not even one fits. */
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
    BoxWithConstraints(contentAlignment = Alignment.Center) {
        val density = LocalDensity.current
        if (number.isEmpty()) {
            val style = MaterialTheme.typography.titleMedium
            if (maxHeight >= with(density) { style.lineHeight.toDp() }) {
                Text(
                    "输入号码或 SIP 地址",
                    style = style,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
        } else {
            // In a low room the figures get smaller rather than cut off, down to a size still easy
            // to read (looked up step by step: large fonts do not scale in proportion).
            fun line(size: Float) = with(density) { (size * 1.2f).sp.toDp() }
            var size = fontSize
            while (size - 1f >= SMALLEST_NUMBER_SIZE && line(size) > maxHeight) size -= 1f
            val lines = (maxHeight / line(size)).toInt().coerceAtMost(2)
            if (lines > 0) {
                FadingText(
                    text = number,
                    style = LocalTextStyle.current.copy(fontSize = size.sp, lineHeight = (size * 1.2f).sp),
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    maxLines = lines,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/**
 * The dial pad, or the field for a number or SIP address typed on the soft keyboard.
 *
 * @param compact beside the other controls: the keys keep together and the field sits in the middle
 */
@Composable
private fun InputArea(
    keyboardMode: Boolean,
    compact: Boolean,
    number: String,
    onNumber: (String) -> Unit,
    onCall: () -> Unit,
    onDigit: (Char) -> Unit,
    onLongZero: () -> Unit,
    modifier: Modifier,
) {
    AnimatedContent(
        targetState = keyboardMode,
        modifier = modifier,
        transitionSpec = {
            (fadeIn(tween(220)) + slideInVertically(tween(260)) { it / 6 }) togetherWith fadeOut(tween(140))
        },
        label = "dialInput",
    ) { keyboard ->
        if (keyboard) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(top = if (compact) 0.dp else 8.dp),
                contentAlignment = if (compact) Alignment.Center else Alignment.TopCenter,
            ) {
                AddressInput(number, onChange = onNumber, onCall = onCall)
            }
        } else {
            DialPad(onDigit = onDigit, onLongZero = onLongZero, modifier = Modifier.fillMaxSize(), compact = compact)
        }
    }
}

@Composable
private fun AddressInput(value: String, onChange: (String) -> Unit, onCall: () -> Unit) {
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
