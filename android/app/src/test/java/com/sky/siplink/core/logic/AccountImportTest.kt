package com.sky.siplink.core.logic

import com.sky.siplink.core.model.SipTransport
import com.sky.siplink.zh
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
        assertEquals(emptyList<Csv.LineError>(), result.errors)
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
        assertEquals(emptyList<Csv.LineError>(), result.errors)
        assertEquals("pa,ss \"w\" ", result.drafts.single().password)
    }

    @Test
    fun `byte order mark at the start of the file is ignored`() {
        val result = AccountImport.parse("\uFEFF1001,secret1,pbx.example.com", emptyList())
        assertEquals(emptyList<Csv.LineError>(), result.errors)
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
    fun `the seventh field is the name of the account`() {
        val result = AccountImport.parse("1001,pw,pbx.example.com,udp,张三,,前台总机\n1002,pw,pbx.example.com", emptyList())
        assertEquals(emptyList<Csv.LineError>(), result.errors)
        assertEquals(listOf("前台总机", ""), result.drafts.map { it.name })
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
    fun `a name that an account or an earlier line has is rejected`() {
        val text = "1001,p,pbx.example.com,,,,前台\n1002,p,pbx.example.com,,,,销售\n1003,p,pbx.example.com,,,,销售\n1004,p,pbx.example.com"
        val result = AccountImport.parse(text, emptyList(), existingNames = listOf("前台"))
        assertEquals(listOf("1002", "1004"), result.drafts.map { it.username })
        assertEquals(listOf(1, 3), result.errors.map { it.line })
        assertEquals("账号名称已存在", zh(result.errors.first().reason))
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
        assertEquals(listOf("a", "b", ""), Csv.split("a, b ,"))
        assertEquals(listOf("a,b", "c"), Csv.split("\"a,b\",c"))
        assertNull(Csv.split("\"open,b"))
    }
}
