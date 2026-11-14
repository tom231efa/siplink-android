package com.sky.siplink.itest

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sky.siplink.core.logic.LdapRules
import com.sky.siplink.core.model.CallPhase
import com.sky.siplink.core.model.DirectoryContact
import com.sky.siplink.core.model.LdapCertCheck
import com.sky.siplink.core.model.LdapConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Shared steps of the LDAP directory tests. */
abstract class DirectoryTestBase : PbxTestBase() {
    private var shown = false

    protected fun ldapConfig(host: String, startTls: Boolean, certCheck: LdapCertCheck = LdapCertCheck.ENABLED) =
        LdapConfig(
            server = "ldap://$host",
            useStartTls = startTls,
            certCheck = certCheck,
            bindDn = env.ldapBindDn,
            password = env.ldapPassword,
            baseDn = env.ldapBaseDn,
            sipDomain = env.pbxHost,
        )

    protected fun configure(host: String, startTls: Boolean, certCheck: LdapCertCheck = LdapCertCheck.ENABLED) {
        val errors = onMain { engine.directory.save(ldapConfig(host, startTls, certCheck)) }
        assertTrue("LDAP configuration rejected: $errors", errors.isEmpty())
    }

    protected fun search(query: String): List<DirectoryContact> {
        // The directory is used with the app on screen. In the background the SIP stack only
        // runs every 500 ms and reads one LDAP message per run, so a search would time out.
        if (!shown) {
            showApp()
            shown = true
        }
        onMain { engine.directory.search(query) }
        await("the LDAP search for '$query' to finish") {
            val state = engine.directory.state.value
            state.query == query && !state.searching
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
}

/** LDAP directory search over StartTLS (trusting the imported CA) and without encryption. */
@RunWith(AndroidJUnit4::class)
class DirectoryTest : DirectoryTestBase() {
    @Test
    fun searchesOverStartTlsAndCallsAResult() {
        assumeFreshLdapTls()
        val ldapName = ldapName()
        onMain { engine.tls.import(env.caPem) }
        register()
        configure(ldapName, startTls = true)

        assertFindsTheStaff(search("100"))

        // An entry is listed once per number: extension first, then the mobile.
        val alice = search("Alice")
        assertEquals(listOf("1006", "13800001006"), alice.map { it.number })
        assertEquals(setOf("Alice Smith"), alice.map { it.name }.toSet())

        // One tap call to a directory entry (1004 answers and echoes).
        val target = search("$ECHO_PHONE").first { it.number == "$ECHO_PHONE" }.callTarget
        dial(target)
        awaitConnected("$ECHO_PHONE")
        awaitAppHearsAudio()
    }

    @Test
    fun searchesWithoutEncryption() {
        register() // numbers only become callable contacts once there is an account
        configure(env.ldapHost, startTls = false)
        assertFindsTheStaff(search("100"))
    }

    @Test
    fun keepsALocalCopyThatAnswersBeforeTheServer() {
        register()
        configure(env.ldapHost, startTls = false)
        onMain { engine.directory.clearCache() }
        assertFindsTheStaff(search("100"))
        onMain { engine.directory.refreshCacheStats() }
        await("the local copy to hold the staff") { engine.directory.cacheStats.value.entries >= 7 }

        // Make the server slow to be asked (the delay does not touch the local copy): the entries
        // found before are on screen long before it answers, marked as coming from the copy.
        val slow = ldapConfig(env.ldapHost, startTls = false).copy(delayMs = SLOW_SERVER_MS)
        assertTrue(onMain { engine.directory.save(slow) }.isEmpty())
        onMain { engine.directory.search("Alice") }
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
        configure(env.ldapHost, startTls = false)
        onMain { engine.directory.clearCache() }
        val echoName = search("$ECHO_PHONE").first { it.number == "$ECHO_PHONE" }.name

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
        // (saving the settings also makes the app forget the names it had in memory), the name
        // is there long before the server could have said anything.
        val slow = ldapConfig(env.ldapHost, startTls = false).copy(delayMs = SLOW_SERVER_MS)
        assertTrue(onMain { engine.directory.save(slow) }.isEmpty())
        phoneCalls(AGENT, "$APP")
        awaitCall("the caller to be named from the local copy", SLOW_SERVER_MS - 1_000L) {
            it.phase == CallPhase.INCOMING && it.remoteName == "王芳"
        }
        onMain { engine.calls.decline() }
        awaitNoCall()
        await("the caller to be released") { !phoneHasCall(AGENT) }
    }

    @Test
    fun startTlsToAnAddressIsRejected() {
        // The LDAP library needs a host name for the TLS handshake, so this is refused up front.
        val errors = onMain { engine.directory.save(ldapConfig(env.ldapHost, startTls = true)) }
        assertEquals(listOf(LdapRules.Error.STARTTLS_NEEDS_HOST_NAME), errors)
    }
}

/** What happens when the CA that signed the LDAP server certificate is not trusted. */
@RunWith(AndroidJUnit4::class)
class DirectoryTrustTest : DirectoryTestBase() {
    @Test
    fun untrustedServerIsRefusedAndCaImportAsksForARestart() {
        assumeFreshLdapTls()
        configure(ldapName(), startTls = true)
        assertTrue("Results were returned over an unverified connection", search("100").isEmpty())

        // The library has now loaded its CA set for this process: the app has to say that the
        // CA imported afterwards needs a restart to reach the directory.
        onMain { engine.tls.import(env.caPem) }
        assertTrue(onMain { engine.directory.tlsChangeNeedsRestart })
    }
}

/** Delay before the server is asked in the test of the local copy, long enough to look at the copy's answer. */
private const val SLOW_SERVER_MS = 4_000
