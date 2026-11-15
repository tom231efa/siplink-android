package com.sky.siplink.core.logic

import com.sky.siplink.core.model.Shortcut
import com.sky.siplink.core.model.ShortcutAction
import com.sky.siplink.core.model.ShortcutIcon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortcutRulesTest {
    private fun key(
        label: String = "前台",
        number: String = "1001",
        action: ShortcutAction = ShortcutAction.SPEED_DIAL,
        category: String = "",
        line: String = "",
        id: Long = 1,
    ) = Shortcut(id, label, number, action, category, line)

    @Test
    fun `a category cannot take a name that is in use or that a list needs`() {
        val taken = listOf("销售", "Sales")
        assertNull(ShortcutRules.categoryNameError("门禁", taken))
        assertNull(ShortcutRules.categoryNameError(" 门禁 ", taken))
        assertEquals(ShortcutRules.Error.CATEGORY_EMPTY, ShortcutRules.categoryNameError("  ", taken))
        assertEquals(ShortcutRules.Error.CATEGORY_TAKEN, ShortcutRules.categoryNameError("销售", taken))
        assertEquals(ShortcutRules.Error.CATEGORY_TAKEN, ShortcutRules.categoryNameError(" sales ", taken))
        assertEquals(ShortcutRules.Error.CATEGORY_RESERVED, ShortcutRules.categoryNameError("未分类", taken))
        assertEquals(ShortcutRules.Error.CATEGORY_RESERVED, ShortcutRules.categoryNameError("全部", taken))
        assertEquals(ShortcutRules.Error.CATEGORY_RESERVED, ShortcutRules.categoryNameError("新建分类", taken))
        assertEquals(
            ShortcutRules.Error.CATEGORY_TOO_LONG,
            ShortcutRules.categoryNameError("a".repeat(ShortcutRules.MAX_CATEGORY_LENGTH + 1), taken),
        )
        // Also where no dialog asks: in an import.
        assertEquals(listOf(ShortcutRules.Error.CATEGORY_RESERVED), ShortcutRules.validate(key(category = "全部")))
    }

    @Test
    fun `a category spelled in another case joins the one there is`() {
        val existing = listOf(key(category = "Sales", id = 1), key(category = "", id = 2))
        val added = listOf(key(category = "sales", id = 3), key(category = "门禁", id = 4), key(category = "SALES", id = 5), key(id = 6))
        assertEquals(listOf("Sales", "门禁", "Sales", ""), ShortcutRules.joinCategories(added, existing).map { it.category })
        // Among new keys the first spelling counts.
        val fresh = listOf(key(category = "vip", id = 1), key(category = "VIP", id = 2))
        assertEquals(listOf("vip", "vip"), ShortcutRules.joinCategories(fresh, emptyList()).map { it.category })
    }

    @Test
    fun `the limit is two thousand keys`() {
        assertEquals(2000, ShortcutRules.MAX_SHORTCUTS)
        assertTrue(ShortcutRules.canAdd(1999))
        assertFalse(ShortcutRules.canAdd(2000))
    }

    @Test
    fun `normalize trims and names a key after its number or its type`() {
        val named = ShortcutRules.normalize(key(label = "  前台 ", number = " 1001 ", category = " 常用 ", line = " 总机 "))
        assertEquals(key(label = "前台", number = "1001", category = "常用", line = "总机"), named)
        assertEquals("*801005", ShortcutRules.normalize(key(label = " ", number = "*801005")).label)
        assertEquals("免打扰", ShortcutRules.normalize(key(label = "", number = "", action = ShortcutAction.DND)).label)
    }

    @Test
    fun `normalize drops what a type has no use for`() {
        // Do not disturb has no number; keys that never call out have no line.
        val dnd = ShortcutRules.normalize(key(number = "123", action = ShortcutAction.DND, line = "总机"))
        assertEquals("", dnd.number)
        // Kept: where the PBX holds do not disturb, the key calls its feature code on that line.
        assertEquals("总机", dnd.line)
        assertEquals("", ShortcutRules.normalize(key(number = "#9", action = ShortcutAction.DTMF, line = "总机")).line)
        assertEquals("总机", ShortcutRules.normalize(key(action = ShortcutAction.BLIND_TRANSFER, line = "总机")).line)
    }

    @Test
    fun `every type but do not disturb needs a number`() {
        for (action in ShortcutAction.entries - ShortcutAction.DND) {
            assertEquals(action.name, listOf(ShortcutRules.Error.NUMBER_EMPTY), ShortcutRules.validate(key(number = "", action = action)))
        }
        assertTrue(ShortcutRules.validate(key(number = "", action = ShortcutAction.DND)).isEmpty())
    }

    @Test
    fun `what a feature code does is a speed dial and no type of its own`() {
        assertEquals(
            listOf("speed", "bxfer", "axfer", "dtmf", "forward", "dnd"),
            ShortcutRules.typeWords,
        )
        for (gone in listOf("park", "pickup", "voicemail", "redial", "listen", "whisper", "barge")) {
            assertEquals(gone, null, ShortcutRules.parseAction(gone))
        }
    }

    @Test
    fun `feature codes and sip addresses are numbers and dtmf keys are digits star and hash`() {
        assertTrue(ShortcutRules.validate(key(number = "*801005")).isEmpty())
        assertTrue(ShortcutRules.validate(key(number = "sip:alice@example.org")).isEmpty())
        assertEquals(listOf(ShortcutRules.Error.NUMBER_INVALID), ShortcutRules.validate(key(number = "10 01")))
        assertTrue(ShortcutRules.validate(key(number = "#9*0", action = ShortcutAction.DTMF)).isEmpty())
        assertEquals(listOf(ShortcutRules.Error.DTMF_INVALID), ShortcutRules.validate(key(number = "12a", action = ShortcutAction.DTMF)))
    }

    @Test
    fun `lengths are limited`() {
        val errors = ShortcutRules.validate(
            key(
                label = "x".repeat(ShortcutRules.MAX_LABEL_LENGTH + 1),
                number = "1".repeat(ShortcutRules.MAX_NUMBER_LENGTH + 1),
                category = "c".repeat(ShortcutRules.MAX_CATEGORY_LENGTH + 1),
            ),
        )
        assertEquals(
            setOf(ShortcutRules.Error.LABEL_TOO_LONG, ShortcutRules.Error.NUMBER_TOO_LONG, ShortcutRules.Error.CATEGORY_TOO_LONG),
            errors.toSet(),
        )
    }

    @Test
    fun `search looks at name number category and type within the chosen category`() {
        val all = listOf(
            key("前台", "1001", category = "常用", id = 1),
            key("仓库对讲", "*801005", category = "对讲", id = 2),
            key("开门", "#9", ShortcutAction.DTMF, "门禁", id = 3),
            key("转总机", "1000", ShortcutAction.BLIND_TRANSFER, "常用", id = 4),
        )
        assertEquals(all, ShortcutRules.filter(all, "", null))
        assertEquals(listOf(1L, 4L), ShortcutRules.filter(all, " ", "常用").map { it.id })
        assertEquals(listOf(2L), ShortcutRules.filter(all, "*80", null).map { it.id })
        assertEquals(listOf(2L), ShortcutRules.filter(all, "对讲", null).map { it.id })
        assertEquals(listOf(4L), ShortcutRules.filter(all, "盲转", null).map { it.id })
        assertEquals(listOf(3L), ShortcutRules.filter(all, "门", null).map { it.id })
        assertEquals(emptyList<Long>(), ShortcutRules.filter(all, "开门", "常用").map { it.id })
    }

    @Test
    fun `categories keep their first appearance order and count their keys`() {
        val all = listOf(key(category = "常用", id = 1), key(category = "", id = 2), key(category = "门禁", id = 3), key(category = "常用", id = 4))
        assertEquals(listOf("常用" to 2, "" to 1, "门禁" to 1), ShortcutRules.categories(all))
    }

    @Test
    fun `types are written as words and also read by their chinese name`() {
        assertEquals(ShortcutAction.entries.size, ShortcutRules.typeWords.toSet().size)
        assertEquals(ShortcutAction.SPEED_DIAL, ShortcutRules.parseAction(""))
        assertEquals(ShortcutAction.BLIND_TRANSFER, ShortcutRules.parseAction("BXFER"))
        assertEquals(ShortcutAction.ATTENDED_TRANSFER, ShortcutRules.parseAction("咨询转"))
        assertEquals(ShortcutAction.DTMF, ShortcutRules.parseAction(" dtmf "))
        // Intercom is not a type of its own: it is a speed dial of the PBX feature code.
        assertNull(ShortcutRules.parseAction("intercom"))
    }

    @Test
    fun `csv text is read with defaults and reports bad lines`() {
        val text = listOf(
            "前台,1001",
            "# 注释",
            "\"A, 一号线\",1002,bxfer,常用,总机",
            "开门,12a,dtmf",
            "未知,1003,teleport",
            "只有名称",
            "\"没关引号,1004",
        ).joinToString("\n")
        val parsed = ShortcutRules.decode(text, firstId = 10)
        assertEquals(
            listOf(
                key("前台", "1001", id = 10),
                key("A, 一号线", "1002", ShortcutAction.BLIND_TRANSFER, "常用", "总机", id = 11),
            ),
            parsed.shortcuts,
        )
        assertEquals(listOf(4, 5, 6, 7), parsed.errors.map { it.line })
        assertEquals(0, parsed.skippedForLimit)
    }

    @Test
    fun `an icon is written as a word and read back`() {
        val parsed = ShortcutRules.decode("开门,#9,dtmf,门禁,,door\n仓库,*801005,,,,仓库\n没有图标,1001\n坏图标,1,,,,rocket")
        assertEquals(listOf(ShortcutIcon.DOOR, ShortcutIcon.WAREHOUSE, null), parsed.shortcuts.map { it.icon })
        assertEquals(listOf(4), parsed.errors.map { it.line })
        assertEquals(
            "开门,#9,dtmf,门禁,,door\n仓库,*801005,speed,,,warehouse\n没有图标,1001,speed,,,\n",
            ShortcutRules.encode(parsed.shortcuts),
        )
        assertEquals(ShortcutIcon.entries.size, ShortcutRules.iconWords.toSet().size)
    }

    @Test
    fun `reading stops at the room that is left`() {
        val text = (1..5).joinToString("\n") { "键$it,10$it" }
        val parsed = ShortcutRules.decode(text, room = 3)
        assertEquals(3, parsed.shortcuts.size)
        assertEquals(2, parsed.skippedForLimit)
        assertTrue(parsed.errors.isEmpty())
    }

    @Test
    fun `two thousand keys survive writing and reading`() {
        val all = (1..ShortcutRules.MAX_SHORTCUTS).map {
            key("键, \"$it\"", "*80$it", ShortcutAction.entries[it % ShortcutAction.entries.size], "组${it % 7}", id = it.toLong())
        }.map { it.copy(icon = ShortcutIcon.entries.getOrNull(it.id.toInt() % (ShortcutIcon.entries.size + 1))) }
            .map(ShortcutRules::normalize).map { if (it.action == ShortcutAction.DTMF) it.copy(number = "#$it".filter { c -> c.isDigit() || c == '#' }.take(8)) else it }
        val parsed = ShortcutRules.decode(ShortcutRules.encode(all))
        assertTrue(parsed.errors.toString(), parsed.errors.isEmpty())
        assertEquals(all, parsed.shortcuts)
    }

    @Test
    fun `a line matters only when the key calls out by itself`() {
        val speed = key()
        assertTrue(ShortcutRules.placesCall(speed, inCall = false, serverDnd = false))
        // During a call a speed dial becomes a consultation call on the line of that call.
        assertFalse(ShortcutRules.placesCall(speed, inCall = true, serverDnd = false))
        assertTrue(ShortcutRules.placesCall(key(action = ShortcutAction.BLIND_TRANSFER), inCall = false, serverDnd = false))
        assertFalse(ShortcutRules.placesCall(key(action = ShortcutAction.BLIND_TRANSFER), inCall = true, serverDnd = false))
        assertFalse(ShortcutRules.placesCall(key(action = ShortcutAction.ATTENDED_TRANSFER), inCall = true, serverDnd = false))
        for (action in listOf(ShortcutAction.DTMF, ShortcutAction.FORWARD)) {
            for (serverDnd in listOf(false, true)) {
                assertFalse(ShortcutRules.usesLine(action, serverDnd))
                assertFalse(ShortcutRules.placesCall(key(action = action), inCall = false, serverDnd = serverDnd))
            }
        }
    }

    @Test
    fun `a do not disturb key calls out only where the pbx keeps that switch`() {
        val dnd = key(action = ShortcutAction.DND, number = "")
        assertFalse(ShortcutRules.usesLine(ShortcutAction.DND, serverDnd = false))
        assertFalse(ShortcutRules.placesCall(dnd, inCall = false, serverDnd = false))
        assertTrue(ShortcutRules.usesLine(ShortcutAction.DND, serverDnd = true))
        assertTrue(ShortcutRules.placesCall(dnd, inCall = false, serverDnd = true))
        // The feature code is a call of its own even while another one is up: the key then says so.
        assertTrue(ShortcutRules.placesCall(dnd, inCall = true, serverDnd = true))
    }

    @Test
    fun `next to a managed configuration the user makes fifty keys of their own`() {
        assertEquals(50, ShortcutRules.MAX_OWN)
        val handedOut = List(300) { Shortcut(it + 1L, "总机$it", "1000") }
        fun own(count: Int) = List(count) { Shortcut(1000L + it, "自建$it", "2000", own = true) }
        // The keys that were handed out do not count, however many there are.
        assertTrue(ShortcutRules.canAddOwn(handedOut))
        assertTrue(ShortcutRules.canAddOwn(handedOut + own(49)))
        assertFalse(ShortcutRules.canAddOwn(handedOut + own(50)))
        assertFalse(ShortcutRules.canAddOwn(own(50)))
    }

    @Test
    fun `a category is the user's to rename when every key in it is their own`() {
        val keys = listOf(
            Shortcut(1, "前台", "1001", category = "常用"),
            Shortcut(2, "家", "1002", category = "常用", own = true),
            Shortcut(3, "车库", "1003", category = "我的", own = true),
            Shortcut(4, "门卫", "1004", category = "我的", own = true),
        )
        assertTrue(ShortcutRules.ownsCategory(keys, "我的"))
        assertFalse(ShortcutRules.ownsCategory(keys, "常用"))
    }

    @Test
    fun `whose key it is is no part of the csv format`() {
        val own = Shortcut(1, "家", "1002", category = "我的", own = true)
        assertEquals(ShortcutRules.encode(listOf(own.copy(own = false))), ShortcutRules.encode(listOf(own)))
        assertFalse(ShortcutRules.decode(ShortcutRules.encode(listOf(own))).shortcuts.single().own)
    }
}
