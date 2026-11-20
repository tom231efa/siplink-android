package com.sky.siplink.itest

import android.app.LocaleManager
import android.os.Build
import android.os.LocaleList
import com.sky.siplink.R
import com.sky.siplink.core.AppLanguage
import com.sky.siplink.core.model.CallPhase
import com.sky.siplink.core.model.Language
import com.sky.siplink.core.model.Phrase
import com.sky.siplink.core.render
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The language of the app: chosen in the settings it shows at once and stays; following the
 * system, a language the app does not have falls through the list to English; a call that is up
 * stays up while its screen changes language; and texts the core puts together follow too.
 *
 * Expects a phone whose system languages hold none of the app's but English (the test phone).
 * Every test starts in Simplified Chinese ([PbxTestBase.pinLanguage]) and ends in it.
 */
class AppLanguageTest : RoomTestBase("languages") {
    private val english get() = textsIn("en")
    private val chinese get() = textsIn("zh-Hans")

    /** The text the core renders when a call ends, in the language the app speaks now. */
    private fun spoken(): String = onMain { engine.texts.get(R.string.call_ended) }

    @After
    fun backToChinese() = pinLanguage(Language.CHINESE_SIMPLIFIED)

    @Test
    fun aLanguageChosenInTheSettingsShowsAtOnceAndStays() {
        showApp()
        tap("设置")
        tap(chinese.getString(R.string.settings_language))
        see(chinese.getString(R.string.settings_language_system))
        tap(Language.ENGLISH.ownName)
        see(english.getString(R.string.settings_language_system))
        absent(chinese.getString(R.string.settings_language_system))
        assertEquals(english.getString(R.string.call_ended), spoken())
        // What a start of the app reads: the choice is kept, not only held by this process.
        val again = AppLanguage(context.applicationContext)
        assertEquals(Language.ENGLISH, again.chosen)
        assertFalse(again.followsSystem)
    }

    @Test
    fun followingTheSystemGivesEnglishHere() {
        pinLanguage(null)
        assertTrue(onMain { engine.language.followsSystem })
        assertNull(onMain { engine.language.chosen })
        assertEquals(english.getString(R.string.call_ended), spoken())
        showApp()
        tap("设置")
        tap(english.getString(R.string.settings_language))
        see(english.getString(R.string.settings_language_system))
    }

    @Test
    fun aLanguageTheAppDoesNotHaveFallsThroughToEnglish() {
        assumeTrue("Per-app languages of the system: Android 13", Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        // Shown, the app hears of a change made outside it as the system's page makes it: as a change of the configuration.
        showApp()
        val manager = context.getSystemService(LocaleManager::class.java)
        val before = manager.applicationLocales
        try {
            // What a fresh installation has: no language of its own.
            manager.applicationLocales = LocaleList.getEmptyLocaleList()
            await("the app to follow the system") { onMain { engine.language.followsSystem } }
            manager.applicationLocales = LocaleList.forLanguageTags("de")
            await("German to fall back to English") { spoken() == english.getString(R.string.call_ended) }
            assertNull(onMain { engine.language.chosen })
            assertFalse(onMain { engine.language.followsSystem })
            // The next language of the list that the app has is taken.
            manager.applicationLocales = LocaleList.forLanguageTags("de,zh-CN")
            await("German, then Chinese, to give Chinese") { spoken() == chinese.getString(R.string.call_ended) }
        } finally {
            manager.applicationLocales = before
        }
    }

    @Test
    fun theScriptOfChineseDecidesTheTexts() {
        val simplified = chinese.getString(R.string.call_ended)
        for (tags in listOf("zh", "zh-CN", "zh-SG", "zh-Hans-HK", "de,zh-CN")) {
            assertEquals(tags, simplified, textsIn(tags).getString(R.string.call_ended))
        }
        // Traditional Chinese: its own texts, until the app has them those of the default.
        val traditional = textsIn("zh-Hant").getString(R.string.call_ended)
        assertNotEquals(simplified, traditional)
        for (tags in listOf("zh-TW", "zh-HK", "zh-MO")) {
            assertEquals(tags, traditional, textsIn(tags).getString(R.string.call_ended))
        }
        assertEquals(english.getString(R.string.call_ended), textsIn("de").getString(R.string.call_ended))
    }

    @Test
    fun aCallThatIsUpStaysUpWhileItsScreenChangesLanguage() {
        openRoom("2501")
        val screen = callScreen()
        val connected = Phrase.of(R.string.room_member_connected)
        see(chinese.render(connected))
        onMain { engine.language.choose(Language.ENGLISH) }
        see(english.render(connected))
        absent(chinese.render(connected))
        assertEquals(CallPhase.CONNECTED, call()!!.phase)
        assertEquals(1, appLegsUp().size)
        // Android 13 and later hand the call screen the new language without recreating it (android:configChanges).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) assertSame(screen, callScreen())
    }

    @Test
    fun aNameTheCoreGaveFollowsTheLanguage() {
        register()
        val withheld = Phrase.of(R.string.call_withheld)
        // The call log is read again with the language, the call that rings may then be in it already.
        val before = engine.history.entries.value.size
        assertEquals(chinese.render(withheld), ringing("\"Anonymous\" <anonymous>").remoteName)
        onMain { engine.language.choose(Language.ENGLISH) }
        awaitCall("the ringing call to be named in English") { it.remoteName == english.render(withheld) }
        onMain { engine.calls.decline() }
        awaitNoCall()
        await("the call to be logged") { engine.history.entries.value.size > before }
        assertEquals(english.render(withheld), engine.history.entries.value.first().name)
        pinLanguage(Language.CHINESE_SIMPLIFIED)
        await("the call log to be in Chinese") { engine.history.entries.value.first().name == chinese.render(withheld) }
    }
}
