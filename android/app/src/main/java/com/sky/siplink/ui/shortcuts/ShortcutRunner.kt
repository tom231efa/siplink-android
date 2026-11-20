package com.sky.siplink.ui.shortcuts

import android.content.res.Resources
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PhoneForwarded
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Business
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Dialpad
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.DoNotDisturbOn
import androidx.compose.material.icons.rounded.Elevator
import androidx.compose.material.icons.rounded.Factory
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Garage
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.HeadsetMic
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Hotel
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.LocalHospital
import androidx.compose.material.icons.rounded.LocalShipping
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MeetingRoom
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.RoomService
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Storefront
import androidx.compose.material.icons.rounded.SupportAgent
import androidx.compose.material.icons.rounded.SwapCalls
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.Warehouse
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sky.siplink.R
import com.sky.siplink.core.SipEngine
import com.sky.siplink.core.logic.ManagedConfig
import com.sky.siplink.core.logic.ShortcutLines
import com.sky.siplink.core.logic.ShortcutRules
import com.sky.siplink.core.model.CallPhase
import com.sky.siplink.core.model.DndMode
import com.sky.siplink.core.model.Shortcut
import com.sky.siplink.core.model.ShortcutAction
import com.sky.siplink.core.model.ShortcutIcon
import com.sky.siplink.core.render
import com.sky.siplink.engine
import com.sky.siplink.ui.components.AccountPickerSheet
import com.sky.siplink.ui.components.CallLauncher
import com.sky.siplink.ui.components.rememberCallLauncher
import com.sky.siplink.ui.components.text

fun shortcutIcon(action: ShortcutAction): ImageVector = when (action) {
    ShortcutAction.SPEED_DIAL -> Icons.Rounded.Call
    ShortcutAction.BLIND_TRANSFER -> Icons.AutoMirrored.Rounded.PhoneForwarded
    ShortcutAction.ATTENDED_TRANSFER -> Icons.Rounded.SupportAgent
    ShortcutAction.DTMF -> Icons.Rounded.Dialpad
    ShortcutAction.FORWARD -> Icons.Rounded.SwapCalls
    ShortcutAction.DND -> Icons.Rounded.DoNotDisturbOn
}

/** The picture of a key: the one chosen for it, else the one of its type. */
fun shortcutIcon(shortcut: Shortcut): ImageVector = shortcut.icon?.let(::shortcutIcon) ?: shortcutIcon(shortcut.action)

fun shortcutIcon(icon: ShortcutIcon): ImageVector = when (icon) {
    ShortcutIcon.PHONE -> Icons.Rounded.Phone
    ShortcutIcon.PERSON -> Icons.Rounded.Person
    ShortcutIcon.GROUP -> Icons.Rounded.Groups
    ShortcutIcon.STAR -> Icons.Rounded.Star
    ShortcutIcon.HOME -> Icons.Rounded.Home
    ShortcutIcon.OFFICE -> Icons.Rounded.Business
    ShortcutIcon.STORE -> Icons.Rounded.Storefront
    ShortcutIcon.RECEPTION -> Icons.Rounded.RoomService
    ShortcutIcon.SUPPORT -> Icons.Rounded.HeadsetMic
    ShortcutIcon.SPEAKER -> Icons.Rounded.Campaign
    ShortcutIcon.MEETING -> Icons.Rounded.Forum
    ShortcutIcon.DOOR -> Icons.Rounded.MeetingRoom
    ShortcutIcon.LOCK -> Icons.Rounded.Lock
    ShortcutIcon.KEY -> Icons.Rounded.Key
    ShortcutIcon.GARAGE -> Icons.Rounded.Garage
    ShortcutIcon.CAR -> Icons.Rounded.DirectionsCar
    ShortcutIcon.ELEVATOR -> Icons.Rounded.Elevator
    ShortcutIcon.SECURITY -> Icons.Rounded.Security
    ShortcutIcon.CAMERA -> Icons.Rounded.Videocam
    ShortcutIcon.BELL -> Icons.Rounded.Notifications
    ShortcutIcon.ALARM -> Icons.Rounded.Warning
    ShortcutIcon.MEDICAL -> Icons.Rounded.LocalHospital
    ShortcutIcon.FIRE -> Icons.Rounded.LocalFireDepartment
    ShortcutIcon.FOOD -> Icons.Rounded.Restaurant
    ShortcutIcon.HOTEL -> Icons.Rounded.Hotel
    ShortcutIcon.WAREHOUSE -> Icons.Rounded.Warehouse
    ShortcutIcon.FACTORY -> Icons.Rounded.Factory
    ShortcutIcon.SCHOOL -> Icons.Rounded.School
    ShortcutIcon.REPAIR -> Icons.Rounded.Build
    ShortcutIcon.COMPUTER -> Icons.Rounded.Computer
    ShortcutIcon.DELIVERY -> Icons.Rounded.LocalShipping
    ShortcutIcon.LIGHT -> Icons.Rounded.Lightbulb
}

/**
 * What pressing a key led to.
 *
 * @param notice what to tell the user; null when the key speaks for itself
 * @param showCall the key acted on the call in progress, whose screen should be shown again
 */
class ShortcutOutcome(val notice: String?, val showCall: Boolean = false)

/**
 * A key waiting for its line to be chosen.
 *
 * @param lost the fixed line of the key, when that is why it has to ask: the account behind it
 *   was removed or switched off
 */
private class Asking(val shortcut: Shortcut, val lost: String?)

/**
 * Runs shortcut keys. Like on a desk phone, what a key does depends on whether a call is up:
 * a speed dial key places a call, or a second one next to the call in progress; a transfer key
 * acts on the call in progress.
 *
 * A key without a fixed line asks for one whenever it calls out; this function shows that list.
 *
 * @param accountId the account chosen on the dial pad (null: the default account), offered
 *   first in that list and used when there is nothing to choose from
 */
@Composable
fun rememberShortcutRunner(accountId: String?, onOutcome: (ShortcutOutcome) -> Unit): (Shortcut) -> Unit {
    val engine = LocalContext.current.engine
    // Texts of the outcomes are put together in callbacks: with the activity's resources, in its language.
    val resources = LocalResources.current
    val launcher = rememberCallLauncher()
    val fallback by rememberUpdatedState(accountId)
    val report by rememberUpdatedState(onOutcome)
    var asking by remember { mutableStateOf<Asking?>(null) }

    asking?.let { ask ->
        val accounts by engine.accounts.accounts.collectAsStateWithLifecycle()
        // The account of the dial pad comes first and is the one marked as chosen.
        val preferred = fallback ?: accounts.firstOrNull { it.isDefault }?.id
        AccountPickerSheet(
            accounts = accounts,
            selectedId = preferred,
            onSelect = { id ->
                asking = null
                report(press(resources, engine, launcher, ask.shortcut, id))
            },
            onDismiss = { asking = null },
            title = stringResource(R.string.shortcut_choose_line_title, ShortcutRules.title(ask.shortcut).text()),
            defaultTitle = null,
            pinnedId = preferred,
            note = ask.lost?.let { stringResource(R.string.shortcut_line_unavailable, it) },
        )
    }

    return remember(resources, engine, launcher) {
        { shortcut ->
            val phase = engine.calls.call.value?.phase
            val accounts = engine.accounts.accounts.value
            val usable = accounts.count { it.enabled }
            val fixed = ShortcutLines.resolve(shortcut.line, accounts)?.takeIf { it.enabled }
            when {
                // The key acts on the call in progress or flips a switch: no line involved.
                !ShortcutRules.placesCall(shortcut, inCall(phase), serverDnd(engine)) ->
                    report(press(resources, engine, launcher, shortcut, fallback))
                phase != null && phase != CallPhase.ENDED -> report(ShortcutOutcome(resources.getString(R.string.call_busy_already)))
                fixed != null -> report(press(resources, engine, launcher, shortcut, fixed.id))
                // The fixed line is gone or switched off: ask, rather than call out on another one unasked.
                shortcut.line.isNotEmpty() && usable > 0 -> asking = Asking(shortcut, lost = shortcut.line)
                // No fixed line: ask, unless there is nothing to choose from.
                usable > 1 -> asking = Asking(shortcut, lost = null)
                else -> report(press(resources, engine, launcher, shortcut, fallback))
            }
        }
    }
}

private fun serverDnd(engine: SipEngine): Boolean = engine.settings.current.dndMode == DndMode.SERVER

private fun inCall(phase: CallPhase?): Boolean = phase == CallPhase.CONNECTED || phase == CallPhase.HELD

/**
 * Does what the key stands for, calling out with [account] (null: the default account) if it calls.
 * What it tells is written with [resources], the activity's.
 */
private fun press(
    resources: Resources,
    engine: SipEngine,
    launcher: CallLauncher,
    shortcut: Shortcut,
    account: String?,
): ShortcutOutcome {
    val number = shortcut.number
    val calls = engine.calls
    val inCall = inCall(calls.call.value?.phase)

    /** An action on the call in progress: show the call again when it worked, else say why not. */
    fun onCall(error: String?) = ShortcutOutcome(error, showCall = error == null)

    when (shortcut.action) {
        ShortcutAction.SPEED_DIAL, ShortcutAction.ATTENDED_TRANSFER ->
            if (inCall) return onCall(calls.startConsultation(number)) else launcher.call(number, account)
        ShortcutAction.BLIND_TRANSFER ->
            if (inCall) return onCall(calls.blindTransfer(number)) else launcher.call(number, account)
        ShortcutAction.DTMF -> return calls.sendDtmfs(number)?.let(::ShortcutOutcome) ?: ShortcutOutcome(resources.getString(R.string.shortcut_dtmf_sent, number), showCall = true)
        ShortcutAction.FORWARD -> {
            val on = engine.settings.current.forwardAlways != number
            // Forwarding is part of a managed configuration: the key then has nothing to switch.
            if (!engine.updateSettings { it.copy(forwardAlways = if (on) number else "") }) return ShortcutOutcome(resources.render(ManagedConfig.NOTICE))
            return ShortcutOutcome(
                if (on) resources.getString(R.string.shortcut_forward_on, number) else resources.getString(R.string.shortcut_forward_off),
            )
        }
        ShortcutAction.DND -> {
            val on = !engine.settings.current.doNotDisturb
            launcher.doNotDisturb(on, account)
            // On the PBX, the call to its feature code speaks for itself.
            if (!serverDnd(engine)) return ShortcutOutcome(
                    resources.getString(if (on) R.string.shortcut_dnd_on else R.string.shortcut_dnd_off),
                )
        }
    }
    return ShortcutOutcome(null)
}
