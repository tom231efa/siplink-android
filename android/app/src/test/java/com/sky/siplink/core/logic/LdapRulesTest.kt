package com.sky.siplink.core.logic

import com.sky.siplink.core.model.LdapDirectory
import com.sky.siplink.core.model.LdapSecurity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LdapRulesTest {
    private val valid = LdapDirectory(host = "ldap.example.com", baseDn = "dc=example,dc=com")

    private fun errors(directory: LdapDirectory, otherNames: List<String> = emptyList(), isNew: Boolean = true) =
        LdapRules.validate(LdapRules.normalize(directory), otherNames, isNew)

    @Test
    fun `defaults plus server and base are valid`() {
        assertEquals(emptyList<LdapRules.Error>(), errors(valid))
    }

    @Test
    fun `normalize takes a typed address apart and strips noise`() {
        val directory = LdapRules.normalize(
            LdapDirectory(
                name = " 总部 ",
                host = " ldap://ldap.example.com:1389/ ",
                baseDn = " dc=example,dc=com ",
                nameAttributes = "cn, sn  displayName",
                numberAttributes = " telephoneNumber,mobile  telephoneNumber ",
                nameFilter = " (cn=%) ",
            ),
        )
        assertEquals("总部", directory.name)
        assertEquals("ldap.example.com", directory.host)
        assertEquals(1389, directory.port)
        assertEquals("dc=example,dc=com", directory.baseDn)
        assertEquals("cn sn displayName", directory.nameAttributes)
        // Naming an attribute twice does not ask for it twice.
        assertEquals("telephoneNumber mobile", directory.numberAttributes)
        assertEquals("(cn=%)", directory.nameFilter)
        // A host alone keeps the port it has.
        assertEquals(1636, LdapRules.normalize(valid.copy(port = 1636)).port)
        assertEquals(LdapDirectory.DEFAULT_PORT, LdapRules.normalize(valid).port)
    }

    @Test
    fun `ldaps is rejected in favour of StartTLS`() {
        assertEquals(listOf(LdapRules.Error.HOST_LDAPS), errors(valid.copy(host = "ldaps://ldap.example.com")))
    }

    @Test
    fun `the server has to be a host and the port a port`() {
        assertEquals(listOf(LdapRules.Error.HOST_EMPTY), errors(valid.copy(host = " ")))
        assertEquals(listOf(LdapRules.Error.HOST_INVALID), errors(valid.copy(host = "ldap example")))
        assertEquals(listOf(LdapRules.Error.HOST_INVALID), errors(valid.copy(host = "http://ldap.example.com")))
        assertEquals(listOf(LdapRules.Error.HOST_INVALID), errors(valid.copy(host = "ldap.example.com:ldap")))
        assertEquals(listOf(LdapRules.Error.PORT_OUT_OF_RANGE), errors(valid.copy(port = 0)))
        assertEquals(listOf(LdapRules.Error.PORT_OUT_OF_RANGE), errors(valid.copy(host = "ldap.example.com:70000")))
        assertEquals(emptyList<LdapRules.Error>(), errors(valid.copy(host = "192.0.2.10")))
    }

    @Test
    fun `StartTLS needs a host name`() {
        val tls = valid.copy(security = LdapSecurity.START_TLS)
        assertEquals(emptyList<LdapRules.Error>(), errors(tls))
        assertEquals(listOf(LdapRules.Error.STARTTLS_NEEDS_HOST_NAME), errors(tls.copy(host = "192.0.2.10")))
    }

    @Test
    fun `what has to be filled in is asked for`() {
        assertEquals(listOf(LdapRules.Error.BASE_DN_EMPTY), errors(valid.copy(baseDn = "")))
        assertEquals(listOf(LdapRules.Error.NAME_ATTRIBUTES_EMPTY), errors(valid.copy(nameAttributes = " ")))
        assertEquals(listOf(LdapRules.Error.NUMBER_ATTRIBUTES_EMPTY), errors(valid.copy(numberAttributes = " , ")))
        assertEquals(emptyList<LdapRules.Error>(), errors(valid.copy(numberAttributes = "ipPhone")))
        // A filter may be left to the attributes, but one that is written has to place the search.
        assertEquals(listOf(LdapRules.Error.NAME_FILTER_NO_PLACEHOLDER), errors(valid.copy(nameFilter = "(cn=*)")))
        assertEquals(listOf(LdapRules.Error.NUMBER_FILTER_NO_PLACEHOLDER), errors(valid.copy(numberFilter = "(mobile=*)")))
        assertEquals(emptyList<LdapRules.Error>(), errors(valid.copy(nameFilter = "(cn=%)", numberFilter = "(mobile=%)")))
        assertEquals(listOf(LdapRules.Error.PASSWORD_WITHOUT_USER), errors(valid.copy(password = "secret")))
        assertEquals(listOf(LdapRules.Error.MAX_RESULTS_OUT_OF_RANGE), errors(valid.copy(maxResults = 0)))
        assertEquals(listOf(LdapRules.Error.MAX_RESULTS_OUT_OF_RANGE), errors(valid.copy(maxResults = LdapRules.MAX_RESULTS + 1)))
    }

    @Test
    fun `two directories cannot share a name`() {
        assertEquals(listOf(LdapRules.Error.NAME_TAKEN), errors(valid.copy(name = "总部"), listOf("分公司", "总部")))
        assertEquals(listOf(LdapRules.Error.NAME_TAKEN), errors(valid.copy(name = "HQ"), listOf("hq")))
        // Without a name a directory goes by its server, and several may do so.
        assertEquals(emptyList<LdapRules.Error>(), errors(valid, listOf("", "总部")))
        assertEquals(listOf(LdapRules.Error.NAME_TOO_LONG), errors(valid.copy(name = "长".repeat(LdapRules.MAX_NAME_LENGTH + 1))))
    }

    @Test
    fun `there are fifty directories at most`() {
        val full = List(LdapRules.MAX_DIRECTORIES) { "" }
        assertEquals(listOf(LdapRules.Error.LIMIT_REACHED), errors(valid, full))
        assertEquals(emptyList<LdapRules.Error>(), errors(valid, full.drop(1)))
        // Changing one of the fifty is not adding one.
        assertEquals(emptyList<LdapRules.Error>(), errors(valid, full.drop(1), isNew = false))
        assertEquals(50, LdapRules.MAX_DIRECTORIES)
    }

    @Test
    fun `the placeholder of a filter is the beginning of what is searched`() {
        assertEquals("(|(cn=%s*)(sn=%s*))", LdapRules.coreFilter("(|(cn=%)(sn=%))"))
        assertEquals("(telephoneNumber=*%s*)", LdapRules.coreFilter("(telephoneNumber=*%)"))
        // Written out, the star is not doubled.
        assertEquals("(cn=%s*)", LdapRules.coreFilter("(cn=%*)"))
        assertEquals("(&(objectClass=person)(cn=%s*))", LdapRules.coreFilter("(&(objectClass=person)(cn=%))"))
        // A filter in the notation of the SIP core is left alone.
        assertEquals("(cn=*%s*)", LdapRules.coreFilter("(cn=*%s*)"))
        assertEquals("(uid=%s)", LdapRules.coreFilter("(uid=%s)"))
    }

    @Test
    fun `a filter of the SIP core becomes one with the placeholder`() {
        assertEquals("(|(cn=*%)(mobile=*%))", LdapRules.fromCoreFilter("(|(cn=*%s*)(mobile=*%s*))"))
        assertEquals("(uid=%)", LdapRules.fromCoreFilter("(uid=%s)"))
        assertEquals("(|(cn=*%s*)(mobile=*%s*))", LdapRules.coreFilter(LdapRules.fromCoreFilter("(|(cn=*%s*)(mobile=*%s*))")))
    }

    @Test
    fun `attributes are told apart by blanks or commas`() {
        assertEquals(listOf("cn", "sn", "displayName"), LdapRules.attributes(" cn sn,displayName "))
        assertEquals(listOf("sn+givenName", "cn"), LdapRules.attributes("sn+givenName, cn"))
        assertEquals(emptyList<String>(), LdapRules.attributes("  "))
    }

    @Test
    fun `a filter that is not written is made from the attributes`() {
        // Whatever contains the search, in any of the attributes.
        assertEquals("(|(cn=*%)(sn=*%))", LdapRules.nameFilter(valid))
        assertEquals("(|(telephoneNumber=*%)(mobile=*%))", LdapRules.numberFilter(valid))
        assertEquals("(cn=*%)", LdapRules.nameFilter(valid.copy(nameAttributes = "cn")))
        assertEquals("(|(telephoneNumber=*%)(ipPhone=*%)(homePhone=*%))", LdapRules.numberFilter(valid.copy(numberAttributes = "telephoneNumber ipPhone homePhone")))
        // A name put together from two attributes is searched in both.
        assertEquals("(|(sn=*%)(givenName=*%)(cn=*%))", LdapRules.nameFilter(valid.copy(nameAttributes = "sn+givenName cn sn")))
        // A filter that is written is the one used.
        assertEquals("(uid=%)", LdapRules.nameFilter(valid.copy(nameFilter = "(uid=%)")))
        assertEquals("(mobile=%)", LdapRules.numberFilter(valid.copy(numberFilter = "(mobile=%)")))
        assertEquals("(|(cn=*%s*)(sn=*%s*))", LdapRules.coreFilter(LdapRules.nameFilter(valid)))
    }

    @Test
    fun `the address of the server is written for the SIP core`() {
        assertEquals("ldap://ldap.example.com:389", LdapRules.serverUrl(valid))
        assertEquals("ldap://192.0.2.10:1389", LdapRules.serverUrl(valid.copy(host = "192.0.2.10", port = 1389)))
        assertEquals("ldap://[2001:db8::1]:389", LdapRules.serverUrl(valid.copy(host = "2001:db8::1")))
        assertEquals("ldap://[2001:db8::1]:389", LdapRules.serverUrl(valid.copy(host = "[2001:db8::1]")))
    }

    @Test
    fun `the server is typed as a host with the port only when it is not the usual one`() {
        assertEquals("ldap.example.com", LdapRules.serverText(valid))
        assertEquals("ldap.example.com:1389", LdapRules.serverText(valid.copy(port = 1389)))
        // What is typed that way comes apart again.
        val typed = LdapRules.normalize(LdapDirectory(host = LdapRules.serverText(valid.copy(port = 1389))))
        assertEquals("ldap.example.com" to 1389, typed.host to typed.port)
    }

    @Test
    fun `a search made of digits is one for a number`() {
        assertTrue(LdapRules.isNumberQuery("1002"))
        assertTrue(LdapRules.isNumberQuery(" +86 138-0000 1006 "))
        assertTrue(LdapRules.isNumberQuery("*80"))
        assertFalse(LdapRules.isNumberQuery("王芳"))
        assertFalse(LdapRules.isNumberQuery("alice2"))
        assertFalse(LdapRules.isNumberQuery(""))
        assertFalse(LdapRules.isNumberQuery("+-"))
    }

    @Test
    fun `a call is named by the directories for its direction and its line`() {
        val all = valid.copy(id = "all")
        val incomingOnly = valid.copy(id = "in", lookupOutgoing = false)
        val outgoingOnly = valid.copy(id = "out", lookupIncoming = false)
        val ofLineOne = valid.copy(id = "line1", line = "account-1")
        val off = valid.copy(id = "off", enabled = false, line = "account-1")
        val directories = listOf(all, incomingOnly, outgoingOnly, ofLineOne, off)

        fun ids(incoming: Boolean, line: String?) = LdapRules.namingDirectories(directories, incoming, line).map { it.id }

        // The directory of the line goes first, then the ones for every line, in their order.
        assertEquals(listOf("line1", "all", "in"), ids(incoming = true, line = "account-1"))
        assertEquals(listOf("line1", "all", "out"), ids(incoming = false, line = "account-1"))
        // The directory of another line is left out.
        assertEquals(listOf("all", "in"), ids(incoming = true, line = "account-2"))
        assertEquals(listOf("all", "out"), ids(incoming = false, line = "account-2"))
        // So it is when the line of the call is not known.
        assertEquals(listOf("all", "out"), ids(incoming = false, line = null))
    }
}
