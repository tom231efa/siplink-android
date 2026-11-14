package com.sky.siplink.itest

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sky.siplink.core.TlsManager
import com.sky.siplink.core.logic.AccountRules
import com.sky.siplink.core.model.RegState
import com.sky.siplink.core.model.SipTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        const val FIRST = 1001
        const val LAST = 1100
    }
}
