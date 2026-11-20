package com.sky.siplink.core.logic

import androidx.annotation.StringRes
import com.sky.siplink.R
import java.net.URI
import java.net.URISyntaxException
import java.util.Base64

/**
 * The address a remote configuration is loaded from: what the QR code of the management platform
 * holds, `https://host[:port][/prefix]/p/{enrollToken}[?pin={pin}]`, and the addresses of the
 * platform that follow from it.
 *
 * `pin` is the SHA-256 of the platform's CA certificate's SubjectPublicKeyInfo, base64url without
 * padding ([CertificatePin]). Without it the platform has to have a certificate the system trusts.
 */
object RemoteAddress {
    /** Where a phone that is bound to the platform syncs and leaves, under the platform's address. */
    private const val SYNC_PATH = "/api/device/v1/sync"
    private const val LEAVE_PATH = "/api/device/v1/leave"
    private const val ENROLL_SEGMENT = "/p/"
    private const val SYNC_SEGMENT = "/sync"
    private const val LEAVE_SEGMENT = "/leave"
    private const val PIN_BYTES = 32
    private val token = Regex("^[A-Za-z0-9_-]+$")

    enum class Error(@StringRes val message: Int) {
        NOT_AN_ADDRESS(R.string.remote_address_invalid),
        SCHEME(R.string.remote_address_scheme),
        NOT_ENROLLMENT(R.string.remote_address_not_enrollment),
        PIN_INVALID(R.string.remote_address_pin),
    }

    /**
     * @param enrollUrl what the phone binds itself with: the address without its query
     * @param origin `scheme://host[:port]`
     * @param host the host as it is checked against the certificate: a name, or an address without brackets
     * @param pin of the platform's CA certificate; null: the system's CAs decide
     * @param syncUrl where the phone syncs until the platform names another place
     */
    data class Enrollment(val enrollUrl: String, val origin: String, val host: String, val pin: String?, val syncUrl: String)

    /** A result of [parse]: the enrollment, or what is wrong with the text. */
    sealed interface Parsed {
        data class Ok(val enrollment: Enrollment) : Parsed
        data class Invalid(val error: Error) : Parsed
    }

    /**
     * Reads an enrollment address as it was scanned or typed.
     *
     * @param allowHttp whether a plain `http://` address is taken: in debug builds only
     */
    fun parse(text: String, allowHttp: Boolean): Parsed {
        val uri = uriOf(text.trim()) ?: return Parsed.Invalid(Error.NOT_AN_ADDRESS)
        val scheme = uri.scheme?.lowercase()
        if (!isAllowedScheme(scheme, allowHttp)) {
            return Parsed.Invalid(if (scheme == "http" || scheme == "https") Error.SCHEME else Error.NOT_AN_ADDRESS)
        }
        val rawHost = uri.host ?: return Parsed.Invalid(Error.NOT_AN_ADDRESS)
        if (uri.rawUserInfo != null) return Parsed.Invalid(Error.NOT_AN_ADDRESS)
        val path = uri.rawPath.orEmpty()
        val at = path.lastIndexOf(ENROLL_SEGMENT)
        val enrollToken = if (at >= 0) path.substring(at + ENROLL_SEGMENT.length) else ""
        if (!token.matches(enrollToken)) return Parsed.Invalid(Error.NOT_ENROLLMENT)
        val pin = queryValue(uri.rawQuery, "pin")
        if (pin != null && !isPin(pin)) return Parsed.Invalid(Error.PIN_INVALID)
        val origin = "$scheme://${uri.rawAuthority}"
        val prefix = path.substring(0, at)
        return Parsed.Ok(
            Enrollment(
                enrollUrl = origin + path,
                origin = origin,
                host = rawHost.removePrefix("[").removeSuffix("]"),
                pin = pin,
                syncUrl = origin + prefix + SYNC_PATH,
            ),
        )
    }

    /**
     * Whether the platform may name [url] as the place to sync: an address of the same kinds the
     * phone binds itself with.
     */
    fun isPlatformUrl(url: String, allowHttp: Boolean): Boolean {
        val uri = uriOf(url) ?: return false
        return isAllowedScheme(uri.scheme?.lowercase(), allowHttp) && uri.host != null && uri.rawUserInfo == null
    }

    /** The host of [url] as it is checked against a certificate; empty for no address. */
    fun hostOf(url: String): String = uriOf(url)?.host?.removePrefix("[")?.removeSuffix("]").orEmpty()

    /**
     * Where the phone says it leaves: next to [syncUrl] (`…/sync` becomes `…/leave`), so that it
     * follows the platform when the platform moves the sync address.
     */
    fun leaveUrl(syncUrl: String): String {
        val uri = uriOf(syncUrl) ?: return syncUrl
        val path = uri.rawPath.orEmpty()
        val leavePath = if (path.endsWith(SYNC_SEGMENT)) path.removeSuffix(SYNC_SEGMENT) + LEAVE_SEGMENT else LEAVE_PATH
        return "${uri.scheme}://${uri.rawAuthority}$leavePath"
    }

    /** Whether [pin] is the base64url form of a SHA-256 without padding. */
    fun isPin(pin: String): Boolean =
        token.matches(pin) && runCatching { Base64.getUrlDecoder().decode(pin).size == PIN_BYTES }.getOrDefault(false)

    private fun isAllowedScheme(scheme: String?, allowHttp: Boolean): Boolean = scheme == "https" || (allowHttp && scheme == "http")

    private fun uriOf(text: String): URI? = try {
        URI(text)
    } catch (e: URISyntaxException) {
        null
    }

    private fun queryValue(query: String?, name: String): String? = query.orEmpty().split('&')
        .firstOrNull { it.substringBefore('=') == name }
        ?.substringAfter('=', "")
}
