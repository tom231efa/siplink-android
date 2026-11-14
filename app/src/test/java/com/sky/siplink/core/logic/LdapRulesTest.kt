package com.sky.siplink.core.logic

import com.sky.siplink.core.model.LdapConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LdapRulesTest {
    private val valid = LdapConfig(server = "ldap://ldap.example.com", baseDn = "dc=example,dc=com")

    @Test
    fun `defaults plus server and base are valid`() {
        assertEquals(emptyList<LdapRules.Error>(), LdapRules.validate(valid))
    }

    @Test
    fun `normalize adds the scheme and strips noise`() {
        val config = LdapRules.normalize(
            LdapConfig(server = " ldap.example.com:389/ ", baseDn = " dc=example,dc=com ", sipAttributes = "mobile, telephoneNumber"),
        )
        assertEquals("ldap://ldap.example.com:389", config.server)
        assertEquals("dc=example,dc=com", config.baseDn)
        assertEquals("mobile,telephoneNumber", config.sipAttributes)
    }

    @Test
    fun `ldaps is rejected in favour of StartTLS`() {
        assertEquals(listOf(LdapRules.Error.SERVER_LDAPS), LdapRules.validate(valid.copy(server = "ldaps://ldap.example.com")))
    }

    @Test
    fun `server problems`() {
        assertTrue(LdapRules.Error.SERVER_EMPTY in LdapRules.validate(valid.copy(server = "")))
        assertTrue(LdapRules.Error.SERVER_INVALID in LdapRules.validate(valid.copy(server = "http://ldap.example.com")))
        assertTrue(LdapRules.Error.SERVER_INVALID in LdapRules.validate(valid.copy(server = "ldap://bad host")))
    }

    @Test
    fun `StartTLS needs a host name`() {
        val expected = listOf(LdapRules.Error.STARTTLS_NEEDS_HOST_NAME)
        assertEquals(expected, LdapRules.validate(valid.copy(server = "ldap://192.0.2.10", useStartTls = true)))
        assertEquals(expected, LdapRules.validate(valid.copy(server = "ldap://192.0.2.10:389", useStartTls = true)))
        assertEquals(expected, LdapRules.validate(valid.copy(server = "ldap://[2001:db8::1]", useStartTls = true)))
        // Without encryption an address is fine, and so is a name with encryption.
        assertEquals(emptyList<LdapRules.Error>(), LdapRules.validate(valid.copy(server = "ldap://192.0.2.10", useStartTls = false)))
        assertEquals(emptyList<LdapRules.Error>(), LdapRules.validate(valid.copy(server = "ldap://ldap.example.com", useStartTls = true)))
    }

    @Test
    fun `filter must contain the placeholder`() {
        assertTrue(LdapRules.Error.FILTER_NO_PLACEHOLDER in LdapRules.validate(valid.copy(filter = "(cn=*)")))
        assertTrue(LdapRules.Error.FILTER_EMPTY in LdapRules.validate(valid.copy(filter = "")))
    }

    @Test
    fun `password without bind dn and out of range numbers`() {
        assertTrue(LdapRules.Error.PASSWORD_WITHOUT_BIND_DN in LdapRules.validate(valid.copy(password = "x")))
        assertTrue(LdapRules.Error.NUMBER_OUT_OF_RANGE in LdapRules.validate(valid.copy(timeoutSec = 0)))
        assertTrue(LdapRules.Error.NUMBER_OUT_OF_RANGE in LdapRules.validate(valid.copy(maxResults = -1)))
        assertTrue(LdapRules.Error.BASE_DN_EMPTY in LdapRules.validate(valid.copy(baseDn = "")))
    }
}
