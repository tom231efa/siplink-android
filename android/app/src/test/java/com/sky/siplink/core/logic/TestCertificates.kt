package com.sky.siplink.core.logic

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.net.InetAddress
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Certificates made while the tests run, never stored: a CA and the server certificates it signs,
 * as the management platform makes them (ECDSA P-256, §6.7 of its design). The JDK has no public
 * API to issue a certificate, so the DER is written here.
 */
object TestCertificates {
    class Issued(val certificate: X509Certificate, val keys: KeyPair)

    private const val DAY_MS = 24 * 60 * 60 * 1000L
    private const val ECDSA_SHA256 = "1.2.840.10045.4.3.2"

    fun keys(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()

    fun ca(name: String = "SipLink Test CA", keys: KeyPair = keys(), now: Long = System.currentTimeMillis()): Issued =
        issue(name, keys, name, keys, now - DAY_MS, now + 3650 * DAY_MS, ca = true, dnsNames = emptyList(), addresses = emptyList())

    /**
     * A server certificate for [dnsNames] and [addresses], signed by [issuer].
     *
     * @param serverAuth whether its extended key usage is for servers (otherwise for clients only)
     */
    fun server(
        issuer: Issued,
        dnsNames: List<String> = listOf("platform.example.com"),
        addresses: List<String> = emptyList(),
        notBefore: Long = System.currentTimeMillis() - DAY_MS,
        notAfter: Long = System.currentTimeMillis() + 397 * DAY_MS,
        serverAuth: Boolean = true,
    ): Issued = issue(
        subject = dnsNames.firstOrNull() ?: addresses.first(),
        subjectKeys = keys(),
        issuerName = commonName(issuer.certificate),
        issuerKeys = issuer.keys,
        notBefore = notBefore,
        notAfter = notAfter,
        ca = false,
        dnsNames = dnsNames,
        addresses = addresses,
        serverAuth = serverAuth,
    )

    private fun commonName(certificate: X509Certificate): String = CertificateTools.commonName(certificate.subjectX500Principal.name)

    private fun issue(
        subject: String,
        subjectKeys: KeyPair,
        issuerName: String,
        issuerKeys: KeyPair,
        notBefore: Long,
        notAfter: Long,
        ca: Boolean,
        dnsNames: List<String>,
        addresses: List<String>,
        serverAuth: Boolean = true,
    ): Issued {
        val extensions = buildList {
            add(extension("2.5.29.19", critical = true, seq(if (ca) bool(true) else ByteArray(0))))
            if (!ca) {
                val names = dnsNames.map { tlv(0x82, it.toByteArray()) } + addresses.map { tlv(0x87, InetAddress.getByName(it).address) }
                add(extension("2.5.29.17", critical = false, seq(*names.toTypedArray())))
                add(extension("2.5.29.37", critical = false, seq(oid(if (serverAuth) "1.3.6.1.5.5.7.3.1" else "1.3.6.1.5.5.7.3.2"))))
            }
        }
        val tbs = seq(
            tlv(0xA0, integer(BigInteger.valueOf(2))),
            integer(BigInteger(64, java.util.Random()).add(BigInteger.ONE)),
            seq(oid(ECDSA_SHA256)),
            name(issuerName),
            seq(time(notBefore), time(notAfter)),
            name(subject),
            subjectKeys.public.encoded,
            tlv(0xA3, seq(*extensions.toTypedArray())),
        )
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(issuerKeys.private)
            update(tbs)
            sign()
        }
        val der = seq(tbs, seq(oid(ECDSA_SHA256)), tlv(0x03, byteArrayOf(0) + signature))
        val certificate = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate
        return Issued(certificate, subjectKeys)
    }

    private fun extension(oid: String, critical: Boolean, value: ByteArray): ByteArray =
        if (critical) seq(oid(oid), bool(true), tlv(0x04, value)) else seq(oid(oid), tlv(0x04, value))

    private fun name(commonName: String): ByteArray = seq(tlv(0x31, seq(oid("2.5.4.3"), tlv(0x0C, commonName.toByteArray()))))

    private fun time(ms: Long): ByteArray {
        val format = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return tlv(0x17, format.format(Date(ms)).toByteArray())
    }

    private fun bool(value: Boolean): ByteArray = tlv(0x01, byteArrayOf(if (value) -1 else 0))

    private fun integer(value: BigInteger): ByteArray = tlv(0x02, value.toByteArray())

    private fun oid(dotted: String): ByteArray {
        val parts = dotted.split('.').map { it.toLong() }
        val out = ByteArrayOutputStream()
        out.write((parts[0] * 40 + parts[1]).toInt())
        for (part in parts.drop(2)) {
            val groups = ArrayList<Int>()
            var rest = part
            do {
                groups += (rest and 0x7F).toInt()
                rest = rest shr 7
            } while (rest > 0)
            groups.reversed().forEachIndexed { index, group -> out.write(if (index < groups.size - 1) group or 0x80 else group) }
        }
        return tlv(0x06, out.toByteArray())
    }

    private fun seq(vararg parts: ByteArray): ByteArray = tlv(0x30, parts.fold(ByteArray(0)) { all, part -> all + part })

    private fun tlv(tag: Int, content: ByteArray): ByteArray {
        val length = content.size
        val header = when {
            length < 0x80 -> byteArrayOf(tag.toByte(), length.toByte())
            length < 0x100 -> byteArrayOf(tag.toByte(), 0x81.toByte(), length.toByte())
            else -> byteArrayOf(tag.toByte(), 0x82.toByte(), (length shr 8).toByte(), length.toByte())
        }
        return header + content
    }
}
