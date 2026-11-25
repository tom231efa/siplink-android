package com.sky.siplink.itest

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Choreographer
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.sky.siplink.MainActivity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Base of the tests that go through the app's screens by their accessibility tree, the way a
 * screen reader does: they find what a screen says, tap it, type into it and look at where it is.
 *
 * Pictures of the screens are saved in the cache directory of the app, under [pictures].
 */
abstract class ScreenTestBase(private val pictures: String) : PbxTestBase() {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation

    // ------------------------------------------------------------------ the screen, as nodes

    /**
     * What is on the screen now. The nodes the system remembers are dropped first: it was seen
     * to go on handing out a screen as it was before a notice left it.
     */
    protected fun nodes(): List<AccessibilityNodeInfo> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) automation.clearCache()
        val all = ArrayList<AccessibilityNodeInfo>()
        fun walk(node: AccessibilityNodeInfo) {
            all += node
            for (index in 0 until node.childCount) node.getChild(index)?.let(::walk)
        }
        automation.rootInActiveWindow?.let(::walk)
        return all
    }

    protected fun says(node: AccessibilityNodeInfo, text: String): Boolean =
        node.text?.toString() == text || node.contentDescription?.toString() == text

    protected fun shown(text: String): List<AccessibilityNodeInfo> = nodes().filter { says(it, text) }

    /** Everything the screen says, for the message of a failure. */
    protected fun screenText(): String = nodes().mapNotNull { it.text ?: it.contentDescription }.joinToString(" | ")

    /**
     * Lets a tap or a scroll show: waits until two looks at the screen find everything where it
     * was. (The system's own "wait for idle" returns in the middle of a scroll, and what is
     * caught passing by takes no tap.)
     */
    protected fun settle() {
        val bounds = Rect()
        val deadline = System.currentTimeMillis() + SETTLE_TIMEOUT_MS
        var before = ""
        var unchanged = 0
        while (unchanged < 2 && System.currentTimeMillis() < deadline) {
            Thread.sleep(LOOK_INTERVAL_MS)
            val now = nodes().joinToString("|") { node ->
                node.getBoundsInScreen(bounds)
                "${node.text}/${node.contentDescription}/${bounds.toShortString()}"
            }
            if (now == before) unchanged++ else unchanged = 0
            before = now
        }
    }

    /** Scrolls what is on screen one step; false when nothing moved. */
    protected fun scroll(forward: Boolean): Boolean {
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        val moved = nodes().filter { it.isScrollable }.map { it.performAction(action) }.any { it }
        if (moved) settle()
        return moved
    }

    /** The node that says [text], looked for through the whole length of the screen; null when there is none. */
    protected fun find(text: String): AccessibilityNodeInfo? {
        shown(text).firstOrNull()?.let { return it }
        // Down to the end, then back up: one of the two passes what is off the screen at the moment.
        for (forward in listOf(true, false)) {
            var steps = 0
            while (steps++ < MAX_SCROLLS && scroll(forward)) shown(text).firstOrNull()?.let { return it }
        }
        return null
    }

    protected fun see(text: String): AccessibilityNodeInfo {
        var node: AccessibilityNodeInfo? = null
        if (!settles(SCREEN_TIMEOUT_MS) { find(text)?.also { node = it } != null }) {
            throw AssertionError("\"$text\" is not on the screen, which says: ${screenText()}")
        }
        return node!!
    }

    protected fun absent(vararg texts: String) = texts.forEach { assertTrue("\"$it\" is on the screen", find(it) == null) }

    /** The node itself and what it is inside of, innermost first. */
    protected fun chain(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = generateSequence(node) { it.parent }.toList()

    /** Whether the control that says [text] takes input: no part of it is disabled, and some part acts on a tap or on typing. */
    protected fun takesInput(text: String): Boolean {
        val chain = chain(see(text))
        return chain.all { it.isEnabled } && chain.any { it.isClickable || it.isEditable }
    }

    protected fun locked(vararg texts: String) = texts.forEach { assertFalse("\"$it\" takes input", takesInput(it)) }

    protected fun usable(vararg texts: String) = texts.forEach { assertTrue("\"$it\" takes no input", takesInput(it)) }

    /** Performs [action] on the control that says [text]. A screen that is still moving may not take it at once. */
    protected fun act(text: String, action: Int) {
        val done = settles(SCREEN_TIMEOUT_MS) {
            val target = chain(see(text)).firstOrNull { node -> node.isEnabled && node.actionList.any { it.id == action } }
            target?.performAction(action) == true
        }
        assertTrue("\"$text\" does not take the action. The screen says: ${screenText()}", done)
        settle()
    }

    protected fun tap(text: String) = act(text, AccessibilityNodeInfo.ACTION_CLICK)

    protected fun back() {
        automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        settle()
    }

    /**
     * One touch of a finger that went down at [downTime]: [action] is one of the MotionEvent.ACTION_ values.
     *
     * @param awaited the app has taken the touch when this returns; without, the next touch can reach
     *   the app together with this one, the way the touches of a real finger do
     * @param eventTime when the touch screen saw it
     */
    private fun touch(downTime: Long, action: Int, x: Float, y: Float, awaited: Boolean = true, eventTime: Long = SystemClock.uptimeMillis()) {
        val event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        assertTrue("The touch was not taken", automation.injectInputEvent(event, awaited))
        event.recycle()
    }

    /**
     * A finger that goes down at the first point, moves to the second in a straight line and is
     * lifted there, after resting for [restMs]. Without a rest it is lifted while it still moves:
     * its last movement and the lifting then reach the app in one go.
     */
    private fun move(fromX: Float, fromY: Float, toX: Float, toY: Float, holdMs: Long, restMs: Long) {
        val downTime = SystemClock.uptimeMillis()
        val inMotion = restMs == 0L
        touch(downTime, MotionEvent.ACTION_DOWN, fromX, fromY)
        Thread.sleep(holdMs)
        for (step in 1..DRAG_STEPS) {
            val fraction = step.toFloat() / DRAG_STEPS
            val last = step == DRAG_STEPS
            touch(
                downTime, MotionEvent.ACTION_MOVE, fromX + (toX - fromX) * fraction, fromY + (toY - fromY) * fraction,
                awaited = !(last && inMotion),
            )
            if (!(last && inMotion)) Thread.sleep(DRAG_STEP_MS)
        }
        Thread.sleep(restMs)
        touch(downTime, MotionEvent.ACTION_UP, toX, toY, awaited = !inMotion)
    }

    /**
     * Holds a finger on [from] until it counts as a long press, moves it onto [to] and lifts it
     * there: the way an item is dragged to another place.
     */
    protected fun drag(from: AccessibilityNodeInfo, to: AccessibilityNodeInfo) {
        val start = Rect().also(from::getBoundsInScreen)
        val end = Rect().also(to::getBoundsInScreen)
        move(
            start.exactCenterX(), start.exactCenterY(), end.exactCenterX(), end.exactCenterY(),
            holdMs = ViewConfiguration.getLongPressTimeout() * 2L, restMs = DRAG_HOLD_MS,
        )
        settle()
    }

    /**
     * A finger that slides from one point of the screen to another without resting first.
     *
     * @param liftInMotion the finger is lifted while it still moves, as most are; without, it
     *   rests a moment at the end
     */
    protected fun swipe(fromX: Float, fromY: Float, toX: Float, toY: Float, liftInMotion: Boolean = false) =
        move(fromX, fromY, toX, toY, holdMs = DRAG_STEP_MS, restMs = if (liftInMotion) 0L else SWIPE_REST_MS)

    /**
     * A short quick flick of a finger from one point towards another, lifted while it still moves.
     * As a real finger's: the touch screen reports where it is at a steady rate, stamped with when
     * it saw it, whatever the app is busy with. (Touches the app is waited for one by one come as
     * far apart as it takes to handle each, and the flick looks slower than it was.)
     */
    protected fun flick(fromX: Float, fromY: Float, toX: Float, toY: Float) {
        val downTime = SystemClock.uptimeMillis()
        touch(downTime, MotionEvent.ACTION_DOWN, fromX, fromY)
        val start = SystemClock.uptimeMillis()
        for (step in 1..FLICK_STEPS) {
            val at = start + step * FLICK_STEP_MS
            Thread.sleep((at - SystemClock.uptimeMillis()).coerceAtLeast(0L))
            val fraction = step.toFloat() / FLICK_STEPS
            touch(downTime, MotionEvent.ACTION_MOVE, fromX + (toX - fromX) * fraction, fromY + (toY - fromY) * fraction, awaited = false, eventTime = at)
        }
        // Lifted with the next report of the touch screen, where the finger was last.
        touch(downTime, MotionEvent.ACTION_UP, toX, toY, awaited = false, eventTime = start + (FLICK_STEPS + 1) * FLICK_STEP_MS)
        settle()
    }

    /**
     * A finger that touches the screen at one point and is lifted there, as a tap is.
     *
     * @param settled returns once the whole screen is at rest; on a screen whose content changes
     *   every second (a call's time) that can take as long as [SETTLE_TIMEOUT_MS]
     */
    protected fun tapAt(x: Float, y: Float, settled: Boolean = true) {
        val downTime = SystemClock.uptimeMillis()
        touch(downTime, MotionEvent.ACTION_DOWN, x, y)
        Thread.sleep(TAP_MS)
        touch(downTime, MotionEvent.ACTION_UP, x, y)
        if (settled) settle()
    }

    /**
     * A finger that touches the screen at one point and is taken away without a tap: the system
     * cancels the touch (as it does when a gesture of its own takes the finger over).
     */
    protected fun touchAt(x: Float, y: Float) {
        val downTime = SystemClock.uptimeMillis()
        touch(downTime, MotionEvent.ACTION_DOWN, x, y)
        Thread.sleep(TAP_MS)
        touch(downTime, MotionEvent.ACTION_CANCEL, x, y)
    }

    /** Sets the text of the field that [field] finds; a screen that is still moving may not have it at once. */
    protected fun setText(text: String, field: () -> AccessibilityNodeInfo?) {
        val arguments = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        val done = settles(SCREEN_TIMEOUT_MS) { field()?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments) == true }
        assertTrue("Nothing takes the text. The screen says: ${screenText()}", done)
        settle()
    }

    /** Types [text] into the one field of what is in front that takes input: the field of a dialog. */
    protected fun type(text: String) = setText(text) { nodes().singleOrNull { it.isEditable && it.isEnabled } }

    /** Types [text] into the field with the label [label]. */
    protected fun typeInto(label: String, text: String) = setText(text) { chain(see(label)).firstOrNull { it.isEditable && it.isEnabled } }

    // ------------------------------------------------------------------ turning the app

    /** The activity of type [T] in front, if there is one. */
    protected inline fun <reified T : Activity> resumed(): T? = onMain {
        ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<T>().firstOrNull()
    }

    /** The app's own screen (the one of the tabs), once it is in front. */
    protected fun app(): MainActivity {
        await("the app to be in front") { resumed<MainActivity>() != null }
        return resumed<MainActivity>()!!
    }

    /** How the display of [activity] is turned: one of the Surface.ROTATION_ values. */
    protected fun rotation(activity: Activity): Int = onMain {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.display!!.rotation
        } else {
            @Suppress("DEPRECATION")
            activity.windowManager.defaultDisplay.rotation
        }
    }

    /**
     * Turns the app the way turning the phone does, without a hand on it or a change to its
     * settings. A quarter turn makes the app anew; half way round it is only laid out anew.
     *
     * @param orientation one of the ActivityInfo.SCREEN_ORIENTATION_ values
     * @return the rotation of the display then, one of the Surface.ROTATION_ values
     */
    protected fun turnApp(orientation: Int): Int {
        val before = app()
        val from = rotation(before)
        val sideways = orientation != ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        onMain { before.requestedOrientation = orientation }
        await("the app to be turned") {
            val now = resumed<MainActivity>()
            now != null && rotation(now) != from &&
                (now.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) == sideways
        }
        Thread.sleep(TURN_MS)
        settle()
        return rotation(app())
    }

    // ------------------------------------------------------------------ the keyboard of the system

    /** The top edge of the keyboard of the system on the screen, in pixels; null while it is away. */
    protected fun keyboardTop(): Int? {
        // The windows of other apps, the keyboard's among them, are only told on request.
        val info = automation.serviceInfo
        if (info.flags and AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS == 0) {
            automation.serviceInfo = info.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        }
        val bounds = Rect()
        val keyboard = automation.windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } ?: return null
        keyboard.getBoundsInScreen(bounds)
        return bounds.top
    }

    /**
     * Types [text] into the empty field that [field] finds the way a user does: a finger on the
     * field brings the keyboard of the system up, and keys type. The keyboard has to stay up and
     * the text has to arrive. ([setText] fills a field without a keyboard, and so never meets a
     * screen that the keyboard rearranges.)
     *
     * The place of the field is looked at once the screen is at rest again: what scrolls brings
     * no more than the line that is written into sight, a moment after the keys.
     *
     * @return what is wrong with the place of the field while the keyboard is up (it is out of
     *   sight or cut off, or the keyboard covers it); null when it stands whole above the keyboard,
     *   or is higher than the room there and fills it
     */
    protected fun typeWithKeyboard(text: String, field: () -> AccessibilityNodeInfo): String? {
        // As a user finds the screen: without a keyboard that the stop before left up, or that the key that keeps the phone awake brought back.
        closeKeyboard()
        val before = Rect()
        field().getBoundsInScreen(before)
        tapAt(before.exactCenterX(), before.exactCenterY())
        await("the keyboard to open") { keyboardTop() != null }
        never("the keyboard closed by itself", KEYBOARD_STAYS_MS) { keyboardTop() == null }
        typeKeys(text)
        settle()
        val keyboard = keyboardTop()
        assertTrue("The keyboard closed while \"$text\" was typed", keyboard != null)
        val now = Rect()
        holding(text)?.getBoundsInScreen(now) ?: return "the field is out of sight"
        return when {
            now.bottom > keyboard!! -> "the keyboard covers the field: the field ends at ${now.bottom}, the keyboard starts at $keyboard"
            now.height() >= before.height() -> null
            // A field of many lines may be higher than the room above the keyboard: it fills that room, and the keyboard covers the rest of it.
            before.height() > keyboard - now.top && keyboard - now.bottom <= EDGE_PX -> null
            else -> "the field is cut off: ${now.height()} of its ${before.height()} pixels are in sight"
        }
    }

    /**
     * The keys that write [text], into the empty field that has the focus: back once the field
     * holds it. Keys that do not come from a touch take the phone out of touch mode, where a
     * screen that opens gives its first field the focus, and the keyboard with it; the keys of
     * the keyboard on the screen are touches, so the phone is put back into touch mode.
     */
    protected fun typeKeys(text: String) {
        shell("input text $text")
        await("\"$text\" to be in the field") { holding(text) != null }
        instrumentation.setInTouchMode(true)
    }

    private fun holding(text: String): AccessibilityNodeInfo? = nodes().firstOrNull { it.isEditable && it.text?.toString() == text }

    /**
     * Sends the keyboard of the system away, as the back key does while it is up. The key that
     * the test runner keeps the phone awake with (run-tests.sh) brings a hidden keyboard up again
     * when it meets a field that has the focus: one that is back before the screen has come to
     * rest is sent away once more, and it may still be back a moment after this returns.
     */
    protected fun closeKeyboard() {
        repeat(KEYBOARD_CLOSES) {
            if (keyboardTop() == null) return
            back()
            if (settles(KEYBOARD_GONE_MS) { keyboardTop() == null }) return
        }
        throw AssertionError("The keyboard does not close. The screen says: ${screenText()}")
    }

    /**
     * Saves what the display shows as [name].png; [name] may hold folders ("ar/dialer"). The
     * screen is let come to rest and be drawn first ([drawn]): the accessibility tree has a
     * change before the display shows it, and a screen that just opened is still fading in.
     */
    protected fun picture(name: String) {
        settle()
        drawn()
        val bitmap = automation.takeScreenshot() ?: return
        val file = File(File(instrumentation.targetContext.cacheDir, pictures), "$name.png").apply { parentFile?.mkdirs() }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /**
     * Waits until the display shows what the app holds now: the main thread at rest, two frames
     * drawn after it, and then longer than the app's longest transition (a screen that opens, the
     * call screen fading in), which the accessibility tree does not see. A screen that moves for
     * ever (the rings of an incoming call) is drawn in its state of the moment.
     */
    private fun drawn() {
        instrumentation.waitForIdleSync()
        repeat(2) {
            val frame = CountDownLatch(1)
            instrumentation.runOnMainSync { Choreographer.getInstance().postFrameCallback { frame.countDown() } }
            frame.await(FRAME_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }
        Thread.sleep(TRANSITION_MS)
    }

    protected companion object {
        /** Longer than the longest transition of the app's screens (300 ms). */
        const val TRANSITION_MS = 500L
        const val FRAME_TIMEOUT_MS = 1_000L

        /** How far apart the looks are that have to find the screen unchanged, and how long it gets to come to rest. */
        const val LOOK_INTERVAL_MS = 120L
        const val SETTLE_TIMEOUT_MS = 4_000L
        const val SCREEN_TIMEOUT_MS = 10_000L
        const val MAX_SCROLLS = 12

        /** How a drag moves: in this many steps this far apart, and how long it rests before the finger is lifted. */
        const val DRAG_STEPS = 20
        const val DRAG_STEP_MS = 16L
        const val DRAG_HOLD_MS = 300L

        /** A flick: a few quick steps, as often as a touch screen reports a moving finger (120 times a second). */
        const val FLICK_STEPS = 5
        const val FLICK_STEP_MS = 8L

        /** A slide rests this long at its end, so that it is not taken for a flick. */
        const val SWIPE_REST_MS = 60L

        /** How long the finger of a tap stays down. */
        const val TAP_MS = 60L

        /** Longer than the animation that turns the screen. */
        const val TURN_MS = 1_000L

        /** A keyboard that a screen sends away again goes within a second; it is watched for longer. */
        const val KEYBOARD_STAYS_MS = 3_000L

        /** How long the keyboard takes at the most to be gone after the back key, and how often it is sent away. */
        const val KEYBOARD_GONE_MS = 2_000L
        const val KEYBOARD_CLOSES = 3

        /** Two edges that are the same edge, rounded apart. */
        const val EDGE_PX = 2
    }
}
