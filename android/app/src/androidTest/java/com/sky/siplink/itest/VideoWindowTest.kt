package com.sky.siplink.itest

import android.Manifest
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Rect
import android.view.TextureView
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.sky.siplink.core.model.CallPhase
import com.sky.siplink.core.model.DockSide
import com.sky.siplink.openCallScreen
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.linphone.mediastream.video.capture.CaptureTextureView
import kotlin.math.abs

/**
 * The buttons of a video call in one row at the top, and the small picture under it: it stands
 * at a side edge, follows the controls up and down at the top and the bottom and not in the
 * middle, is dragged and flung to the other side, and a tap on it swaps the two pictures, as
 * often as one likes, without one of them going black.
 */
class VideoWindowTest : ScreenTestBase("video-window") {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @After
    fun normal() {
        runCatching { fontScale(1f) }
        runCatching { turnCallScreen(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) }
    }

    /** A video call to the echo of the PBX, which sends the camera back: both pictures flow. */
    private fun videoCall() {
        instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName, Manifest.permission.CAMERA)
        settings { it.copy(previewSide = DockSide.RIGHT, previewPlace = 0f) }
        showApp()
        register()
        ok(onMain { engine.calls.dial("2000", null, video = true) })
        awaitCall("both pictures") { it.phase == CallPhase.CONNECTED && it.video?.sending == true && it.video.received != null }
        instrumentation.targetContext.openCallScreen()
        callScreen()
        turnCallScreen(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
        // Upright pictures both ways, once the camera knows how the screen stands: until then the
        // small window has the shape of a camera on its side.
        awaitCall("both pictures upright") { call ->
            val video = call.video
            video?.sent?.let { it.height > it.width } == true && video.received?.let { it.height > it.width } == true
        }
        showControls()
    }

    private fun bounds(node: AccessibilityNodeInfo): Rect = Rect().also(node::getBoundsInScreen)

    /** The small picture; it says whose picture it shows. */
    private fun window(): AccessibilityNodeInfo = shown(OWN).firstOrNull() ?: shown(THEIRS).firstOrNull() ?: see(OWN)

    private fun windowBounds(): Rect = bounds(window())

    /*
     * What is checked together is read in one look at the screen ([nodes]), right after the
     * controls came: they go four seconds after the last touch, and a look at this screen during a
     * video call takes a few hundred milliseconds. Looks one after another took four seconds.
     */

    private fun windowIn(look: List<AccessibilityNodeInfo>): Rect =
        bounds(look.firstOrNull { says(it, OWN) } ?: look.firstOrNull { says(it, THEIRS) } ?: throw AssertionError("No small picture: ${screenText()}"))

    /** What takes the tap on [label] in [look]. */
    private fun buttonIn(look: List<AccessibilityNodeInfo>, label: String): Rect {
        val node = look.firstOrNull { says(it, label) } ?: throw AssertionError("\"$label\" is not on the screen: ${screenText()}")
        return bounds(chain(node).firstOrNull { it.isClickable } ?: throw AssertionError("$label takes no tap"))
    }

    private fun assertNear(what: String, expected: Number, actual: Number, tolerance: Int = px(2)) =
        assertTrue("$what: $actual, not $expected", abs(expected.toFloat() - actual.toFloat()) <= tolerance)

    private fun controlsShown(): Boolean = shown(HANG_UP).isNotEmpty()

    /** The middle of the screen: on the picture, beside the controls. */
    private fun middle(): Pair<Float, Float> = callScreen().resources.displayMetrics.let { it.widthPixels * 0.5f to it.heightPixels * 0.5f }

    /**
     * The controls on the screen, with nearly the whole of their time ahead: they go four seconds
     * after the last touch. A tap on the picture brings them when they are not there; when they
     * are, a touch that is no tap (it would take them away) starts their time again. Whether they
     * stayed is seen once the small picture, which moves with them, stands still: a touch in the
     * moment they went by themselves does not bring them back. Nothing here waits for the whole
     * screen to rest: the call's time changes every second, and such a wait can take four seconds.
     */
    private fun showControls() {
        repeat(SHOW_TRIES) {
            val (x, y) = middle()
            if (controlsShown()) touchAt(x, y) else tapAt(x, y, settled = false)
            windowAtRest()
            if (controlsShown()) return
        }
        throw AssertionError("The controls do not stay: ${screenText()}")
    }

    /**
     * Until the small picture stands still (it moves with the controls); with the camera off there
     * is none, and two looks find the same. Quicker than a look at the whole screen, which has four
     * seconds before the controls go again.
     */
    private fun windowAtRest() {
        var before: Rect? = Rect()
        await("the small picture to stand still") {
            Thread.sleep(LOOK_INTERVAL_MS)
            val now = (shown(OWN).firstOrNull() ?: shown(THEIRS).firstOrNull())?.let(::bounds)
            (now == before).also { before = now }
        }
    }

    private fun awaitControlsGone() {
        assertTrue("The controls did not go", settles(HIDE_TIMEOUT_MS) { !controlsShown() })
        settle()
    }

    /** The system bars and the cut-out, as the window has them. */
    private fun safe(): android.graphics.Insets {
        val screen = callScreen()
        return onMain { screen.window.decorView.rootWindowInsets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()) }
    }

    private fun px(dp: Int): Int = (dp * callScreen().resources.displayMetrics.density).toInt()

    /** One of the custom actions a screen reader offers on the small picture. */
    private fun readerAction(label: String) {
        val action = window().actionList.firstOrNull { it.label == label }
            ?: throw AssertionError("No action \"$label\" on the small picture: ${window().actionList.map { it.label }}")
        assertTrue(window().performAction(action.id))
        settle()
    }

    /** As the system font size does, without touching the settings of the phone: for the call screen alone. */
    private fun fontScale(scale: Float) {
        val screen = callScreen()
        onMain {
            val config = Configuration(screen.resources.configuration).apply { fontScale = scale }
            @Suppress("DEPRECATION")
            screen.resources.updateConfiguration(config, screen.resources.displayMetrics)
            screen.findViewById<ViewGroup>(android.R.id.content).getChildAt(0).dispatchConfigurationChanged(config)
        }
        settle()
    }

    /** The buttons of the top row in [look]: in one line, in this order, the room button first. */
    private fun assertTopRow(how: String, look: List<AccessibilityNodeInfo> = nodes()): Rect {
        val labels = listOf("会议室", "开始录音", "切换摄像头", "关闭摄像头")
        val rects = labels.map { label -> buttonIn(look, label) }
        for ((label, rect) in labels.zip(rects)) {
            assertTrue("$label is not in the row ($how): $rect, ${rects[0]}", abs(rect.centerY() - rects[0].centerY()) <= px(4))
        }
        rects.zipWithNext().forEachIndexed { index, (a, b) -> assertTrue("${labels[index]} is not before ${labels[index + 1]} ($how)", a.right <= b.left) }
        val screen = callScreen().resources.displayMetrics
        assertTrue("The row runs off the screen ($how)", rects.last().right <= screen.widthPixels)
        // The buttons are 40 dp high; what takes a touch, 48 dp.
        return Rect(rects[0].left, rects.last().centerY() - px(20), rects.last().right, rects.last().centerY() + px(20))
    }

    @Test
    fun theButtonsStandInOneRowAboveTheSmallPicture() {
        videoCall()
        showControls()
        val upright = nodes()
        val row = assertTopRow("upright", upright)
        assertTrue("The small picture is not under the row", windowIn(upright).top >= row.bottom)
        picture("portrait-shown")
        showControls()
        tap("关闭摄像头")
        await("the own picture to go") { shown(OWN).isEmpty() && shown("打开摄像头").isNotEmpty() }
        settle()
        picture("portrait-camera-off")
        showControls()
        tap("打开摄像头")
        await("the own picture to come back") { shown(OWN).isNotEmpty() }
        settle()

        fontScale(LARGEST_FONT)
        showControls()
        assertTopRow("largest font")
        picture("portrait-largest-font")
        fontScale(1f)

        turnCallScreen(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
        await("the call screen to be turned") { callScreen().resources.displayMetrics.let { it.widthPixels > it.heightPixels } }
        // The own picture takes the shape of a camera on its side once the camera knows how the
        // screen stands (a second or two), and the small window with it.
        awaitCall("the own picture on its side") { call -> call.video?.sent?.let { it.width > it.height } == true }
        showControls()
        val wide = nodes()
        val wideRow = assertTopRow("on its side", wide)
        val small = windowIn(wide)
        assertTrue("The small picture is not under the row on its side", small.top >= wideRow.bottom)
        val hangUp = buttonIn(wide, HANG_UP)
        assertFalse("The small picture lies over the controls", Rect.intersects(small, hangUp))
        picture("landscape-shown")
        fontScale(LARGEST_FONT)
        showControls()
        assertTopRow("on its side, largest font")
        picture("landscape-largest-font")
    }

    @Test
    fun theSmallPictureFollowsTheControlsAtTheTopAndTheBottomButNotInTheMiddle() {
        videoCall()
        val safe = safe()
        val screen = callScreen().resources.displayMetrics
        // At the top: under the row while the controls are shown, under the status bar without them.
        showControls()
        val top = nodes()
        val row = assertTopRow("upright", top)
        val topShown = windowIn(top)
        picture("top-shown")
        assertNear("Under the row", row.bottom + px(MARGIN_DP), topShown.top)
        awaitControlsGone()
        val topHidden = windowBounds()
        picture("top-hidden")
        assertNear("Under the status bar", safe.top + px(MARGIN_DP), topHidden.top)

        // In the middle it stays where it is.
        readerAction("下移")
        val middleHidden = windowBounds()
        showControls()
        val middleShown = windowBounds()
        picture("middle")
        assertNear("The middle moved", middleHidden.top, middleShown.top)

        // At the bottom: over the navigation bar without the controls, over the controls with them.
        awaitControlsGone()
        readerAction("下移")
        val bottomHidden = windowBounds()
        picture("bottom-hidden")
        assertNear("Over the navigation bar", screen.heightPixels - safe.bottom - px(MARGIN_DP), bottomHidden.bottom)
        showControls()
        val bottom = nodes()
        val bottomShown = windowIn(bottom)
        val controlsTop = bottom.filter { node -> listOf("静音", "键盘", "扬声器", "保留").any { says(node, it) } }.minOf { bounds(it).top }
        picture("bottom-shown")
        assertTrue("It lies over the controls: $bottomShown, controls from $controlsTop", bottomShown.bottom <= controlsTop)
        assertTrue("It did not go up with the controls", bottomShown.top < bottomHidden.top - px(40))

        // The other side, and back up, for a screen reader.
        readerAction("移到左侧")
        assertNear("At the left edge", safe.left + px(MARGIN_DP), windowBounds().left)
        picture("left")
        readerAction("上移")
        readerAction("上移")
        showControls()
        assertNear("Back at the top", topShown.top, windowBounds().top)
        assertEquals(DockSide.LEFT to 0f, onMain { engine.settings.current.let { it.previewSide to it.previewPlace } })
    }

    @Test
    fun aDragTakesItToASideEdgeAndATapSwapsThePictures() {
        videoCall()
        val screen = callScreen().resources.displayMetrics
        val start = windowBounds()
        // Let go a little left of the middle: it goes to the left edge, at the height it was let go
        // (above the controls).
        swipe(start.exactCenterX(), start.exactCenterY(), screen.widthPixels * 0.45f, screen.heightPixels * LET_GO_AT)
        // At rest at its edge, well within the controls' time without a touch.
        windowAtRest()
        assertTrue("A drag took the controls away: ${screenText()}", controlsShown())
        val left = windowBounds()
        picture("dragged-left")
        assertNear("At the left edge", safe().left + px(MARGIN_DP), left.left)
        // The picture follows the finger once it has moved beyond the touch slop.
        val slop = ViewConfiguration.get(callScreen()).scaledTouchSlop
        assertNear("Where it was let go", screen.heightPixels * LET_GO_AT, left.exactCenterY(), slop + px(4))
        assertTrue("A drag swapped the pictures", shown(OWN).isNotEmpty())
        // Flung towards the right, let go still in the left half: it goes right.
        flick(left.exactCenterX(), left.exactCenterY(), left.exactCenterX() + px(FLICK_DP), left.exactCenterY())
        windowAtRest()
        assertNear("Flung to the right edge", screen.widthPixels - safe().right - px(MARGIN_DP), windowBounds().right)

        // A tap swaps the pictures and leaves the controls as they are.
        assertTrue(window().actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK && it.label == "交换画面" })
        showControls()
        windowBounds().let { tapAt(it.exactCenterX(), it.exactCenterY(), settled = false) }
        see(THEIRS)
        windowAtRest()
        assertTrue("The tap took the controls away", controlsShown())
        assertFalse("The fill key is of no use on the own picture", shown("铺满屏幕").isNotEmpty() || shown("显示完整画面").isNotEmpty())
        picture("swapped")
        windowBounds().let { tapAt(it.exactCenterX(), it.exactCenterY()) }
        see(OWN)

        // Quick swaps: both pictures still run, in either arrangement.
        repeat(QUICK_SWAPS) {
            val at = windowBounds()
            tapAt(at.exactCenterX(), at.exactCenterY())
        }
        see(OWN)
        assertBothPicturesRun("after $QUICK_SWAPS swaps", ownLarge = false)
        picture("after-quick-swaps")
        windowBounds().let { tapAt(it.exactCenterX(), it.exactCenterY()) }
        see(THEIRS)
        assertBothPicturesRun("after one more", ownLarge = true)

        // The camera off: back to normal, and no swap without the own picture.
        showControls()
        tap("关闭摄像头")
        await("the own picture to go") { shown(OWN).isEmpty() && shown(THEIRS).isEmpty() }
        showControls()
        tap("打开摄像头")
        see(OWN)
    }



    /**
     * Until pictures of the other side are decoded again; how long that took. The SIP core counts
     * the pictures it decoded in the last second (none for longer than that: 0 per second).
     *
     * A picture that lost a packet on the way (the test phone's WLAN loses up to a few in a
     * hundred) stands still until the next key frame, swapped or not. The core asks for one at
     * once and every five seconds (SIP INFO picture_fast_update), but the echo of the test PBX,
     * Asterisk's Echo(), passes on only the first such request of a call: the key frame then comes
     * when the own camera sends one by itself, every 20 seconds (i-frame-interval=20), and one of
     * those can be lost too. (The core's notice of the next decoded picture is no way to wait:
     * asking for it makes the core forget the picture's size until then, and with it the call
     * screen the swap.)
     */
    private fun awaitTheirPicture(how: String): Long {
        val started = SystemClock.uptimeMillis()
        val running = settles(KEY_FRAME_WAIT_MS) { onMain { engine.core.calls.first().currentParams.receivedFramerate } > 0f }
        assertTrue("No picture of the other side $how", running)
        return SystemClock.uptimeMillis() - started
    }

    /**
     * The other side's picture goes on (a new one is decoded), both views of the pictures are on
     * the screen with their surfaces, the large one the screen's width, and the own camera sends.
     * (Whether a picture is light says nothing: the test phone's camera looks at a ceiling, dark
     * at night.)
     */
    private fun assertBothPicturesRun(how: String, ownLarge: Boolean) {
        Log.i(TAG, "A new picture of the other side $how after ${awaitTheirPicture(how)} ms")
        val screen = callScreen()
        val views = onMain {
            fun all(view: View): List<TextureView> = when (view) {
                is TextureView -> listOf(view)
                is ViewGroup -> (0 until view.childCount).flatMap { all(view.getChildAt(it)) }
                else -> emptyList()
            }
            all(screen.window.decorView).map { Triple(it is CaptureTextureView, it.isAvailable && it.isShown, it.width) }
        }
        assertEquals("Views of pictures $how: $views", 2, views.size)
        assertTrue("A view has no surface $how: $views", views.all { it.second })
        assertEquals("The wrong picture is large $how: $views", ownLarge, views.maxBy { it.third }.first)
        val sent = onMain { engine.core.calls.first().currentParams.sentFramerate }
        assertTrue("The own camera sends nothing $how: $sent", sent > 0f)
    }

    private companion object {
        const val OWN = "自己的画面"
        const val THEIRS = "对方的画面"
        const val HANG_UP = "挂断"
        const val MARGIN_DP = 12
        const val FLICK_DP = 80
        const val LET_GO_AT = 0.35f

        /** The largest font size the system offers. */
        const val LARGEST_FONT = 2f

        /** Four seconds without a touch, and some for the fading and the look at the screen. */
        const val HIDE_TIMEOUT_MS = 9_000L
        const val QUICK_SWAPS = 20

        /** Tries at bringing the controls, should one meet the moment they go by themselves. */
        const val SHOW_TRIES = 3

        /** The own camera's key frames come every 20 seconds: two of them, should one be lost, and some more. */
        const val KEY_FRAME_WAIT_MS = 45_000L

        const val TAG = "SipLinkVideoWindowTest"
    }
}
