package com.sky.siplink.core.logic

import com.sky.siplink.core.model.AccountDraft

/**
 * Bulk account import from CSV text. One account per line:
 *
 *     username,password,domain[,transport[,displayName[,server]]]
 *
 * Blank lines and lines starting with `#` are ignored. Fields may be wrapped in double quotes
 * (use `""` for a literal quote) so passwords can contain commas.
 */
object AccountImport {
    const val FORMAT_HINT = "用户名,密码,域名[,传输协议[,显示名称[,代理服务器]]]"

    data class LineError(val line: Int, val reason: String)

    data class Result(
        val drafts: List<AccountDraft>,
        val errors: List<LineError>,
        /** Valid lines dropped because the account limit was reached. */
        val skippedForLimit: Int,
    )

    /**
     * @param existingKeys identity keys ([AccountRules.identityKey]) already configured
     */
    fun parse(text: String, existingKeys: Collection<String>): Result {
        val drafts = mutableListOf<AccountDraft>()
        val errors = mutableListOf<LineError>()
        val seen = existingKeys.toMutableSet()
        var skipped = 0

        text.lineSequence().forEachIndexed { index, raw ->
            val lineNumber = index + 1
            val line = raw.trim().removePrefix("\uFEFF")
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed

            val fields = splitCsvLine(line)
            if (fields == null) {
                errors += LineError(lineNumber, "引号未闭合")
                return@forEachIndexed
            }
            if (fields.size < 3) {
                errors += LineError(lineNumber, "字段不足，格式：$FORMAT_HINT")
                return@forEachIndexed
            }
            val transport = AccountRules.parseTransport(fields.getOrElse(3) { "" })
            if (transport == null) {
                errors += LineError(lineNumber, "未知的传输协议“${fields[3]}”，可用 udp / tcp / tls")
                return@forEachIndexed
            }
            val draft = AccountRules.normalize(
                AccountDraft(
                    username = fields[0],
                    password = fields[1],
                    domain = fields[2],
                    transport = transport,
                    displayName = fields.getOrElse(4) { "" },
                    server = fields.getOrElse(5) { "" },
                ),
            )
            // Limit is checked separately below so it is reported as "skipped", not as a line error.
            val problems = AccountRules.validate(draft, emptyList())
            if (problems.isNotEmpty()) {
                errors += LineError(lineNumber, problems.first().message)
                return@forEachIndexed
            }
            val key = AccountRules.identityKey(draft.username, draft.domain)
            if (!seen.add(key)) {
                errors += LineError(lineNumber, AccountRules.Error.DUPLICATE.message)
                return@forEachIndexed
            }
            if (seen.size > AccountRules.MAX_ACCOUNTS) {
                seen.remove(key)
                skipped++
                return@forEachIndexed
            }
            drafts += draft
        }
        return Result(drafts, errors, skipped)
    }

    /** Splits one CSV line; returns null when a quoted field is not closed. */
    internal fun splitCsvLine(line: String): List<String>? {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var wasQuoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                quoted && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> {
                    current.append('"')
                    i++
                }
                c == '"' -> {
                    quoted = !quoted
                    wasQuoted = true
                }
                c == ',' && !quoted -> {
                    fields += if (wasQuoted) current.toString() else current.toString().trim()
                    current.setLength(0)
                    wasQuoted = false
                }
                else -> current.append(c)
            }
            i++
        }
        if (quoted) return null
        fields += if (wasQuoted) current.toString() else current.toString().trim()
        return fields
    }
}
