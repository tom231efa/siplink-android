package com.sky.siplink.itest

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.sky.siplink.core.model.LdapDirectory
import com.sky.siplink.core.model.LdapSecurity
import com.sky.siplink.core.model.Shortcut
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lists of entries that may come twice, alike to the letter: two speed dial keys with the same
 * name and number, two entries of a directory with the same name and number (in the test
 * directory under ou=desks, testenv/ldap/people.ldif, told apart by their organization only).
 * The list shows every row it is given; none takes it down.
 */
class TwinEntriesTest : RoomTestBase("twins") {
    @Test
    fun twoSpeedDialKeysAlikeAreBothOfferedForTheRoom() {
        await("the speed dial keys to be loaded") { engine.shortcuts.loaded.value }
        repeat(2) { onMain { assertTrue(engine.shortcuts.add(Shortcut(0, TWIN, TWIN_NUMBER)).isEmpty()) } }
        assertEquals(2, engine.shortcuts.shortcuts.value.count { it.label == TWIN })
        openRoom("2501")
        tap("添加成员")
        tap("快捷键")
        awaitRows(TWIN_NUMBER, 2)
        picture("room-add-shortcuts")
        // Either one picks the number, which is called once.
        assertTrue(chain(shown(TWIN_NUMBER).first()).first { it.isClickable }.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        tap("呼叫（1）")
        awaitRoom("the member in the room") { connected(it) == 2 }
    }

    @Test
    fun directoryEntriesAlikeAreListedAsTheyCome() {
        register()
        val desks = LdapDirectory(
            name = "前台",
            host = env.ldapHost,
            security = LdapSecurity.NONE,
            bindDn = env.ldapBindDn,
            password = env.ldapPassword,
            baseDn = "ou=desks,${env.ldapBaseDn}",
        )
        assertTrue(onMain { engine.directory.save(desks) }.isEmpty())
        showApp()
        tap("通讯录")
        // Nothing typed: the directory lists what the server answered. The SIP core reads no
        // organization from LDAP and leaves out a result alike to the one before it, so the two
        // may come as one; whatever comes is listed, a row each.
        await("the answer of the directory") { engine.directory.state.value.let { !it.searching && !it.fromCache && it.contacts.isNotEmpty() } }
        val listed = engine.directory.state.value.contacts.filter { it.number == DESK_NUMBER }
        Log.i(TAG, "entries for the two desks: ${listed.size}, organizations ${listed.map { it.organization }}")
        assertTrue(listed.isNotEmpty())
        awaitRows(DESK_NUMBER, listed.size)
        picture("directory")
    }

    /** Waits for [count] rows that show the number [number]. */
    private fun awaitRows(number: String, count: Int) {
        if (!settles(TIMEOUT_MS) { shown(number).size == count }) {
            throw AssertionError("Not $count rows with $number on the screen, which says: ${screenText()}")
        }
    }

    private companion object {
        const val TAG = "SipLinkTwinEntriesTest"
        const val TWIN = "前台"
        const val TWIN_NUMBER = "2502"
        const val DESK_NUMBER = "3002"
    }
}
