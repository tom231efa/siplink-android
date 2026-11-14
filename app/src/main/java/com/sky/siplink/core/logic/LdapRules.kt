package com.sky.siplink.core.logic

import com.sky.siplink.core.model.LdapConfig

/** Pure validation rules for the LDAP directory configuration. */
object LdapRules {
    /**
     * @param subject what the error is about
     * @param message what is wrong with it, as shown right under that field
     */
    enum class Error(val subject: String, val message: String) {
        SERVER_EMPTY("服务器", "必填"),
        SERVER_INVALID("服务器", "应为 ldap://主机[:端口]"),
        SERVER_LDAPS("服务器", "不支持 ldaps://，请用 StartTLS"),
        STARTTLS_NEEDS_HOST_NAME("服务器", "StartTLS 需用域名"),
        BASE_DN_EMPTY("搜索基准 DN", "必填"),
        FILTER_EMPTY("过滤器", "必填"),
        FILTER_NO_PLACEHOLDER("过滤器", "需包含 %s"),
        NAME_ATTRIBUTES_EMPTY("姓名属性", "必填"),
        SIP_ATTRIBUTES_EMPTY("号码属性", "必填"),
        PASSWORD_WITHOUT_BIND_DN("绑定 DN", "必填（已填密码）"),
        NUMBER_OUT_OF_RANGE("数值", "超出范围"),
        ;

        /** The error where no field stands next to it: in a notice. */
        val full: String get() = subject + message
    }

    /** Trims fields and adds the `ldap://` scheme when the user typed a bare host. */
    fun normalize(config: LdapConfig): LdapConfig {
        var server = config.server.trim().trimEnd('/')
        if (server.isNotEmpty() && "://" !in server) server = "ldap://$server"
        return config.copy(
            server = server,
            bindDn = config.bindDn.trim(),
            baseDn = config.baseDn.trim(),
            filter = config.filter.trim(),
            nameAttributes = config.nameAttributes.replace(" ", ""),
            sipAttributes = config.sipAttributes.replace(" ", ""),
            sipDomain = config.sipDomain.trim(),
        )
    }

    private val ipv4 = Regex("""^\d{1,3}(\.\d{1,3}){3}(:\d+)?$""")

    /** True for `192.0.2.1`, `192.0.2.1:389` and bracketed IPv6 literals. */
    fun isIpLiteral(hostPort: String): Boolean = hostPort.startsWith("[") || ipv4.matches(hostPort)

    /** @param config already normalized configuration */
    fun validate(config: LdapConfig): List<Error> {
        val errors = mutableListOf<Error>()
        val server = config.server
        when {
            server.isEmpty() -> errors += Error.SERVER_EMPTY
            server.startsWith("ldaps://", ignoreCase = true) -> errors += Error.SERVER_LDAPS
            !server.startsWith("ldap://", ignoreCase = true) -> errors += Error.SERVER_INVALID
            !AccountRules.isValidHostPort(server.substring("ldap://".length)) -> errors += Error.SERVER_INVALID
            // The LDAP library of the SIP stack refuses the TLS handshake without a host name to
            // check the certificate against, even when verification is switched off.
            config.useStartTls && isIpLiteral(server.substring("ldap://".length)) ->
                errors += Error.STARTTLS_NEEDS_HOST_NAME
        }
        if (config.baseDn.isEmpty()) errors += Error.BASE_DN_EMPTY
        when {
            config.filter.isEmpty() -> errors += Error.FILTER_EMPTY
            "%s" !in config.filter -> errors += Error.FILTER_NO_PLACEHOLDER
        }
        if (config.nameAttributes.isEmpty()) errors += Error.NAME_ATTRIBUTES_EMPTY
        if (config.sipAttributes.isEmpty()) errors += Error.SIP_ATTRIBUTES_EMPTY
        if (config.bindDn.isEmpty() && config.password.isNotEmpty()) errors += Error.PASSWORD_WITHOUT_BIND_DN
        if (config.maxResults !in 0..1000 || config.minChars !in 0..20 ||
            config.timeoutSec !in 1..60 || config.delayMs !in 0..10_000
        ) {
            errors += Error.NUMBER_OUT_OF_RANGE
        }
        return errors
    }
}
