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
    fun `a backup server is a second host that differs from the first`() {
        fun errors(draft: AccountDraft) = AccountRules.validate(AccountRules.normalize(draft), emptyList())
        assertEquals(emptyList<AccountRules.Error>(), errors(valid.copy(backupServer = " pbx2.example.com:5080 ")))
        assertEquals(listOf(AccountRules.Error.BACKUP_INVALID), errors(valid.copy(backupServer = "pbx2 example")))
        // Without a server of its own the account is on its domain.
        assertEquals(listOf(AccountRules.Error.BACKUP_SAME), errors(valid.copy(backupServer = "PBX.example.com")))
        assertEquals(
            listOf(AccountRules.Error.BACKUP_SAME),
            errors(valid.copy(server = "sbc.example.com", backupServer = "sbc.example.com")),
        )
        assertEquals(emptyList<AccountRules.Error>(), errors(valid.copy(server = "sbc.example.com", backupServer = "pbx.example.com")))
    }

    @Test
    fun `a dns server is given by its address`() {
        fun errors(dns: String) = AccountRules.validate(AccountRules.normalize(valid.copy(dnsServer = dns)), emptyList())
        assertEquals(emptyList<AccountRules.Error>(), errors(" 192.0.2.53 "))
        assertEquals(emptyList<AccountRules.Error>(), errors("192.0.2.53:5300"))
        assertEquals(listOf(AccountRules.Error.DNS_INVALID), errors("dns.example.com"))
        assertEquals(listOf(AccountRules.Error.DNS_INVALID), errors("192.0.2.53:99999"))
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
    fun `a port typed behind the domain goes to the port field`() {
        val typed = AccountRules.normalize(AccountDraft(username = "1001", domain = " Pbx.Example.com:5090 "))
        assertEquals("Pbx.Example.com", typed.domain)
        assertEquals("5090", typed.port)
        assertEquals("Pbx.Example.com:5090", typed.address)

        // It is the port that is meant, whatever the port field shows; a Chinese keyboard types a wide colon.
        val wide = AccountRules.normalize(AccountDraft(username = "1001", domain = "pbx.example.com\uFF1A5090", port = "5060"))
        assertEquals("pbx.example.com", wide.domain)
        assertEquals("5090", wide.port)

        val address = AccountRules.normalize(AccountDraft(username = "1001", domain = "[2001:db8::1]:5061"))
        assertEquals("[2001:db8::1]", address.domain)
        assertEquals("5061", address.port)

        // Without a port in the domain the port field stands; a bare IPv6 address has no port in it.
        val apart = AccountRules.normalize(AccountDraft(username = "1001", domain = "[2001:db8::1]", port = " 5070 "))
        assertEquals("[2001:db8::1]", apart.domain)
        assertEquals("5070", apart.port)
        assertEquals("", AccountRules.normalize(valid).port)
    }

    @Test
    fun `a port is a number from 1 to 65535`() {
        listOf("1", "5060", "65535").forEach {
            assertEquals(it, emptyList<AccountRules.Error>(), AccountRules.validate(valid.copy(port = it), emptyList()))
        }
        listOf("0", "65536", "123456", "50a", "+5060", "-1", "5 060").forEach {
            assertEquals(it, listOf(AccountRules.Error.PORT_INVALID), AccountRules.validate(valid.copy(port = it), emptyList()))
        }
        // What is typed behind the domain is checked as a port too.
        val typed = AccountRules.normalize(valid.copy(domain = "pbx.example.com:0"))
        assertEquals(listOf(AccountRules.Error.PORT_INVALID), AccountRules.validate(typed, emptyList()))
    }

    @Test
    fun `the port is part of the address of the account`() {
        val account = valid.copy(port = "5090")
        assertEquals("sip:1001@pbx.example.com:5090", AccountRules.identityUri(account))
        assertEquals("sip:pbx.example.com:5090;transport=udp", AccountRules.serverUri(account))
        // A proxy of its own is where the account registers, on the port given with it.
        assertEquals("sip:edge.example.com;transport=udp", AccountRules.serverUri(account.copy(server = "edge.example.com")))

        // The same user on another port of the host is another account; on the same port it is the same.
        val existing = listOf(AccountRules.identityKey("1001", "pbx.example.com"))
        assertEquals(emptyList<AccountRules.Error>(), AccountRules.validate(account, existing))
        val taken = listOf(AccountRules.identityKey("1001", "PBX.example.com:5090"))
        assertEquals(listOf(AccountRules.Error.DUPLICATE), AccountRules.validate(account, taken))
        // The backup server must differ from the server the account is on, port included.
        assertTrue(AccountRules.Error.BACKUP_SAME in AccountRules.validate(account.copy(backupServer = "pbx.example.com:5090"), emptyList()))
        assertFalse(AccountRules.Error.BACKUP_SAME in AccountRules.validate(account.copy(backupServer = "pbx.example.com"), emptyList()))
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
