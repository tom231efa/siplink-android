package com.sky.siplink.core

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.sky.siplink.core.logic.ShortcutLines
import com.sky.siplink.core.logic.ShortcutRules
import com.sky.siplink.core.model.Shortcut
import com.sky.siplink.core.model.SipAccount
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.Executors

/**
 * The shortcut keys (up to [ShortcutRules.MAX_SHORTCUTS]), kept in a CSV file of their own:
 * thousands of them would only slow down the SIP core configuration. Used from the main thread;
 * the file is read and written on a background thread.
 */
class ShortcutManager(private val file: File) {
    private val state = MutableStateFlow<List<Shortcut>>(emptyList())
    val shortcuts: StateFlow<List<Shortcut>> = state.asStateFlow()

    private val ready = MutableStateFlow(false)

    /** False while the file is still being read; nothing can be changed until then. */
    val loaded: StateFlow<Boolean> = ready.asStateFlow()

    private val io = Executors.newSingleThreadExecutor { Thread(it, "shortcuts-io") }
    private val main = Handler(Looper.getMainLooper())
    private var nextId = 1L

    init {
        io.execute {
            val stored = runCatching { if (file.isFile) ShortcutRules.decode(file.readText()).shortcuts else emptyList() }
                .onFailure { Log.e(TAG, "Unable to read the shortcut keys", it) }
                .getOrDefault(emptyList())
            main.post {
                state.value = stored
                nextId = stored.size + 1L
                ready.value = true
            }
        }
    }

    /** Adds a key; returns what is wrong with it, or nothing when it was added. */
    fun add(draft: Shortcut): List<ShortcutRules.Error> {
        if (!ready.value) return emptyList()
        if (!ShortcutRules.canAdd(state.value.size)) return listOf(ShortcutRules.Error.LIMIT_REACHED)
        val shortcut = ShortcutRules.normalize(draft).copy(id = nextId)
        val errors = ShortcutRules.validate(shortcut)
        if (errors.isNotEmpty()) return errors
        nextId++
        publish(state.value + ShortcutRules.joinCategories(listOf(shortcut), state.value))
        return emptyList()
    }

    /** Replaces the key with the same id. */
    fun update(changed: Shortcut): List<ShortcutRules.Error> {
        val normalized = ShortcutRules.normalize(changed)
        val errors = ShortcutRules.validate(normalized)
        if (errors.isNotEmpty()) return errors
        val shortcut = ShortcutRules.joinCategories(listOf(normalized), state.value.filter { it.id != normalized.id }).single()
        publish(state.value.map { if (it.id == shortcut.id) shortcut else it })
        return emptyList()
    }

    fun remove(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        val gone = ids.toHashSet()
        publish(state.value.filterNot { it.id in gone })
    }

    /** Gives every key of [category] the category [renamed]; an empty one ungroups them. */
    fun renameCategory(category: String, renamed: String) {
        val target = renamed.trim().take(ShortcutRules.MAX_CATEGORY_LENGTH)
        if (target == category) return
        publish(state.value.map { if (it.category == category) it.copy(category = target) else it })
    }

    /**
     * Keeps the fixed lines of the keys in step with the accounts after one of them was renamed
     * or removed: see [ShortcutLines.follow].
     */
    fun followAccounts(before: List<SipAccount>, after: List<SipAccount>) {
        val followed = ShortcutLines.follow(state.value, before, after)
        if (followed !== state.value) publish(followed)
    }

    /** Adds the keys of a CSV text ([ShortcutRules.FORMAT_HINT]) as far as the limit allows. */
    fun import(text: String): ShortcutRules.Parsed {
        if (!ready.value) return ShortcutRules.Parsed(emptyList(), emptyList(), 0)
        val parsed = ShortcutRules.decode(text, ShortcutRules.MAX_SHORTCUTS - state.value.size, nextId)
        if (parsed.shortcuts.isNotEmpty()) {
            nextId += parsed.shortcuts.size
            publish(state.value + ShortcutRules.joinCategories(parsed.shortcuts, state.value))
        }
        return parsed
    }

    /** Every key as CSV text, the format [import] reads. */
    fun export(): String = ShortcutRules.encode(state.value)

    private fun publish(list: List<Shortcut>) {
        if (!ready.value) return
        state.value = list
        io.execute {
            // Written next to the file and then moved over it, so a crash never leaves half a file.
            runCatching {
                val draft = File(file.parentFile, file.name + ".tmp")
                draft.writeText(ShortcutRules.encode(list))
                if (!draft.renameTo(file)) error("rename failed")
            }.onFailure { Log.e(TAG, "Unable to store the shortcut keys", it) }
        }
    }

    private companion object {
        const val TAG = "SipLink.Shortcuts"
    }
}
