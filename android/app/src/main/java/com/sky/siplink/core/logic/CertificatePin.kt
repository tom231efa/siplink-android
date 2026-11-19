package com.sky.siplink.core.logic

import java.net.InetAddress
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Date

/**
 * Trust in the management platform's own certificate authority, which no system knows: the QR
 * code names it by a pin, the SHA-256 of its SubjectPublicKeyInfo ([pinOf]). A server chain is
 * taken when it leads from the server's certificate up to a certificate with that pin, every
 * link signed by the next one, and the server's certificate names the host that was asked
 * ([check]).
 */
object CertificatePin {
    /** Thrown when a chain does not lead to the pinned certificate, or does not fit the host. */
    class Mismatch(message: String) : CertificateException(message)

    private const val SAN_DNS = 2
    private const val SAN_IP = 7
    private const val SERVER_AUTH = "1.3.6.1.5.5.7.3.1"

    /** The pin of [certificate]: SHA-256 over the DER of its SubjectPublicKeyInfo, base64url without padding. */
    fun pinOf(certificate: X509Certificate): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(certificate.publicKey.encoded))

    /**
     * Checks a chain as a server presented it, its own certificate first.
     *
     * @param host the name or literal address that was asked, without brackets
     * @return the certificate with [pin]: the one to keep and trust from now on
     * @throws Mismatch when no certificate has the pin, a link is not signed by the next one,
     *   a certificate is out of its validity, or the server's certificate does not name [host]
     */
    fun check(chain: List<X509Certificate>, pin: String, host: String, nowMs: Long): X509Certificate {
        val anchor = chain.indexOfFirst { pinOf(it) == pin }
        if (anchor < 0) throw Mismatch("No certificate of the chain has the pin")
        val now = Date(nowMs)
        for (index in 0..anchor) {
            val certificate = chain[index]
            try {
                certificate.checkValidity(now)
            } catch (e: GeneralSecurityException) {
                throw Mismatch("Certificate $index is not valid now")
            }
            if (index == anchor) break
            val issuer = chain[index + 1]
            if (certificate.issuerX500Principal != issuer.subjectX500Principal || issuer.basicConstraints < 0) {
                throw Mismatch("Certificate $index is not issued by the next one")
            }
            try {
                certificate.verify(issuer.publicKey)
            } catch (e: GeneralSecurityException) {
                throw Mismatch("Certificate $index is not signed by the next one")
            }
        }
        val leaf = chain[0]
        val usages = leaf.extendedKeyUsage
        if (usages != null && SERVER_AUTH !in usages) throw Mismatch("The server certificate is not for servers")
        if (!names(leaf, host)) throw Mismatch("The server certificate does not name the host")
        return chain[anchor]
    }

    /**
     * Whether [certificate] names [host] among its subject alternative names: an address as an
     * address, a host name as a name (`*.example.com` standing for one label in front of it).
     */
    fun names(certificate: X509Certificate, host: String): Boolean {
        val entries = certificate.subjectAlternativeNames ?: return false
        val address = literalAddress(host)
        return entries.any { entry ->
            val type = entry[0] as? Int
            val value = entry[1] as? String ?: return@any false
            when {
                address != null -> type == SAN_IP && literalAddress(value)?.contentEquals(address) == true
                type == SAN_DNS -> nameMatches(value, host)
                else -> false
            }
        }
    }

    private fun nameMatches(pattern: String, host: String): Boolean {
        val name = host.trimEnd('.').lowercase()
        val wanted = pattern.trimEnd('.').lowercase()
        if (!wanted.startsWith("*.")) return wanted == name
        val rest = wanted.substring(1)
        return name.endsWith(rest) && name.length > rest.length && '.' !in name.substring(0, name.length - rest.length)
    }

    /** The bytes of [text] when it is a literal IPv4 or IPv6 address; null for a host name. */
    private fun literalAddress(text: String): ByteArray? {
        val plain = text.removePrefix("[").removeSuffix("]")
        val isV4 = AccountRoute.isAddress(plain)
        val isV6 = ':' in plain && plain.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' || it == ':' || it == '.' }
        // Only literals reach the resolver here, which does not look them up.
        return if (isV4 || isV6) runCatching { InetAddress.getByName(plain).address }.getOrNull() else null
    }
}
