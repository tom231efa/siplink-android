package com.sky.siplink.itest

import android.content.pm.ActivityInfo
import com.sky.siplink.R
import com.sky.siplink.core.model.CallPhase
import com.sky.siplink.core.model.Language
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every language of the app on the main screens, with data on them: the four tabs (a history of
 * two calls, a directory with its staff), the settings, the account list with its account, an
 * account form, the line diagnosis, the advanced settings, the language, the remote configuration
 * (not set up: its first page) and the about page; in a call the call screen, its keypad (upright
 * and on its side), the transfer panel, the conference room with its sheet to add members, and
 * an incoming call. A crash fails the walk, and so does a text of the app that a screen cuts off
 * ([CutTexts]); a picture of every stop is saved under `shots/{language}/`.
 *
 * In Arabic the screens run right to left, but the keys of the dial pad and of the keypad of a
 * call keep 1 2 3 from left to right, the keypad on its side stands on the screen, and the slide
 * of an incoming call answers to the right.
 */
class LanguageScreensTest : LanguageWalkBase() {

    @Test
    fun theMainScreensInEveryLanguage() {
        register()
        saveStaffDirectory()
        // Two calls in the history, as the caller ID tests present them: one with a name, one bare number.
        for (caller in listOf("\"O'Brien, Bob\" <1002>", "<02155512345>")) {
            ringing(caller)
            onMain { engine.calls.decline() }
            awaitNoCall()
        }
        await("the calls to be in the history") { engine.history.entries.value.size >= 2 }
        val line = account(APP)!!.label
        showApp()
        for (language in Language.entries) {
            pinLanguage(language)
            tab(say(language, R.string.main_tab_dialer))
            see(say(language, R.string.main_tab_dialer))
            stop(language, "dialer")
            if (language == Language.ARABIC) {
                assertTrue("The tabs run right to left", center(say(language, R.string.main_tab_dialer)) > center(say(language, R.string.main_tab_settings)))
                assertTrue("The dial pad keeps 1 left of 3", center("1") < center("3"))
            }
            tab(say(language, R.string.main_tab_history))
            pages(language, "history")
            tab(say(language, R.string.main_tab_directory))
            pages(language, "directory")
            tab(say(language, R.string.main_tab_settings))
            pages(language, "settings")

            tap(say(language, R.string.settings_sip_accounts))
            stop(language, "accounts")
            tap(say(language, R.string.account_add))
            pages(language, "account-new")
            back()
            tap(line)
            pages(language, "account")
            tap(say(language, R.string.account_line_diagnosis))
            see(say(language, R.string.diagnosis_title))
            pages(language, "diagnosis")
            back()
            back()
            back()

            for ((page, name) in listOf(
                R.string.settings_advanced to "advanced",
                R.string.settings_language to "language",
                R.string.settings_remote_config to "remote",
                R.string.settings_about to "about",
            )) {
                tap(say(language, page))
                pages(language, name)
                back()
            }
        }
        noneCut()
    }

    @Test
    fun theCallScreensInEveryLanguage() {
        callFirstMember("2501")
        val screen = callScreen()
        for (language in Language.entries) {
            pinLanguage(language)
            see(say(language, R.string.call_keypad))
            stop(language, "call")
            tap(say(language, R.string.call_keypad))
            see(say(language, R.string.call_keypad_close))
            stop(language, "keypad")
            if (language == Language.ARABIC) assertTrue("The keypad of a call keeps 1 left of 3", center("1") < center("3"))
            tap(say(language, R.string.call_keypad_close))
            tap(say(language, R.string.call_transfer))
            see(say(language, R.string.call_transfer_title))
            stop(language, "transfer")
            back()

            onMain { screen.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            await("the call screen to be on its side") { screen.resources.displayMetrics.let { it.widthPixels > it.heightPixels } }
            see(say(language, R.string.call_keypad))
            stop(language, "call-landscape")
            // The keypad on its side comes in from the edge it stands at: the right in Arabic.
            tap(say(language, R.string.call_keypad))
            see(say(language, R.string.call_keypad_close))
            stop(language, "keypad-landscape")
            val width = screen.resources.displayMetrics.widthPixels
            for (key in listOf("1", "3", "#")) {
                val at = bounds(key)
                assertTrue("${language.tag}: the key $key of the keypad on its side is off the screen: $at", at.left >= 0 && at.right <= width)
            }
            if (language == Language.ARABIC) assertTrue("The keypad on its side stands at the right in Arabic", center("1") > width / 2)
            tap(say(language, R.string.call_keypad_close))
            onMain { screen.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            await("the call screen to be upright") { screen.resources.displayMetrics.let { it.widthPixels < it.heightPixels } }
        }

        ok(onMain { engine.calls.openRoom() })
        awaitRoom("the room to hold its first member") { connected(it) >= 1 }
        for (language in Language.entries) {
            pinLanguage(language)
            see(say(language, R.string.room_add_members))
            stop(language, "room")
            tap(say(language, R.string.room_add_members))
            see(say(language, R.string.room_add_title))
            stop(language, "room-add")
            back()
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
                stop(language, "incoming")
            }
            // In Arabic too, the slide answers to the right, as its text says.
            pinLanguage(Language.ARABIC)
            val track = bounds(say(Language.ARABIC, R.string.call_slide_description))
            val reach = track.width() / 2f - END_MARGIN_PX
            swipe(track.exactCenterX(), track.exactCenterY(), track.exactCenterX() + reach, track.exactCenterY())
            assertTrue("The slide did not answer to the right in Arabic", settles(ANSWER_TIMEOUT_MS) { call()?.phase == CallPhase.CONNECTED })
        } finally {
            phones.command("channel request hangup all")
            onMain { engine.calls.hangUp() }
            awaitNoCall()
            await("the caller to be released") { !phoneHasCall(AGENT) }
        }
        noneCut()
    }

    private companion object {
        /** The finger stops this far before the end of the track of the slide. */
        const val END_MARGIN_PX = 20f
        const val ANSWER_TIMEOUT_MS = 5_000L
    }
}
