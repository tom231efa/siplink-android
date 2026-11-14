package com.sky.siplink.core.logic

import com.sky.siplink.core.model.LdapDirectory
import com.sky.siplink.core.model.LdapSecurity

/** Pure rules for the LDAP directories: what a valid one looks like, and what the SIP core is told about it. */
object LdapRules {
    const val MAX_DIRECTORIES = 50
    const val MAX_NAME_LENGTH = 40
    const val MAX_RESULTS = 1000

    /** What stands for the searched text in a filter. */
    const val PLACEHOLDER = '%'

    /**
     * @param subject what the error is about
     * @param message what is wrong with it, as shown right under that field
     */
    enum class Error(val subject: String, val message: String) {
        LIMIT_REACHED("", "最多 $MAX_DIRECTORIES 个通讯录"),
        NAME_TAKEN("名称", "已存在"),
        NAME_TOO_LONG("名称", "最多 $MAX_NAME_LENGTH 个字符"),
        HOST_EMPTY("服务器地址", "必填"),
        HOST_INVALID("服务器地址", "应为 主机[:端口]"),
        HOST_LDAPS("服务器地址", "不支持 ldaps://，请用 StartTLS"),
        STARTTLS_NEEDS_HOST_NAME("服务器地址", "StartTLS 需用域名"),
        PORT_OUT_OF_RANGE("服务器地址", "端口应为 1–65535"),
        PASSWORD_WITHOUT_USER("用户名", "必填（已填密码）"),
        BASE_DN_EMPTY("搜索基准", "必填"),
        MAX_RESULTS_OUT_OF_RANGE("最大结果数", "应为 1–$MAX_RESULTS"),
        NAME_ATTRIBUTES_EMPTY("姓名属性", "必填"),
        NUMBER_ATTRIBUTES_EMPTY("号码属性", "必填"),
        NAME_FILTER_NO_PLACEHOLDER("姓名过滤器", "需包含 %"),
        NUMBER_FILTER_NO_PLACEHOLDER("号码过滤器", "需包含 %"),
        ;

        /** The error where no field stands next to it: in a notice. */
        val full: String get() = subject + message
    }

    private val schemes = listOf("ldap://", "ldaps://")
    private val separators = Regex("[\\s,]+")

    /**
     * Trims every field. A server typed as an address (`ldap://host:389`) is taken apart into
     * host and port; `ldaps://` stays, for [validate] to refuse.
     */
    fun normalize(directory: LdapDirectory): LdapDirectory {
        var host = directory.host.trim().trimEnd('/')
        var port = directory.port
        val scheme = schemes.firstOrNull { host.startsWith(it, ignoreCase = true) }
        if (scheme == schemes[0]) host = host.substring(scheme.length)
        if (scheme != schemes[1]) {
            val (name, typedPort) = AccountRoute.split(host)
            if (typedPort != null && name.isNotEmpty()) {
                host = name
                port = typedPort
            }
        }
        return directory.copy(
            name = directory.name.trim(),
            host = host,
            port = port,
            bindDn = directory.bindDn.trim(),
            baseDn = directory.baseDn.trim(),
            nameAttributes = attributes(directory.nameAttributes).joinToString(" "),
            numberAttributes = attributes(directory.numberAttributes).distinct().joinToString(" "),
            nameFilter = directory.nameFilter.trim(),
            numberFilter = directory.numberFilter.trim(),
        )
    }

    /**
     * @param directory already normalized
     * @param otherNames the names of the other directories
     * @param isNew it is about to be added to [otherNames]' directories rather than replacing one of its own
     */
    fun validate(directory: LdapDirectory, otherNames: Collection<String>, isNew: Boolean): List<Error> {
        val errors = mutableListOf<Error>()
        if (isNew && otherNames.size >= MAX_DIRECTORIES) errors += Error.LIMIT_REACHED
        when {
            directory.name.length > MAX_NAME_LENGTH -> errors += Error.NAME_TOO_LONG
            isNameTaken(directory.name, otherNames) -> errors += Error.NAME_TAKEN
        }
        val host = directory.host
        when {
            host.isEmpty() -> errors += Error.HOST_EMPTY
            host.startsWith(schemes[1], ignoreCase = true) -> errors += Error.HOST_LDAPS
            !AccountRules.isValidHostPort(host) || AccountRoute.split(host).second != null -> errors += Error.HOST_INVALID
            // The LDAP library of the SIP stack refuses the TLS handshake without a host name to
            // check the certificate against.
            directory.security == LdapSecurity.START_TLS && AccountRoute.isAddress(host) -> errors += Error.STARTTLS_NEEDS_HOST_NAME
        }
        if (directory.port !in 1..65_535) errors += Error.PORT_OUT_OF_RANGE
        if (directory.bindDn.isEmpty() && directory.password.isNotEmpty()) errors += Error.PASSWORD_WITHOUT_USER
        if (directory.baseDn.isEmpty()) errors += Error.BASE_DN_EMPTY
        if (directory.maxResults !in 1..MAX_RESULTS) errors += Error.MAX_RESULTS_OUT_OF_RANGE
        if (directory.nameAttributes.isEmpty()) errors += Error.NAME_ATTRIBUTES_EMPTY
        if (directory.numberAttributes.isEmpty()) errors += Error.NUMBER_ATTRIBUTES_EMPTY
        // A filter left empty is made from the attributes; one that is written has to say where the search goes.
        if (directory.nameFilter.isNotEmpty() && PLACEHOLDER !in directory.nameFilter) errors += Error.NAME_FILTER_NO_PLACEHOLDER
        if (directory.numberFilter.isNotEmpty() && PLACEHOLDER !in directory.numberFilter) errors += Error.NUMBER_FILTER_NO_PLACEHOLDER
        return errors
    }

    /** Two directories may not go by one name; without a name they go by their server. */
    fun isNameTaken(name: String, otherNames: Collection<String>): Boolean =
        name.isNotEmpty() && otherNames.any { it.equals(name, ignoreCase = true) }

    /** The server as it is typed: the host, with the port behind it when that is not the usual one. */
    fun serverText(directory: LdapDirectory): String =
        if (directory.port == LdapDirectory.DEFAULT_PORT) directory.host else "${directory.host}:${directory.port}"

    /** The server as the SIP core wants it. */
    fun serverUrl(directory: LdapDirectory): String {
        val host = if (':' in directory.host && !directory.host.startsWith("[")) "[${directory.host}]" else directory.host
        return "ldap://$host:${directory.port}"
    }

    /**
     * A filter as the SIP core wants it, which puts the searched text where `%s` stands and
     * leaves the rest to the filter: `(cn=%)` becomes `(cn=%s*)`. A filter written for the
     * core (`%s`) is taken as it is.
     */
    fun coreFilter(filter: String): String {
        if ("%s" in filter) return filter
        val out = StringBuilder(filter.length + 8)
        var index = 0
        while (index < filter.length) {
            val char = filter[index++]
            if (char != PLACEHOLDER) {
                out.append(char)
                continue
            }
            out.append("%s")
            // "%*" already asks for "begins with".
            if (index >= filter.length || filter[index] != '*') out.append('*')
        }
        return out.toString()
    }

    /** A filter written for the SIP core, as a filter with [PLACEHOLDER]: what [coreFilter] undoes. */
    fun fromCoreFilter(filter: String): String = filter.replace("%s*", "%").replace("%s", "%")

    /** The names of the attributes in a list typed with blanks or commas between them. */
    fun attributes(list: String): List<String> = list.split(separators).filter { it.isNotEmpty() }

    /** The filter a name is searched with: the one of the directory, or else "contains it" over its name attributes. */
    fun nameFilter(directory: LdapDirectory): String =
        directory.nameFilter.ifEmpty { filterOver(attributes(directory.nameAttributes)) }

    /** The filter a number is searched with: the one of the directory, or else "contains it" over its number attributes. */
    fun numberFilter(directory: LdapDirectory): String =
        directory.numberFilter.ifEmpty { filterOver(attributes(directory.numberAttributes)) }

    /**
     * A filter for entries of which one of [attributes] contains what is searched. A name put
     * together from several attributes (`sn+givenName`) is searched in each of them.
     */
    fun filterOver(attributes: List<String>): String {
        val terms = attributes.flatMap { it.split('+') }.filter { it.isNotEmpty() }.distinct().map { "($it=*$PLACEHOLDER)" }
        return if (terms.size == 1) terms[0] else terms.joinToString("", prefix = "(|", postfix = ")")
    }

    /**
     * Whether [query] is looked for among the numbers rather than among the names: it is made
     * of what numbers are made of.
     */
    fun isNumberQuery(query: String): Boolean {
        val text = query.trim()
        return text.any(Char::isDigit) && text.all { it.isDigit() || it in "+*#-() " }
    }

    /**
     * The directories that name the other side of a call, in the order they are asked: the
     * ones of the line of the call first, then the ones that are for every line. A directory
     * of another line is not asked.
     *
     * @param incoming the call came in, rather than being placed
     * @param line id of the account the call is on, null when that is not known
     */
    fun namingDirectories(directories: List<LdapDirectory>, incoming: Boolean, line: String?): List<LdapDirectory> {
        val asked = directories.filter { it.enabled && (if (incoming) it.lookupIncoming else it.lookupOutgoing) }
        val ofTheLine = if (line.isNullOrEmpty()) emptyList() else asked.filter { it.line == line }
        return ofTheLine + asked.filter { it.line.isEmpty() }
    }
}
