package com.sky.siplink.itest

import android.content.pm.ActivityInfo
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.StringRes
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.sky.siplink.CallActivity
import com.sky.siplink.R
import com.sky.siplink.core.model.CallPhase
import com.sky.siplink.core.model.Language
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every language of the app on the main screens: the four tabs, the settings, the advanced
 * settings, an account form, the call screen with its keypad (upright and on its side), the
 * conference room and an incoming call. A crash fails the test; the pictures of every screen are
 * saved under `shots/{language}/` in the cache directory of the app, to look at.
 *
 * In Arabic the screens run right to left, but the keys of the dial pad and of the keypad of a
 * call keep 1 2 3 from left to right, and the slide of an incoming call answers to the right.
 */
class LanguageScreensTest : RoomTestBase("shots") {
    @After
    fun backToChinese() {
        // Upright, not left to the system: that may leave the screen on its side for the next test.
        onMain {
            ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<CallActivity>()
                .forEach { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        }
        pinLanguage(Language.CHINESE_SIMPLIFIED)
    }

    private fun say(language: Language, @StringRes id: Int): String = textsIn(language.tag).getString(id)

    @Test
    fun theMainScreensInEveryLanguage() {
        showApp()
        for (language in Language.entries) {
            pinLanguage(language)
            val shot = { name: String -> picture("${language.tag}/$name") }
            tab(say(language, R.string.main_tab_dialer))
            shot("dialer")
            if (language == Language.ARABIC) {
                assertTrue("The tabs run right to left", center(say(language, R.string.main_tab_dialer)) > center(say(language, R.string.main_tab_settings)))
                assertTrue("The dial pad keeps 1 left of 3", center("1") < center("3"))
            }
            tab(say(language, R.string.main_tab_history))
            shot("history")
            tab(say(language, R.string.main_tab_directory))
            shot("directory")
            tab(say(language, R.string.main_tab_settings))
            pages(shot, "settings")
            tap(say(language, R.string.settings_advanced))
            pages(shot, "advanced")
            back()
            tap(say(language, R.string.settings_sip_accounts))
            tap(say(language, R.string.account_add))
            pages(shot, "account")
            back()
            back()
        }
    }

    @Test
    fun theCallScreensInEveryLanguage() {
        callFirstMember("2501")
        val screen = callScreen()
        for (language in Language.entries) {
            pinLanguage(language)
            settle()
            val shot = { name: String -> picture("${language.tag}/$name") }
            shot("call")
            tap(say(language, R.string.call_keypad))
            shot("keypad")
            if (language == Language.ARABIC) assertTrue("The keypad of a call keeps 1 left of 3", center("1") < center("3"))
            tap(say(language, R.string.call_keypad_close))
            onMain { screen.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            await("the call screen to be on its side") { screen.resources.displayMetrics.let { it.widthPixels > it.heightPixels } }
            settle()
            shot("call-landscape")
            onMain { screen.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            await("the call screen to be upright") { screen.resources.displayMetrics.let { it.widthPixels < it.heightPixels } }
            settle()
        }

        ok(onMain { engine.calls.openRoom() })
        awaitRoom("the room to hold its first member") { connected(it) >= 1 }
        for (language in Language.entries) {
            pinLanguage(language)
            see(say(language, R.string.room_add_members))
            picture("${language.tag}/room")
        }
        onMain { engine.calls.hangUp() }
        awaitNoCall()

        phoneCalls(AGENT, "$APP")
        try {
            awaitCall("the incoming call") { it.phase == CallPhase.INCOMING }
            callScreen()
            for (language in Language.entries) {
                pinLanguage(language)
                see(say(language, R.string.call_slide_description))
                picture("${language.tag}/incoming")
            }
            // In Arabic too, the slide answers to the right, as its text says.
            pinLanguage(Language.ARABIC)
            val track = Rect().also { see(say(Language.ARABIC, R.string.call_slide_description)).getBoundsInScreen(it) }
            val reach = track.width() / 2f - END_MARGIN_PX
            swipe(track.exactCenterX(), track.exactCenterY(), track.exactCenterX() + reach, track.exactCenterY())
            assertTrue("The slide did not answer to the right in Arabic", settles(ANSWER_TIMEOUT_MS) { call()?.phase == CallPhase.CONNECTED })
        } finally {
            phones.command("channel request hangup all")
            onMain { engine.calls.hangUp() }
            awaitNoCall()
            await("the caller to be released") { !phoneHasCall(AGENT) }
        }
    }

    /** Opens the tab [label]; the tab shown already takes no tap. */
    private fun tab(label: String) {
        if (chain(see(label)).none { it.isSelected }) tap(label)
        settle()
    }

    /** Pictures of the page from its top to its end ([name]-1, [name]-2, …), then back up. */
    private fun pages(shot: (String) -> Unit, name: String) {
        settle()
        var steps = 0
        do {
            shot("$name-${steps + 1}")
        } while (steps++ < MAX_PAGES && scroll(forward = true))
        while (steps-- > 0) {
            if (!scroll(forward = false)) break
        }
    }

    /** The middle of what says [text] across the screen, in pixels from the left. */
    private fun center(text: String): Int {
        val node: AccessibilityNodeInfo = see(text)
        return Rect().also { node.getBoundsInScreen(it) }.centerX()
    }

    private companion object {
        /** Pictures of one page at most: the settings and the advanced settings are the longest. */
        const val MAX_PAGES = 8

        /** The finger stops this far before the end of the track of the slide. */
        const val END_MARGIN_PX = 20f
        const val ANSWER_TIMEOUT_MS = 5_000L
    }
}
