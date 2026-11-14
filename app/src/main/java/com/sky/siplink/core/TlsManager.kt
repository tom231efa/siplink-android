package com.sky.siplink.core

import android.content.Context
import android.util.Log
import com.sky.siplink.core.logic.CertificateTools
import com.sky.siplink.core.model.CertInfo
import com.sky.siplink.core.model.MediaSecurity
import com.sky.siplink.core.model.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.linphone.core.Core
import org.linphone.core.MediaEncryption
import java.io.File
import java.security.cert.X509Certificate

/**
 * TLS trust and encryption settings.
 *
 * User imported CA certificates are stored as PEM in the app's private files. The SIP core is
 * pointed at a single bundle file made of its stock root CAs followed by the user CAs; the same
 * file is used by the core for SIP over TLS and for LDAP StartTLS.
 */
class TlsManager(context: Context, private val core: Core) {
    sealed interface ImportResult {
        data class Added(val count: Int) : ImportResult
        data object AlreadyTrusted : ImportResult
        data class Invalid(val reason: String) : ImportResult
    }

    private val directory = File(context.filesDir, "tls")
    private val userCaFile = File(directory, "user-ca.pem")
    private val bundleFile = File(directory, "trust-bundle.pem")
    private val sdkRootCa = File(context.filesDir, "share/linphone/rootca.pem")

    /** Root CA path the SIP core used before any user CA was installed. */
    private val defaultRootCa: String? = resolveDefaultRootCa()

    private var userCertificates: List<X509Certificate> = loadUserCertificates()

    private val state = MutableStateFlow<List<CertInfo>>(emptyList())
    val certificates: StateFlow<List<CertInfo>> = state.asStateFlow()

    init {
        applyTrust(reconnect = false)
    }

    /** Parses PEM / DER / PKCS#7 data and trusts every certificate found in it. */
    fun import(data: ByteArray): ImportResult {
        val parsed = try {
            CertificateTools.parse(data)
        } catch (e: Exception) {
            return ImportResult.Invalid("不是有效的 X.509 证书文件（支持 PEM / CRT / CER / DER）")
        }
        val (merged, added) = CertificateTools.merge(userCertificates, parsed)
        if (added == 0) return ImportResult.AlreadyTrusted
        userCertificates = merged
        return try {
            applyTrust(reconnect = true)
            ImportResult.Added(added)
        } catch (e: Exception) {
            Log.e(TAG, "Unable to store imported CA", e)
            ImportResult.Invalid("证书保存失败：${e.message}")
        }
    }

    fun remove(fingerprint: String) {
        userCertificates = userCertificates.filterNot { CertificateTools.fingerprint(it) == fingerprint }
        applyTrust(reconnect = true)
    }

    /** Applies certificate verification and media encryption preferences to the core. */
    fun applySettings(settings: Settings) {
        core.verifyServerCertificates(settings.verifyTlsCertificate)
        core.verifyServerCn(settings.verifyTlsHostname)
        when (settings.mediaSecurity) {
            MediaSecurity.NONE -> {
                core.isMediaEncryptionMandatory = false
                core.mediaEncryption = MediaEncryption.None
            }
            MediaSecurity.SRTP_OPTIONAL -> {
                core.mediaEncryption = MediaEncryption.SRTP
                core.isMediaEncryptionMandatory = false
            }
            MediaSecurity.SRTP_MANDATORY -> {
                core.mediaEncryption = MediaEncryption.SRTP
                core.isMediaEncryptionMandatory = true
            }
        }
    }

    /**
     * Drops the SIP connections and registers again, so that changed trust or verification
     * settings take effect. Only refreshing the registrations would keep using the TLS
     * connections that were accepted before the change. To the SIP stack this is a short
     * loss of the network.
     */
    fun reconnect() {
        core.setSipNetworkReachable(false)
        core.setSipNetworkReachable(true)
    }

    private fun applyTrust(reconnect: Boolean) {
        if (userCertificates.isEmpty()) {
            userCaFile.delete()
            bundleFile.delete()
            defaultRootCa?.let { core.rootCa = it }
        } else {
            directory.mkdirs()
            userCaFile.writeText(userCertificates.joinToString("") { CertificateTools.toPem(it) })
            bundleFile.writeText(CertificateTools.bundle(readDefaultRoots(), userCertificates))
            core.rootCa = bundleFile.absolutePath
        }
        val now = System.currentTimeMillis()
        state.value = userCertificates.map { CertificateTools.describe(it, now) }
        if (reconnect) reconnect()
    }

    private fun loadUserCertificates(): List<X509Certificate> {
        if (!userCaFile.isFile) return emptyList()
        return try {
            CertificateTools.parse(userCaFile.readBytes())
        } catch (e: Exception) {
            Log.e(TAG, "Stored user CA file is unreadable, ignoring it", e)
            emptyList()
        }
    }

    private fun readDefaultRoots(): String? {
        val file = defaultRootCa?.let(::File)?.takeIf { it.isFile } ?: return null
        return try {
            file.readText()
        } catch (e: Exception) {
            Log.w(TAG, "Unable to read stock root CA file", e)
            null
        }
    }

    private fun resolveDefaultRootCa(): String? {
        val config = core.config
        val current = core.rootCa
        if (!current.isNullOrEmpty() && current != bundleFile.absolutePath) {
            config.setString(SettingsStore.SECTION, KEY_DEFAULT_ROOT_CA, current)
            return current
        }
        return config.getString(SettingsStore.SECTION, KEY_DEFAULT_ROOT_CA, null)?.takeIf { File(it).exists() }
            // Location the Linphone SDK copies its bundled root CAs to on Android.
            ?: sdkRootCa.takeIf { it.isFile }?.absolutePath
    }

    private companion object {
        const val TAG = "SipLink.Tls"
        const val KEY_DEFAULT_ROOT_CA = "default_root_ca"
    }
}
