package com.sky.siplink.core.logic

import com.sky.siplink.core.model.SideCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AssertedPartiesTest {
    private val parties = AssertedParties()
    private val member = SideCall("成员02", "2502@pbx.example.com")
    private val other = SideCall("前台", "1000@pbx.example.com")

    private fun of(call: Long, heard: SideCall?, ringingIn: Boolean = false, released: Boolean = false) =
        parties.of(call, heard, ringingIn, released)

    @Test
    fun `a call the PBX asserted no one for is named by its own address`() {
        assertNull(of(1, null))
    }

    @Test
    fun `a message that asserts no one takes nothing back`() {
        assertEquals(member, of(1, member))
        // The BYE of the PBX, the answer to our own BYE, a 100 Trying.
        assertEquals(member, of(1, null))
        assertEquals(member, of(1, null))
    }

    @Test
    fun `another identity the PBX asserts takes the place of the one before`() {
        of(1, member)
        assertEquals(other, of(1, other))
        assertEquals(other, of(1, null))
    }

    @Test
    fun `each call keeps its own`() {
        of(1, member)
        of(2, other)
        assertEquals(member, of(1, null))
        assertEquals(other, of(2, null))
        assertNull(of(3, null))
    }

    @Test
    fun `an incoming call that still rings is named by whom it says it is from, and by the asserted identity once answered`() {
        assertNull(of(1, member, ringingIn = true))
        assertNull(of(1, null, ringingIn = true))
        assertEquals(member, of(1, null))
    }

    @Test
    fun `a released call is forgotten`() {
        of(1, member)
        parties.forget(1)
        assertNull(of(1, null))
    }

    @Test
    fun `nothing is taken in for a call that is released already`() {
        of(1, member)
        parties.forget(1)
        // Looked at once more after its release, the call is named by what its last message says, and nothing stays.
        assertEquals(other, of(1, other, released = true))
        assertNull(of(1, null))
    }
}
