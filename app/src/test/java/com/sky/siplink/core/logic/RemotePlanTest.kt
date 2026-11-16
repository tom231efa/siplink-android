package com.sky.siplink.core.logic

import com.sky.siplink.core.model.BannerPosition
import com.sky.siplink.core.model.LdapDirectory
import com.sky.siplink.core.model.Settings
import com.sky.siplink.core.model.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Test

class RemotePlanTest {
    @Test
    fun theHandedOutSettingsKeepTheUsersOwn() {
        val current = Settings(doNotDisturb = true, themeMode = ThemeMode.DARK, dynamicColor = true, callBannerPosition = BannerPosition.TOP, lockScreenPromptDeclined = true, forwardBusy = "1002")
        val remote = Settings(forwardBusy = "1003", videoCalls = false, autoUpdateCheck = false)
        val merged = RemotePlan.settings(remote, current)
        assertEquals(
            remote.copy(doNotDisturb = true, themeMode = ThemeMode.DARK, dynamicColor = true, callBannerPosition = BannerPosition.TOP, lockScreenPromptDeclined = true),
            merged,
        )
        // Taken back, the defaults return and the user's own settings stay.
        assertEquals(Settings().copy(doNotDisturb = true, themeMode = ThemeMode.DARK, dynamicColor = true, callBannerPosition = BannerPosition.TOP, lockScreenPromptDeclined = true), RemotePlan.wipedSettings(merged))
    }

    @Test
    fun knownKeysKeepTheirIdsAndNewOnesGetNewIds() {
        var made = 0
        val ids = RemotePlan.ids(
            keys = listOf("p1", "p2", "p3"),
            known = mapOf("p1" to "id-1", "p2" to "id-gone", "p9" to "id-9"),
            existing = setOf("id-1", "id-9", "own"),
        ) { "new-${++made}" }
        assertEquals(mapOf("p1" to "id-1", "p2" to "new-1", "p3" to "new-2"), ids)
    }

    @Test
    fun directoriesGetLocalIdsAndLines() {
        val remote = listOf(
            RemoteDirectory("d1", LdapDirectory(name = "总部", host = "ldap.example.com", bindDn = "cn=a", password = "secret"), lineKey = "p1"),
            RemoteDirectory("d2", LdapDirectory(name = "分部", host = "ldap.example.com"), lineKey = "p7"),
            RemoteDirectory("d3", LdapDirectory(name = "公共", host = "ldap.example.com"), lineKey = ""),
        )
        val ids = mapOf("d1" to "x1", "d2" to "x2", "d3" to "x3")
        val directories = RemotePlan.directories(remote, ids, accountIds = mapOf("p1" to "acc-1"))
        assertEquals(listOf("x1", "x2", "x3"), directories.map { it.id })
        // An account the phone has not got (left out by the platform) makes the directory one for every line.
        assertEquals(listOf("acc-1", "", ""), directories.map { it.line })
        assertEquals(listOf("", "", ""), directories.map { it.password })
        assertEquals(mapOf("x1" to "secret", "x2" to "", "x3" to ""), RemotePlan.directoryPasswords(remote, ids))
    }

    @Test
    fun namedCodecsComeFirstAndTheOthersAreSwitchedOff() {
        val current = listOf("opus/48000/2", "speex/16000/1", "PCMU/8000/1", "PCMA/8000/1", "G722/8000/1")
        val wanted = listOf(
            CodecChoice("PCMA/8000/1", true),
            CodecChoice("AMR/8000/1", true),
            CodecChoice("opus/48000/2", false),
            CodecChoice("PCMU/8000/1", true),
            CodecChoice("PCMA/8000/1", false),
        )
        assertEquals(
            listOf(
                CodecChoice("PCMA/8000/1", true),
                CodecChoice("opus/48000/2", false),
                CodecChoice("PCMU/8000/1", true),
                CodecChoice("speex/16000/1", false),
                CodecChoice("G722/8000/1", false),
            ),
            RemotePlan.codecOrder(current, wanted),
        )
        // Nothing named: every codec is off, in its order (the manager then keeps the defaults for audio).
        assertEquals(current.map { CodecChoice(it, false) }, RemotePlan.codecOrder(current, emptyList()))
    }
}
