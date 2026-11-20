package com.sky.siplink.core.logic

import com.sky.siplink.R
import com.sky.siplink.core.model.Phrase
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
     * One key per line: name, number[, type[, category[, line[, icon]]]]. The type is a word of
     * [typeWords]; the line is an account name or user@domain, without one the key asks for its
     * line whenever it calls out; the icon is a word of [iconWords], without one the key shows
     * the icon of its type.
     */
    val FORMAT: Phrase = Phrase.of(R.string.shortcut_csv_format)

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
     * @param message what is wrong, as shown right under the field it is about
     * @param full the error where no field stands next to it, in a list of import errors: it names the field
     */
    enum class Error(val message: Phrase, full: Phrase? = null) {
        LIMIT_REACHED(Phrase.count(R.plurals.shortcut_limit, MAX_SHORTCUTS)),
        OWN_LIMIT_REACHED(Phrase.count(R.plurals.shortcut_own_limit, MAX_OWN)),
        NUMBER_EMPTY(Phrase.of(R.string.form_required), Phrase.of(R.string.shortcut_error_number_empty)),
        NUMBER_INVALID(Phrase.of(R.string.shortcut_error_spaces), Phrase.of(R.string.shortcut_error_number_spaces)),
        DTMF_INVALID(Phrase.of(R.string.shortcut_error_dtmf), Phrase.of(R.string.shortcut_error_dtmf_full)),
        NUMBER_TOO_LONG(FormRules.maxChars(MAX_NUMBER_LENGTH), Phrase.count(R.plurals.shortcut_error_number_too_long, MAX_NUMBER_LENGTH)),
        LABEL_TOO_LONG(FormRules.maxChars(MAX_LABEL_LENGTH), Phrase.count(R.plurals.shortcut_error_name_too_long, MAX_LABEL_LENGTH)),
        CATEGORY_TOO_LONG(FormRules.maxChars(MAX_CATEGORY_LENGTH), Phrase.count(R.plurals.shortcut_error_category_too_long, MAX_CATEGORY_LENGTH)),
        CATEGORY_EMPTY(Phrase.of(R.string.form_required), Phrase.of(R.string.shortcut_error_category_empty)),
        CATEGORY_RESERVED(Phrase.of(R.string.shortcut_error_reserved), Phrase.of(R.string.shortcut_error_category_reserved)),
        CATEGORY_TAKEN(Phrase.of(R.string.form_taken), Phrase.of(R.string.shortcut_error_category_taken)),

        /** Not a fault of the key: the configuration is managed and takes no change from the phone. */
        MANAGED(ManagedConfig.NOTICE),
        ;

        val full: Phrase = full ?: message
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
     * called by its number; without a number either it keeps no name and is called by its type ([title]).
     */
    fun normalize(shortcut: Shortcut): Shortcut {
        val number = if (shortcut.action.hasNumber) shortcut.number.trim() else ""
        return shortcut.copy(
            label = shortcut.label.trim().ifEmpty { number.take(MAX_LABEL_LENGTH) },
            number = number,
            category = shortcut.category.trim(),
            // Kept for every type that may call out, whatever the do not disturb mode is right now.
            line = if (usesLine(shortcut.action, serverDnd = true)) shortcut.line.trim().take(MAX_LINE_LENGTH) else "",
        )
    }

    /** What a key is called: its name, or the name of its type when it has none. */
    fun title(shortcut: Shortcut): Phrase = if (shortcut.label.isEmpty()) Phrase.of(shortcut.action.label) else Phrase.plain(shortcut.label)

    /**
     * @param shortcut already normalized
     * @param reserved the names of the category lists of the moment ([RESERVED_CATEGORIES])
     */
    fun validate(shortcut: Shortcut, reserved: Set<String>): List<Error> = buildList {
        val number = shortcut.number
        when {
            number.isEmpty() -> if (shortcut.action.hasNumber) add(Error.NUMBER_EMPTY)
            shortcut.action == ShortcutAction.DTMF -> if (!number.all(Formatters::isDtmf)) add(Error.DTMF_INVALID)
            number.any { it.isWhitespace() } -> add(Error.NUMBER_INVALID)
        }
        if (number.length > MAX_NUMBER_LENGTH) add(Error.NUMBER_TOO_LONG)
        if (shortcut.label.length > MAX_LABEL_LENGTH) add(Error.LABEL_TOO_LONG)
        if (shortcut.category.length > MAX_CATEGORY_LENGTH) add(Error.CATEGORY_TOO_LONG)
        if (shortcut.category in reserved) add(Error.CATEGORY_RESERVED)
    }

    /**
     * What the lists call every key, the keys without a category, and the way to make a
     * category: a category of that name could not be told from them. Reserved by their names in
     * the language of the moment.
     */
    val RESERVED_CATEGORIES = listOf(R.string.shortcut_category_all, R.string.shortcut_category_none, R.string.shortcut_category_new)

    /**
     * What keeps [name] from being the name of a category, null when nothing does. Upper and
     * lower case do not make two categories.
     *
     * @param taken the names of the other categories
     * @param reserved as for [validate]
     */
    fun categoryNameError(name: String, taken: Collection<String>, reserved: Set<String>): Error? {
        val trimmed = name.trim()
        return when {
            trimmed.isEmpty() -> Error.CATEGORY_EMPTY
            trimmed.length > MAX_CATEGORY_LENGTH -> Error.CATEGORY_TOO_LONG
            trimmed in reserved -> Error.CATEGORY_RESERVED
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
     * contains [query], in their stored order. [typeName] is the name of a type in the language of the moment.
     */
    fun filter(all: List<Shortcut>, query: String, category: String?, typeName: (ShortcutAction) -> String): List<Shortcut> {
        val text = query.trim()
        if (text.isEmpty() && category == null) return all
        return all.filter {
            (category == null || it.category == category) &&
                (text.isEmpty() || it.label.contains(text, true) || it.number.contains(text, true) ||
                    it.category.contains(text, true) || typeName(it.action).contains(text, true))
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
        return ShortcutAction.entries.firstOrNull { typeWord(it).equals(word, ignoreCase = true) } ?: CHINESE_TYPES[word]
    }

    /** The icon behind a word of the CSV format ([iconWords], or its Chinese name). */
    fun parseIcon(value: String): ShortcutIcon? {
        val word = value.trim()
        return ShortcutIcon.entries.firstOrNull { it.name.equals(word, ignoreCase = true) } ?: CHINESE_ICONS[word]
    }

    /**
     * The Chinese names of the types and icons, which the CSV format takes besides its words
     * (files written before the app had other languages use them). Part of the format: they are
     * data and stay as they are in every language.
     */
    private val CHINESE_TYPES = mapOf(
        "速拨" to ShortcutAction.SPEED_DIAL, "盲转" to ShortcutAction.BLIND_TRANSFER, "咨询转" to ShortcutAction.ATTENDED_TRANSFER,
        "发送按键" to ShortcutAction.DTMF, "呼叫前转" to ShortcutAction.FORWARD, "免打扰" to ShortcutAction.DND,
    )
    private val CHINESE_ICONS = mapOf(
        "电话" to ShortcutIcon.PHONE, "个人" to ShortcutIcon.PERSON, "团队" to ShortcutIcon.GROUP, "收藏" to ShortcutIcon.STAR,
        "家" to ShortcutIcon.HOME, "办公楼" to ShortcutIcon.OFFICE, "门店" to ShortcutIcon.STORE, "前台" to ShortcutIcon.RECEPTION,
        "客服" to ShortcutIcon.SUPPORT, "广播" to ShortcutIcon.SPEAKER, "会议" to ShortcutIcon.MEETING, "门" to ShortcutIcon.DOOR,
        "锁" to ShortcutIcon.LOCK, "钥匙" to ShortcutIcon.KEY, "车库" to ShortcutIcon.GARAGE, "车辆" to ShortcutIcon.CAR,
        "电梯" to ShortcutIcon.ELEVATOR, "保安" to ShortcutIcon.SECURITY, "监控" to ShortcutIcon.CAMERA, "门铃" to ShortcutIcon.BELL,
        "报警" to ShortcutIcon.ALARM, "医务" to ShortcutIcon.MEDICAL, "消防" to ShortcutIcon.FIRE, "餐厅" to ShortcutIcon.FOOD,
        "客房" to ShortcutIcon.HOTEL, "仓库" to ShortcutIcon.WAREHOUSE, "车间" to ShortcutIcon.FACTORY, "教室" to ShortcutIcon.SCHOOL,
        "维修" to ShortcutIcon.REPAIR, "机房" to ShortcutIcon.COMPUTER, "物流" to ShortcutIcon.DELIVERY, "照明" to ShortcutIcon.LIGHT,
    )

    data class Parsed(val shortcuts: List<Shortcut>, val errors: List<Csv.LineError>, val skippedForLimit: Int)

    /**
     * Reads keys from CSV text ([FORMAT]).
     *
     * @param room how many keys still fit under the limit
     * @param firstId id of the first key read; the following ones count up from it
     * @param reserved as for [validate]
     */
    fun decode(text: String, room: Int = MAX_SHORTCUTS, firstId: Long = 1, reserved: Set<String>): Parsed {
        val shortcuts = ArrayList<Shortcut>()
        val errors = ArrayList<Csv.LineError>()
        var skipped = 0
        for ((line, fields) in Csv.rows(text)) {
            if (fields == null || fields.size < 2) {
                errors += Csv.LineError(line, Csv.shapeError(fields, FORMAT))
                continue
            }
            val action = parseAction(fields.getOrElse(2) { "" })
            if (action == null) {
                errors += Csv.LineError(line, Phrase.of(R.string.csv_unknown_type, fields[2], typeWords.joinToString(" / ")))
                continue
            }
            val iconWord = fields.getOrElse(5) { "" }.trim()
            val icon = parseIcon(iconWord)
            if (iconWord.isNotEmpty() && icon == null) {
                errors += Csv.LineError(line, Phrase.of(R.string.csv_unknown_icon, iconWord, iconWords.joinToString(" / ")))
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
            val problems = validate(shortcut, reserved)
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
