package com.sky.siplink.itest

import android.Manifest
import android.content.pm.ActivityInfo
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
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
 * the keys are big enough to hit, and nothing lies over anything else.
 */
class LandscapeScreensTest : RoomTestBase("landscape") {
    @After
    fun upright() {
        runCatching { turnCallScreen(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) }
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

    private fun px(dp: Int): Int = (dp * callScreen().resources.displayMetrics.density).toInt()

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

    private companion object {
        val KEYS = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#")

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
