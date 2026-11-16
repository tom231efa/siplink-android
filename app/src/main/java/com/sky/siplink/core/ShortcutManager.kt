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
 *
 * While the configuration is managed ([ConfigLock]) the keys that were handed out cannot be
 * changed here; they still run, and can be exported. Next to them the user may make up to
 * [ShortcutRules.MAX_OWN] keys of their own ([Shortcut.own]), which stay theirs to change and to
 * remove. Those are kept in a file of their own beside [file], and go when the managed
 * configuration goes ([dropOwn]).
 */
class ShortcutManager(private val file: File, private val lock: ConfigLock) {
    private val state = MutableStateFlow<List<Shortcut>>(emptyList())
    val shortcuts: StateFlow<List<Shortcut>> = state.asStateFlow()

    private val ready = MutableStateFlow(false)

    /** False while the file is still being read; nothing can be changed until then. */
    val loaded: StateFlow<Boolean> = ready.asStateFlow()

    /** The user's own keys next to a managed configuration, in the format of [file]. */
    private val ownFile = File(file.parentFile, file.nameWithoutExtension + OWN_SUFFIX + "." + file.extension)

    private val io = Executors.newSingleThreadExecutor { Thread(it, "shortcuts-io") }
    private val main = Handler(Looper.getMainLooper())
    private var nextId = 1L

    init {
        io.execute {
            val stored = read(file, firstId = 1)
            val own = read(ownFile, firstId = stored.size + 1L).map { it.copy(own = true) }
            main.post {
                val kept = if (lock.managed.value) own else emptyList()
                state.value = stored + kept
                nextId = stored.size + own.size + 1L
                ready.value = true
                // Keys of the user's own that outlived their managed configuration go now.
                if (kept.size != own.size) io.execute { ownFile.delete() }
            }
        }
    }

    private fun read(from: File, firstId: Long): List<Shortcut> =
        runCatching { if (from.isFile) ShortcutRules.decode(from.readText(), firstId = firstId).shortcuts else emptyList() }
            .onFailure { Log.e(TAG, "Unable to read the shortcut keys of ${from.name}", it) }
            .getOrDefault(emptyList())

    /**
     * Adds a key; returns what is wrong with it, or nothing when it was added. While the
     * configuration is managed the key becomes one of the user's own, of which there is a limit.
     */
    fun add(draft: Shortcut): List<ShortcutRules.Error> {
        val own = lock.refuses
        if (!ready.value) return emptyList()
        if (own && !ShortcutRules.canAddOwn(state.value)) return listOf(ShortcutRules.Error.OWN_LIMIT_REACHED)
        if (!ShortcutRules.canAdd(state.value.size)) return listOf(ShortcutRules.Error.LIMIT_REACHED)
        val shortcut = ShortcutRules.normalize(draft).copy(id = nextId, own = own)
        val errors = ShortcutRules.validate(shortcut)
        if (errors.isNotEmpty()) return errors
        nextId++
        publish(state.value + ShortcutRules.joinCategories(listOf(shortcut), state.value))
        return emptyList()
    }

    /** Replaces the key with the same id. While the configuration is managed that has to be one of the user's own. */
    fun update(changed: Shortcut): List<ShortcutRules.Error> {
        val stored = state.value.firstOrNull { it.id == changed.id }
        if (lock.refuses && stored?.own != true) return listOf(ShortcutRules.Error.MANAGED)
        // Whose key it is does not change with it.
        val normalized = ShortcutRules.normalize(changed).copy(own = stored?.own == true)
        val errors = ShortcutRules.validate(normalized)
        if (errors.isNotEmpty()) return errors
        val shortcut = ShortcutRules.joinCategories(listOf(normalized), state.value.filter { it.id != normalized.id }).single()
        publish(state.value.map { if (it.id == shortcut.id) shortcut else it })
        return emptyList()
    }

    /**
     * Removes the keys with these ids; while the configuration is managed only those of them
     * that are the user's own.
     *
     * @return false when no key was removed
     */
    fun remove(ids: Collection<Long>): Boolean {
        if (!ready.value || ids.isEmpty()) return false
        val gone = ids.toHashSet()
        val ownOnly = lock.refuses
        val kept = state.value.filterNot { it.id in gone && (it.own || !ownOnly) }
        if (kept.size == state.value.size) return false
        publish(kept)
        return true
    }

    /**
     * Gives every key of [category] the category [renamed]; an empty one ungroups them. While
     * the configuration is managed that takes a category of nothing but the user's own keys.
     *
     * @return false when nothing was changed: that is its name already, or the category is not the user's to rename
     */
    fun renameCategory(category: String, renamed: String): Boolean {
        if (!ready.value) return false
        if (lock.refuses && !ShortcutRules.ownsCategory(state.value, category)) return false
        val target = renamed.trim().take(ShortcutRules.MAX_CATEGORY_LENGTH)
        if (target == category) return false
        publish(state.value.map { if (it.category == category) it.copy(category = target) else it })
        return true
    }

    /**
     * Removes the keys the user made next to a managed configuration: called when that
     * configuration is gone ([ConfigLock.onReleased]).
     */
    fun dropOwn() {
        if (state.value.any { it.own }) publish(state.value.filterNot { it.own })
    }

    /**
     * Replaces the keys that were handed out by [keys], in their order, and keeps every key of
     * the user's own ([Shortcut.own]) behind them: for the remote side. Nothing is written when
     * the handed-out keys are [keys] already.
     *
     * @return false when nothing was replaced: the keys are not read yet, or the configuration is
     *   managed and this is not the remote side
     */
    fun replaceHandedOut(keys: List<Shortcut>): Boolean {
        if (lock.refuses || !ready.value) return false
        val (own, handedOut) = state.value.partition { it.own }
        val wanted = keys.map { it.copy(id = 0, own = false) }
        if (handedOut.map { it.copy(id = 0) } == wanted) return true
        val replaced = wanted.mapIndexed { index, key -> key.copy(id = nextId + index) }
        nextId += replaced.size
        publish(replaced + own)
        return true
    }

    /**
     * Keeps the fixed lines of the keys in step with the accounts after one of them was renamed
     * or removed: see [ShortcutLines.follow].
     */
    fun followAccounts(before: List<SipAccount>, after: List<SipAccount>) {
        if (lock.refuses) return
        val followed = ShortcutLines.follow(state.value, before, after)
        if (followed !== state.value) publish(followed)
    }

    /**
     * Adds the keys of a CSV text ([ShortcutRules.FORMAT_HINT]) as far as the limit allows.
     *
     * @return what was read and added; null when the configuration is managed, and nothing was
     */
    fun import(text: String): ShortcutRules.Parsed? {
        if (lock.refuses) return null
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
        val before = state.value
        state.value = list
        val (own, handedOut) = list.partition { it.own }
        val ownChanged = own != before.filter { it.own }
        io.execute {
            runCatching {
                write(file, handedOut)
                if (ownChanged) {
                    if (own.isEmpty()) ownFile.delete() else write(ownFile, own)
                }
            }.onFailure { Log.e(TAG, "Unable to store the shortcut keys", it) }
        }
    }

    /** Written next to the file and then moved over it, so a crash never leaves half a file. */
    private fun write(to: File, keys: List<Shortcut>) {
        val draft = File(to.parentFile, to.name + ".tmp")
        draft.writeText(ShortcutRules.encode(keys))
        if (!draft.renameTo(to)) error("rename failed")
    }

    private companion object {
        const val TAG = "SipLink.Shortcuts"

        /** What the name of the file of the user's own keys has after the name of the main one. */
        const val OWN_SUFFIX = "-own"
    }
}
