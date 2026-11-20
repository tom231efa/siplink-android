package com.sky.siplink.itest

import android.Manifest
import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Build
import android.view.Surface
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.sky.siplink.CallActivity
import com.sky.siplink.MainActivity
import com.sky.siplink.core.FeedAnswer
import com.sky.siplink.core.ReleaseFeed
import com.sky.siplink.core.model.CallPhase
import com.sky.siplink.openCallScreen
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * What the call screen opens over a call, with the phone on its side: the keypad over a voice and
 * over a video call, the transfer and shortcut sheets, the room and its sheet. Nothing is cut off,
 * the keys are big enough to hit, and nothing lies over anything else; the keypad of a call does
 * not stay over the room the call became.
 *
 * The main screens on its side, turned both ways round: the tabs stand in a rail at the side
 * instead of the bar at the bottom, clear of the camera cutout and the navigation bar, and the
 * screen beside the rail leaves no gap twice. Turning keeps the tab, the screens opened from it
 * and what was typed.
 */
class LandscapeScreensTest : RoomTestBase("landscape") {
    @After
    fun upright() {
        // Asked for upright, not left to the system: that may leave the screen on its side for the next test.
        for (activity in listOfNotNull(resumed<CallActivity>(), resumed<MainActivity>())) {
            runCatching { onMain { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT } }
        }
        onMain {
            engine.updates.feed = NO_RELEASES
            engine.updates.forget()
        }
    }

    private fun turn() {
        val screen = callScreen()
        turnCallScreen(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
        await("the call screen to be turned") { screen.resources.displayMetrics.let { it.widthPixels > it.heightPixels } }
        Thread.sleep(TURN_MS)
    }

    private fun bounds(text: String): Rect = Rect().also { see(text).getBoundsInScreen(it) }

    /** The bounds of what takes the tap on [text]: the key around its label. */
    private fun touchBounds(text: String): Rect = Rect().also { rect -> chain(see(text)).first { it.isClickable }.getBoundsInScreen(rect) }

    /**
     * The visible part of the button [label] as it is now, without scrolling to it: a button
     * partly cut off (by the edge of what scrolls, or by what lies over it) has it smaller.
     */
    private fun visible(label: String): Rect {
        // What takes the tap on the key's own description, not on its label under it (which may lie on the picture).
        val buttons = shown(label).mapNotNull { node -> chain(node).firstOrNull { it.isClickable } }
        return buttons.map { button -> Rect().also(button::getBoundsInScreen) }.minByOrNull { it.width() * it.height() }
            ?: throw AssertionError("No button \"$label\" in sight: ${screenText()}")
    }

    private fun screen(): Rect = callScreen().resources.displayMetrics.let { Rect(0, 0, it.widthPixels, it.heightPixels) }

    /** The round keys of the controls beside the name all whole on the screen, the hang up key too. */
    private fun assertControlsWhole() {
        val screen = screen()
        for (label in CONTROLS) {
            val rect = visible(label)
            assertTrue("Key $label cut off: $rect on $screen", screen.contains(rect) && abs(rect.width() - rect.height()) <= ROUNDING_PX)
            assertTrue("Key $label smaller than $KEY_DP dp: $rect", rect.height() >= px(KEY_DP))
        }
    }

    /** The time of the call in sight beside the controls, with nothing further down to scroll to. */
    private fun assertHeadWhole() {
        val time = nodes().firstOrNull { it.text?.toString()?.matches(TIME) == true } ?: throw AssertionError("No time in sight: ${screenText()}")
        val scrolls = chain(time).any { node -> node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD } }
        assertFalse("What stands beside the controls goes on below the screen", scrolls)
    }

    private fun px(dp: Int): Int = (dp * InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density).toInt()

    /** The buttons [labels] as much in sight as they are now, once the keypad is open. */
    private fun inSight(vararg labels: String): Map<String, Rect> = labels.associateWith(::visible)

    /**
     * Every key of the keypad whole on the screen, at least 48 dp each way, none over another;
     * the buttons at the top ([inSight], as they were before) stay in sight beside it.
     */
    private fun assertKeysFit(inSight: Map<String, Rect>) {
        val screen = screen()
        val keys = KEYS.map { key -> key to touchBounds(key) }
        for ((key, rect) in keys) {
            assertTrue("Key $key cut off: $rect on $screen", screen.contains(rect))
            assertTrue("Key $key smaller than $KEY_DP dp: $rect", rect.width() >= px(KEY_DP) && rect.height() >= px(KEY_DP))
        }
        for ((i, a) in keys.withIndex()) {
            for (b in keys.drop(i + 1)) assertTrue("Keys ${a.first} and ${b.first} overlap", !Rect.intersects(a.second, b.second))
        }
        val close = touchBounds("收起键盘")
        assertTrue("The close button is cut off: $close", screen.contains(close))
        assertEquals("The close button lies over a key", null, keys.firstOrNull { Rect.intersects(it.second, close) }?.first)
        for ((label, before) in inSight) assertEquals("The keypad lies over \"$label\"", before, visible(label))
    }

    private fun voiceCall() {
        onMain { engine.core.useFiles = false }
        showApp()
        register()
        dial("2000")
        awaitConnected("2000")
        context.openCallScreen()
        turn()
    }

    @Test
    fun theKeypadFitsOverAVoiceCall() {
        voiceCall()
        settle()
        picture("voice")
        assertControlsWhole()
        assertHeadWhole()
        val top = inSight("会议室", "开始录音")
        tap("键盘")
        see("收起键盘")
        settle()
        picture("keypad-voice")
        assertKeysFit(top)
    }

    @Test
    fun theKeypadFitsOverAVideoCall() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        showApp()
        register()
        ok(onMain { engine.calls.dial("2000", null, video = true) })
        awaitCall("the picture of the echo") { it.phase == CallPhase.CONNECTED && it.video?.received != null }
        context.openCallScreen()
        turn()
        // The controls of a video call go by themselves: a tap brings them back for a few seconds.
        if (shown("键盘").isEmpty()) {
            val metrics = callScreen().resources.displayMetrics
            tapAt(metrics.widthPixels * 0.6f, metrics.heightPixels * 0.5f)
            see("键盘")
        }
        assertControlsWhole()
        val top = inSight("会议室", "开始录音", "切换摄像头", "关闭摄像头")
        tap("键盘")
        see("收起键盘")
        settle()
        picture("keypad-video")
        assertKeysFit(top)
    }

    @Test
    fun theSheetsFit() {
        voiceCall()
        tap("转接")
        see("盲转")
        settle()
        picture("transfer")
        val screenHeight = callScreen().resources.displayMetrics.heightPixels
        for (button in listOf("咨询转", "盲转")) assertTrue("$button is cut off", bounds(button).bottom <= screenHeight)
        back()
        await("the transfer sheet to close") { find("盲转") == null }
        tap("快捷键")
        see("暂无快捷键")
        settle()
        picture("shortcuts")
    }

    @Test
    fun theKeypadClosesWhenTheRoomOpens() {
        callFirstMember()
        turn()
        tap("键盘")
        see("收起键盘")
        // The room button stays in sight beside the keypad; the room takes the place of the call and its keypad.
        tap("会议室")
        awaitRoom("the room") { it.present == 1 }
        await("the keypad to close") { shown("收起键盘").isEmpty() }
        settle()
        picture("room-after-keypad")
        assertTrue("The room's controls are covered", takesInput("结束会议") && takesInput("添加成员"))
    }

    @Test
    fun theRoomAndItsSheetFit() {
        openRoom("2501", "2502")
        turn()
        see("添加成员")
        picture("room")
        tap("添加成员")
        see("号码或姓名")
        settle()
        picture("room-add")
    }

    // ------------------------------------------------------------------ the main screens

    /** The activity of type [T] in front, if there is one. */
    private inline fun <reified T : Activity> resumed(): T? = onMain {
        ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<T>().firstOrNull()
    }

    /** The app's own screen (the one of the tabs), once it is in front. */
    private fun app(): MainActivity {
        await("the app to be in front") { resumed<MainActivity>() != null }
        return resumed<MainActivity>()!!
    }

    /** How the display of [activity] is turned: one of the Surface.ROTATION_ values. */
    private fun rotation(activity: Activity): Int = onMain {
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
    private fun turnApp(orientation: Int): Int {
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

    /** The app's window on the screen. */
    private fun window(): Rect {
        val decor = app().window.decorView
        return onMain { IntArray(2).also(decor::getLocationOnScreen).let { (x, y) -> Rect(x, y, x + decor.width, y + decor.height) } }
    }

    /** What the system bars and the camera cutout take of each side of the app's window, in pixels. */
    private fun insets(): Insets {
        val decor = app().window.decorView
        return onMain {
            WindowInsetsCompat.toWindowInsetsCompat(decor.rootWindowInsets)
                .getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        }
    }

    /** Lays the app out from right to left, as a language written that way would, until it is made anew. */
    private fun rightToLeft() {
        val decor = app().window.decorView
        onMain { decor.layoutDirection = View.LAYOUT_DIRECTION_RTL }
        Thread.sleep(TURN_MS)
        settle()
    }

    /**
     * What a label of the bar or the rail is the label of: a tab, which takes a tap, or is the
     * chosen one (that one takes none).
     */
    private fun tabOf(node: AccessibilityNodeInfo): AccessibilityNodeInfo? = chain(node).firstOrNull { it.isClickable || it.isSelected }

    /** The tabs that say [label]. */
    private fun tabs(label: String): List<AccessibilityNodeInfo> = shown(label).mapNotNull(::tabOf)

    /** The tab [label] in the bar or the rail: there is one. */
    private fun tab(label: String): AccessibilityNodeInfo {
        val tabs = tabs(label)
        return tabs.singleOrNull() ?: throw AssertionError("${tabs.size} tabs \"$label\": ${screenText()}")
    }

    private fun tabBounds(): List<Rect> = TABS.map { label -> Rect().also(tab(label)::getBoundsInScreen) }

    /** The tab shown as chosen. */
    private fun chosenTab(): String {
        val chosen = TABS.filter { label -> tab(label).isSelected }
        return chosen.singleOrNull() ?: throw AssertionError("Chosen tabs: $chosen")
    }

    /** Opens the tab [label], which shows [sign]; the tab is shown as chosen. */
    private fun openTab(label: String, sign: String) {
        assertTrue("The tab $label takes no tap", tab(label).performAction(AccessibilityNodeInfo.ACTION_CLICK))
        settle()
        see(sign)
        assertEquals(label, chosenTab())
    }

    /** The tabs in one row along the bottom, above the navigation bar, and nowhere else. */
    private fun assertBar() {
        val tabs = tabBounds()
        val bottom = window().bottom - insets().bottom
        tabs.zipWithNext { a, b -> assertTrue("The tabs are not in a row: $tabs", abs(a.top - b.top) <= ROUNDING_PX && b.left >= a.right) }
        assertTrue("The bar is not at the bottom: $tabs above $bottom", tabs.all { it.bottom in bottom - px(BAR_GAP_DP)..bottom + ROUNDING_PX })
    }

    /**
     * The tabs one above the other at the start of the window (the left, or the right [rtl]),
     * right next to what the cutout and the system bars take there and clear of it, and nowhere
     * else: no bar at the bottom.
     *
     * @return the edge of the rail that the screen beside it starts from
     */
    private fun assertRail(rtl: Boolean = false): Int {
        val tabs = tabBounds()
        val window = window()
        val insets = insets()
        tabs.zipWithNext { a, b ->
            assertTrue("The tabs do not stand one above the other: $tabs", abs(a.left - b.left) <= ROUNDING_PX && b.top >= a.bottom)
        }
        for (tab in tabs) {
            assertTrue("A tab lies under a system bar: $tab, insets $insets", tab.top >= window.top + insets.top && tab.bottom <= window.bottom - insets.bottom)
            if (rtl) {
                assertTrue("A tab is not at the right, clear of what is there: $tab, insets $insets", abs(tab.right - (window.right - insets.right)) <= ROUNDING_PX)
            } else {
                assertTrue("A tab is not at the left, clear of what is there: $tab, insets $insets", abs(tab.left - (window.left + insets.left)) <= ROUNDING_PX)
            }
        }
        return if (rtl) tabs.minOf { it.left } else tabs.maxOf { it.right }
    }

    /** Where the screen beside the rail (or above the bar) may put anything, left to right: between [railEdge] and the other side's insets. */
    private fun room(railEdge: Int?, rtl: Boolean = false): IntRange {
        val window = window()
        val insets = insets()
        return when {
            railEdge == null -> window.left + insets.left..window.right - insets.right
            rtl -> window.left + insets.left..railEdge
            else -> railEdge..window.right - insets.right
        }
    }

    /**
     * The dial pad in two panes in [room]: the call button on one side, the keys on the other;
     * every key whole and at least 48 dp each way, nothing under a system bar.
     */
    private fun assertDialPadFits(room: IntRange) {
        val window = window()
        val insets = insets()
        val keys = KEYS.map(::touchBounds)
        val call = touchBounds("呼叫")
        for (rect in keys + call) {
            assertTrue("$rect is not within $room", rect.left >= room.first && rect.right <= room.last)
            assertTrue("$rect lies under a system bar", rect.top >= window.top + insets.top && rect.bottom <= window.bottom - insets.bottom)
        }
        keys.forEach { assertTrue("Key smaller than $KEY_DP dp: $it", it.width() >= px(KEY_DP) && it.height() >= px(KEY_DP)) }
        val pad = Rect(keys.first()).apply { keys.forEach(::union) }
        assertTrue("The call button is not beside the keys: $call, keys $pad", call.right <= pad.left || call.left >= pad.right)
    }

    /**
     * The title of the settings [gap] from the rail, as far as upright from the edge of the
     * screen, and their rows within [room]: the screen beside the rail starts at the rail, with
     * no gap twice, and keeps clear of what the system takes of its other side.
     */
    private fun assertSettingsFit(railEdge: Int, room: IntRange, gap: Int, rtl: Boolean = false) {
        val title = settingsTitle()
        val fromRail = if (rtl) railEdge - title.right else title.left - railEdge
        assertTrue("The title is $fromRail px from the rail, upright $gap px from the edge", abs(fromRail - gap) <= ROUNDING_PX)
        val row = touchBounds("SIP 账号")
        assertTrue("The row $row is not within $room", row.left >= room.first && row.right <= room.last)
    }

    /** The title of the settings page (its tab says the same, but takes a tap). */
    private fun settingsTitle(): Rect = Rect().also { rect ->
        val title = shown("设置").firstOrNull { tabOf(it) == null }
        (title ?: throw AssertionError("No title: ${screenText()}")).getBoundsInScreen(rect)
    }

    /** The dot of a newer version is on the settings tab: its label tells of it. */
    private fun assertDotOnSettingsTab() {
        val label = tabOf(see(UPDATE_DOT)) ?: throw AssertionError("The dot is on no tab")
        val tab = tab("设置")
        assertEquals("The dot is not on the settings tab", Rect().also(tab::getBoundsInScreen), Rect().also(label::getBoundsInScreen))
    }

    /** GitHub answers that a version newer than any release of the app is out: the settings tab gets its dot. */
    private fun offerNewerVersion() {
        onMain {
            engine.updates.forget()
            engine.updates.feed = ReleaseFeed { FeedAnswer(200, body = """{"tag_name": "v$NEWER"}""") }
            engine.updates.checkNow()
        }
        await("the newer version to be found") { engine.updates.status.value.latest?.version == NEWER }
    }

    /** Shows the tabs, the dial pad in front; [newer]: with the dot of a newer version on the settings tab. */
    private fun showMainScreens(newer: Boolean = false) {
        assertTrue(settings { it.copy(lockScreenPromptDeclined = true) })
        if (newer) offerNewerVersion()
        showApp()
        app()
        settle()
    }

    @Test
    fun theTabsGoToARailOnItsSide() {
        showMainScreens(newer = true)
        assertBar()
        assertEquals("拨号", chosenTab())
        assertDotOnSettingsTab()
        // What upright stands between the title of a page and the edge of the screen.
        openTab("设置", "SIP 账号")
        val gap = settingsTitle().left - window().left
        openTab("拨号", "呼叫")
        tap("1")
        tap("#")
        see("1#")
        picture("upright")

        // The left side first and then the right takes the cutout (or the navigation bar).
        for ((orientation, name) in listOf(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE to "side", ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE to "other-side")) {
            val rotation = turnApp(orientation)
            assertTrue("Not on its side: rotation $rotation", rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270)
            val edge = assertRail()
            assertEquals("拨号", chosenTab())
            // What was typed is still there; the dial pad stands in two panes beside the rail.
            see("1#")
            assertDialPadFits(room(edge))
            // The dot of the newer version is on the rail's settings tab.
            assertDotOnSettingsTab()
            picture("rail-$name-$rotation")
            openTab("记录", "通话记录")
            openTab("通讯录", "LDAP 设置")
            openTab("设置", "SIP 账号")
            assertSettingsFit(edge, room(edge), gap)
            picture("rail-settings-$name-$rotation")
            openTab("拨号", "呼叫")
        }

        // Written from right to left the rail stands at the right.
        rightToLeft()
        val edge = assertRail(rtl = true)
        assertDialPadFits(room(edge, rtl = true))
        openTab("设置", "SIP 账号")
        assertSettingsFit(edge, room(edge, rtl = true), gap, rtl = true)
        picture("rail-rtl-${rotation(app())}")

        // Upright again, the bar is back with the same tab chosen.
        turnApp(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
        assertBar()
        assertEquals("设置", chosenTab())
        assertDotOnSettingsTab()
        picture("upright-again")
    }

    @Test
    fun turningKeepsTheScreensOpenedFromATab() {
        showMainScreens()
        openTab("设置", "SIP 账号")
        tap("高级设置")
        see("返回")
        turnApp(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
        // A page under a tab has no tabs, on its side as upright; its back arrow clears the cutout.
        see("返回")
        TABS.forEach { label -> assertTrue("The tab $label is shown over a page", tabs(label).isEmpty()) }
        val arrow = touchBounds("返回")
        assertTrue("The back arrow $arrow lies under the cutout or a system bar", arrow.left >= room(null).first && arrow.right <= room(null).last)
        picture("page-on-its-side")
        back()
        assertRail()
        assertEquals("设置", chosenTab())
        see("SIP 账号")
        turnApp(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
        assertBar()
        assertEquals("设置", chosenTab())
    }

    private companion object {
        val KEYS = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#")

        /** The tabs, in their order. */
        val TABS = listOf("拨号", "记录", "通讯录", "设置")

        /** What the settings tab says to a screen reader while it carries the dot of a newer version. */
        const val UPDATE_DOT = "设置，有新版本可用"

        /** A version no release of the app will reach. */
        const val NEWER = "99.0.0"

        /** How far above the navigation bar the tabs of the bar may end. */
        const val BAR_GAP_DP = 16

        /** The keys of a call that are always there: the first of each row and the hang up key. */
        val CONTROLS = listOf("静音", "键盘", "扬声器", "转接", "快捷键", "挂断")
        const val KEY_DP = 48

        /** A round key whole on the screen is as high as it is wide, to the pixel of rounding. */
        const val ROUNDING_PX = 2

        /** How long the call has lasted, as the call screen shows it. */
        val TIME = Regex("\\d+:\\d{2}")
        const val TURN_MS = 1_000L
    }
}
