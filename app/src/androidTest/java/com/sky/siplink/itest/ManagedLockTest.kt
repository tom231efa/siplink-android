package com.sky.siplink.itest

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sky.siplink.core.ConfigLock
import com.sky.siplink.core.ShortcutManager
import com.sky.siplink.core.TlsManager
import com.sky.siplink.core.logic.AccountRules
import com.sky.siplink.core.logic.LdapRules
import com.sky.siplink.core.logic.ShortcutLines
import com.sky.siplink.core.logic.ShortcutRules
import com.sky.siplink.core.model.BannerPosition
import com.sky.siplink.core.model.CallPhase
import com.sky.siplink.core.model.DndMode
import com.sky.siplink.core.model.DtmfMode
import com.sky.siplink.core.model.LdapDirectory
import com.sky.siplink.core.model.MediaSecurity
import com.sky.siplink.core.model.RegState
import com.sky.siplink.core.model.Settings
import com.sky.siplink.core.model.Shortcut
import com.sky.siplink.core.model.ShortcutAction
import com.sky.siplink.core.model.SipAccount
import com.sky.siplink.core.model.ThemeMode
import com.sky.siplink.core.model.VideoSize
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/**
 * A managed configuration: once the state is switched on, no function of the engine takes a
 * change made on the phone. What stays the user's own goes on working, the remote side has its
 * own way in, and switching the state off gives everything back.
 */
@RunWith(AndroidJUnit4::class)
class ManagedLockTest : PbxTestBase() {
    private lateinit var directory: LdapDirectory
    private lateinit var key: Shortcut

    private val first: SipAccount get() = account(APP)!!
    private val second: SipAccount get() = account(SECOND)!!
    private val shortcuts: List<Shortcut> get() = engine.shortcuts.shortcuts.value
    private val fingerprint: String get() = engine.tls.certificates.value.single().fingerprint

    /** What a remote side could have handed out: something of everything that can be configured. */
    @Before
    fun handOut() {
        await("the shortcut keys to be read") { engine.shortcuts.loaded.value }
        onMain { engine.shortcuts.remove(shortcuts.map { it.id }) }
        addAccount(draft(APP).copy(name = MAIN_LINE))
        addAccount(SECOND)
        onMain {
            val errors = engine.directory.save(
                LdapDirectory(
                    name = "总部",
                    host = env.ldapHost,
                    bindDn = env.ldapBindDn,
                    password = env.ldapPassword,
                    baseDn = env.ldapBaseDn,
                    line = first.id,
                ),
            )
            assertTrue("LDAP directory rejected: $errors", errors.isEmpty())
            assertEquals(TlsManager.ImportResult.Added(1), engine.tls.import(env.caPem))
            assertTrue(engine.shortcuts.add(Shortcut(0, "前台", "$AGENT", category = "常用", line = ShortcutLines.of(first))).isEmpty())
            assertTrue(engine.updateSettings { it.copy(forwardBusy = "$CUSTOMER", rejectAnonymous = true) })
            // Codecs that are not as the SDK ships them, so that restoring the defaults would show.
            val codecs = engine.codecs
            assertTrue(codecs.move(codecs.codecs.value.first().key, 1))
            assertTrue(codecs.setEnabled(codecs.codecs.value.last().key, !codecs.codecs.value.last().enabled))
        }
        directory = engine.directory.directories.value.single()
        key = shortcuts.single()
    }

    @After
    fun takeBack() {
        onMain {
            engine.lock.setManaged(false)
            engine.shortcuts.remove(shortcuts.map { it.id })
            engine.codecs.restoreDefaults()
        }
    }

    private fun manage(on: Boolean) = onMain { engine.lock.setManaged(on) }

    /** Everything that is configured, without what the registrations are doing at the moment. */
    private fun configuration(): List<Any> = onMain {
        listOf(
            engine.settings.current,
            engine.accounts.accounts.value.map { it.copy(state = RegState.NONE, error = null, onBackup = false) },
            engine.directory.directories.value,
            engine.tls.certificates.value,
            engine.codecs.codecs.value,
            engine.codecs.videoCodecs.value,
            shortcuts,
        )
    }

    @Test
    fun refusesEveryChangeMadeOnThePhone() {
        val before = configuration()
        manage(true)
        assertTrue(engine.lock.managed.value)
        onMain {
            lockedSettings.forEachIndexed { index, change -> assertFalse("Settings change $index was taken", engine.updateSettings(change)) }

            val accounts = engine.accounts
            assertEquals(listOf(AccountRules.Error.MANAGED), accounts.add(draft(THIRD)))
            assertEquals(0, accounts.addAll(listOf(draft(THIRD), draft(FOURTH))))
            assertEquals(listOf(AccountRules.Error.MANAGED), accounts.update(first.id, draft(APP).copy(name = "前台")))
            assertFalse(accounts.remove(first.id))
            assertFalse(accounts.setEnabled(first.id, false))
            assertFalse(accounts.setDefault(second.id))
            // Without a password of its own the configuration lets the stored ones be looked at.
            assertEquals(env.sipPassword, accounts.passwordOf(first.id))

            val directories = engine.directory
            assertEquals(listOf(LdapRules.Error.MANAGED), directories.save(LdapDirectory(host = env.ldapHost, baseDn = env.ldapBaseDn)))
            assertEquals(listOf(LdapRules.Error.MANAGED), directories.save(directory.copy(name = "分公司", enabled = false)))
            assertFalse(directories.remove(directory.id))
            assertEquals(env.ldapPassword, directories.passwordOf(directory.id))

            assertEquals(TlsManager.ImportResult.Managed, engine.tls.import(env.caPem))
            assertFalse(engine.tls.remove(fingerprint))

            val codecs = engine.codecs
            val audio = codecs.codecs.value
            assertFalse(codecs.setEnabled(audio.first().key, !audio.first().enabled))
            assertFalse(codecs.move(audio.first().key, 1))
            codecs.videoCodecs.value.firstOrNull()?.let { assertFalse(codecs.setEnabled(it.key, !it.enabled)) }
            assertFalse(codecs.restoreDefaults())

            // The keys that were handed out; keys of the user's own are another matter, and another test.
            val keys = engine.shortcuts
            assertEquals(listOf(ShortcutRules.Error.MANAGED), keys.update(key.copy(number = "$CUSTOMER")))
            assertFalse(keys.remove(listOf(key.id)))
            assertFalse(keys.renameCategory(key.category, "总机"))
            assertNull(keys.import("仓库,$RINGING_PHONE,speed"))
        }
        assertEquals(before, configuration())
    }

    @Test
    fun whatIsTheUsersOwnStillChanges() {
        awaitRegistered(APP)
        manage(true)

        // The look of the app and the answer to the lock screen question.
        assertTrue(settings { it.copy(themeMode = ThemeMode.DARK, dynamicColor = true, callBannerPosition = BannerPosition.TOP) })
        assertTrue(settings { it.copy(lockScreenPromptDeclined = true) })
        val look = engine.settings.current
        assertEquals(ThemeMode.DARK, look.themeMode)
        assertTrue(look.dynamicColor)
        assertEquals(BannerPosition.TOP, look.callBannerPosition)
        assertTrue(look.lockScreenPromptDeclined)

        // The do not disturb switch, which does what it says.
        assertTrue(settings { it.copy(doNotDisturb = true) })
        val mark = phones.mark()
        phoneCalls(AGENT, "$APP")
        awaitPhoneCallReleased(AGENT, mark)
        assertNull("A call was shown although do not disturb is on", call())
        assertTrue(settings { it.copy(doNotDisturb = false) })

        // A change that reaches further is taken as far as it is the user's own, and says so.
        assertFalse(settings { it.copy(doNotDisturb = true, forwardAlways = "$CUSTOMER") })
        assertTrue(engine.settings.current.doNotDisturb)
        assertEquals("", engine.settings.current.forwardAlways)
        assertTrue(settings { it.copy(doNotDisturb = false) })

        // Calls go on as ever, and their history is the user's to delete.
        dial("2000")
        awaitConnected("2000")
        onMain { engine.calls.hangUp() }
        awaitNoCall()
        await("the call in the history") { engine.history.entries.value.any { it.address.startsWith("2000") } }
        val entry = engine.history.entries.value.first { it.address.startsWith("2000") }
        onMain { engine.history.remove(entry.id) }
        assertTrue(engine.history.entries.value.none { it.id == entry.id })
        onMain { engine.history.clear() }
        assertTrue(engine.history.entries.value.isEmpty())

        // The local copy of the directories can be emptied, the keys exported.
        onMain { engine.directory.clearCache() }
        assertTrue(key.label in onMain { engine.shortcuts.export() })
    }

    @Test
    fun doNotDisturbKeptByThePbxCanStillBeSwitched() {
        awaitRegistered(APP)
        settings { it.copy(dndMode = DndMode.SERVER) }
        manage(true)
        val codes = engine.settings.current.features
        try {
            // What the switch does in this mode: a call to the feature code, which the switch follows.
            dial(codes.dndOn)
            awaitCall("the PBX to take the code") { it.phase == CallPhase.CONNECTED }
            awaitNoCall()
            assertTrue(settings { it.copy(doNotDisturb = true) })
            assertTrue(engine.settings.current.doNotDisturb)
            val mark = phones.mark()
            phoneCalls(AGENT, "$APP")
            awaitPhoneCallReleased(AGENT, mark)
            assertNull("The PBX sent a call although it was told not to", call())

            dial(codes.dndOff)
            awaitCall("the PBX to take the code") { it.phase == CallPhase.CONNECTED }
            awaitNoCall()
            assertTrue(settings { it.copy(doNotDisturb = false) })
            phoneCalls(AGENT, "$APP")
            awaitCall("calls to arrive again") { it.phase == CallPhase.INCOMING }

            // Who keeps do not disturb is not the user's to change.
            assertFalse(settings { it.copy(dndMode = DndMode.SOFTWARE) })
            assertEquals(DndMode.SERVER, engine.settings.current.dndMode)
        } finally {
            pbx.command("database deltree DND")
        }
    }

    @Test
    fun switchedOffEverythingCanBeChangedAgain() {
        manage(true)
        manage(false)
        assertFalse(engine.lock.managed.value)
        onMain {
            assertTrue(engine.updateSettings { it.copy(callWaiting = false, forwardAlways = "$CUSTOMER", dtmfMode = DtmfMode.SIP_INFO) })

            val accounts = engine.accounts
            assertEquals(env.sipPassword, accounts.passwordOf(first.id))
            assertTrue(accounts.update(first.id, draft(APP).copy(name = "前台")).isEmpty())
            assertTrue(accounts.setEnabled(first.id, false))
            assertTrue(accounts.setDefault(second.id))
            assertTrue(accounts.add(draft(THIRD)).isEmpty())
            assertEquals(1, accounts.addAll(listOf(draft(FOURTH))))
            assertTrue(accounts.remove(first.id))

            val directories = engine.directory
            assertEquals(env.ldapPassword, directories.passwordOf(directory.id))
            assertTrue(directories.save(directory.copy(name = "分公司")).isEmpty())
            assertTrue(directories.save(LdapDirectory(name = "门店", host = env.ldapHost, baseDn = env.ldapBaseDn)).isEmpty())
            assertTrue(directories.remove(directory.id))

            assertTrue(engine.tls.remove(fingerprint))
            assertEquals(TlsManager.ImportResult.Added(1), engine.tls.import(env.caPem))

            val codecs = engine.codecs
            val audio = codecs.codecs.value
            assertTrue(codecs.setEnabled(audio.first().key, !audio.first().enabled))
            assertTrue(codecs.move(audio.first().key, 1))
            assertTrue(codecs.restoreDefaults())

            val keys = engine.shortcuts
            assertTrue(keys.add(Shortcut(0, "仓库", "$RINGING_PHONE")).isEmpty())
            assertTrue(keys.update(key.copy(number = "$CUSTOMER")).isEmpty())
            assertTrue(keys.renameCategory(key.category, "总机"))
            assertEquals(1, keys.import("开门,#9,dtmf")?.shortcuts?.size)
            assertTrue(keys.remove(listOf(key.id)))
        }
        val now = engine.settings.current
        assertFalse(now.callWaiting)
        assertEquals("$CUSTOMER", now.forwardAlways)
        assertEquals(DtmfMode.SIP_INFO, now.dtmfMode)
        assertEquals(setOf("$SECOND", "$THIRD", "$FOURTH"), accounts().map { it.username }.toSet())
        assertTrue(second.isDefault)
        assertEquals(listOf("门店"), engine.directory.directories.value.map { it.name })
        assertEquals(1, engine.tls.certificates.value.size)
        assertEquals(setOf("仓库", "开门"), shortcuts.map { it.label }.toSet())
    }

    @Test
    fun theRemoteSideChangesAManagedConfiguration() {
        manage(true)
        val removed = first.id
        onMain {
            engine.lock.fromRemote {
                assertTrue(engine.updateSettings { it.copy(forwardAlways = "$CUSTOMER", mediaSecurity = MediaSecurity.SRTP_OPTIONAL) })
                assertTrue(engine.accounts.add(draft(THIRD)).isEmpty())
                assertTrue(engine.accounts.setDefault(second.id))
                // What refers to an account follows when the remote side takes the account away.
                assertTrue(engine.accounts.remove(removed))
                assertTrue(engine.directory.save(LdapDirectory(name = "门店", host = env.ldapHost, baseDn = env.ldapBaseDn)).isEmpty())
                assertTrue(engine.tls.remove(fingerprint))
                assertTrue(engine.codecs.restoreDefaults())
                assertTrue(engine.shortcuts.add(Shortcut(0, "呼叫前转", "$CUSTOMER", ShortcutAction.FORWARD)).isEmpty())
            }
        }
        assertTrue("The remote change switched the state off", engine.lock.managed.value)
        assertEquals("$CUSTOMER", engine.settings.current.forwardAlways)
        assertEquals(MediaSecurity.SRTP_OPTIONAL, engine.settings.current.mediaSecurity)
        assertEquals(setOf("$SECOND", "$THIRD"), accounts().map { it.username }.toSet())
        assertTrue(second.isDefault)
        assertEquals(listOf("总部" to "", "门店" to ""), engine.directory.directories.value.map { it.name to it.line })
        assertTrue(engine.tls.certificates.value.isEmpty())
        assertEquals(listOf("前台" to "", "呼叫前转" to ""), shortcuts.map { it.label to it.line })

        // Once the remote side is done the phone is refused again, also after a change that failed half-way.
        assertFalse(onMain { engine.accounts.remove(second.id) })
        assertTrue(runCatching { onMain { engine.lock.fromRemote { error("the remote change failed") } } }.isFailure)
        assertEquals(listOf(AccountRules.Error.MANAGED), onMain { engine.accounts.add(draft(FOURTH)) })
        assertFalse(settings { it.copy(forwardAlways = "") })
    }

    @Test
    fun theUserMakesKeysOfTheirOwnNextToAManagedConfiguration() {
        val keys = engine.shortcuts
        val files = InstrumentationRegistry.getInstrumentation().targetContext.filesDir
        val ownFile = File(files, OWN_SHORTCUTS_FILE)
        manage(true)
        onMain {
            // As many as the limit allows, each of them the user's own.
            repeat(ShortcutRules.MAX_OWN) { index ->
                assertTrue(keys.add(Shortcut(0, "自建$index", "${3000 + index}", category = OWN_CATEGORY)).isEmpty())
            }
            assertEquals(listOf(ShortcutRules.Error.OWN_LIMIT_REACHED), keys.add(Shortcut(0, "多一个", "$RINGING_PHONE")))
        }
        val own = shortcuts.filter { it.own }
        assertEquals(ShortcutRules.MAX_OWN, own.size)
        assertEquals("What was handed out changed", listOf(key), shortcuts.filterNot { it.own })
        onMain {
            // They are the user's to change and to remove; what was handed out still is not.
            assertTrue(keys.update(own[0].copy(label = CHANGED_KEY, number = "$CUSTOMER")).isEmpty())
            val changed = shortcuts.first { it.id == own[0].id }
            assertEquals(CHANGED_KEY to "$CUSTOMER", changed.label to changed.number)
            assertTrue("A key that was changed is no longer the user's own", changed.own)
            assertEquals(listOf(ShortcutRules.Error.MANAGED), keys.update(key.copy(number = "$CUSTOMER")))
            // Of the keys named, the user's own are removed.
            assertTrue(keys.remove(listOf(key.id, own[1].id)))
            assertEquals(listOf(key), shortcuts.filterNot { it.own })
            assertTrue(shortcuts.none { it.id == own[1].id })
            assertFalse(keys.remove(listOf(key.id)))
            // One less, so there is room for one more.
            assertTrue(keys.add(Shortcut(0, "补一个", "$AGENT", category = OWN_CATEGORY)).isEmpty())
            assertEquals(listOf(ShortcutRules.Error.OWN_LIMIT_REACHED), keys.add(Shortcut(0, "多一个", "$RINGING_PHONE")))
            // A category of nothing but their own keys is theirs to rename; one with a key that was handed out is not.
            assertTrue(keys.renameCategory(OWN_CATEGORY, "自用"))
            assertEquals(ShortcutRules.MAX_OWN, shortcuts.count { it.category == "自用" })
            assertFalse(keys.renameCategory(key.category, "总机"))
            // Importing stays closed, and what the remote side adds is handed out, not the user's own.
            assertNull(keys.import("仓库,$RINGING_PHONE,speed"))
            engine.lock.fromRemote { assertTrue(keys.add(Shortcut(0, REMOTE_KEY, "$CUSTOMER")).isEmpty()) }
            assertFalse(shortcuts.first { it.label == REMOTE_KEY }.own)
            // The export has all of them.
            val exported = keys.export()
            listOf(key.label, CHANGED_KEY, REMOTE_KEY).forEach { assertTrue("$it is not exported", it in exported) }
        }

        // The user's own keys are kept in a file of their own, and are theirs again at the next start.
        val mainFile = File(files, SHORTCUTS_FILE)
        await("the keys to be written") {
            ownFile.isFile && CHANGED_KEY in ownFile.readText() && "自用" in ownFile.readText() && REMOTE_KEY in mainFile.readText()
        }
        assertFalse("A key of the user's own is in the file of those handed out", CHANGED_KEY in mainFile.readText())
        val restarted = onMain { ShortcutManager(mainFile, engine.lock) }
        await("the keys to be read again") { restarted.loaded.value }
        fun List<Shortcut>.withoutIds() = map { it.copy(id = 0) }.toSet()
        assertEquals(shortcuts.withoutIds(), restarted.shortcuts.value.withoutIds())
        assertEquals(ShortcutRules.MAX_OWN, restarted.shortcuts.value.count { it.own })

        // When the managed configuration goes they go with it; what was handed out stays, and can be changed again.
        manage(false)
        assertEquals(setOf(key.label, REMOTE_KEY), shortcuts.map { it.label }.toSet())
        assertTrue(shortcuts.none { it.own })
        await("the file of the user's own keys to be gone") { !ownFile.exists() }
        onMain { assertTrue(keys.add(Shortcut(0, "仓库", "$RINGING_PHONE")).isEmpty()) }
        assertFalse("A key made without a managed configuration counts as one made next to it", shortcuts.first { it.label == "仓库" }.own)

        // Keys of the user's own that are found without a managed configuration, left behind by one, are not taken up.
        ownFile.writeText("遗留,$AGENT,speed")
        val later = onMain { ShortcutManager(mainFile, engine.lock) }
        await("the keys to be read once more") { later.loaded.value }
        assertTrue(later.shortcuts.value.none { it.label == "遗留" || it.own })
        await("the file that was left behind to be gone") { !ownFile.exists() }
    }

    @Test
    fun aPasswordOfTheConfigurationKeepsTheStoredOnesBack() {
        val lock = engine.lock
        onMain { lock.setManaged(true, sha256(CONFIG_PASSWORD)) }
        assertTrue(lock.passwordsProtected.value)
        onMain {
            val accounts = engine.accounts
            val directories = engine.directory
            assertFalse(lock.showsPasswords())
            assertFalse(lock.showsPasswords(""))
            assertFalse(lock.showsPasswords("$CONFIG_PASSWORD "))
            assertFalse("The hash itself was taken for the password", lock.showsPasswords(sha256(CONFIG_PASSWORD)))
            assertTrue(lock.showsPasswords(CONFIG_PASSWORD))
            assertEquals("A kept password was handed out", "", accounts.passwordOf(first.id))
            assertEquals("A kept password was handed out", "", accounts.passwordOf(first.id, "not the password"))
            assertEquals(env.sipPassword, accounts.passwordOf(first.id, CONFIG_PASSWORD))
            assertEquals("A kept password was handed out", "", directories.passwordOf(directory.id))
            assertEquals("A kept password was handed out", "", directories.passwordOf(directory.id, env.ldapPassword))
            assertEquals(env.ldapPassword, directories.passwordOf(directory.id, CONFIG_PASSWORD))
            // The remote side is handed them whatever it is.
            lock.fromRemote {
                assertEquals(env.sipPassword, accounts.passwordOf(first.id))
                assertEquals(env.ldapPassword, directories.passwordOf(directory.id))
            }
            // Who knows the password gets to see, not to change.
            assertEquals(listOf(AccountRules.Error.MANAGED), accounts.update(first.id, draft(APP).copy(name = "前台")))
        }

        // Handed out again without one, the configuration keeps nothing back.
        manage(true)
        assertFalse(lock.passwordsProtected.value)
        assertEquals(env.sipPassword, onMain { engine.accounts.passwordOf(first.id) })

        // Switched off, the password goes with the state: it is not asked for by what is handed out next.
        onMain { lock.setManaged(true, sha256(CONFIG_PASSWORD)) }
        assertTrue(lock.passwordsProtected.value)
        manage(false)
        assertFalse(lock.passwordsProtected.value)
        assertEquals(env.sipPassword, onMain { engine.accounts.passwordOf(first.id) })
        manage(true)
        assertFalse("The password of an earlier configuration is still asked for", lock.passwordsProtected.value)
    }

    @Test
    fun theStateIsKeptInTheConfigurationFile() {
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, CONFIG_FILE)
        /** What the app finds at its next start. */
        fun stored(): Boolean = onMain { ConfigLock(engine.core.config).managed.value }

        assertFalse(stored())
        manage(true)
        assertTrue(stored())
        assertNotNull("Not written to the file", MANAGED_ENTRY.find(file.readText()))
        manage(false)
        assertFalse(stored())
        assertNull("Still in the file", MANAGED_ENTRY.find(file.readText()))

        // The password of the configuration is kept as the hash that was handed out, and found again at the next start.
        val hash = sha256(CONFIG_PASSWORD)
        onMain { engine.lock.setManaged(true, hash) }
        assertTrue("The hash is not in the file", hash in file.readText())
        assertFalse("The password itself is in the file", CONFIG_PASSWORD in file.readText())
        onMain {
            val next = ConfigLock(engine.core.config)
            assertTrue(next.managed.value)
            assertTrue(next.passwordsProtected.value)
            assertFalse(next.showsPasswords())
            assertTrue(next.showsPasswords(CONFIG_PASSWORD))
        }
        manage(false)
        assertFalse("The hash is still in the file", hash in file.readText())
        assertFalse(onMain { ConfigLock(engine.core.config).passwordsProtected.value })
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        /** Extensions of the PBX that no scripted phone uses, for further accounts of the app. */
        const val SECOND = 1006
        const val THIRD = 1007
        const val FOURTH = 1008

        const val MAIN_LINE = "总机"

        /** The files of the shortcut keys in the app's files: those handed out or made without a managed configuration, and the user's own next to one. */
        const val SHORTCUTS_FILE = "shortcuts.csv"
        const val OWN_SHORTCUTS_FILE = "shortcuts-own.csv"

        const val OWN_CATEGORY = "我的"
        const val CHANGED_KEY = "改过的自建键"
        const val REMOTE_KEY = "远程加的键"

        /** The password a managed configuration of the tests has: made up here, and only its hash is handed out. */
        const val CONFIG_PASSWORD = "itest-config-password"
        const val CONFIG_FILE = "linphonerc"

        /** The state as the configuration file has it while it is on, inside the section of the app. */
        val MANAGED_ENTRY = Regex("""\[siplink][^\[]*^managed=1$""", RegexOption.MULTILINE)

        /** A change of every setting that is not the user's own. */
        val lockedSettings = listOf<(Settings) -> Settings>(
            { it.copy(keepAlive = !it.keepAlive) },
            { it.copy(intercomAutoAnswer = !it.intercomAutoAnswer) },
            { it.copy(intercomSpeaker = !it.intercomSpeaker) },
            { it.copy(verifyTlsCertificate = !it.verifyTlsCertificate) },
            { it.copy(verifyTlsHostname = !it.verifyTlsHostname) },
            { it.copy(mediaSecurity = MediaSecurity.SRTP_MANDATORY) },
            { it.copy(dndMode = DndMode.SERVER) },
            { it.copy(callWaiting = !it.callWaiting) },
            { it.copy(rejectAnonymous = !it.rejectAnonymous) },
            { it.copy(dtmfMode = DtmfMode.SIP_INFO) },
            { it.copy(forwardAlways = "1003") },
            { it.copy(forwardBusy = "") },
            { it.copy(forwardNoAnswer = "1003") },
            { it.copy(forwardNoAnswerSeconds = it.forwardNoAnswerSeconds + 5) },
            { it.copy(features = it.features.copy(park = "", voicemail = "*98")) },
            { it.copy(videoCalls = !it.videoCalls) },
            { it.copy(videoSize = VideoSize.HD) },
            { it.copy(videoFrameRate = 30) },
            { Settings() },
        )
    }
}
