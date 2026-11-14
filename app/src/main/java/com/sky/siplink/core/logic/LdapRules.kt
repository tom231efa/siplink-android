package com.sky.siplink.core.logic

import com.sky.siplink.core.model.LdapConfig

/** Pure validation rules for the LDAP directory configuration. */
object LdapRules {
    enum class Error(val message: String) {
        SERVER_EMPTY("请输入 LDAP 服务器地址"),
        SERVER_INVALID("服务器地址格式不正确，应为 ldap://主机[:端口]"),
        SERVER_LDAPS("不支持 ldaps://，请使用 ldap:// 并开启 StartTLS 加密"),
        STARTTLS_NEEDS_HOST_NAME("StartTLS 需要填写服务器域名（证书上的主机名），不能使用 IP 地址"),
        BASE_DN_EMPTY("请输入搜索基准 DN"),
        FILTER_EMPTY("请输入搜索过滤器"),
        FILTER_NO_PLACEHOLDER("过滤器中必须包含 %s 占位符"),
        NAME_ATTRIBUTES_EMPTY("请输入姓名属性"),
        SIP_ATTRIBUTES_EMPTY("请输入号码属性"),
        PASSWORD_WITHOUT_BIND_DN("填写了密码但缺少绑定 DN"),
        NUMBER_OUT_OF_RANGE("数值超出允许范围"),
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
