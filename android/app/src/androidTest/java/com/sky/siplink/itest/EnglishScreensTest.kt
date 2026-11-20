package com.sky.siplink.itest

import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.StringRes
import com.sky.siplink.R
import com.sky.siplink.core.model.Language
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * With the app in English, no screen shows a Chinese character: the four tabs, the pages of the
 * settings that need no call and no account, the call screen and the conference room. What
 * the screens show of data (the names the PBX gives calls, the names of keys) and the languages'
 * own names in the language list are let through.
 */
class EnglishScreensTest : RoomTestBase("english") {
    private val english get() = textsIn("en")

    private fun en(@StringRes id: Int): String = english.getString(id)

    @Before
    fun inEnglish() = pinLanguage(Language.ENGLISH)

    @After
    fun backToChinese() = pinLanguage(Language.CHINESE_SIMPLIFIED)

    @Test
    fun theTabsAndTheSettingsHoldNoChinese() {
        showApp()
        // The dialer, its call features and its shortcut keys.
        see(en(R.string.main_tab_dialer))
        page("Dialer")
        tap(en(R.string.dialer_call_features))
        page("Call features")
        back()
        tap(en(R.string.dialer_shortcuts))
        page("Shortcut keys")
        tap(en(R.string.shortcut_new))
        page("New shortcut key")
        back()
        back()

        tap(en(R.string.main_tab_history))
        page("History")
        tap(en(R.string.main_tab_directory))
        page("Directory")

        tap(en(R.string.main_tab_settings))
        page("Settings")
        subPage(R.string.settings_sip_accounts) { open(R.string.account_add) }
        subPage(R.string.settings_recording) { open(R.string.settings_recording_files) }
        subPage(R.string.settings_auto_answer)
        subPage(R.string.settings_ldap) { open(R.string.directory_add) }
        subPage(R.string.settings_feature_codes)
        subPage(R.string.settings_codecs)
        subPage(R.string.settings_advanced)
        subPage(R.string.settings_tls)
        subPage(R.string.settings_remote_config)
        subPage(R.string.settings_about)
        // The languages' own names are what the list is for.
        subPage(R.string.settings_language, allowed = Language.entries.map { it.ownName })
    }

    @Test
    fun theCallScreenAndTheRoomHoldNoChinese() {
        callFirstMember("2501")
        page("Call screen")
        tap(en(R.string.call_transfer))
        page("Transfer")
        back()
        ok(onMain { engine.calls.openRoom() })
        awaitRoom("the room to hold its first member") { connected(it) >= 1 }
        see(en(R.string.room_add_members))
        page("Conference room")
        tap(en(R.string.room_add_members))
        page("Add members")
        back()
    }

    /** Opens the settings page [label], checks it, runs [inside] there, and comes back. */
    private fun subPage(@StringRes label: Int, allowed: List<String> = emptyList(), inside: () -> Unit = {}) {
        tap(en(label))
        page(en(label), allowed)
        inside()
        back()
    }

    /** Opens the page behind [label], checks it, and comes back. */
    private fun open(@StringRes label: Int) {
        tap(en(label))
        page(en(label))
        back()
    }

    /** Checks the screen from its top to its end, then scrolls back up. */
    private fun page(where: String, allowed: List<String> = emptyList()) {
        settle()
        var steps = 0
        do {
            val found = chinese(allowed)
            assertTrue("$where shows Chinese: $found. The screen says: ${screenText()}", found.isEmpty())
        } while (steps++ < MAX_STEPS && scroll(forward = true))
        while (steps-- > 0) {
            if (!scroll(forward = false)) break
        }
    }

    /** The texts on the screen that hold Chinese once [allowed] and the data the app holds are taken out. */
    private fun chinese(allowed: List<String>): List<String> {
        val data = allowed + onMain {
            engine.history.entries.value.flatMap { listOf(it.name, it.address, it.accountLabel) } +
                engine.shortcuts.shortcuts.value.flatMap { listOf(it.label, it.number, it.category, it.line) } +
                // The names the PBX gives the calls (the test PBX names its extensions in Chinese).
                listOfNotNull(call()?.remoteName) + call()?.room?.members.orEmpty().map { it.name }
        }.filter { it.isNotEmpty() }
        return nodes().flatMap(::said).distinct()
            .filter { text -> data.sortedByDescending { it.length }.fold(text) { left, value -> left.replace(value, "") }.any(::isChinese) }
    }

    private fun said(node: AccessibilityNodeInfo): List<String> = listOfNotNull(
        node.text,
        node.contentDescription,
        node.hintText,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) node.stateDescription else null,
    ).map { it.toString() }

    private fun isChinese(char: Char): Boolean {
        if (Character.UnicodeScript.of(char.code) == Character.UnicodeScript.HAN) return true
        val block = Character.UnicodeBlock.of(char)
        return block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION || block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS
    }

    private companion object {
        /** Scroll steps to the end of the longest page (the advanced settings). */
        const val MAX_STEPS = 20
    }
}
