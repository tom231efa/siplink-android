package com.sky.siplink.itest

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sky.siplink.core.TlsManager
import com.sky.siplink.core.logic.AccountRules
import com.sky.siplink.core.model.RegState
import com.sky.siplink.core.model.SipTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.linphone.core.Factory
import org.linphone.core.LogLevel

/** Registration over UDP, TCP and TLS (with a user imported CA), and the 100 account limit. */
@RunWith(AndroidJUnit4::class)
class RegistrationTest : PbxTestBase() {
    @Test
    fun registersOverUdpAndTcp() {
        for (transport in listOf(SipTransport.UDP, SipTransport.TCP)) {
            val account = register(APP, transport)
            await("the PBX to know the contact of $APP") { pbxHasContact(APP) }
            if (transport == SipTransport.TCP) {
                val aor = pbx.command("pjsip show aor $APP").lowercase()
                assertTrue("The PBX contact is not a TCP one:\n$aor", "transport=tcp" in aor)
            }
            onMain { engine.accounts.remove(account.id) }
            await("the PBX to forget the contact of $APP") { !pbxHasContact(APP) }
        }
    }

    @Test
    fun registersAtANameWithAPort() {
        assumeTrue("The device has no name for the PBX", env.pbxName != env.pbxHost)
        register(APP, domain = "${env.pbxName}:5060")
        await("the PBX to know the contact of $APP") { pbxHasContact(APP) }
    }

    @Test
    fun tlsNeedsTheImportedCa() {
        // The PBX certificate is signed by a private CA the device does not know. Where the
        // device can resolve it, the account uses the DNS name of the PBX; BasicCallTest covers
        // TLS to its IP address.
        addAccount(APP, SipTransport.TLS, env.pbxName)
        await("the TLS registration to fail without the CA") { account(APP)?.state == RegState.FAILED }
        never("registered over TLS without trusting the CA", 5_000) { account(APP)?.state == RegState.OK }

        val imported = onMain { engine.tls.import(env.caPem) }
        assertEquals(TlsManager.ImportResult.Added(1), imported)
        await("the TLS registration to succeed with the imported CA") { account(APP)?.state == RegState.OK }
        val aor = pbx.command("pjsip show aor $APP").lowercase()
        assertTrue("The PBX contact is not a TLS one:\n$aor", "transport=tls" in aor)

        // Importing the same file again changes nothing.
        assertEquals(TlsManager.ImportResult.AlreadyTrusted, onMain { engine.tls.import(env.caPem) })

        // Removing the CA takes the trust away again.
        onMain { engine.tls.certificates.value.forEach { engine.tls.remove(it.fingerprint) } }
        await("the TLS registration to fail once the CA is removed") { account(APP)?.state == RegState.FAILED }
    }

    @Test
    fun anAccountAddedAgainUsesItsNewPassword() {
        val first = register(APP)
        // Removed and added again at once, while the first one is still unregistering.
        onMain { engine.accounts.remove(first.id) }
        addAccount(draft(APP).copy(password = WRONG_PASSWORD))
        await("the registration with the wrong password to be refused") { account(APP)?.state == RegState.FAILED }
        never("registered with the password of the removed account", QUIET_WINDOW_MS) { account(APP)?.state == RegState.OK }
    }

    @Test
    fun accountsWithTheSameUserNameKeepTheirOwnPasswords() {
        // The same number on two servers. Both names lead to the one PBX here, so the password
        // of the other account is recognisable: it is the wrong one.
        assumeTrue("The device has no name for the PBX", env.pbxName != env.pbxHost)
        register(APP)
        addAccount(draft(APP, domain = env.pbxName).copy(password = WRONG_PASSWORD))
        await("the registration with the wrong password to be refused") { at(env.pbxName)?.state == RegState.FAILED }
        never("registered with the password of the other account", QUIET_WINDOW_MS) { at(env.pbxName)?.state == RegState.OK }
        assertEquals(RegState.OK, at(env.pbxHost)?.state)
    }

    @Test
    fun anAccountIsNotRefusedOverThePasswordOfAnotherWithItsUserName() {
        assumeTrue("The device has no name for the PBX", env.pbxName != env.pbxHost)
        addAccount(draft(APP, domain = env.pbxName).copy(password = WRONG_PASSWORD))
        await("the registration with the wrong password to be refused") { at(env.pbxName)?.state == RegState.FAILED }
        addAccount(draft(APP))
        await("the registration with the right password") { at(env.pbxHost)?.state == RegState.OK }
        // The refused account may just be trying once more: what counts is that it does not get in.
        never("registered with the password of the other account", QUIET_WINDOW_MS) { at(env.pbxName)?.state == RegState.OK }
        await("the other account to be refused again") { at(env.pbxName)?.state == RegState.FAILED }
    }

    @Test
    fun anAccountMovedToAnotherServerKeepsItsPassword() {
        // Both names lead to the one PBX here: what counts is that the account is not asked for its password.
        assumeTrue("The device has no name for the PBX", env.pbxName != env.pbxHost)
        val account = register(APP)
        assertEquals(env.sipPassword, onMain { engine.accounts.passwordOf(account.id) })

        val moved = draft(APP, domain = env.pbxName).copy(password = "")
        assertEquals(emptyList<AccountRules.Error>(), onMain { engine.accounts.update(account.id, moved) })
        // Without its password the account would be refused at once: the PBX asks for it with the first request.
        never("refused at the other name of the PBX", QUIET_WINDOW_MS) { at(env.pbxName)?.state == RegState.FAILED }
        assertEquals(RegState.OK, at(env.pbxName)?.state)
        assertEquals(env.sipPassword, onMain { engine.accounts.passwordOf(account.id) })
    }

    @Test
    fun anAccountWithOnlyADigestSavedNeedsItsPasswordToMove() {
        assumeTrue("The device has no name for the PBX", env.pbxName != env.pbxHost)
        // What earlier versions saved: once registered, the core kept a digest in place of the password.
        onMain { engine.core.config.setInt("sip", "store_ha1_passwd", 1) }
        try {
            val account = register(APP)
            await("the core to keep a digest only") { onMain { engine.accounts.passwordOf(account.id) }.isEmpty() }

            val moved = draft(APP, domain = env.pbxName)
            val errors = onMain { engine.accounts.update(account.id, moved.copy(password = "")) }
            assertEquals(listOf(AccountRules.Error.PASSWORD_REQUIRED), errors)
            assertEquals(emptyList<AccountRules.Error>(), onMain { engine.accounts.update(account.id, moved) })
            await("the registration at the other name of the PBX") { at(env.pbxName)?.state == RegState.OK }
        } finally {
            onMain { engine.core.config.setInt("sip", "store_ha1_passwd", 0) }
        }
    }

    @Test
    fun aPasswordGivenWithAnotherPortIsTheSavedOneAtOnce() {
        // An account as an earlier version saved it: a digest in place of the password.
        onMain { engine.core.config.setInt("sip", "store_ha1_passwd", 1) }
        val account = try {
            register(APP).also { await("the core to keep a digest only") { onMain { engine.accounts.passwordOf(it.id) }.isEmpty() } }
        } finally {
            onMain { engine.core.config.setInt("sip", "store_ha1_passwd", 0) }
        }
        // On another port the host and the user name are the same: the digest must not stay next to the password.
        val moved = draft(APP, domain = "${env.pbxHost}:5060")
        assertEquals(emptyList<AccountRules.Error>(), onMain { engine.accounts.update(account.id, moved) })
        assertEquals(env.sipPassword, onMain { engine.accounts.passwordOf(account.id) })
        never("refused on the other port", QUIET_WINDOW_MS) { account(APP)?.state == RegState.FAILED }
        assertEquals(RegState.OK, account(APP)?.state)
        assertEquals(env.sipPassword, onMain { engine.accounts.passwordOf(account.id) })
    }

    /** The account of [APP] at [domain]. */
    private fun at(domain: String) = accounts().firstOrNull { it.domain == domain }

    @Test
    fun registersOneHundredAccountsAndRefusesOneMore() {
        // The debug build logs every SIP message. Without hardware acceleration that alone
        // makes the emulator too slow for the burst of 100 registrations, so it is turned down.
        val logging = Factory.instance().loggingService
        onMain { logging.setLogLevel(LogLevel.Warning) }
        try {
            val drafts = (FIRST..LAST).map { draft(it) }
            assertEquals(AccountRules.MAX_ACCOUNTS, onMain { engine.accounts.addAll(drafts) })
            await("all ${AccountRules.MAX_ACCOUNTS} accounts to register", LONG_TIMEOUT_MS) {
                accounts().count { it.state == RegState.OK } == AccountRules.MAX_ACCOUNTS
            }
        } finally {
            onMain { logging.setLogLevel(LogLevel.Message) }
        }

        val errors = onMain { engine.accounts.add(draft(LAST + 1)) }
        assertEquals(listOf(AccountRules.Error.LIMIT_REACHED), errors)
        assertEquals(AccountRules.MAX_ACCOUNTS, accounts().size)
        assertTrue("The PBX does not see every account", (FIRST..LAST).all(::pbxHasContact))

        // Calls still work with that many accounts registered.
        dial("2000")
        awaitConnected("2000")
        awaitAppHearsAudio()
    }

    private companion object {
        const val WRONG_PASSWORD = "not the password"
        const val FIRST = 1001
        const val LAST = 1100
    }
}
