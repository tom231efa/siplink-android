package com.sky.siplink.core.logic

import com.sky.siplink.core.model.AccountDraft

/**
 * Bulk account import from CSV text. One account per line:
 *
 *     username,password,domain[,transport[,displayName[,server[,name]]]]
 *
 * Blank lines and lines starting with `#` are ignored. Fields may be wrapped in double quotes
 * (use `""` for a literal quote) so passwords can contain commas.
 */
object AccountImport {
    const val FORMAT_HINT = "用户名,密码,域名[,传输协议[,显示名称[,代理服务器[,账号名称]]]]"

    data class Result(
        val drafts: List<AccountDraft>,
        val errors: List<Csv.LineError>,
        /** Valid lines dropped because the account limit was reached. */
        val skippedForLimit: Int,
    )

    /**
     * @param existingKeys identity keys ([AccountRules.identityKey]) already configured
     * @param existingNames names of the accounts already configured
     */
    fun parse(text: String, existingKeys: Collection<String>, existingNames: Collection<String> = emptyList()): Result {
        val drafts = mutableListOf<AccountDraft>()
        val errors = mutableListOf<Csv.LineError>()
        val seen = existingKeys.toMutableSet()
        val names = existingNames.toMutableList()
        var skipped = 0

        for ((line, fields) in Csv.rows(text)) {
            val error = when {
                fields == null -> "引号未闭合"
                fields.size < 3 -> "字段不足，格式：$FORMAT_HINT"
                AccountRules.parseTransport(fields.getOrElse(3) { "" }) == null ->
                    "未知的传输协议“${fields[3]}”，可用 udp / tcp / tls"
                else -> null
            }
            if (fields == null || error != null) {
                errors += Csv.LineError(line, error.orEmpty())
                continue
            }
            val draft = AccountRules.normalize(
                AccountDraft(
                    username = fields[0],
                    password = fields[1],
                    domain = fields[2],
                    transport = AccountRules.parseTransport(fields.getOrElse(3) { "" }) ?: continue,
                    displayName = fields.getOrElse(4) { "" },
                    server = fields.getOrElse(5) { "" },
                    name = fields.getOrElse(6) { "" },
                ),
            )
            // Limit is checked separately below so it is reported as "skipped", not as a line error.
            val problems = AccountRules.validate(draft, emptyList(), takenNames = names)
            val key = AccountRules.identityKey(draft.username, draft.domain)
            when {
                problems.isNotEmpty() -> errors += Csv.LineError(line, problems.first().full)
                !seen.add(key) -> errors += Csv.LineError(line, AccountRules.Error.DUPLICATE.full)
                seen.size > AccountRules.MAX_ACCOUNTS -> {
                    seen.remove(key)
                    skipped++
                }
                else -> {
                    drafts += draft
                    names += draft.name
                }
            }
        }
        return Result(drafts, errors, skipped)
    }
}
