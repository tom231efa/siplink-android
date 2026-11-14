package com.sky.siplink.core.logic

import com.sky.siplink.core.model.CertInfo
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64

/** Parsing / formatting helpers for user supplied CA certificates (pure JVM, no Android APIs). */
object CertificateTools {
    private const val PEM_BEGIN = "-----BEGIN CERTIFICATE-----"
    private const val PEM_END = "-----END CERTIFICATE-----"

    /**
     * Parses PEM (one or several certificates), DER or PKCS#7 encoded data.
     * @throws CertificateException when the data holds no X.509 certificate
     */
    fun parse(data: ByteArray): List<X509Certificate> {
        val factory = CertificateFactory.getInstance("X.509")
        val certificates = factory.generateCertificates(ByteArrayInputStream(data))
            .filterIsInstance<X509Certificate>()
        if (certificates.isEmpty()) throw CertificateException("No X.509 certificate found")
        return certificates
    }

    fun toPem(certificate: X509Certificate): String {
        val body = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(certificate.encoded)
        return "$PEM_BEGIN\n$body\n$PEM_END\n"
    }

    fun fingerprint(certificate: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
            .joinToString(":") { "%02X".format(it) }

    fun describe(certificate: X509Certificate, nowMs: Long): CertInfo = CertInfo(
        fingerprint = fingerprint(certificate),
        subject = commonName(certificate.subjectX500Principal.name),
        issuer = commonName(certificate.issuerX500Principal.name),
        notBeforeMs = certificate.notBefore.time,
        notAfterMs = certificate.notAfter.time,
        isCa = certificate.basicConstraints >= 0,
        expired = nowMs > certificate.notAfter.time,
    )

    /** Extracts the CN from an RFC 2253 distinguished name, falling back to the whole DN. */
    fun commonName(distinguishedName: String): String {
        val match = Regex("(?:^|,)\\s*CN=((?:\\\\.|[^,])+)", RegexOption.IGNORE_CASE).find(distinguishedName)
        return match?.groupValues?.get(1)?.replace(Regex("\\\\(.)"), "$1")?.trim() ?: distinguishedName
    }

    /**
     * Merges [existing] and [added], dropping duplicates (same fingerprint) while keeping order.
     * @return the merged list and how many certificates of [added] were really new
     */
    fun merge(
        existing: List<X509Certificate>,
        added: List<X509Certificate>,
    ): Pair<List<X509Certificate>, Int> {
        val seen = existing.mapTo(mutableSetOf()) { fingerprint(it) }
        val merged = existing.toMutableList()
        var newCount = 0
        for (certificate in added) {
            if (seen.add(fingerprint(certificate))) {
                merged += certificate
                newCount++
            }
        }
        return merged to newCount
    }

    /** Builds the trust bundle handed to the SIP core: stock root CAs followed by the user CAs. */
    fun bundle(defaultRootsPem: String?, userCertificates: List<X509Certificate>): String = buildString {
        if (!defaultRootsPem.isNullOrBlank()) {
            append(defaultRootsPem.trimEnd())
            append("\n")
        }
        userCertificates.forEach { append(toPem(it)) }
    }
}
