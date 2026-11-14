package com.sky.siplink.core.logic

import com.sky.siplink.core.model.SipTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountImportTest {
    @Test
    fun `parses minimal and full lines and skips comments`() {
        val text = """
            # extension list
            1001,secret1,pbx.example.com

            1002,secret2,pbx.example.com,tls,张三,edge.example.com:5061
        """.trimIndent()
        val result = AccountImport.parse(text, emptyList())
        assertEquals(emptyList<AccountImport.LineError>(), result.errors)
        assertEquals(2, result.drafts.size)

        val first = result.drafts[0]
        assertEquals("1001", first.username)
        assertEquals("secret1", first.password)
        assertEquals(SipTransport.UDP, first.transport)

        val second = result.drafts[1]
        assertEquals(SipTransport.TLS, second.transport)
        assertEquals("张三", second.displayName)
        assertEquals("edge.example.com:5061", second.server)
    }

    @Test
    fun `quoted fields keep commas quotes and spaces`() {
        val result = AccountImport.parse("1001,\"pa,ss \"\"w\"\" \",pbx.example.com", emptyList())
        assertEquals(emptyList<AccountImport.LineError>(), result.errors)
        assertEquals("pa,ss \"w\" ", result.drafts.single().password)
    }

    @Test
    fun `byte order mark at the start of the file is ignored`() {
        val result = AccountImport.parse("\uFEFF1001,secret1,pbx.example.com", emptyList())
        assertEquals(emptyList<AccountImport.LineError>(), result.errors)
        assertEquals("1001", result.drafts.single().username)
    }

    @Test
    fun `reports bad lines with their line number`() {
        val text = listOf(
            "1001,pw,pbx.example.com",
            "only,two",
            "1003,pw,pbx.example.com,sctp",
            "1004,\"unterminated,pbx.example.com",
            "bad user,pw,pbx.example.com",
        ).joinToString("\n")
        val result = AccountImport.parse(text, emptyList())
        assertEquals(1, result.drafts.size)
        assertEquals(listOf(2, 3, 4, 5), result.errors.map { it.line })
    }

    @Test
    fun `duplicates against existing accounts and within the file are rejected`() {
        val existing = listOf(AccountRules.identityKey("1001", "pbx.example.com"))
        val text = "1001,pw,pbx.example.com\n1002,pw,pbx.example.com\n1002,pw,PBX.example.com"
        val result = AccountImport.parse(text, existing)
        assertEquals(listOf("1002"), result.drafts.map { it.username })
        assertEquals(listOf(1, 3), result.errors.map { it.line })
    }

    @Test
    fun `never imports beyond the one hundred account limit`() {
        val existing = (1..97).map { AccountRules.identityKey("old$it", "pbx.example.com") }
        val text = (1..10).joinToString("\n") { "new$it,pw,pbx.example.com" }
        val result = AccountImport.parse(text, existing)
        assertEquals(3, result.drafts.size)
        assertEquals(7, result.skippedForLimit)
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun `a full file of one hundred accounts imports completely`() {
        val text = (1..100).joinToString("\n") { "${1000 + it},pw$it,pbx.example.com,tcp" }
        val result = AccountImport.parse(text, emptyList())
        assertEquals(100, result.drafts.size)
        assertEquals(0, result.skippedForLimit)
    }

    @Test
    fun `csv splitting`() {
        assertEquals(listOf("a", "b", ""), AccountImport.splitCsvLine("a, b ,"))
        assertEquals(listOf("a,b", "c"), AccountImport.splitCsvLine("\"a,b\",c"))
        assertNull(AccountImport.splitCsvLine("\"open,b"))
    }
}
