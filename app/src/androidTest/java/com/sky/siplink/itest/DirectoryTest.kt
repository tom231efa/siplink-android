package com.sky.siplink.itest

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sky.siplink.core.DirectoryCache
import com.sky.siplink.core.DirectoryManager
import com.sky.siplink.core.logic.LdapRules
import com.sky.siplink.core.model.CallPhase
import com.sky.siplink.core.model.DirectoryContact
import com.sky.siplink.core.model.LdapDirectory
import com.sky.siplink.core.model.LdapSecurity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.linphone.core.Ldap

/** Shared steps of the LDAP directory tests. */
abstract class DirectoryTestBase : PbxTestBase() {
    private var shown = false

    /** The staff of the test directory; [unit] is the part of it to look in. */
    protected fun directory(host: String, startTls: Boolean = false, unit: String = PEOPLE, name: String = "") = LdapDirectory(
        name = name,
        host = host,
        security = if (startTls) LdapSecurity.START_TLS else LdapSecurity.NONE,
        bindDn = env.ldapBindDn,
        password = env.ldapPassword,
        baseDn = "ou=$unit,${env.ldapBaseDn}",
    )

    /** Saves [directory] and returns it as it is stored, with its id. */
    protected fun save(directory: LdapDirectory): LdapDirectory {
        val errors = onMain { engine.directory.save(directory) }
        assertTrue("LDAP directory rejected: $errors", errors.isEmpty())
        val stored = engine.directory.directories.value
        return if (directory.id.isEmpty()) stored.last() else stored.first { it.id == directory.id }
    }

    protected fun configure(host: String, startTls: Boolean = false): LdapDirectory = save(directory(host, startTls))

    protected fun search(directory: LdapDirectory, query: String): List<DirectoryContact> {
        // The directory is used with the app on screen: off it, the SIP stack looks at the
        // network far less often and reads one LDAP message each time.
        if (!shown) {
            showApp()
            shown = true
        }
        onMain { engine.directory.search(directory.id, query) }
        await("the LDAP search for '$query' to finish") {
            val state = engine.directory.state.value
            state.directory == directory.id && state.query == query && !state.searching
        }
        return engine.directory.state.value.contacts
    }

    protected fun assertFindsTheStaff(contacts: List<DirectoryContact>) {
        val numbers = contacts.map { it.number }.toSet()
        assertTrue("Expected the extensions 1001-1007, got $contacts", (1001..1007).all { "$it" in numbers })
        assertEquals("王芳", contacts.first { it.number == "$AGENT" }.name)
    }

    /**
     * The LDAP library keeps the CA certificates of its first TLS connection for the rest of
     * the process, so a TLS scenario is only meaningful in a process that has not used LDAP
     * over TLS yet. testenv/run-tests.sh runs every test class in a process of its own.
     */
    protected fun assumeFreshLdapTls() =
        assumeFalse("LDAP was already used over TLS in this process", onMain { engine.directory.tlsUsed })

    /** StartTLS only works with a host name (see LdapRules), which not every device can resolve. */
    protected fun ldapName(): String {
        assumeTrue("The device has no DNS name for the LDAP server", env.ldapName != null)
        return env.ldapName!!
    }

    protected companion object {
        /** The two parts of the test directory: the staff, and people from outside. */
        const val PEOPLE = "people"
        const val PARTNERS = "partners"
        const val PARTNER = "合作伙伴甲"
    }
}

/** LDAP directories: searching one over StartTLS (trusting the imported CA) and without encryption, and several side by side. */
@RunWith(AndroidJUnit4::class)
class DirectoryTest : DirectoryTestBase() {
    @Test
    fun searchesOverStartTlsAndCallsAResult() {
        assumeFreshLdapTls()
        val ldapName = ldapName()
        onMain { engine.tls.import(env.caPem) }
        register()
        val staff = configure(ldapName, startTls = true)

        assertFindsTheStaff(search(staff, "100"))

        // An entry is listed once per number: extension first, then the mobile.
        val alice = search(staff, "Alice")
        assertEquals(listOf("1006", "13800001006"), alice.map { it.number })
        assertEquals(setOf("Alice Smith"), alice.map { it.name }.toSet())

        // One tap call to a directory entry (1004 answers and echoes).
        val target = search(staff, "$ECHO_PHONE").first { it.number == "$ECHO_PHONE" }.callTarget
        dial(target)
        awaitConnected("$ECHO_PHONE")
        awaitAppHearsAudio()
    }

    @Test
    fun searchesWithoutEncryptionAndListsWithoutASearch() {
        register() // numbers only become callable contacts once there is an account
        val staff = configure(env.ldapHost)
        assertFindsTheStaff(search(staff, "100"))
        // Nothing typed: what the directory has, as on a desk phone.
        assertFindsTheStaff(search(staff, ""))
    }

    @Test
    fun namesAndNumbersAreSearchedWithTheirOwnFilters() {
        register()
        // Names by their beginning, numbers by the beginning of the mobile number alone.
        val staff = save(directory(env.ldapHost).copy(nameFilter = "(cn=%)", numberFilter = "(mobile=%)"))
        assertEquals(setOf("Alice Smith"), search(staff, "Ali").map { it.name }.toSet())
        // The local copy is searched for anything that contains the text; here the server is to answer alone.
        onMain { engine.directory.clearCache() }
        assertTrue("A name was found by its middle", search(staff, "lice").isEmpty())
        assertEquals(setOf("Alice Smith"), search(staff, "1380000100").filter { it.number == "1006" }.map { it.name }.toSet())
        onMain { engine.directory.clearCache() }
        assertTrue("An extension was found although only mobiles are searched", search(staff, "1006").isEmpty())
    }

    @Test
    fun keepsALocalCopyThatAnswersBeforeTheServer() {
        register()
        val staff = configure(env.ldapHost)
        onMain { engine.directory.clearCache() }
        assertFindsTheStaff(search(staff, "100"))
        onMain { engine.directory.refreshCacheStats() }
        await("the local copy to hold the staff") { engine.directory.cacheStats.value.entries >= 7 }

        // Make the server slow to be asked (the delay does not touch the local copy): the entries
        // found before are on screen long before it answers, marked as coming from the copy.
        onMain { engine.directory.serverDelayMs = SLOW_SERVER_MS }
        onMain { engine.directory.search(staff.id, "Alice") }
        await("the local copy to answer first", SLOW_SERVER_MS.toLong()) {
            val state = engine.directory.state.value
            state.query == "Alice" && state.searching && state.fromCache && state.contacts.any { it.number == "1006" }
        }
        await("the answer of the server to take over") {
            val state = engine.directory.state.value
            !state.searching && !state.fromCache && state.contacts.any { it.number == "1006" }
        }

        // Emptying the copy leaves the server's results alone and reports an empty copy.
        onMain { engine.directory.clearCache() }
        await("the local copy to be empty") { engine.directory.cacheStats.value.entries == 0L }
    }

    @Test
    fun namesCallersAndCalleesFromTheDirectory() {
        register()
        val staff = configure(env.ldapHost)
        onMain { engine.directory.clearCache() }
        val echoName = search(staff, "$ECHO_PHONE").first { it.number == "$ECHO_PHONE" }.name

        // Incoming: whatever name the PBX sends along, the directory knows 1002 as 王芳. Her entry
        // is not in the local copy yet, so this one is answered by the server.
        phoneCalls(AGENT, "$APP")
        awaitCall("the caller to be named by the directory") { it.phase == CallPhase.INCOMING && it.remoteName == "王芳" }
        onMain { engine.calls.decline() }
        awaitNoCall()
        await("the caller to be released") { !phoneHasCall(AGENT) }
        await("the call history to use the name") { engine.history.entries.value.firstOrNull()?.name == "王芳" }

        // Outgoing: the name of the number that was dialled.
        dial("$ECHO_PHONE")
        awaitCall("the callee to be named by the directory") { it.phase == CallPhase.CONNECTED && it.remoteName == echoName }
        onMain { engine.calls.hangUp() }
        awaitNoCall()
        await("the PBX to release the call") { pbxChannel(pbxLeg(APP)) == null }

        // From now on the local copy answers. With a server that is only asked after seconds
        // (saving the directory also makes the app forget the names it had in memory), the name
        // is there long before the server could have said anything.
        onMain { engine.directory.serverDelayMs = SLOW_SERVER_MS }
        save(staff.copy(maxResults = staff.maxResults + 1))
        phoneCalls(AGENT, "$APP")
        awaitCall("the caller to be named from the local copy", SLOW_SERVER_MS - 1_000L) {
            it.phase == CallPhase.INCOMING && it.remoteName == "王芳"
        }
        onMain { engine.calls.decline() }
        awaitNoCall()
        await("the caller to be released") { !phoneHasCall(AGENT) }
    }

    @Test
    fun severalDirectoriesAreSearchedOneAtATime() {
        register()
        val staff = save(directory(env.ldapHost, name = "员工"))
        val partners = save(directory(env.ldapHost, unit = PARTNERS, name = "合作伙伴").copy(numberAttributes = "telephoneNumber homePhone"))
        assertEquals(listOf("员工", "合作伙伴"), engine.directory.directories.value.map { it.title })

        // Each one answers for its own part of the server only.
        assertFindsTheStaff(search(staff, "100"))
        assertEquals(setOf(PARTNER), search(partners, "100").map { it.name }.toSet())
        assertEquals(setOf("Partner Lee"), search(partners, "Partner").map { it.name }.toSet())
        assertTrue(search(staff, "Partner").isEmpty())
        // Every attribute that holds numbers is listed with the entry and searched, the mobile
        // number of this directory's other entry is neither.
        assertEquals(setOf("1002", "02155550001"), search(partners, "合作").map { it.number }.toSet())
        assertEquals(setOf(PARTNER), search(partners, "0215555").map { it.name }.toSet())
        assertEquals(listOf("3001"), search(partners, "Partner").map { it.number })
        onMain { engine.directory.clearCache() }
        assertTrue("A number was found in an attribute that is not one for numbers", search(partners, "1390000").isEmpty())

        // A directory that is switched off or removed answers nothing; the other one goes on.
        save(partners.copy(enabled = false))
        assertTrue(search(partners, "100").isEmpty())
        onMain { engine.directory.remove(partners.id) }
        assertEquals(listOf(staff.id), engine.directory.directories.value.map { it.id })
        assertFindsTheStaff(search(staff, "100"))
    }

    @Test
    fun aCallIsNamedByTheDirectoryOfItsLineBeforeTheOthers() {
        val line = register()
        // The staff directory is for every line. The other one, where 1002 is somebody else,
        // belongs to this line, and names its callers only.
        save(directory(env.ldapHost, name = "员工"))
        val partners = save(directory(env.ldapHost, unit = PARTNERS, name = "合作伙伴").copy(line = line.id, lookupOutgoing = false))
        showApp()

        fun callerIsNamed(phone: Int, name: String) {
            phoneCalls(phone, "$APP")
            awaitCall("the caller $phone to be named $name") { it.phase == CallPhase.INCOMING && it.remoteName == name }
            onMain { engine.calls.decline() }
            awaitNoCall()
            await("the caller to be released") { !phoneHasCall(phone) }
        }

        fun calleeIsNamed(name: String) {
            dial("$AGENT")
            awaitCall("the callee to be named $name") { it.remoteName == name }
            onMain { engine.calls.hangUp() }
            awaitNoCall()
            await("the PBX to release the call") { pbxChannel(pbxLeg(APP)) == null && !phoneHasCall(AGENT) }
        }

        // A caller is named by the directory of the line; one it does not know, by the
        // directory for every line. So is whoever is called, as that directory stays out of it.
        callerIsNamed(AGENT, PARTNER)
        callerIsNamed(CUSTOMER, "李娜")
        calleeIsNamed("王芳")

        // Naming the calls placed as well, the directory of the line goes first there too.
        save(partners.copy(lookupOutgoing = true))
        calleeIsNamed(PARTNER)

        // As the directory of another line it has no say about the calls of this one.
        val other = onMain {
            assertTrue(engine.accounts.add(draft(SECOND_LINE)).isEmpty())
            account(SECOND_LINE)!!
        }
        save(partners.copy(line = other.id, lookupOutgoing = true))
        callerIsNamed(AGENT, "王芳")
        calleeIsNamed("王芳")

        // Without that account it is a directory for every line again.
        onMain { engine.accounts.remove(other.id) }
        assertEquals(listOf("", ""), engine.directory.directories.value.map { it.line })
    }

    @Test
    fun holdsFiftyDirectoriesAndKeepsTheirNamesApart() {
        val first = save(directory("ldap1.example.com", name = "目录 1"))
        (2..LdapRules.MAX_DIRECTORIES).forEach { save(directory("ldap$it.example.com", name = "目录 $it")) }
        assertEquals(LdapRules.MAX_DIRECTORIES, engine.directory.directories.value.size)
        assertEquals(listOf(LdapRules.Error.LIMIT_REACHED), onMain { engine.directory.save(directory("ldap51.example.com")) })

        // A name that is taken is refused, for a new directory as for a renamed one.
        assertEquals(listOf(LdapRules.Error.NAME_TAKEN), onMain { engine.directory.save(first.copy(name = "目录 2")) })
        onMain { engine.directory.remove(engine.directory.directories.value.last().id) }
        assertEquals(listOf(LdapRules.Error.NAME_TAKEN), onMain { engine.directory.save(directory("ldap51.example.com", name = "目录 1")) })
        save(first.copy(name = "总部"))

        // What is kept is what another start of the app finds: all of them, in their order,
        // and never a password.
        val restarted = afterARestart { it.directories.value }
        assertEquals(engine.directory.directories.value, restarted)
        assertEquals("总部", restarted.first().title)
        assertEquals(LdapRules.MAX_DIRECTORIES - 1, restarted.size)
        assertFalse(restarted.any { it.password.isNotEmpty() })
    }

    @Test
    fun theDirectoryOfAnEarlierVersionIsTakenOver() {
        // Before there could be several, the one directory was kept by the SIP core itself.
        onMain {
            val params = engine.core.createLdapParams()
            params.enabled = true
            params.isTlsEnabled = false
            params.server = "ldap://old.example.com:1389"
            params.authMethod = Ldap.AuthMethod.Simple
            params.bindDn = "cn=old,dc=example,dc=com"
            params.password = "an old password"
            params.baseObject = "dc=example,dc=com"
            params.filter = "(|(cn=*%s*)(telephoneNumber=*%s*))"
            params.nameAttribute = "cn,displayName"
            params.sipAttribute = "telephoneNumber,mobile,ipPhone"
            params.maxResults = 30
            engine.core.addLdap(engine.core.createLdapWithParams(params))
        }
        val adopted = afterARestart { it.directories.value }.single()
        assertEquals("old.example.com", adopted.host)
        assertEquals(1389, adopted.port)
        assertEquals(LdapSecurity.NONE, adopted.security)
        assertEquals("cn=old,dc=example,dc=com", adopted.bindDn)
        assertEquals("", adopted.password)
        assertEquals("dc=example,dc=com", adopted.baseDn)
        assertEquals("(|(cn=*%)(telephoneNumber=*%))", adopted.nameFilter)
        assertEquals(adopted.nameFilter, adopted.numberFilter)
        assertEquals("cn displayName", adopted.nameAttributes)
        assertEquals("telephoneNumber mobile ipPhone", adopted.numberAttributes)
        assertEquals(30, adopted.maxResults)
        assertTrue(adopted.enabled)
        // It is one of the app's directories now, and stays the only one at the next start.
        assertEquals(listOf(adopted), afterARestart { it.directories.value })

        // Once the last directory is removed, nothing is left to be taken over again. (The
        // manager of the running app has not seen the take-over, so it replaces what is stored.)
        val last = save(directory("ldap.example.com"))
        onMain { engine.directory.remove(last.id) }
        assertTrue(afterARestart { it.directories.value }.isEmpty())
    }

    /** What a directory manager finds that starts on what is stored now, as after a restart of the app. */
    private fun <T> afterARestart(look: (DirectoryManager) -> T): T {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val cache = DirectoryCache(context, fileName = OTHER_CACHE)
        try {
            return onMain { look(DirectoryManager(engine.core, cache, engine.lock)) }
        } finally {
            cache.close()
            context.deleteDatabase(OTHER_CACHE)
        }
    }

    @Test
    fun startTlsToAnAddressIsRejected() {
        // The LDAP library needs a host name for the TLS handshake, so this is refused up front.
        val errors = onMain { engine.directory.save(directory(env.ldapHost, startTls = true)) }
        assertEquals(listOf(LdapRules.Error.STARTTLS_NEEDS_HOST_NAME), errors)
    }

    private companion object {
        /** An extension no scripted phone uses, for a second account of the app. */
        const val SECOND_LINE = 1006
        const val OTHER_CACHE = "itest-directory-restart.db"
    }
}

/** What happens when the CA that signed the LDAP server certificate is not trusted. */
@RunWith(AndroidJUnit4::class)
class DirectoryTrustTest : DirectoryTestBase() {
    @Test
    fun untrustedServerIsRefusedAndCaImportAsksForARestart() {
        assumeFreshLdapTls()
        val staff = configure(ldapName(), startTls = true)
        assertTrue("Results were returned over an unverified connection", search(staff, "100").isEmpty())

        // The library has now loaded its CA set for this process: the app has to say that the
        // CA imported afterwards needs a restart to reach the directory.
        onMain { engine.tls.import(env.caPem) }
        assertTrue(onMain { engine.directory.tlsChangeNeedsRestart })
    }
}

/** Delay before the server is asked in the test of the local copy, long enough to look at the copy's answer. */
private const val SLOW_SERVER_MS = 4_000
