package com.sky.siplink.itest

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.io.File

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
     */
    private fun touch(downTime: Long, action: Int, x: Float, y: Float, awaited: Boolean = true) {
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
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

    /** Saves what the display shows as [name].png. */
    protected fun picture(name: String) {
        val bitmap = automation.takeScreenshot() ?: return
        val directory = File(instrumentation.targetContext.cacheDir, pictures).apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    protected companion object {
        /** How far apart the looks are that have to find the screen unchanged, and how long it gets to come to rest. */
        const val LOOK_INTERVAL_MS = 120L
        const val SETTLE_TIMEOUT_MS = 4_000L
        const val SCREEN_TIMEOUT_MS = 10_000L
        const val MAX_SCROLLS = 12

        /** How a drag moves: in this many steps this far apart, and how long it rests before the finger is lifted. */
        const val DRAG_STEPS = 20
        const val DRAG_STEP_MS = 16L
        const val DRAG_HOLD_MS = 300L

        /** A slide rests this long at its end, so that it is not taken for a flick. */
        const val SWIPE_REST_MS = 60L
    }
}
