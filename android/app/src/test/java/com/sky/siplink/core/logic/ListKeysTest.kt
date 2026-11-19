package com.sky.siplink.core.logic

import org.junit.Assert.assertEquals
import org.junit.Test

class ListKeysTest {
    @Test
    fun `items that come twice get keys of their own`() {
        val keys = ListKeys.distinct(listOf("1002|王芳", "1003|李娜", "1002|王芳", "1002|王芳"))
        assertEquals(listOf("1002|王芳" to 0, "1003|李娜" to 0, "1002|王芳" to 1, "1002|王芳" to 2), keys)
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `a row keeps its key while the rows before it stay`() {
        val before = ListKeys.distinct(listOf("a", "b", "a"))
        val after = ListKeys.distinct(listOf("a", "b", "a", "c"))
        assertEquals(before, after.take(3))
    }
}
