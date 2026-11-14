@file:OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)

package com.sky.siplink.ui.dialer

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Dialpad
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
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
import com.sky.siplink.core.model.RegState
import com.sky.siplink.core.model.SipAccount
import com.sky.siplink.engine
import com.sky.siplink.ui.components.LocalMessenger
import com.sky.siplink.ui.components.StatusDot
import com.sky.siplink.ui.components.regStateLabel
import com.sky.siplink.ui.components.rememberCallLauncher
import com.sky.siplink.ui.theme.Brand

private val dialKeys = listOf(
    '1' to "", '2' to "ABC", '3' to "DEF",
    '4' to "GHI", '5' to "JKL", '6' to "MNO",
    '7' to "PQRS", '8' to "TUV", '9' to "WXYZ",
    '*' to "", '0' to "+", '#' to "",
)

@Composable
fun DialerScreen(onAddAccount: () -> Unit) {
    val context = LocalContext.current
    val accounts by context.engine.accounts.accounts.collectAsStateWithLifecycle()
    val launcher = rememberCallLauncher()
    val messenger = LocalMessenger.current
    val haptics = LocalHapticFeedback.current

    var number by rememberSaveable { mutableStateOf("") }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var keyboardMode by rememberSaveable { mutableStateOf(false) }
    var pickerOpen by remember { mutableStateOf(false) }

    val selected = accounts.firstOrNull { it.id == selectedId }
    val active = selected ?: accounts.firstOrNull { it.isDefault } ?: accounts.firstOrNull()

    fun place(intercom: Boolean) {
        when {
            active == null -> onAddAccount()
            number.isBlank() -> messenger("请输入号码或 SIP 地址")
            intercom -> launcher.intercom(number, selected?.id)
            else -> launcher.call(number, selected?.id)
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
            Spacer(Modifier.width(48.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                AccountChip(active, onClick = { if (active == null) onAddAccount() else pickerOpen = true })
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
                AddressInput(number, onChange = { number = it }, onCall = { place(false) })
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
            SideAction(visible = true, label = "对讲", onClick = { place(true) }) {
                Icon(Icons.Rounded.Campaign, contentDescription = "对讲呼叫")
            }
            CallButton(enabled = number.isNotBlank() || active == null, onClick = { place(false) })
            SideAction(
                visible = number.isNotEmpty(),
                label = "删除",
                onClick = { number = number.dropLast(1) },
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    number = ""
                },
            ) {
                Icon(Icons.AutoMirrored.Rounded.Backspace, contentDescription = "删除")
            }
        }
        Spacer(Modifier.height(16.dp))
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

@Composable
private fun AccountChip(account: SipAccount?, onClick: () -> Unit) {
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
                Icon(Icons.Rounded.PersonAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("添加 SIP 账号", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.width(8.dp))
            } else {
                StatusDot(account.state)
                Spacer(Modifier.width(8.dp))
                Text(
                    account.identity,
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
            label = { Text("号码或 SIP 地址") },
            placeholder = { Text("例如 1001 或 alice@example.com") },
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { onCall() }),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "只输入号码时，会自动补全所选账号的域名。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            .shadow(if (enabled) 10.dp else 0.dp, CircleShape, spotColor = Brand.CallGreen)
            .clip(CircleShape)
            .background(Brush.linearGradient(listOf(Color(0xFF34D17F), Brand.CallGreen, Color(0xFF12A65A))))
            .clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.Call, contentDescription = "呼叫", tint = Color.White, modifier = Modifier.size(34.dp))
    }
}

@Composable
private fun SideAction(
    visible: Boolean,
    label: String,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    icon: @Composable () -> Unit,
) {
    Box(Modifier.size(width = 72.dp, height = 76.dp), contentAlignment = Alignment.Center) {
        AnimatedVisibility(visible, enter = fadeIn() + scaleIn(initialScale = 0.7f), exit = fadeOut() + scaleOut(targetScale = 0.7f)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .combinedClickable(onClick = onClick, onLongClick = onLongClick),
                    contentAlignment = Alignment.Center,
                ) {
                    icon()
                }
                Spacer(Modifier.height(4.dp))
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun AccountPickerSheet(
    accounts: List<SipAccount>,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(accounts, query) {
        accounts.filter { it.enabled && (query.isBlank() || it.identity.contains(query, true) || it.displayName.contains(query, true)) }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding()) {
            Text(
                "选择呼出账号",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            if (accounts.size > 6) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                    placeholder = { Text("搜索账号") },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    singleLine = true,
                    shape = CircleShape,
                )
            }
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                item(key = "default") {
                    PickerRow(
                        title = "跟随默认账号",
                        subtitle = accounts.firstOrNull { it.isDefault }?.identity ?: "尚未设置",
                        state = accounts.firstOrNull { it.isDefault }?.state ?: RegState.NONE,
                        selected = selectedId == null,
                        onClick = { onSelect(null) },
                    )
                }
                items(filtered, key = { it.id }) { account ->
                    PickerRow(
                        title = account.label,
                        subtitle = "${account.identity} · ${regStateLabel(account.state)}",
                        state = account.state,
                        selected = account.id == selectedId,
                        onClick = { onSelect(account.id) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun PickerRow(
    title: String,
    subtitle: String,
    state: RegState,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(state, size = 12.dp)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (selected) {
            Icon(Icons.Rounded.Check, contentDescription = "已选择", tint = MaterialTheme.colorScheme.primary)
        }
    }
}
