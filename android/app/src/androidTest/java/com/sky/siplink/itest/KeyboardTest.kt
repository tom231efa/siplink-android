package com.sky.siplink.itest

import android.content.pm.ActivityInfo
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.StringRes
import com.sky.siplink.MainActivity
import com.sky.siplink.R
import com.sky.siplink.core.model.Language
import com.sky.siplink.core.model.Shortcut
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fields of the app, typed into the way a user does: a finger on the field brings the
 * keyboard of the system up, the keyboard stays, its keys type, and the field stands whole above
 * it ([typeWithKeyboard]). Every other test fills its fields without a keyboard, and so never
 * meets a screen that rearranges itself when the keyboard takes its room.
 *
 * One stop for every kind of place a field stands in, upright and with the phone on its side,
 * where the keyboard leaves hardly more than the field. On the main screens: the feature sheet,
 * the sheet to choose an account from (with so many accounts that it opens half-way), the search
 * of the shortcut keys and the sheet of a new key, the address field in the place of the dial
 * pad, the search of the accounts, their import sheet, an account form at its top and further
 * down, and the search of the directory. In a call: the transfer panel, the search of the
 * shortcut keys, and the sheet that calls members into the room. A picture of every stop is saved
 * under `shots/keyboard/`.
 */
class KeyboardTest : LanguageWalkBase() {
    /** The stops where the keyboard left the field cut off or covered: a walk goes on past them, and fails at its end with all of them. */
    private val badlyPlaced = ArrayList<String>()

    @After
    fun appUpright() {
        // Asked for upright, not left to the system: that may leave the screen on its side for the next test.
        resumed<MainActivity>()?.let { app -> runCatching { onMain { app.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT } } }
    }

    @Test
    fun theFieldsOfTheMainScreensKeepTheKeyboard() = mainScreens(sideways = false)

    @Test
    fun theFieldsOfTheMainScreensKeepTheKeyboardOnItsSide() = mainScreens(sideways = true)

    @Test
    fun theFieldsOfACallKeepTheKeyboard() = call(sideways = false)

    @Test
    fun theFieldsOfACallKeepTheKeyboardOnItsSide() = call(sideways = true)

    private fun mainScreens(sideways: Boolean) {
        val at = if (sideways) "-landscape" else ""
        register()
        (MORE_FIRST..MORE_LAST).forEach { addAccount(it) }
        saveStaffDirectory()
        addKey()
        showApp()
        if (sideways) turnApp(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)

        tab(text(R.string.main_tab_dialer))
        tap(text(R.string.dialer_call_features))
        typed("features$at", "1002") { named(R.string.feature_sheet_target) }
        backTo(R.string.dialer_switch_to_keyboard)
        tap(text(R.string.dialer_choose_account))
        typed("account-picker$at", "101") { named(R.string.account_picker_search) }
        backTo(R.string.dialer_switch_to_keyboard)

        tap(text(R.string.dialer_shortcuts))
        typed("shortcuts$at", "$AGENT") { named(R.string.shortcut_search) }
        tapInBar(R.string.shortcut_new)
        typed("shortcut-new$at", "123") { named(R.string.shortcut_field_name) }
        backTo(R.string.common_more)
        backTo(R.string.dialer_switch_to_keyboard)

        // Last on this screen: a number typed here is the target of the feature sheet, and takes the place of the key to the shortcut keys.
        tap(text(R.string.dialer_switch_to_keyboard))
        typed("dialer$at", "1002") { named(R.string.dialer_address_label) }
        tap(text(R.string.dialer_switch_to_pad))

        tab(text(R.string.main_tab_settings))
        tap(text(R.string.settings_sip_accounts))
        tap(text(R.string.account_search_action))
        typed("accounts-search$at", "101") { onlyField() }
        tap(text(R.string.account_search_close))
        tapInBar(R.string.account_import_action)
        typed("accounts-import$at", "123") { onlyField() }
        backTo(R.string.account_search_action)
        tap(text(R.string.account_add))
        typed("account-top$at", "1009") { named(R.string.account_field_username) }
        // The keyboard away, a field further down: the keyboard that comes up has to leave it in sight.
        closeKeyboard()
        typed("account-further-down$at", "123") { named(R.string.account_field_display_name) }
        backTo(R.string.account_search_action)
        backTo(R.string.main_tab_settings)

        tab(text(R.string.main_tab_directory))
        typed("directory$at", "10") { named(R.string.directory_search) }
        allWellPlaced()
    }

    private fun call(sideways: Boolean) {
        val at = if (sideways) "-landscape" else ""
        addKey()
        callFirstMember("2501")
        if (sideways) {
            val screen = callScreen()
            turnCallScreen(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
            await("the call screen to be on its side") { screen.resources.displayMetrics.let { it.widthPixels > it.heightPixels } }
            Thread.sleep(TURN_MS)
        }
        tap(text(R.string.call_transfer))
        typed("transfer$at", "1002") { named(R.string.call_transfer_to) }
        backTo(R.string.call_transfer)
        tap(text(R.string.call_shortcuts))
        typed("call-shortcuts$at", "$AGENT") { named(R.string.shortcut_search) }
        backTo(R.string.call_transfer)

        ok(onCore { engine.calls.openRoom() })
        awaitRoom("the room to hold its first member") { connected(it) >= 1 }
        tap(text(R.string.room_add_members))
        typed("room-add$at", "2502") { named(R.string.room_add_field) }
        // The keyboard still up, the number is taken. On its side the keyboard covers the call button.
        tap(text(R.string.room_add_number))
        if (sideways) closeKeyboard()
        val call = Rect().also { see(callKey(1)).getBoundsInScreen(it) }
        tap(callKey(1))
        awaitRoom("the typed number to be called into the room") { room -> room.members.any { it.address.startsWith("2502@") } }

        // Upright the call button stays whole above the keyboard, however many numbers are picked.
        if (!sideways) {
            for (number in MANY_FIRST..MANY_LAST) {
                typeKeys("$number")
                tap(text(R.string.room_add_number))
            }
            picture("keyboard/room-add-many")
            val now = Rect().also { see(callKey(MANY_LAST - MANY_FIRST + 1)).getBoundsInScreen(it) }
            val keyboard = keyboardTop()
            assertTrue("The keyboard closed while the numbers were typed", keyboard != null)
            assertTrue("The call button is cut off or covered: $now, with one number picked $call, the keyboard starts at $keyboard", now.height() >= call.height() && now.bottom <= keyboard!!)
        }
        allWellPlaced()
    }

    /** What the call button of the room's sheet says with [picked] members picked. */
    private fun callKey(picked: Int): String = textsIn(Language.CHINESE_SIMPLIFIED.tag).getString(R.string.room_call_count, picked.toString())

    /**
     * A stop: [value] typed with the keyboard into the field that [field] finds, and a picture of
     * it, also of a stop that fails. The values are digits: a keyboard may write a letter that
     * starts a field as a capital.
     */
    private fun typed(name: String, value: String, field: () -> AccessibilityNodeInfo) {
        try {
            typeWithKeyboard(value, field)?.let {
                badlyPlaced += "$name: $it"
                Log.w(TAG, "$name: $it")
            }
        } finally {
            picture("keyboard/$name")
        }
    }

    private fun allWellPlaced() = assertEquals("Fields that the keyboard leaves cut off or covered", "", badlyPlaced.joinToString("\n"))

    /**
     * The back key, until what says [below] is in front again: the screen under a sheet, or the
     * page before this one. The first press may only send the keyboard away.
     */
    private fun backTo(@StringRes below: Int) {
        var presses = 0
        while (shown(text(below)).isEmpty()) {
            assertTrue("\"${text(below)}\" is not in front after $presses presses of the back key. The screen says: ${screenText()}", presses++ < MAX_BACKS)
            back()
        }
    }

    /**
     * Taps what says [id] in the top bar of the page, the keyboard sent away first: with the
     * phone on its side the bar gives way to the keyboard. (The key that keeps the phone awake
     * may bring the keyboard back once.)
     */
    private fun tapInBar(@StringRes id: Int) {
        repeat(MAX_BACKS) {
            closeKeyboard()
            if (settles(BAR_BACK_MS) { shown(text(id)).isNotEmpty() }) return tap(text(id))
        }
        throw AssertionError("\"${text(id)}\" is not in the top bar. The screen says: ${screenText()}")
    }

    private fun text(@StringRes id: Int): String = say(Language.CHINESE_SIMPLIFIED, id)

    /** The field named [id]: by its label, or by what it says while it is empty. */
    private fun named(@StringRes id: Int): AccessibilityNodeInfo = chain(see(text(id))).first { it.isEditable }

    /** The field of a screen that has one. */
    private fun onlyField(): AccessibilityNodeInfo = nodes().single { it.isEditable && it.isEnabled }

    /** A speed dial key: without one the shortcut keys show no board to search. */
    private fun addKey() {
        await("the shortcut keys to be loaded") { engine.shortcuts.loaded.value }
        onCore { assertTrue(engine.shortcuts.add(Shortcut(0, "Agent", "$AGENT")).isEmpty()) }
    }

    private companion object {
        /** Accounts next to the first one: together more than the sheet to choose from shows at its half height. */
        const val MORE_FIRST = 1010
        const val MORE_LAST = 1018

        /** Numbers picked for the room at once: more rows of them than the sheet has room for next to its list and its call button. */
        const val MANY_FIRST = 2503
        const val MANY_LAST = 2526

        /** The keyboard, a sheet, and the key that keeps the phone awake bringing the keyboard back once. */
        const val MAX_BACKS = 3

        /** How long the top bar of a page takes at the most to be back once the keyboard is gone. */
        const val BAR_BACK_MS = 2_000L
        const val TAG = "SipLinkKeyboardTest"
    }
}
