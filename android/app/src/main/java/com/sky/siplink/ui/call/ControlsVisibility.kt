package com.sky.siplink.ui.call

import android.os.SystemClock
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay

/**
 * Whether the controls of a video call are on screen. During a connected video call they go by
 * themselves after [AUTO_HIDE_MS] without a touch, so that the picture is seen whole; a tap on
 * the picture brings them back, and a tap while they are there takes them away.
 *
 * They stay while something on screen needs them ([KeepControlsShown]: an open menu), and while
 * the call screen says so itself (a sheet, the keypad, a notice, video that is not flowing yet,
 * a screen reader: see [canHide]).
 */
@Stable
internal class ControlsVisibility {
    var hidden by mutableStateOf(false)

    /** How many things on screen need the controls right now. */
    var holds by mutableIntStateOf(0)

    /** SystemClock.uptimeMillis() of the last touch: the time without one starts again from there. */
    var touchedAt by mutableLongStateOf(0L)

    /** Whether the controls may go at all: false brings them back. */
    var canHide by mutableStateOf(false)

    /** A tap on the picture: the controls come or go. */
    fun toggle() {
        hidden = !hidden && canHide && holds == 0
    }
}

internal val LocalControlsVisibility = staticCompositionLocalOf<ControlsVisibility?> { null }

/** While this is part of the screen, the controls of a video call stay on it. */
@Composable
internal fun KeepControlsShown() {
    val controls = LocalControlsVisibility.current ?: return
    DisposableEffect(controls) {
        controls.holds++
        controls.hidden = false
        onDispose { controls.holds-- }
    }
}

/**
 * Hides the controls once the time without a touch has run out, and shows them whenever they
 * may not be hidden.
 */
@Composable
internal fun AutoHideControls(controls: ControlsVisibility, canHide: Boolean) {
    SideEffect { controls.canHide = canHide }
    // The time a user has asked the system to leave controls on screen, where that is longer.
    val timeout = LocalAccessibilityManager.current?.calculateRecommendedTimeoutMillis(
        AUTO_HIDE_MS,
        containsIcons = true,
        containsText = true,
        containsControls = true,
    ) ?: AUTO_HIDE_MS
    val allowed = canHide && controls.holds == 0
    LaunchedEffect(allowed, controls.hidden, controls.touchedAt, timeout) {
        if (!allowed) {
            controls.hidden = false
        } else if (!controls.hidden) {
            delay(timeout)
            controls.hidden = true
        }
    }
}

/**
 * Every touch restarts the time without one; a tap that nothing else took (a tap on the picture,
 * not on a button) toggles the controls.
 */
internal fun Modifier.pictureTaps(controls: ControlsVisibility, enabled: Boolean): Modifier =
    if (!enabled) {
        this
    } else {
        pointerInput(controls) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                controls.touchedAt = SystemClock.uptimeMillis()
                // Null when a button took the tap or the finger moved away: then it was no tap on the picture.
                if (waitForUpOrCancellation() != null) controls.toggle()
            }
        }
    }

/** Whether a screen reader explores the screen by touch (TalkBack and the like). */
@Composable
internal fun touchExplorationOn(): Boolean {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(AccessibilityManager::class.java) }
    var on by remember(manager) { mutableStateOf(manager.isTouchExplorationEnabled) }
    DisposableEffect(manager) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { on = it }
        manager.addTouchExplorationStateChangeListener(listener)
        onDispose { manager.removeTouchExplorationStateChangeListener(listener) }
    }
    return on
}

/** How long the controls of a video call stay without a touch. */
private const val AUTO_HIDE_MS = 4_000L
