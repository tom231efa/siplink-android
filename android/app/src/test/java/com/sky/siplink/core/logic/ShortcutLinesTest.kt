package com.sky.siplink.core.logic

import com.sky.siplink.core.model.RegState
import com.sky.siplink.core.model.Shortcut
import com.sky.siplink.core.model.SipAccount
import com.sky.siplink.core.model.SipTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ShortcutLinesTest {
    private fun account(id: String, user: String, name: String = "", enabled: Boolean = true) = SipAccount(
        id = id,
        username = user,
        domain = "pbx.example.com",
        displayName = "",
        authUser = "",
        server = "",
        transport = SipTransport.UDP,
        expires = 600,
        enabled = enabled,
        state = RegState.NONE,
        isDefault = false,
        name = name,
    )

    private fun key(id: Long, line: String) = Shortcut(id = id, label = "key$id", number = "1000", line = line)

    private val office = account("a", "1001", name = "办公室")
    private val desk = account("b", "1002")

    @Test
    fun `a line is the name of the account or its address when it has none`() {
        assertEquals("办公室", ShortcutLines.of(office))
        assertEquals("1002@pbx.example.com", ShortcutLines.of(desk))
    }

    @Test
    fun `a line finds its account by name or by address`() {
        val accounts = listOf(office, desk)
        assertSame(office, ShortcutLines.resolve("办公室", accounts))
        assertSame(office, ShortcutLines.resolve("1001@PBX.example.com", accounts))
        assertSame(desk, ShortcutLines.resolve("1002@pbx.example.com", accounts))
        assertNull(ShortcutLines.resolve("仓库", accounts))
        assertNull(ShortcutLines.resolve("", accounts))
    }

    @Test
    fun `of two accounts with one name the one switched on is meant`() {
        val off = account("c", "1003", name = "办公室", enabled = false)
        assertSame(office, ShortcutLines.resolve("办公室", listOf(off, office)))
        assertSame(off, ShortcutLines.resolve("办公室", listOf(off)))
    }

    @Test
    fun `counts the keys fixed to an account`() {
        val keys = listOf(key(1, "办公室"), key(2, "1001@pbx.example.com"), key(3, ""), key(4, "1002@pbx.example.com"))
        assertEquals(2, ShortcutLines.fixedTo(office, keys))
        assertEquals(1, ShortcutLines.fixedTo(desk, keys))
    }

    @Test
    fun `keys of a removed account ask for their line again`() {
        val keys = listOf(key(1, "办公室"), key(2, ""), key(3, "1002@pbx.example.com"))
        val followed = ShortcutLines.follow(keys, before = listOf(office, desk), after = listOf(desk))
        assertEquals(listOf("", "", "1002@pbx.example.com"), followed.map { it.line })
    }

    @Test
    fun `keys follow an account that was renamed or moved`() {
        val keys = listOf(key(1, "办公室"), key(2, "1002@pbx.example.com"))
        val renamed = office.copy(name = "总部")
        val moved = desk.copy(username = "2002")
        val followed = ShortcutLines.follow(keys, before = listOf(office, desk), after = listOf(renamed, moved))
        assertEquals(listOf("总部", "2002@pbx.example.com"), followed.map { it.line })
    }

    @Test
    fun `a key stays with its line while any account still answers to it`() {
        // The key named the address; giving the account a name does not break that.
        val keys = listOf(key(1, "1002@pbx.example.com"), key(2, "办公室"))
        val named = desk.copy(name = "前台")
        assertSame(keys, ShortcutLines.follow(keys, before = listOf(office, desk), after = listOf(office, named)))
        // Another account of the same name takes over when the first one goes.
        val twin = account("c", "1003", name = "办公室")
        assertSame(keys, ShortcutLines.follow(keys, before = listOf(office, desk, twin), after = listOf(desk, twin)))
    }

    @Test
    fun `a line that never was an account is left alone`() {
        val keys = listOf(key(1, "分公司"))
        assertSame(keys, ShortcutLines.follow(keys, before = listOf(office), after = emptyList()))
    }

    @Test
    fun `nothing changes when no key is affected`() {
        val keys = listOf(key(1, ""), key(2, "1002@pbx.example.com"))
        assertSame(keys, ShortcutLines.follow(keys, before = listOf(office, desk), after = listOf(desk)))
    }
}
