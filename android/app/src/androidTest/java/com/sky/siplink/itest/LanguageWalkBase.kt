package com.sky.siplink.itest

import android.content.pm.ActivityInfo
import android.graphics.Rect
import androidx.annotation.StringRes
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.sky.siplink.CallActivity
import com.sky.siplink.core.model.Language
import com.sky.siplink.core.model.LdapDirectory
import com.sky.siplink.core.model.LdapSecurity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before

/**
 * Steps of the walks through the screens in every language: each stop saves a picture under
 * `shots/{language}/` in the cache directory of the app and notes the texts of the app the screen
 * cuts off ([CutTexts]); a walk fails at its end with all of them.
 */
abstract class LanguageWalkBase : RoomTestBase("shots") {
    private val cuts = CutTexts()
    private val found = ArrayList<String>()
    private val known = HashMap<Language, LanguageTexts>()

    /** Before the first screen of the test: only screens made after this are noted. */
    @Before
    fun watchTexts() = cuts.watch()

    @After
    fun backToChinese() {
        cuts.stop()
        // Upright, not left to the system: that may leave the screen on its side for the next test.
        onMain {
            ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<CallActivity>()
                .forEach { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        }
        pinLanguage(Language.CHINESE_SIMPLIFIED)
    }

    protected fun say(language: Language, @StringRes id: Int): String = textsIn(language.tag).getString(id)

    /** A stop of the walk: the picture [name] of what is shown now, and the texts it cuts off. */
    protected fun stop(language: Language, name: String) {
        picture("${language.tag}/$name")
        val texts = known.getOrPut(language) { LanguageTexts(textsIn(language.tag)) }
        val result = onMain { cuts.find(texts) }
        if (result.seen == 0) found += "${language.tag} $name: the check saw nothing (no text of the app on a screen it can read)"
        result.cut.forEach { found += "${language.tag} $name: $it" }
    }

    /** Stops on the page from its top to its end ([name]-1, [name]-2, …), then scrolls back up. */
    protected fun pages(language: Language, name: String) {
        settle()
        var steps = 0
        do {
            stop(language, "$name-${steps + 1}")
        } while (steps++ < MAX_PAGES && scroll(forward = true))
        while (steps-- > 0) {
            if (!scroll(forward = false)) break
        }
    }

    /** Fails with every text of the app that a stop found cut off. */
    protected fun noneCut() = assertEquals("Texts of the app cut off", "", found.joinToString("\n"))

    /** Opens the tab [label]; the tab shown already takes no tap. */
    protected fun tab(label: String) {
        if (chain(see(label)).none { it.isSelected }) tap(label)
        settle()
    }

    /** The middle of what says [text] across the screen, in pixels from the left. */
    protected fun center(text: String): Int = Rect().also { see(text).getBoundsInScreen(it) }.centerX()

    /** Where what says [text] is on the screen. */
    protected fun bounds(text: String): Rect = Rect().also { see(text).getBoundsInScreen(it) }

    /** The test directory's staff, as the directory tests have it, saved. */
    protected fun saveStaffDirectory() {
        val staff = LdapDirectory(
            host = env.ldapHost,
            security = LdapSecurity.NONE,
            bindDn = env.ldapBindDn,
            password = env.ldapPassword,
            baseDn = "ou=$PEOPLE,${env.ldapBaseDn}",
        )
        val errors = onCore { engine.directory.save(staff) }
        assertTrue("LDAP directory rejected: $errors", errors.isEmpty())
    }

    protected companion object {
        /** Pictures of one page at most: the settings and the advanced settings are the longest. */
        const val MAX_PAGES = 8

        /** The part of the test directory with the staff (the directory tests use it too). */
        const val PEOPLE = "people"
    }
}
