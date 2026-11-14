package com.sky.siplink.core.model

enum class SipTransport { UDP, TCP, TLS }

enum class RegState { DISABLED, NONE, PROGRESS, OK, FAILED }

/** Editable form of a SIP account, before it is handed to the SIP core. */
data class AccountDraft(
    val username: String = "",
    val password: String = "",
    val domain: String = "",
    val displayName: String = "",
    val authUser: String = "",
    /** Optional registrar / outbound proxy as host[:port]. Empty means "same as domain". */
    val server: String = "",
    val transport: SipTransport = SipTransport.UDP,
    val expires: Int = 3600,
    val enabled: Boolean = true,
)

/** Snapshot of an account living in the SIP core. */
data class SipAccount(
    val id: String,
    val username: String,
    val domain: String,
    val displayName: String,
    val authUser: String,
    val server: String,
    val transport: SipTransport,
    val expires: Int,
    val enabled: Boolean,
    val state: RegState,
    val isDefault: Boolean,
    val error: String? = null,
) {
    val identity: String get() = "$username@$domain"
    val label: String get() = displayName.ifBlank { username }

    fun toDraft(): AccountDraft = AccountDraft(
        username = username,
        password = "",
        domain = domain,
        displayName = displayName,
        authUser = authUser,
        server = server,
        transport = transport,
        expires = expires,
        enabled = enabled,
    )
}

enum class CallPhase { INCOMING, OUTGOING, CONNECTED, HELD, ENDED }

enum class AudioRoute { EARPIECE, SPEAKER, BLUETOOTH, HEADSET }

data class CallInfo(
    val phase: CallPhase,
    val remoteName: String,
    val remoteAddress: String,
    val accountLabel: String,
    val incoming: Boolean,
    val intercom: Boolean,
    val muted: Boolean = false,
    val route: AudioRoute = AudioRoute.EARPIECE,
    val availableRoutes: List<AudioRoute> = listOf(AudioRoute.EARPIECE, AudioRoute.SPEAKER),
    /** SystemClock.elapsedRealtime() at the moment media started, null before that. */
    val connectedAt: Long? = null,
    val endReason: String? = null,
)

enum class HistoryKind { OUTGOING, INCOMING, MISSED, FAILED }

data class HistoryEntry(
    val id: String,
    val name: String,
    val address: String,
    val kind: HistoryKind,
    val startedAtMs: Long,
    val durationSec: Int,
    val accountLabel: String,
)

data class DirectoryContact(
    val name: String,
    val number: String,
    /** What gets dialled: a full SIP URI when the directory provided one, else the number. */
    val callTarget: String,
    val organization: String = "",
)

data class DirectoryState(
    val query: String = "",
    val searching: Boolean = false,
    val contacts: List<DirectoryContact> = emptyList(),
    val limitReached: Boolean = false,
)

enum class LdapCertCheck { DEFAULT, ENABLED, DISABLED }

data class LdapConfig(
    val enabled: Boolean = true,
    val server: String = "",
    val useStartTls: Boolean = true,
    val certCheck: LdapCertCheck = LdapCertCheck.DEFAULT,
    val bindDn: String = "",
    val password: String = "",
    val baseDn: String = "",
    val filter: String = DEFAULT_FILTER,
    val nameAttributes: String = DEFAULT_NAME_ATTRIBUTES,
    val sipAttributes: String = DEFAULT_SIP_ATTRIBUTES,
    val sipDomain: String = "",
    val maxResults: Int = 50,
    val minChars: Int = 1,
    val timeoutSec: Int = 5,
    val delayMs: Int = 500,
) {
    companion object {
        const val DEFAULT_FILTER = "(|(cn=*%s*)(sn=*%s*)(givenName=*%s*)(telephoneNumber=*%s*)(mobile=*%s*))"
        const val DEFAULT_NAME_ATTRIBUTES = "cn,displayName,sn+givenName"
        const val DEFAULT_SIP_ATTRIBUTES = "telephoneNumber,mobile,ipPhone"
    }
}

data class CertInfo(
    val fingerprint: String,
    val subject: String,
    val issuer: String,
    val notBeforeMs: Long,
    val notAfterMs: Long,
    val isCa: Boolean,
    val expired: Boolean,
)

enum class IntercomHeaderMode { CALL_INFO, ALERT_INFO, ANSWER_MODE, ALL }

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class MediaSecurity { NONE, SRTP_OPTIONAL, SRTP_MANDATORY }

data class Settings(
    val keepAlive: Boolean = true,
    val intercomAutoAnswer: Boolean = false,
    val intercomSpeaker: Boolean = true,
    val intercomHeaderMode: IntercomHeaderMode = IntercomHeaderMode.CALL_INFO,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val verifyTlsCertificate: Boolean = true,
    val verifyTlsHostname: Boolean = true,
    val mediaSecurity: MediaSecurity = MediaSecurity.NONE,
)
