package com.sky.siplink.core.logic

import com.sky.siplink.core.model.Shortcut
import com.sky.siplink.core.model.ShortcutAction
import com.sky.siplink.core.model.ShortcutIcon

/** Pure rules of the shortcut keys: limits, validation, searching, grouping and CSV exchange. */
object ShortcutRules {
    const val MAX_SHORTCUTS = 2000

    /** How many keys of their own the user may make next to a managed configuration ([Shortcut.own]). */
    const val MAX_OWN = 50
    const val MAX_LABEL_LENGTH = 24
    const val MAX_NUMBER_LENGTH = 128
    const val MAX_CATEGORY_LENGTH = 16
    const val MAX_LINE_LENGTH = 160

    /**
     * One key per line. The type is a word of [typeWords]; the line is an account name or
     * user@domain, without one the key asks for its line whenever it calls out; the icon is a
     * word of [iconWords], without one the key shows the icon of its type.
     */
    const val FORMAT_HINT = "名称,号码[,类型[,分类[,线路[,图标]]]]"

    /** The icons of the CSV format, in the order of [ShortcutIcon]. */
    val iconWords: List<String> = ShortcutIcon.entries.map { it.name.lowercase() }

    /** The words of the CSV format, one per type, in the order of the types. */
    val typeWords: List<String> = ShortcutAction.entries.map(::typeWord)

    fun typeWord(action: ShortcutAction): String = when (action) {
        ShortcutAction.SPEED_DIAL -> "speed"
        ShortcutAction.BLIND_TRANSFER -> "bxfer"
        ShortcutAction.ATTENDED_TRANSFER -> "axfer"
        ShortcutAction.DTMF -> "dtmf"
        ShortcutAction.FORWARD -> "forward"
        ShortcutAction.DND -> "dnd"
    }

    /**
     * @param subject what the error is about
     * @param message what is wrong with it, as shown right under that field
     */
    enum class Error(val subject: String, val message: String) {
        LIMIT_REACHED("", "最多 $MAX_SHORTCUTS 个快捷键"),
        OWN_LIMIT_REACHED("", "最多自建 $MAX_OWN 个快捷键"),
        NUMBER_EMPTY("号码", "必填"),
        NUMBER_INVALID("号码", "不能包含空格"),
        DTMF_INVALID("按键", "仅限 0-9 * #"),
        NUMBER_TOO_LONG("号码", "最多 $MAX_NUMBER_LENGTH 个字符"),
        LABEL_TOO_LONG("名称", "最多 $MAX_LABEL_LENGTH 个字符"),
        CATEGORY_TOO_LONG("分类", "最多 $MAX_CATEGORY_LENGTH 个字符"),
        CATEGORY_EMPTY("分类", "必填"),
        CATEGORY_RESERVED("分类", "名称不可用"),
        CATEGORY_TAKEN("分类", "已存在"),

        /** Not a fault of the key: the configuration is managed and takes no change from the phone. */
        MANAGED("", ManagedConfig.NOTICE),
        ;

        /** The error where no field stands next to it: in a list of import errors. */
        val full: String get() = subject + message
    }

    fun canAdd(count: Int): Boolean = count < MAX_SHORTCUTS

    /** Whether the user may make one more key of their own next to a managed configuration. */
    fun canAddOwn(all: List<Shortcut>): Boolean = all.count { it.own } < MAX_OWN

    /**
     * The keys in the order every list shows and stores them: the user's own ([Shortcut.own])
     * first, then those that were handed out, each group in its own order.
     */
    fun ownFirst(all: List<Shortcut>): List<Shortcut> {
        if (all.none { it.own }) return all
        val (own, handedOut) = all.partition { it.own }
        return own + handedOut
    }

    /**
     * Whether [moved] may take the place of [target] in the order of the keys.
     *
     * @param ownOnly the configuration is managed: only the user's own keys move, and only among
     *   themselves, so that they stay ahead of those that were handed out
     */
    fun canMove(moved: Shortcut, target: Shortcut, ownOnly: Boolean): Boolean = !ownOnly || (moved.own && target.own)

    /**
     * [all] with the key [movedId] in the place of the key [targetId]; the keys between the two
     * move up or down by one. Null when nothing moves: one of them is missing, they are the same
     * key, or [canMove] refuses.
     */
    fun move(all: List<Shortcut>, movedId: Long, targetId: Long, ownOnly: Boolean): List<Shortcut>? {
        if (movedId == targetId) return null
        val from = all.indexOfFirst { it.id == movedId }
        val to = all.indexOfFirst { it.id == targetId }
        if (from < 0 || to < 0 || !canMove(all[from], all[to], ownOnly)) return null
        return all.toMutableList().apply { add(to, removeAt(from)) }
    }

    /**
     * The key a dragged key was dropped on: the one that stood in [before] where [movedId] stands
     * in [after], a reordering of [before] (ids). Moving [movedId] to the place of that key in a
     * longer list ([move]) puts it in the same place among the keys of [before]. Null when it
     * ended where it started.
     */
    fun dropTarget(before: List<Long>, after: List<Long>, movedId: Long): Long? {
        val target = before.getOrNull(after.indexOf(movedId)) ?: return null
        return target.takeIf { it != movedId }
    }

    /**
     * Whether the keys of [category] are the user's to rename as a group while the
     * configuration is managed: every one of them is their own.
     */
    fun ownsCategory(all: List<Shortcut>, category: String): Boolean = all.none { it.category == category && !it.own }

    /**
     * Whether keys of this type ever call out, and so have a line to choose.
     *
     * @param serverDnd do not disturb is kept by the PBX, which is told by a call to its
     *   feature code; a do not disturb key then calls out as well
     */
    fun usesLine(action: ShortcutAction, serverDnd: Boolean): Boolean = when (action) {
        ShortcutAction.SPEED_DIAL, ShortcutAction.BLIND_TRANSFER, ShortcutAction.ATTENDED_TRANSFER -> true
        ShortcutAction.DND -> serverDnd
        ShortcutAction.DTMF, ShortcutAction.FORWARD -> false
    }

    /**
     * Whether pressing the key now starts a call of its own, which is when its line matters.
     *
     * @param inCall a call is established: a transfer key then acts on that call, and a speed
     *   dial becomes a second call on the line of the first one
     */
    fun placesCall(shortcut: Shortcut, inCall: Boolean, serverDnd: Boolean): Boolean = when (shortcut.action) {
        ShortcutAction.DND -> serverDnd
        else -> usesLine(shortcut.action, serverDnd) && !inCall
    }

    /**
     * Trims every field and drops the number of a type that has none. A key without a name is
     * called by its number, or by its type when it has no number either.
     */
    fun normalize(shortcut: Shortcut): Shortcut {
        val number = if (shortcut.action.hasNumber) shortcut.number.trim() else ""
        return shortcut.copy(
            label = shortcut.label.trim().ifEmpty { number.take(MAX_LABEL_LENGTH).ifEmpty { shortcut.action.label } },
            number = number,
            category = shortcut.category.trim(),
            // Kept for every type that may call out, whatever the do not disturb mode is right now.
            line = if (usesLine(shortcut.action, serverDnd = true)) shortcut.line.trim().take(MAX_LINE_LENGTH) else "",
        )
    }

    /** @param shortcut already normalized */
    fun validate(shortcut: Shortcut): List<Error> = buildList {
        val number = shortcut.number
        when {
            number.isEmpty() -> if (shortcut.action.hasNumber) add(Error.NUMBER_EMPTY)
            shortcut.action == ShortcutAction.DTMF -> if (!number.all(Formatters::isDtmf)) add(Error.DTMF_INVALID)
            number.any { it.isWhitespace() } -> add(Error.NUMBER_INVALID)
        }
        if (number.length > MAX_NUMBER_LENGTH) add(Error.NUMBER_TOO_LONG)
        if (shortcut.label.length > MAX_LABEL_LENGTH) add(Error.LABEL_TOO_LONG)
        if (shortcut.category.length > MAX_CATEGORY_LENGTH) add(Error.CATEGORY_TOO_LONG)
        if (shortcut.category in reservedCategories) add(Error.CATEGORY_RESERVED)
    }

    /**
     * What the lists call every key, the keys without a category, and the way to make a
     * category: a category of that name could not be told from them.
     */
    private val reservedCategories = setOf("全部", "未分类", "新建分类")

    /**
     * What keeps [name] from being the name of a category, null when nothing does. Upper and
     * lower case do not make two categories.
     *
     * @param taken the names of the other categories
     */
    fun categoryNameError(name: String, taken: Collection<String>): Error? {
        val trimmed = name.trim()
        return when {
            trimmed.isEmpty() -> Error.CATEGORY_EMPTY
            trimmed.length > MAX_CATEGORY_LENGTH -> Error.CATEGORY_TOO_LONG
            trimmed in reservedCategories -> Error.CATEGORY_RESERVED
            taken.any { it.equals(trimmed, ignoreCase = true) } -> Error.CATEGORY_TAKEN
            else -> null
        }
    }

    /**
     * [added] with their categories spelled the way [existing] and the keys before them spell
     * them: "sales" joins "Sales" instead of becoming a second category next to it.
     */
    fun joinCategories(added: List<Shortcut>, existing: List<Shortcut>): List<Shortcut> {
        val spelling = HashMap<String, String>()
        for (shortcut in existing) spelling.putIfAbsent(shortcut.category.lowercase(), shortcut.category)
        return added.map { shortcut ->
            val known = spelling.getOrPut(shortcut.category.lowercase()) { shortcut.category }
            if (known == shortcut.category) shortcut else shortcut.copy(category = known)
        }
    }

    /**
     * The keys of [category] (null: every category) whose name, number, category or type
     * contains [query], in their stored order.
     */
    fun filter(all: List<Shortcut>, query: String, category: String?): List<Shortcut> {
        val text = query.trim()
        if (text.isEmpty() && category == null) return all
        return all.filter {
            (category == null || it.category == category) &&
                (text.isEmpty() || it.label.contains(text, true) || it.number.contains(text, true) ||
                    it.category.contains(text, true) || it.action.label.contains(text, true))
        }
    }

    /** Categories in the order they first appear, each with its number of keys. */
    fun categories(all: List<Shortcut>): List<Pair<String, Int>> {
        val counts = LinkedHashMap<String, Int>()
        for (shortcut in all) counts[shortcut.category] = (counts[shortcut.category] ?: 0) + 1
        return counts.map { it.key to it.value }
    }

    /** The type behind a word of the CSV format ([typeWords], or its Chinese name); empty is a speed dial. */
    fun parseAction(value: String): ShortcutAction? {
        val word = value.trim()
        if (word.isEmpty()) return ShortcutAction.SPEED_DIAL
        return ShortcutAction.entries.firstOrNull { typeWord(it).equals(word, ignoreCase = true) || it.label == word }
    }

    /** The icon behind a word of the CSV format ([iconWords], or its Chinese name). */
    fun parseIcon(value: String): ShortcutIcon? {
        val word = value.trim()
        return ShortcutIcon.entries.firstOrNull { it.name.equals(word, ignoreCase = true) || it.label == word }
    }

    data class Parsed(val shortcuts: List<Shortcut>, val errors: List<Csv.LineError>, val skippedForLimit: Int)

    /**
     * Reads keys from CSV text ([FORMAT_HINT]).
     *
     * @param room how many keys still fit under the limit
     * @param firstId id of the first key read; the following ones count up from it
     */
    fun decode(text: String, room: Int = MAX_SHORTCUTS, firstId: Long = 1): Parsed {
        val shortcuts = ArrayList<Shortcut>()
        val errors = ArrayList<Csv.LineError>()
        var skipped = 0
        for ((line, fields) in Csv.rows(text)) {
            val action = fields?.let { parseAction(it.getOrElse(2) { "" }) }
            val iconWord = fields?.getOrElse(5) { "" }.orEmpty().trim()
            val icon = parseIcon(iconWord)
            val error = when {
                fields == null -> "引号未闭合"
                fields.size < 2 -> "字段不足，格式：$FORMAT_HINT"
                action == null -> "未知的类型“${fields[2]}”，可选 ${typeWords.joinToString(" / ")}"
                iconWord.isNotEmpty() && icon == null -> "未知的图标“$iconWord”，可选 ${iconWords.joinToString(" / ")}"
                else -> null
            }
            if (fields == null || action == null || error != null) {
                errors += Csv.LineError(line, error.orEmpty())
                continue
            }
            val shortcut = normalize(
                Shortcut(
                    id = firstId + shortcuts.size,
                    label = fields[0],
                    number = fields[1],
                    action = action,
                    category = fields.getOrElse(3) { "" },
                    line = fields.getOrElse(4) { "" },
                    icon = icon,
                ),
            )
            val problems = validate(shortcut)
            when {
                problems.isNotEmpty() -> errors += Csv.LineError(line, problems.first().full)
                shortcuts.size >= room -> skipped++
                else -> shortcuts += shortcut
            }
        }
        return Parsed(shortcuts, errors, skipped)
    }

    /** The opposite of [decode]. */
    fun encode(shortcuts: List<Shortcut>): String = buildString {
        for (shortcut in shortcuts) {
            val fields = listOf(
                shortcut.label,
                shortcut.number,
                typeWord(shortcut.action),
                shortcut.category,
                shortcut.line,
                shortcut.icon?.name?.lowercase().orEmpty(),
            )
            append(Csv.join(fields))
            append('\n')
        }
    }
}
