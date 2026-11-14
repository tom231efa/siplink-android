package com.sky.siplink.core.logic

import com.sky.siplink.core.model.AccountDraft
import com.sky.siplink.core.model.SipTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountRulesTest {
    private val valid = AccountDraft(username = "1001", password = "x", domain = "pbx.example.com")

    @Test
    fun `valid draft has no errors`() {
        assertEquals(emptyList<AccountRules.Error>(), AccountRules.validate(valid, emptyList()))
    }

    @Test
    fun `limit is exactly one hundred accounts`() {
        assertEquals(100, AccountRules.MAX_ACCOUNTS)
        val ninetyNine = (1..99).map { AccountRules.identityKey("u$it", "pbx.example.com") }
        assertTrue(AccountRules.canAdd(ninetyNine.size))
        assertEquals(emptyList<AccountRules.Error>(), AccountRules.validate(valid, ninetyNine))

        val hundred = ninetyNine + AccountRules.identityKey("u100", "pbx.example.com")
        assertFalse(AccountRules.canAdd(hundred.size))
        assertTrue(AccountRules.Error.LIMIT_REACHED in AccountRules.validate(valid, hundred))
    }

    @Test
    fun `editing an existing account is allowed when the list is full`() {
        val hundred = (1..100).map { AccountRules.identityKey("u$it", "pbx.example.com") }
        val editing = AccountDraft(username = "u7", domain = "pbx.example.com")
        assertEquals(emptyList<AccountRules.Error>(), AccountRules.validate(editing, hundred, hundred[6]))
    }

    @Test
    fun `duplicate identity is rejected and host comparison ignores case`() {
        val existing = listOf(AccountRules.identityKey("1001", "PBX.Example.com"))
        assertTrue(AccountRules.Error.DUPLICATE in AccountRules.validate(valid, existing))
        // The user part stays case-sensitive.
        val other = valid.copy(username = "Alice")
        assertFalse(
            AccountRules.Error.DUPLICATE in
                AccountRules.validate(other, listOf(AccountRules.identityKey("alice", "pbx.example.com"))),
        )
    }

    @Test
    fun `renaming an account onto another existing identity is rejected`() {
        val keys = listOf(
            AccountRules.identityKey("1001", "pbx.example.com"),
            AccountRules.identityKey("1002", "pbx.example.com"),
        )
        val renamed = valid.copy(username = "1002")
        assertTrue(AccountRules.Error.DUPLICATE in AccountRules.validate(renamed, keys, keys[0]))
    }

    @Test
    fun `missing and malformed fields are reported`() {
        val errors = AccountRules.validate(AccountDraft(username = "", domain = "", expires = 5), emptyList())
        assertTrue(AccountRules.Error.USERNAME_EMPTY in errors)
        assertTrue(AccountRules.Error.DOMAIN_EMPTY in errors)
        assertTrue(AccountRules.Error.EXPIRES_OUT_OF_RANGE in errors)

        assertTrue(
            AccountRules.Error.USERNAME_INVALID in AccountRules.validate(valid.copy(username = "a b"), emptyList()),
        )
        assertTrue(
            AccountRules.Error.DOMAIN_INVALID in AccountRules.validate(valid.copy(domain = "bad_host!"), emptyList()),
        )
        assertTrue(
            AccountRules.Error.SERVER_INVALID in AccountRules.validate(valid.copy(server = "host:99999"), emptyList()),
        )
    }

    @Test
    fun `two accounts cannot have the same name`() {
        val named = valid.copy(name = "前台")
        assertEquals(emptyList<AccountRules.Error>(), AccountRules.validate(named, emptyList(), takenNames = listOf("销售", "")))
        assertEquals(listOf(AccountRules.Error.NAME_TAKEN), AccountRules.validate(named, emptyList(), takenNames = listOf("销售", "前台")))
        assertEquals(
            listOf(AccountRules.Error.NAME_TAKEN),
            AccountRules.validate(valid.copy(name = "Front"), emptyList(), takenNames = listOf("front")),
        )
        // Any number of accounts may go without a name.
        assertEquals(emptyList<AccountRules.Error>(), AccountRules.validate(valid, emptyList(), takenNames = listOf("", "")))
    }

    @Test
    fun `an account name is trimmed and limited in length`() {
        assertEquals("总机", AccountRules.normalize(valid.copy(name = "  总机 ")).name)
        assertTrue(AccountRules.validate(valid.copy(name = "x".repeat(AccountRules.MAX_NAME_LENGTH)), emptyList()).isEmpty())
        assertTrue(
            AccountRules.Error.NAME_TOO_LONG in
                AccountRules.validate(valid.copy(name = "x".repeat(AccountRules.MAX_NAME_LENGTH + 1)), emptyList()),
        )
    }

    @Test
    fun `normalize trims strips scheme and splits user at domain`() {
        val draft = AccountRules.normalize(AccountDraft(username = "  sip:alice@example.org ", domain = " "))
        assertEquals("alice", draft.username)
        assertEquals("example.org", draft.domain)

        // An explicit domain wins over the one typed into the user field.
        val explicit = AccountRules.normalize(AccountDraft(username = "bob@ignored.org", domain = "pbx.local"))
        assertEquals("bob", explicit.username)
        assertEquals("pbx.local", explicit.domain)
    }

    @Test
    fun `host and port validation`() {
        listOf("pbx.example.com", "pbx", "10.0.0.5", "10.0.0.5:5061", "pbx.example.com:5060", "[::1]", "[2001:db8::1]:5061")
            .forEach { assertTrue(it, AccountRules.isValidHostPort(it)) }
        listOf("", "pbx example", "pbx:", "pbx:0", "pbx:70000", "pbx:abc", "-pbx.com", "[::1", "[::1]5060", "a..b")
            .forEach { assertFalse(it, AccountRules.isValidHostPort(it)) }
    }

    @Test
    fun `uris carry identity proxy and transport`() {
        assertEquals("sip:1001@pbx.example.com", AccountRules.identityUri(valid))
        assertEquals("sip:pbx.example.com;transport=udp", AccountRules.serverUri(valid))
        val tls = valid.copy(server = "edge.example.com:5061", transport = SipTransport.TLS)
        assertEquals("sip:edge.example.com:5061;transport=tls", AccountRules.serverUri(tls))
    }

    @Test
    fun `transport names are parsed case-insensitively`() {
        assertEquals(SipTransport.UDP, AccountRules.parseTransport(""))
        assertEquals(SipTransport.TCP, AccountRules.parseTransport(" TCP "))
        assertEquals(SipTransport.TLS, AccountRules.parseTransport("tls"))
        assertEquals(null, AccountRules.parseTransport("sctp"))
    }
}
