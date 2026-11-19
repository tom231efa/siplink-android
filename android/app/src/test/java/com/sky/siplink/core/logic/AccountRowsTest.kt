package com.sky.siplink.core.logic

import com.sky.siplink.core.model.RegState
import com.sky.siplink.core.model.SipAccount
import com.sky.siplink.core.model.SipTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class AccountRowsTest {
    private fun account(id: String, user: String, isDefault: Boolean = false) = SipAccount(
        id = id,
        username = user,
        domain = "pbx.example.com",
        displayName = "",
        authUser = "",
        server = "",
        transport = SipTransport.UDP,
        expires = 600,
        enabled = true,
        state = RegState.NONE,
        isDefault = isDefault,
    )

    private val a = account("a", "1001", isDefault = true)
    private val b = account("b", "1002")
    private val c = account("c", "1003")

    @Test
    fun `a changed account keeps its place and the others stay as they were`() {
        val changed = b.copy(username = "2002")
        val rows = AccountRows.replace(listOf(a, b, c), "b", changed, defaultId = "a")
        assertEquals(listOf(a, changed, c), rows)
        assertSame(a, rows[0])
        assertSame(c, rows[2])
    }

    @Test
    fun `a new account comes last`() {
        val d = account("d", "1004")
        assertEquals(listOf(a, b, c, d), AccountRows.replace(listOf(a, b, c), "d", d, defaultId = "a"))
    }

    @Test
    fun `an account that went leaves the list`() {
        assertEquals(listOf(a, c), AccountRows.replace(listOf(a, b, c), "b", null, defaultId = "a"))
        assertEquals(listOf(a, b, c), AccountRows.replace(listOf(a, b, c), "x", null, defaultId = "a"))
    }

    @Test
    fun `an account whose id changed takes the place of its old row`() {
        val moved = account("sip:1002@pbx.example.com", "1002")
        val rows = AccountRows.replace(listOf(a, b, c), "b", moved, defaultId = "a")
        assertEquals(listOf("a", "sip:1002@pbx.example.com", "c"), rows.map { it.id })
    }

    @Test
    fun `only the default account is marked as the default`() {
        val rows = AccountRows.replace(listOf(a, b, c), "a", null, defaultId = "b")
        assertEquals(listOf("b" to true, "c" to false), rows.map { it.id to it.isDefault })
        val first = AccountRows.replace(emptyList(), "a", account("a", "1001"), defaultId = "a")
        assertEquals(listOf(true), first.map { it.isDefault })
        assertEquals(listOf(false, false, false), AccountRows.replace(listOf(a, b, c), "c", c, defaultId = null).map { it.isDefault })
    }
}
