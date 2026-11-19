package com.sky.siplink.core.logic

import com.sky.siplink.core.model.AccountDraft
import com.sky.siplink.core.model.DndMode
import com.sky.siplink.core.model.DtmfMode
import com.sky.siplink.core.model.FeatureCodes
import com.sky.siplink.core.model.LdapDirectory
import com.sky.siplink.core.model.LdapSecurity
import com.sky.siplink.core.model.MediaSecurity
import com.sky.siplink.core.model.Settings
import com.sky.siplink.core.model.Shortcut
import com.sky.siplink.core.model.ShortcutAction
import com.sky.siplink.core.model.SipTransport
import com.sky.siplink.core.model.VideoSize
import java.security.cert.X509Certificate

/** An account of a remote configuration; [key] names it there and stays the same across its changes. */
data class RemoteAccount(val key: String, val draft: AccountDraft)

/**
 * A directory of a remote configuration. Its [LdapDirectory.id] and [LdapDirectory.line] are
 * left empty: the phone gives it an id of its own, and [lineKey] names the account it belongs to
 * by that account's [RemoteAccount.key] (empty: every account).
 */
data class RemoteDirectory(val key: String, val directory: LdapDirectory, val lineKey: String)

/** A codec as a remote configuration wants it: by [com.sky.siplink.core.model.Codec.key], on or off. */
data class CodecChoice(val key: String, val enabled: Boolean)

/** The codecs in the order of preference a remote configuration names them. */
data class RemoteCodecs(val audio: List<CodecChoice>, val video: List<CodecChoice>)

/**
 * The document of the management platform: everything it hands out to the phone. Field names
 * and meanings are those of its design (§3.5); a field that is missing takes the app's default
 * value, not the phone's current one.
 *
 * @param codecs null: the SDK's defaults
 * @param settings the handed-out settings; the user's own ones in it are the defaults, and stay
 *   the phone's ([ManagedConfig.localUpdate])
 * @param shortcuts the handed-out keys, without ids
 */
data class RemoteDocument(
    val schema: Int,
    val version: String,
    val generatedAt: String,
    val deviceKey: String,
    val platformName: String,
    val syncUrl: String,
    val clientName: String,
    val region: String,
    val refreshMinutes: Int,
    val configPasswordHash: String,
    val settings: Settings,
    val accounts: List<RemoteAccount>,
    val defaultAccount: String,
    val codecs: RemoteCodecs?,
    val caCertificates: List<X509Certificate>,
    val directories: List<RemoteDirectory>,
    val shortcuts: List<Shortcut>,
) {
    /** A result of [parse]: the document, or every reason it was refused. */
    sealed interface Parsed {
        data class Ok(val document: RemoteDocument) : Parsed
        data class Invalid(val errors: List<String>) : Parsed
    }

    companion object {
        /** The major version of the document this app reads. */
        const val SCHEMA = 1
        const val DEFAULT_REFRESH_MINUTES = 60
        const val MIN_REFRESH_MINUTES = 5
        const val MAX_REFRESH_MINUTES = 1440

        /**
         * Reads and checks a document ([RemoteChecks]). Nothing of a document that has a fault
         * may be applied, so every fault is reported at once.
         */
        fun parse(text: String): Parsed {
            val root = try {
                Json.parse(text)
            } catch (e: Json.SyntaxException) {
                return Parsed.Invalid(listOf("配置不是有效的 JSON"))
            }
            if (root !is Map<*, *>) return Parsed.Invalid(listOf("配置不是 JSON 对象"))
            val errors = ArrayList<String>()
            val document = read(Fields(root, "", errors), errors)
            if (errors.isNotEmpty()) return Parsed.Invalid(errors)
            return RemoteChecks.check(document)
        }

        /**
         * The device key of a document that binds the phone, whether or not the rest of it
         * passes: the platform has bound the phone with it either way. Empty where there is none.
         */
        fun deviceKeyOf(text: String): String =
            ((runCatching { Json.parse(text) }.getOrNull() as? Map<*, *>)?.get("deviceKey") as? String).orEmpty()

        private fun read(top: Fields, errors: MutableList<String>): RemoteDocument {
            val platform = top.obj("platform")
            val client = top.obj("client")
            return RemoteDocument(
                schema = top.int("schema", 0),
                version = top.string("version"),
                generatedAt = top.string("generatedAt"),
                deviceKey = top.string("deviceKey"),
                platformName = platform?.string("name").orEmpty(),
                syncUrl = platform?.string("syncUrl").orEmpty(),
                clientName = client?.string("name").orEmpty(),
                region = client?.string("region").orEmpty(),
                refreshMinutes = top.int("refreshMinutes", DEFAULT_REFRESH_MINUTES).coerceIn(MIN_REFRESH_MINUTES, MAX_REFRESH_MINUTES),
                configPasswordHash = top.string("configPasswordHash"),
                settings = settings(top.obj("settings")),
                accounts = top.objects("accounts").map(::account),
                defaultAccount = top.string("defaultAccount"),
                codecs = top.obj("codecs")?.let { RemoteCodecs(codecs(it, "audio"), codecs(it, "video")) },
                caCertificates = top.strings("caCertificates").flatMapIndexed { index, pem ->
                    runCatching { CertificateTools.parse(pem.toByteArray()) }.getOrNull()
                        ?: emptyList<X509Certificate>().also { errors += "CA 证书 ${index + 1}：无法解析" }
                },
                directories = top.objects("directories").map(::directory),
                shortcuts = top.objects("shortcuts").map(::shortcut),
            )
        }

        private fun settings(fields: Fields?): Settings {
            val d = Settings()
            if (fields == null) return d
            val features = fields.obj("features")
            val f = FeatureCodes()
            return d.copy(
                keepAlive = fields.bool("keepAlive", d.keepAlive),
                intercomAutoAnswer = fields.bool("intercomAutoAnswer", d.intercomAutoAnswer),
                intercomSpeaker = fields.bool("intercomSpeaker", d.intercomSpeaker),
                autoAnswer = fields.bool("autoAnswer", d.autoAnswer),
                autoAnswerSeconds = AutoAnswerRules.seconds(fields.int("autoAnswerSeconds", d.autoAnswerSeconds)),
                verifyTlsCertificate = fields.bool("verifyTlsCertificate", d.verifyTlsCertificate),
                verifyTlsHostname = fields.bool("verifyTlsHostname", d.verifyTlsHostname),
                mediaSecurity = fields.enum("mediaSecurity", d.mediaSecurity, MediaSecurity.entries),
                dndMode = fields.enum("dndMode", d.dndMode, DndMode.entries),
                callWaiting = fields.bool("callWaiting", d.callWaiting),
                rejectAnonymous = fields.bool("rejectAnonymous", d.rejectAnonymous),
                dtmfMode = fields.enum("dtmfMode", d.dtmfMode, DtmfMode.entries),
                forwardAlways = fields.string("forwardAlways", d.forwardAlways),
                forwardBusy = fields.string("forwardBusy", d.forwardBusy),
                forwardNoAnswer = fields.string("forwardNoAnswer", d.forwardNoAnswer),
                forwardNoAnswerSeconds = fields.int("forwardNoAnswerSeconds", d.forwardNoAnswerSeconds),
                features = if (features == null) f else FeatureCodes(
                    park = features.string("park", f.park),
                    listen = features.string("listen", f.listen),
                    whisper = features.string("whisper", f.whisper),
                    barge = features.string("barge", f.barge),
                    forceHangUp = features.string("forceHangUp", f.forceHangUp),
                    pickup = features.string("pickup", f.pickup),
                    groupPickup = features.string("groupPickup", f.groupPickup),
                    voicemail = features.string("voicemail", f.voicemail),
                    intercom = features.string("intercom", f.intercom),
                    dndOn = features.string("dndOn", f.dndOn),
                    dndOff = features.string("dndOff", f.dndOff),
                ),
                videoCalls = fields.bool("videoCalls", d.videoCalls),
                videoSize = fields.enum("videoSize", d.videoSize, VideoSize.entries),
                videoFrameRate = fields.int("videoFrameRate", d.videoFrameRate),
                autoUpdateCheck = fields.bool("autoUpdateCheck", d.autoUpdateCheck),
                userAgent = fields.string("userAgent", d.userAgent),
                adaptiveRateControl = fields.bool("adaptiveRateControl", d.adaptiveRateControl),
                echoCancellation = fields.bool("echoCancellation", d.echoCancellation),
                ipv6 = fields.bool("ipv6", d.ipv6),
                callRecording = fields.bool("callRecording", d.callRecording),
                recordByDefault = fields.bool("recordByDefault", d.recordByDefault),
                // Clamped, not refused: a newer platform may offer a larger room than this app
                // holds, and the rest of the document must still be taken; the app's own limit wins.
                roomLimit = RoomRules.limitOf(fields.int("roomLimit", d.roomLimit)),
                autoNetwork = fields.bool("autoNetwork", d.autoNetwork),
                stunServer = fields.string("stunServer", d.stunServer).takeIf(MediaAdapt::isValidStunServer)
                    ?: d.stunServer.also { fields.fault("stunServer") },
            )
        }

        private fun account(fields: Fields): RemoteAccount {
            val d = AccountDraft()
            return RemoteAccount(
                key = fields.string("key"),
                draft = AccountDraft(
                    name = fields.string("name", d.name),
                    username = fields.string("username", d.username),
                    password = fields.string("password", d.password),
                    authUser = fields.string("authUser", d.authUser),
                    displayName = fields.string("displayName", d.displayName),
                    domain = fields.string("domain", d.domain),
                    port = fields.string("port", d.port),
                    server = fields.string("server", d.server),
                    backupServer = fields.string("backupServer", d.backupServer),
                    dnsServer = fields.string("dnsServer", d.dnsServer),
                    transport = fields.enum("transport", d.transport, SipTransport.entries),
                    expires = fields.int("expires", d.expires),
                    subscribeMwi = fields.bool("subscribeMwi", d.subscribeMwi),
                    enabled = fields.bool("enabled", d.enabled),
                ),
            )
        }

        private fun directory(fields: Fields): RemoteDirectory {
            val d = LdapDirectory()
            return RemoteDirectory(
                key = fields.string("key"),
                lineKey = fields.string("line"),
                directory = LdapDirectory(
                    name = fields.string("name", d.name),
                    enabled = fields.bool("enabled", d.enabled),
                    host = fields.string("host", d.host),
                    port = fields.int("port", d.port),
                    security = fields.enum("security", d.security, LdapSecurity.entries),
                    bindDn = fields.string("bindDn", d.bindDn),
                    password = fields.string("password", d.password),
                    baseDn = fields.string("baseDn", d.baseDn),
                    lookupIncoming = fields.bool("lookupIncoming", d.lookupIncoming),
                    lookupOutgoing = fields.bool("lookupOutgoing", d.lookupOutgoing),
                    maxResults = fields.int("maxResults", d.maxResults),
                    nameAttributes = fields.string("nameAttributes", d.nameAttributes),
                    numberAttributes = fields.string("numberAttributes", d.numberAttributes),
                    nameFilter = fields.string("nameFilter", d.nameFilter),
                    numberFilter = fields.string("numberFilter", d.numberFilter),
                ),
            )
        }

        /** A key with the words of the app's CSV format for its type and icon ([ShortcutRules.typeWord], [ShortcutRules.iconWords]). */
        private fun shortcut(fields: Fields): Shortcut {
            val actionWord = fields.string("action")
            val iconWord = fields.string("icon")
            val action = ShortcutRules.parseAction(actionWord) ?: fields.fault("action")
            val icon = if (iconWord.isBlank()) null else ShortcutRules.parseIcon(iconWord) ?: fields.fault("icon")
            return Shortcut(
                id = 0,
                label = fields.string("label"),
                number = fields.string("number"),
                action = action ?: ShortcutAction.SPEED_DIAL,
                category = fields.string("category"),
                line = fields.string("line"),
                icon = icon,
            )
        }

        private fun codecs(fields: Fields, name: String): List<CodecChoice> =
            fields.objects(name).map { CodecChoice(it.string("key"), it.bool("enabled", true)) }
    }
}

/**
 * The fields of one JSON object of the document, read with a type each. A field that is missing
 * or null gives the default; one of another type is reported to [errors] as a fault of [where].
 */
private class Fields(private val map: Map<*, *>, private val where: String, private val errors: MutableList<String>) {
    fun string(name: String, default: String = ""): String = typed(name, default)

    fun bool(name: String, default: Boolean): Boolean = typed(name, default)

    fun int(name: String, default: Int): Int {
        val number = typed<Long?>(name, null) ?: return default
        return if (number in Int.MIN_VALUE..Int.MAX_VALUE) number.toInt() else default.also { fault(name) }
    }

    fun <T : Enum<T>> enum(name: String, default: T, values: List<T>): T {
        val word = typed<String?>(name, null) ?: return default
        return values.firstOrNull { it.name == word } ?: default.also { fault(name) }
    }

    fun obj(name: String): Fields? = typed<Map<*, *>?>(name, null)?.let { Fields(it, "$where$name.", errors) }

    fun objects(name: String): List<Fields> = typed<List<*>?>(name, null).orEmpty().mapIndexedNotNull { index, item ->
        if (item is Map<*, *>) Fields(item, "$where$name[$index].", errors) else null.also { fault("$name[$index]") }
    }

    fun strings(name: String): List<String> = typed<List<*>?>(name, null).orEmpty().mapIndexedNotNull { index, item ->
        item as? String ?: null.also { fault("$name[$index]") }
    }

    /** Reports [name] as a field of the wrong type or value; null, for the caller to go on with a default. */
    fun fault(name: String): Nothing? {
        errors += "$where$name 格式不正确"
        return null
    }

    private inline fun <reified T> typed(name: String, default: T): T {
        val value = map[name] ?: return default
        return value as? T ?: default.also { fault(name) }
    }
}
