package com.sky.siplink.core

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.content.edit
import com.sky.siplink.core.logic.DownloadState
import com.sky.siplink.core.logic.FoundRelease
import com.sky.siplink.core.logic.ReleaseAsset
import com.sky.siplink.core.logic.UpdateFailure
import com.sky.siplink.core.logic.UpdateRules
import com.sky.siplink.core.logic.UpdateState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Asks GitHub for the latest release of the app ([GitHubReleases]), keeps what it found, and
 * downloads the APK of a newer version when the user asks for it. Installing is the system's,
 * after the user's tap (ui/settings/UpdateInstaller.kt).
 *
 * Nothing here ever shows itself: a check that fails only says so in the version row of the
 * settings, which reads [status]. One request at a time; after a rate-limit answer GitHub is not
 * asked before the time it named.
 *
 * What it keeps (what it found, which version the user skipped, GitHub's ETag and wait) is no
 * configuration: it lives in shared preferences of its own, outside the configuration file that
 * a remote configuration would hand out. Whether to ask by itself is a setting, read through
 * [autoCheck].
 */
class UpdateChecker(
    private val context: Context,
    private val autoCheck: () -> Boolean,
    private val abis: List<String> = Build.SUPPORTED_ABIS.toList(),
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val state = MutableStateFlow(load())
    val status: StateFlow<UpdateState> = state.asStateFlow()

    private val downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)

    /** The download of the APK of the version offered. */
    val download: StateFlow<DownloadState> = downloadState.asStateFlow()

    /** Where the GitHub API answers come from; the tests replace it with answers of their own. */
    @Volatile
    internal var feed: ReleaseFeed = GitHubReleases.feed

    /** Where the APK comes from; the tests replace it as well. */
    @Volatile
    internal var assets: AssetSource = GitHubReleases.assets

    private val worker = Executors.newSingleThreadExecutor { Thread(it, "update-check") }
    private val downloader = Executors.newSingleThreadExecutor { Thread(it, "update-download") }
    private val directory = File(context.cacheDir, DIRECTORY)

    @Volatile
    private var cancelled = false

    init {
        // What an earlier run downloaded is installed by now, or stale.
        downloader.execute { directory.listFiles()?.forEach { it.delete() } }
    }

    /** The app came to the front: checks when the user lets it and GitHub may be asked ([UpdateRules.checksOnEntry]). */
    fun checkOnEntry() {
        if (autoCheck() && UpdateRules.checksOnEntry(now(), state.value)) check(manual = false)
    }

    /**
     * Checks now, for the user who asked. A version the user skipped is offered again when this
     * finds it: they asked. While GitHub asked to wait, it is not asked.
     */
    fun checkNow() {
        if (now() < state.value.blockedUntil) {
            state.update { it.copy(failure = UpdateFailure.RATE_LIMITED) }
            return
        }
        check(manual = true)
    }

    /** The user does not want to be offered the version found; a newer one is offered again. */
    fun skip() {
        val latest = state.value.latest ?: return
        state.update { it.copy(skipped = latest.version) }
        preferences.edit { putString(KEY_SKIPPED, latest.version) }
    }

    /** Forgets what earlier checks found and which version was skipped, as on a new installation. For the tests. */
    internal fun forget() {
        preferences.edit { clear() }
        state.value = UpdateState()
        downloadState.value = DownloadState.Idle
    }

    private fun check(manual: Boolean) {
        // One request at a time: the one that is running answers for this one too.
        val before = state.value
        if (before.checking || !state.compareAndSet(before, before.copy(checking = true))) return
        val startedAt = now()
        val source = feed
        // The ETag only stands for the answer that is kept.
        val etag = before.etag.takeIf { before.latest != null }
        worker.execute {
            val answer = runCatching { source.fetch(etag) }
            state.update { current -> answered(current, answer, startedAt, manual) }
            save(state.value)
        }
    }

    /** The state after GitHub's [answer], applied to the state as it is now: the user may have skipped a version meanwhile. */
    private fun answered(current: UpdateState, answer: Result<FeedAnswer>, startedAt: Long, manual: Boolean): UpdateState {
        val base = current.copy(checking = false, lastAttempt = startedAt)
        val got = answer.getOrElse {
            Log.i(TAG, "No answer from GitHub: $it")
            return base.copy(failure = UpdateFailure.CHECK_FAILED)
        }
        val success = base.copy(lastSuccess = startedAt, failure = null, blockedUntil = 0, skipped = if (manual) "" else current.skipped)
        UpdateRules.blockedUntil(got.status, got.remaining, got.reset, got.retryAfter, startedAt)?.let {
            return base.copy(failure = UpdateFailure.RATE_LIMITED, blockedUntil = it)
        }
        return when (got.status) {
            UpdateRules.HTTP_NOT_MODIFIED -> if (current.latest != null) success else base.copy(failure = UpdateFailure.CHECK_FAILED, etag = "")
            UpdateRules.HTTP_OK -> runCatching { UpdateRules.found(GitHubReleases.parse(got.body), abis) }.fold(
                onSuccess = { found -> success.copy(latest = found, etag = got.etag.orEmpty()) },
                onFailure = { base.copy(failure = UpdateFailure.CHECK_FAILED) },
            )
            else -> base.copy(failure = UpdateFailure.CHECK_FAILED)
        }
    }

    // ---------------------------------------------------------------- download

    /**
     * Downloads the APK of [release] for this device; the progress is in [download]. Checked
     * before it counts as ready: its size against the one GitHub states, its SHA-256 against
     * GitHub's digest where the release has one, and that it is a package of this app.
     */
    fun startDownload(release: FoundRelease) {
        val apk = release.apk ?: return
        val current = downloadState.value
        if (current is DownloadState.Running || (current is DownloadState.Ready && current.version == release.version)) return
        cancelled = false
        downloadState.value = DownloadState.Running(release.version, 0, apk.size)
        val source = assets
        downloader.execute {
            val result = runCatching { fetch(source, release.version, apk) }
            downloadState.value = result.fold(
                onSuccess = { file -> DownloadState.Ready(release.version, file.path) },
                onFailure = {
                    Log.i(TAG, "Download of ${apk.name} ended: $it")
                    if (cancelled) DownloadState.Idle else DownloadState.Failed(release.version)
                },
            )
        }
    }

    /** Stops the download that is running; what it got so far goes. */
    fun cancelDownload() {
        cancelled = true
    }

    private fun fetch(source: AssetSource, version: String, apk: ReleaseAsset): File {
        directory.mkdirs()
        directory.listFiles()?.forEach { it.delete() }
        val part = File(directory, "$version.part")
        val file = File(directory, "SipLink-$version.apk")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            source.open(apk.url).use { stream ->
                if (apk.size > 0 && stream.length >= 0 && stream.length != apk.size) throw IOException("Size ${stream.length}, expected ${apk.size}")
                val total = if (apk.size > 0) apk.size else stream.length.coerceAtLeast(0)
                part.outputStream().use { out ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    var done = 0L
                    var shown = 0L
                    while (true) {
                        if (cancelled) throw IOException("Cancelled")
                        val read = stream.input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        done += read
                        if (apk.size > 0 && done > apk.size) throw IOException("Longer than ${apk.size}")
                        if (done - shown >= PROGRESS_STEP_BYTES) {
                            shown = done
                            downloadState.value = DownloadState.Running(version, done, total)
                        }
                    }
                }
            }
            if (apk.size > 0 && part.length() != apk.size) throw IOException("Size ${part.length()}, expected ${apk.size}")
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            if (apk.sha256.isNotEmpty() && sha != apk.sha256) throw IOException("SHA-256 $sha, expected ${apk.sha256}")
            val archive = context.packageManager.getPackageArchiveInfo(part.path, 0)
            if (archive?.packageName != context.packageName) throw IOException("Not a package of this app")
            if (!part.renameTo(file)) throw IOException("rename failed")
            return file
        } catch (e: Exception) {
            part.delete()
            file.delete()
            throw e
        }
    }

    // ---------------------------------------------------------------- kept state

    private fun load(): UpdateState {
        val version = preferences.getString(KEY_VERSION, null)
        val apkUrl = preferences.getString(KEY_APK_URL, null)
        return UpdateState(
            latest = version?.let {
                FoundRelease(
                    version = it,
                    notes = preferences.getString(KEY_NOTES, null).orEmpty(),
                    pageUrl = preferences.getString(KEY_PAGE, null) ?: UpdateRules.RELEASES_PAGE,
                    apk = apkUrl?.let { url ->
                        ReleaseAsset(
                            name = preferences.getString(KEY_APK_NAME, null).orEmpty(),
                            url = url,
                            size = preferences.getLong(KEY_APK_SIZE, 0),
                            sha256 = preferences.getString(KEY_APK_SHA256, null).orEmpty(),
                        )
                    },
                )
            },
            skipped = preferences.getString(KEY_SKIPPED, null).orEmpty(),
            lastAttempt = preferences.getLong(KEY_LAST_ATTEMPT, 0),
            lastSuccess = preferences.getLong(KEY_LAST_SUCCESS, 0),
            failure = preferences.getString(KEY_FAILURE, null)?.let { name -> UpdateFailure.entries.firstOrNull { it.name == name } },
            // An ETag is only of use with the answer it stands for.
            etag = if (version != null) preferences.getString(KEY_ETAG, null).orEmpty() else "",
            blockedUntil = preferences.getLong(KEY_BLOCKED_UNTIL, 0),
        )
    }

    private fun save(value: UpdateState) = preferences.edit {
        val latest = value.latest
        listOf(KEY_VERSION, KEY_NOTES, KEY_PAGE, KEY_APK_NAME, KEY_APK_URL, KEY_APK_SIZE, KEY_APK_SHA256).forEach(::remove)
        if (latest != null) {
            putString(KEY_VERSION, latest.version)
            putString(KEY_NOTES, latest.notes)
            putString(KEY_PAGE, latest.pageUrl)
            latest.apk?.let {
                putString(KEY_APK_NAME, it.name)
                putString(KEY_APK_URL, it.url)
                putLong(KEY_APK_SIZE, it.size)
                putString(KEY_APK_SHA256, it.sha256)
            }
        }
        putString(KEY_SKIPPED, value.skipped)
        putLong(KEY_LAST_ATTEMPT, value.lastAttempt)
        putLong(KEY_LAST_SUCCESS, value.lastSuccess)
        if (value.failure == null) remove(KEY_FAILURE) else putString(KEY_FAILURE, value.failure.name)
        putString(KEY_ETAG, value.etag)
        putLong(KEY_BLOCKED_UNTIL, value.blockedUntil)
    }

    private companion object {
        const val TAG = "SipLink.Update"
        const val PREFERENCES = "update_check"

        /** In the app's cache: what [fetch] downloads, and what the installer is handed (res/xml/update_paths.xml). */
        const val DIRECTORY = "updates"
        const val BUFFER_BYTES = 64 * 1024
        const val PROGRESS_STEP_BYTES = 256 * 1024L

        const val KEY_VERSION = "latest_version"
        const val KEY_NOTES = "latest_notes"
        const val KEY_PAGE = "latest_url"
        const val KEY_APK_NAME = "latest_apk_name"
        const val KEY_APK_URL = "latest_apk_url"
        const val KEY_APK_SIZE = "latest_apk_size"
        const val KEY_APK_SHA256 = "latest_apk_sha256"
        const val KEY_SKIPPED = "skipped_version"
        const val KEY_LAST_ATTEMPT = "last_attempt"
        const val KEY_LAST_SUCCESS = "last_success"
        const val KEY_FAILURE = "failure"
        const val KEY_ETAG = "etag"
        const val KEY_BLOCKED_UNTIL = "blocked_until"
    }
}
