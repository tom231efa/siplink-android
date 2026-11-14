package com.sky.siplink.core.model

enum class SipTransport { UDP, TCP, TLS }

enum class RegState { DISABLED, NONE, PROGRESS, OK, FAILED }

/** Editable form of a SIP account, before it is handed to the SIP core. */
data class AccountDraft(
    /** Name the user gave the account. It stays on the device and is never sent to the server. */
    val name: String = "",
    val username: String = "",
    val password: String = "",
    val domain: String = "",
    val displayName: String = "",
    val authUser: String = "",
    /** Optional registrar / outbound proxy as host[:port]. Empty means "same as domain". */
    val server: String = "",
    /** Optional second server as host[:port], used while [server] is out of reach. */
    val backupServer: String = "",
    /** Optional DNS server of this account as address[:port]; the servers are looked up there instead of by the system. */
    val dnsServer: String = "",
    val transport: SipTransport = SipTransport.UDP,
    val expires: Int = 3600,
    /** Ask the PBX to report new voicemail (SUBSCRIBE); a PBX that reports it unasked needs none. */
    val subscribeMwi: Boolean = false,
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
    val name: String = "",
    val backupServer: String = "",
    val dnsServer: String = "",
    val subscribeMwi: Boolean = false,
    /** The account is on its backup server at the moment. */
    val onBackup: Boolean = false,
) {
    val identity: String get() = "$username@$domain"

    /** What the account is called in lists: its name, else the display name, else the user. */
    val label: String get() = name.ifBlank { displayName.ifBlank { username } }

    /** True when [query] is part of anything the user may know the account by. */
    fun matches(query: String): Boolean =
        query.isBlank() || name.contains(query, true) || identity.contains(query, true) || displayName.contains(query, true)

    fun toDraft(): AccountDraft = AccountDraft(
        name = name,
        username = username,
        password = "",
        domain = domain,
        displayName = displayName,
        authUser = authUser,
        server = server,
        backupServer = backupServer,
        dnsServer = dnsServer,
        transport = transport,
        expires = expires,
        subscribeMwi = subscribeMwi,
        enabled = enabled,
    )
}

enum class CallPhase { INCOMING, OUTGOING, CONNECTED, HELD, ENDED }

enum class AudioRoute { EARPIECE, SPEAKER, BLUETOOTH, HEADSET }

/** The second call of the device: either parked on hold or ringing on top of the current call. */
data class SideCall(val name: String, val address: String)

enum class TransferStatus { IN_PROGRESS, SUCCEEDED, FAILED }

/** Supervisor ("班长") call modes, switched on the PBX with DTMF 4 / 5 / 6 during the call. */
enum class SupervisionMode(val dtmf: Char) { LISTEN('4'), WHISPER('5'), BARGE('6') }

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
    /** Another call kept on hold while this one is active. */
    val heldCall: SideCall? = null,
    /** Another call ringing while this one is active (call waiting). */
    val waitingCall: SideCall? = null,
    /** True while this call is the consultation leg of an attended transfer. */
    val consulting: Boolean = false,
    val transfer: TransferStatus? = null,
    /** True once the call was parked and the PBX is announcing the parking slot on it. */
    val parked: Boolean = false,
    /** True when both calls were merged into a local three-way conference. */
    val conference: Boolean = false,
    val supervision: SupervisionMode? = null,
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
    /** The contacts come from the local copy: the server has not answered (yet), or had nothing. */
    val fromCache: Boolean = false,
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


enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class MediaSecurity { NONE, SRTP_OPTIONAL, SRTP_MANDATORY }

/**
 * PBX feature codes. They are plain dial strings understood by the PBX, so they must match its
 * dial plan; the defaults match the sample Asterisk dial plan in testenv/.
 */
data class FeatureCodes(
    /** Extension a call is blind-transferred to in order to park it. */
    val park: String = "700",
    val listen: String = "*91",
    val whisper: String = "*92",
    val barge: String = "*93",
    val forceHangUp: String = "*94",
    val pickup: String = "**",
    val groupPickup: String = "*8",
    val voicemail: String = "*97",
    /** Prefix of an intercom call: the PBX asks the called phone to answer by itself. */
    val intercom: String = "*80",
    /** Dialled to switch do not disturb on and off on the PBX ([DndMode.SERVER]). */
    val dndOn: String = "*78",
    val dndOff: String = "*79",
)

/** Who refuses the calls while do not disturb is on. */
enum class DndMode {
    /** This phone: it declines every incoming call itself. */
    SOFTWARE,

    /** The PBX: it is told by a call to its feature code and deals with the callers itself. */
    SERVER,
}

/** How the keys pressed during a call reach the other side. */
enum class DtmfMode {
    /** As events in the audio stream (RFC 2833 / 4733): what nearly every PBX expects. */
    RFC2833,

    /** As SIP INFO requests, for a PBX or trunk that asks for those. */
    SIP_INFO,
}

/** Where the strip that leads back to a running call sits on the main screens. */
enum class BannerPosition { TOP, BOTTOM }

/** An audio codec of the SIP core; the list order is the order of preference. */
data class AudioCodec(val mime: String, val clockRate: Int, val channels: Int, val enabled: Boolean) {
    val key: String get() = "$mime/$clockRate/$channels"
}

data class Settings(
    val keepAlive: Boolean = true,
    val intercomAutoAnswer: Boolean = false,
    val intercomSpeaker: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = false,
    val callBannerPosition: BannerPosition = BannerPosition.BOTTOM,
    val verifyTlsCertificate: Boolean = true,
    val verifyTlsHostname: Boolean = true,
    val mediaSecurity: MediaSecurity = MediaSecurity.NONE,
    /** Do not disturb is on; in [DndMode.SERVER] this is what the PBX was last told. */
    val doNotDisturb: Boolean = false,
    val dndMode: DndMode = DndMode.SOFTWARE,
    val callWaiting: Boolean = true,
    /** Refuse callers who withhold their number. */
    val rejectAnonymous: Boolean = false,
    val dtmfMode: DtmfMode = DtmfMode.RFC2833,
    /** Forwarding targets; an empty string disables the rule. */
    val forwardAlways: String = "",
    val forwardBusy: String = "",
    val forwardNoAnswer: String = "",
    val forwardNoAnswerSeconds: Int = 20,
    val features: FeatureCodes = FeatureCodes(),
)
