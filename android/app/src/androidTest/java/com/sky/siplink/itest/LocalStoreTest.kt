package com.sky.siplink.itest

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sky.siplink.SipLinkApp
import com.sky.siplink.core.DirectoryCache
import com.sky.siplink.core.ShortcutManager
import com.sky.siplink.core.logic.ShortcutRules
import com.sky.siplink.core.model.DirectoryContact
import com.sky.siplink.core.model.Shortcut
import com.sky.siplink.core.model.ShortcutAction
import com.sky.siplink.core.model.ShortcutIcon
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
private val context: Context get() = instrumentation.targetContext

private fun <T> onMain(block: () -> T): T {
    var result: Result<T>? = null
    instrumentation.runOnMainSync { result = runCatching(block) }
    return result!!.getOrThrow()
}

private fun await(what: String, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + 20_000
    while (!condition()) {
        if (System.currentTimeMillis() > deadline) throw AssertionError("Timed out waiting for $what")
        Thread.sleep(50)
    }
}

/** The shortcut keys on disk: they come back after a restart and never exceed their limit. Needs no PBX. */
@RunWith(AndroidJUnit4::class)
class ShortcutStoreTest {
    private val file get() = File(context.cacheDir, "itest-shortcuts.csv")

    @Before
    @After
    fun removeFile() {
        file.delete()
    }

    /** A manager on the test file, as the app creates one at start. */
    private fun open(): ShortcutManager {
        val manager = onMain { ShortcutManager(file, (context.applicationContext as SipLinkApp).engine.lock, (context.applicationContext as SipLinkApp).engine.texts) }
        await("the shortcut keys to be read") { manager.loaded.value }
        return manager
    }

    private fun awaitStored(count: Int) =
        await("$count keys in the file") { file.isFile && file.readLines().count { it.isNotBlank() } == count }

    private fun keys(manager: ShortcutManager) = manager.shortcuts.value.map { it.copy(id = 0) }

    @Test
    fun keysSurviveARestart() {
        val first = open()
        assertTrue(onMain { first.add(Shortcut(0, "前台", "1001", category = "常用")) }.isEmpty())
        assertTrue(onMain { first.add(Shortcut(0, "对讲, 仓库", "*801005", category = "对讲", line = "总机")) }.isEmpty())
        assertTrue(onMain { first.add(Shortcut(0, "开门", "#9", ShortcutAction.DTMF, "门禁", icon = ShortcutIcon.DOOR)) }.isEmpty())
        assertEquals(listOf(ShortcutRules.Error.NUMBER_EMPTY), onMain { first.add(Shortcut(0, "没有号码", "")) })
        assertEquals(3, first.shortcuts.value.map { it.id }.toSet().size)
        awaitStored(3)

        val second = open()
        assertEquals(keys(first), keys(second))

        // Changing, regrouping and removing are stored as well.
        val door = second.shortcuts.value.first { it.action == ShortcutAction.DTMF }
        assertTrue(onMain { second.update(door.copy(number = "#99")) }.isEmpty())
        onMain { second.renameCategory("常用", "总机") }
        onMain { second.remove(listOf(second.shortcuts.value.first { it.category == "对讲" }.id)) }
        awaitStored(2)
        await("the changed keys in the file") { "#99" in file.readText() && "总机" in file.readText() }

        val third = open()
        assertEquals(
            listOf(
                Shortcut(0, "前台", "1001", category = "总机"),
                Shortcut(0, "开门", "#99", ShortcutAction.DTMF, "门禁", icon = ShortcutIcon.DOOR),
            ),
            keys(third),
        )
    }

    @Test
    fun keysKeepTheOrderTheyWereMovedInto() {
        val first = open()
        onMain { listOf("一", "二", "三", "四").forEachIndexed { index, label -> assertTrue(first.add(Shortcut(0, label, "${1001 + index}")).isEmpty()) } }
        fun labels(manager: ShortcutManager) = manager.shortcuts.value.map { it.label }
        val ids = first.shortcuts.value.map { it.id }
        assertTrue(onMain { first.move(ids[3], ids[0]) })
        assertTrue(onMain { first.move(ids[1], ids[2]) })
        assertFalse(onMain { first.move(ids[2], ids[2]) })
        assertEquals(listOf("四", "一", "三", "二"), labels(first))
        await("the new order in the file") { file.isFile && file.readLines().map { it.substringBefore(',') } == listOf("四", "一", "三", "二") }

        val second = open()
        assertEquals(keys(first), keys(second))
    }

    @Test
    fun neverHoldsMoreThanTwoThousandKeys() {
        val manager = open()
        val text = (1..ShortcutRules.MAX_SHORTCUTS + 5).joinToString("\n") { "键$it,${10_000 + it},speed,组${it % 20}" }
        val parsed = onMain { manager.import(text) }!!
        assertEquals(ShortcutRules.MAX_SHORTCUTS, parsed.shortcuts.size)
        assertEquals(5, parsed.skippedForLimit)
        assertEquals(listOf(ShortcutRules.Error.LIMIT_REACHED), onMain { manager.add(Shortcut(0, "多一个", "1")) })
        assertEquals(ShortcutRules.MAX_SHORTCUTS, manager.shortcuts.value.size)

        // Searching and grouping the full board.
        assertEquals(listOf("键1999"), ShortcutRules.filter(manager.shortcuts.value, "11999", null) { context.getString(it.label) }.map { it.label })
        assertEquals(20, ShortcutRules.categories(manager.shortcuts.value).size)
        assertEquals(100, ShortcutRules.filter(manager.shortcuts.value, "", "组7") { context.getString(it.label) }.size)

        awaitStored(ShortcutRules.MAX_SHORTCUTS)
        assertEquals(keys(manager), keys(open()))
    }
}

/** The local copy of the directories: search, renewal, expiry, its size limit, and what belongs to which directory. Needs no PBX. */
@RunWith(AndroidJUnit4::class)
class DirectoryCacheTest {
    private var cache: DirectoryCache? = null

    @Before
    @After
    fun removeDatabase() {
        cache?.close()
        cache = null
        context.deleteDatabase(FILE)
    }

    private fun open(maxBytes: Long = DirectoryCache.MAX_BYTES) = DirectoryCache(context, maxBytes, FILE).also { cache = it }

    private fun contact(name: String, number: String, organization: String = "") =
        DirectoryContact(name, number, "sip:$number@pbx.example.com", organization)

    @Test
    fun storesSearchesAndClears() {
        val cache = open()
        cache.store(
            HQ,
            listOf(
                contact("王芳", "1002", "销售"),
                contact("Alice Smith", "1006"),
                contact("Alice Smith", "13800001006"),
                contact("100%_折扣", "1009"),
            ),
        )
        assertEquals(setOf("1006", "13800001006"), cache.search(HQ, "alice", 50).map { it.number }.toSet())
        assertEquals(listOf("王芳"), cache.search(HQ, "芳", 50).map { it.name })
        assertEquals(setOf("1002", "1006", "13800001006", "1009"), cache.search(HQ, "100", 50).map { it.number }.toSet())
        assertEquals("sip:1002@pbx.example.com", cache.search(HQ, "1002", 50).single().callTarget)
        // The wildcards of the database are ordinary characters in a query.
        assertEquals(listOf("1009"), cache.search(HQ, "%_", 50).map { it.number })
        assertEquals(1, cache.search(HQ, "100", 1).size)

        // An entry that comes again is renewed, not doubled.
        cache.store(HQ, listOf(contact("王芳", "1002", "市场")))
        assertEquals(4, cache.stats().entries)
        assertEquals("市场", cache.search(HQ, "王芳", 5).single().organization)

        cache.clear()
        assertEquals(0, cache.stats().entries)
        assertTrue(cache.search(HQ, "1", 5).isEmpty())
    }

    @Test
    fun findsTheEntryOfANumberHoweverItIsWritten() {
        val cache = open()
        val now = System.currentTimeMillis()
        cache.store(HQ, listOf(contact("王芳", "1002"), contact("Alice Smith", "138-0000-1006"), contact("总机", "0755 8888 1000")), now)
        assertEquals("王芳", cache.find("1002", listOf(HQ))?.name)
        assertEquals("Alice Smith", cache.find("13800001006", listOf(HQ))?.name)
        // The same line with a country code or a trunk prefix in front, in either direction.
        assertEquals("Alice Smith", cache.find("8613800001006", listOf(HQ))?.name)
        assertEquals("总机", cache.find("88881000", listOf(HQ))?.name)
        // An extension is not the tail of an outside number, and unknown numbers stay unknown.
        assertEquals(null, cache.find("1006", listOf(HQ)))
        assertEquals(null, cache.find("13800001007", listOf(HQ)))

        // Of two entries with one number the one the server returned last is taken.
        cache.store(HQ, listOf(contact("王芳（新）", "1002").copy(callTarget = "sip:1002@other.example.com")), now + 1)
        assertEquals("王芳（新）", cache.find("1002", listOf(HQ))?.name)
        assertEquals(mapOf("1002" to "王芳（新）", "13800001006" to "Alice Smith"), cache.names(listOf("1002", "13800001006", "4711"), listOf(HQ)))
    }

    @Test
    fun keepsTheEntriesOfEveryDirectoryApart() {
        val cache = open()
        val now = System.currentTimeMillis()
        cache.store(HQ, listOf(contact("王芳", "1002"), contact("Alice Smith", "1006")), now)
        cache.store(BRANCH, listOf(contact("分公司前台", "1002"), contact("李娜", "1003")), now + 1)
        assertEquals(4, cache.stats().entries)

        assertEquals(listOf("王芳"), cache.search(HQ, "1002", 10).map { it.name })
        assertEquals(listOf("分公司前台"), cache.search(BRANCH, "1002", 10).map { it.name })
        assertTrue(cache.search(BRANCH, "Alice", 10).isEmpty())

        // A number is named by the first of the directories asked that has it, whichever saw it last.
        assertEquals("王芳", cache.find("1002", listOf(HQ, BRANCH))?.name)
        assertEquals("分公司前台", cache.find("1002", listOf(BRANCH, HQ))?.name)
        assertEquals("李娜", cache.find("1003", listOf(HQ, BRANCH))?.name)
        assertEquals(null, cache.find("1003", listOf(HQ)))
        assertEquals(null, cache.find("1002", emptyList()))
        assertEquals(mapOf("1002" to "王芳", "1003" to "李娜"), cache.names(listOf("1002", "1003"), listOf(HQ, BRANCH)))
        assertEquals(mapOf("1002" to "分公司前台", "1003" to "李娜"), cache.names(listOf("1002", "1003"), listOf(BRANCH, HQ)))
        assertEquals(mapOf("1002" to "王芳"), cache.names(listOf("1002", "1003"), listOf(HQ)))

        // Removing a directory takes its entries along and leaves the others.
        cache.clear(BRANCH)
        assertEquals(2, cache.stats().entries)
        assertTrue(cache.search(BRANCH, "1002", 10).isEmpty())
        assertEquals("王芳", cache.find("1002", listOf(BRANCH, HQ))?.name)
    }

    @Test
    fun dropsEntriesTheServerHasNotReturnedForNinetyDays() {
        val cache = open()
        val now = System.currentTimeMillis()
        cache.store(HQ, listOf(contact("离职员工", "1101")), now - DirectoryCache.MAX_AGE_MS - 60_000)
        cache.store(HQ, listOf(contact("在职员工", "1102")), now - DirectoryCache.MAX_AGE_MS + 60_000)
        cache.store(HQ, listOf(contact("新员工", "1103")), now)
        assertEquals(setOf("在职员工", "新员工"), cache.search(HQ, "员工", 10).map { it.name }.toSet())
    }

    @Test
    fun staysBelowItsSizeLimitByDroppingTheOldestEntries() {
        val limit = 256L * 1024
        val cache = open(limit)
        val now = System.currentTimeMillis()
        // Far more than fits: 20 000 entries in batches, each batch "seen" later than the one before.
        val batches = 80
        val perBatch = 250
        repeat(batches) { batch ->
            cache.store(HQ, (0 until perBatch).map { contact("员工${batch * perBatch + it}号", "${200_000 + batch * perBatch + it}", "研发中心") }, now + batch)
            val size = cache.stats().bytes
            assertTrue("The copy grew to $size bytes, over its limit of $limit", size <= limit)
        }
        val stats = cache.stats()
        assertTrue("Nothing was kept: $stats", stats.entries in 1 until batches * perBatch)
        // The newest entries are there, the oldest ones made room.
        assertEquals(1, cache.search(HQ, "员工${batches * perBatch - 1}号", 5).size)
        assertTrue(cache.search(HQ, "员工0号", 5).isEmpty())
    }

    @Test
    fun theLimitOfTheAppIsOneHundredMegabytes() {
        assertEquals(100L * 1024 * 1024, DirectoryCache.MAX_BYTES)
        assertEquals(DirectoryCache.MAX_BYTES, open().stats().maxBytes)
    }

    private companion object {
        const val FILE = "itest-directory-cache.db"

        /** Ids of two directories. */
        const val HQ = "hq"
        const val BRANCH = "branch"
    }
}
