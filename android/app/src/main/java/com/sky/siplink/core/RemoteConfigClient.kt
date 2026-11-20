package com.sky.siplink.core

import android.annotation.SuppressLint
import com.sky.siplink.BuildConfig
import com.sky.siplink.R
import com.sky.siplink.core.logic.CertificatePin
import com.sky.siplink.core.logic.RemoteSync
import com.sky.siplink.core.model.Phrase
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * The requests to the management platform: one JSON POST each, over HTTPS (plain HTTP in debug
 * builds only, see [com.sky.siplink.core.logic.RemoteAddress]). Called off the main thread.
 *
 * Nothing of a request or an answer is logged: the addresses carry the enrollment token, the
 * headers the device key, the answers passwords.
 */
internal object RemoteConfigClient {
    private const val TIMEOUT_MS = 10_000

    /** A document with 2,000 shortcut keys and 100 accounts is a few hundred kilobytes. */
    private const val MAX_ANSWER_BYTES = 4 shl 20

    /** Whom the platform's certificate has to satisfy. */
    sealed interface Trust {
        /** Android's CAs: an address without a pin. */
        data object System : Trust

        /** The first scan: a chain that leads to the certificate with [pin] ([CertificatePin.check]). */
        data class Pinned(val pin: String) : Trust

        /** After it: Android's CAs, or the CA the first scan kept. */
        data class Kept(val ca: X509Certificate) : Trust
    }

    /**
     * @param body the answer, empty for none or one that is not read (any status but 200)
     * @param retryAfterSeconds what the platform asked to wait (429), null for nothing
     * @param pinned the certificate that had the pin ([Trust.Pinned]), to be kept
     */
    class Answer(val status: Int, val body: String, val retryAfterSeconds: Long?, val pinned: X509Certificate?)

    /** The request did not get an answer; [reason] says why, for the user. */
    class Failure(val reason: Phrase) : IOException()

    /**
     * Posts [body] to [url].
     *
     * @param deviceKey sent as the bearer of the request; null for none (binding)
     * @throws Failure when there is no answer
     */
    fun post(url: String, body: String, deviceKey: String?, trust: Trust, nowMs: Long): Answer {
        val connection = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (e: IOException) {
            throw Failure(RemoteSync.NO_CONNECTION)
        }
        val pinning = (trust as? Trust.Pinned)?.let { PinChecker(it.pin, connection.url.host.removePrefix("[").removeSuffix("]"), nowMs) }
        if (connection is HttpsURLConnection) {
            val managers = when (trust) {
                Trust.System -> null
                is Trust.Pinned -> arrayOf<TrustManager>(checkNotNull(pinning))
                is Trust.Kept -> keptTrust(trust.ca).trustManagers
            }
            // The host name is checked against the certificate by the connection's own verifier in every case.
            if (managers != null) connection.sslSocketFactory = SSLContext.getInstance("TLS").apply { init(null, managers, null) }.socketFactory
        }
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            // The bearer must never follow a redirection to somewhere else.
            connection.instanceFollowRedirects = false
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "SipLink/${BuildConfig.VERSION_NAME}")
            if (deviceKey != null) connection.setRequestProperty("Authorization", "Bearer $deviceKey")
            val bytes = body.toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
            val status = connection.responseCode
            val answer = if (status == HttpURLConnection.HTTP_OK) {
                val read = connection.inputStream.use { it.readAtMost(MAX_ANSWER_BYTES + 1) }
                if (read.size > MAX_ANSWER_BYTES) throw Failure(Phrase.of(R.string.remote_answer_too_long))
                read.toString(Charsets.UTF_8)
            } else {
                ""
            }
            val retryAfter = connection.getHeaderField("Retry-After")?.trim()?.toLongOrNull()
            return Answer(status, answer, retryAfter, pinning?.pinned)
        } catch (e: Failure) {
            throw e
        } catch (e: IOException) {
            // A certificate that was refused says so; anything else is no connection.
            val refused = generateSequence<Throwable>(e) { it.cause }.any { it is CertificateException }
            throw Failure(
                when {
                    !refused -> RemoteSync.NO_CONNECTION
                    pinning != null -> RemoteSync.PIN_MISMATCH
                    else -> RemoteSync.UNTRUSTED
                },
            )
        } finally {
            connection.disconnect()
        }
    }

    /**
     * The first scan's check: the chain has to lead to the certificate with [pin] and name [host]
     * ([CertificatePin.check], unit tested). That certificate is the platform's own CA, which no
     * system trusts yet; it is kept as [pinned]. A custom trust manager is unavoidable here: the
     * CA is only known by its pin until the server shows it.
     */
    @SuppressLint("CustomX509TrustManager")
    private class PinChecker(private val pin: String, private val host: String, private val nowMs: Long) : X509TrustManager {
        @Volatile
        var pinned: X509Certificate? = null

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            pinned = CertificatePin.check(chain.toList(), pin, host, nowMs)
        }

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
            throw CertificateException("No client is checked here")

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    /**
     * Android's own check of server certificates, with the CAs of the system and [ca] as the
     * anchors: what every sync after the first scan trusts.
     */
    private fun keptTrust(ca: X509Certificate): TrustManagerFactory {
        val anchors = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        val system = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
        // The CAs the user installed are not trusted by apps by default, and are not here either.
        system.aliases().toList().filter { it.startsWith("system:") }.forEach { anchors.setCertificateEntry(it, system.getCertificate(it)) }
        anchors.setCertificateEntry(PLATFORM_CA_ALIAS, ca)
        return TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(anchors) }
    }

    private const val PLATFORM_CA_ALIAS = "siplink-platform-ca"
}
