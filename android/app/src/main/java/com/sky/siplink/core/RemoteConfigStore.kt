package com.sky.siplink.core

import android.annotation.SuppressLint
import android.content.Context
import androidx.core.content.edit
import com.sky.siplink.core.logic.Json
import com.sky.siplink.core.logic.PhraseCodec
import com.sky.siplink.core.model.Phrase
import java.util.UUID

/**
 * What the app remembers of its remote configuration, in shared preferences of their own: private
 * to the app, left out of backups (the manifest allows none), and never written to a log. The
 * device key in it is what the platform knows the phone by.
 *
 * Everything but the installation id goes when the remote configuration goes ([clear]).
 */
internal class RemoteConfigStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    /** The errors are kept as phrases, by the names of their resources, and shown in the language of the moment. */
    @SuppressLint("DiscouragedApi") // Looked up by name on purpose: the ids of the resources change from build to build.
    private val codec = context.resources.let { resources ->
        PhraseCodec(resources::getResourceEntryName) { name, plural ->
            resources.getIdentifier(name, if (plural) "plurals" else "string", context.packageName).takeIf { it != 0 }
        }
    }

    /** Made once per installation and sent when the phone binds itself; it outlives the configurations. */
    val installId: String
        get() = preferences.getString(KEY_INSTALL_ID, null) ?: UUID.randomUUID().toString().also { id ->
            preferences.edit(commit = true) { putString(KEY_INSTALL_ID, id) }
        }

    /** The phone is bound to a platform: it has a device key. */
    val enrolled: Boolean get() = deviceKey.isNotEmpty()

    val deviceKey: String get() = text(KEY_DEVICE_KEY)
    val syncUrl: String get() = text(KEY_SYNC_URL)

    /** PEM of the platform's CA that the first scan trusted; empty: the system's CAs decide. */
    val platformCa: String get() = text(KEY_PLATFORM_CA)

    /** Version of the document that was applied in full; empty before the first one. */
    val version: String get() = text(KEY_VERSION)

    /** A document of the platform was applied: there is a configuration to take back when it goes. */
    val applied: Boolean get() = preferences.getBoolean(KEY_APPLIED, false)

    val refreshMinutes: Int get() = preferences.getInt(KEY_REFRESH_MINUTES, 0)
    val platformName: String get() = text(KEY_PLATFORM_NAME)
    val clientName: String get() = text(KEY_CLIENT_NAME)
    val region: String get() = text(KEY_REGION)
    val generatedAt: String get() = text(KEY_GENERATED_AT)
    val lastAttemptMs: Long get() = preferences.getLong(KEY_LAST_ATTEMPT, 0)
    val lastSyncMs: Long get() = preferences.getLong(KEY_LAST_SYNC, 0)

    /** What went wrong with the last sync; null: it worked. */
    val lastError: Phrase? get() = codec.decode(text(KEY_LAST_ERROR))?.first()

    /** The last document the phone tried: its version, when, and why it was refused (empty: it was not). */
    val appliedVersion: String get() = text(KEY_APPLIED_VERSION)
    val appliedAt: String get() = text(KEY_APPLIED_AT)
    val appliedErrors: List<Phrase> get() = codec.decode(text(KEY_APPLIED_ERROR)).orEmpty()

    /** Local ids of the handed-out accounts and directories, by their keys in the document. */
    val accountIds: Map<String, String> get() = map(KEY_ACCOUNT_IDS)
    val directoryIds: Map<String, String> get() = map(KEY_DIRECTORY_IDS)

    /** Remembers the binding to a platform; written at once, as the platform has already used up the QR code. */
    fun enroll(deviceKey: String, syncUrl: String, platformCa: String) = preferences.edit(commit = true) {
        putString(KEY_DEVICE_KEY, deviceKey)
        putString(KEY_SYNC_URL, syncUrl)
        putString(KEY_PLATFORM_CA, platformCa)
    }

    /** What the platform says about itself and this client, as shown on the screen. */
    fun describe(platformName: String, clientName: String, region: String, generatedAt: String, refreshMinutes: Int, syncUrl: String) =
        preferences.edit {
            putString(KEY_PLATFORM_NAME, platformName)
            putString(KEY_CLIENT_NAME, clientName)
            putString(KEY_REGION, region)
            putString(KEY_GENERATED_AT, generatedAt)
            putInt(KEY_REFRESH_MINUTES, refreshMinutes)
            putString(KEY_SYNC_URL, syncUrl)
        }

    /** A document was applied in full ([version]), or in part (empty [version]: the next sync brings it again). */
    fun applied(version: String, accountIds: Map<String, String>, directoryIds: Map<String, String>) = preferences.edit(commit = true) {
        putString(KEY_VERSION, version)
        putBoolean(KEY_APPLIED, true)
        putString(KEY_ACCOUNT_IDS, Json.write(accountIds))
        putString(KEY_DIRECTORY_IDS, Json.write(directoryIds))
    }

    fun tried(version: String, at: String, errors: List<Phrase>) = preferences.edit {
        putString(KEY_APPLIED_VERSION, version)
        putString(KEY_APPLIED_AT, at)
        putString(KEY_APPLIED_ERROR, if (errors.isEmpty()) "" else codec.encode(errors))
    }

    fun attempted(atMs: Long) = preferences.edit { putLong(KEY_LAST_ATTEMPT, atMs) }

    /** The result of a sync that ended at [atMs]: null [error] for one that worked. */
    fun synced(atMs: Long, error: Phrase?) = preferences.edit {
        if (error == null) putLong(KEY_LAST_SYNC, atMs)
        putString(KEY_LAST_ERROR, error?.let { codec.encode(listOf(it)) }.orEmpty())
    }

    /** Forgets the remote configuration; the installation id stays. */
    fun clear() = preferences.edit(commit = true) {
        val installId = preferences.getString(KEY_INSTALL_ID, null)
        clear()
        if (installId != null) putString(KEY_INSTALL_ID, installId)
    }

    private fun text(key: String): String = preferences.getString(key, null).orEmpty()

    private fun map(key: String): Map<String, String> {
        val parsed = runCatching { Json.parse(text(key).ifEmpty { "{}" }) }.getOrNull() as? Map<*, *> ?: return emptyMap()
        return parsed.entries.mapNotNull { (k, v) -> if (k is String && v is String) k to v else null }.toMap()
    }

    private companion object {
        const val PREFERENCES = "remote_config"
        const val KEY_INSTALL_ID = "install_id"
        const val KEY_DEVICE_KEY = "device_key"
        const val KEY_SYNC_URL = "sync_url"
        const val KEY_PLATFORM_CA = "platform_ca"
        const val KEY_VERSION = "version"
        const val KEY_APPLIED = "applied"
        const val KEY_REFRESH_MINUTES = "refresh_minutes"
        const val KEY_PLATFORM_NAME = "platform_name"
        const val KEY_CLIENT_NAME = "client_name"
        const val KEY_REGION = "region"
        const val KEY_GENERATED_AT = "generated_at"
        const val KEY_LAST_ATTEMPT = "last_attempt"
        const val KEY_LAST_SYNC = "last_sync"
        const val KEY_LAST_ERROR = "last_error"
        const val KEY_APPLIED_VERSION = "applied_version"
        const val KEY_APPLIED_AT = "applied_at"
        const val KEY_APPLIED_ERROR = "applied_error"
        const val KEY_ACCOUNT_IDS = "account_ids"
        const val KEY_DIRECTORY_IDS = "directory_ids"
    }
}
