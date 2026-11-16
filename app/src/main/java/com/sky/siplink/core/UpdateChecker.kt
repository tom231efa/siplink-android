package com.sky.siplink.core

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import com.sky.siplink.core.logic.FoundRelease
import com.sky.siplink.core.logic.UpdateRules
import com.sky.siplink.core.logic.UpdateState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Asks GitHub for the latest release of the app ([GitHubReleases]) and keeps what it found. The
 * app neither downloads nor installs anything: the settings offer the newer version, and the
 * browser downloads it.
 *
 * What it keeps (when it last asked, what it found, which version the user skipped) is no
 * configuration: it lives in shared preferences of its own, outside the configuration file that
 * a remote configuration would hand out. Whether to ask by itself is a setting, read through
 * [autoCheck].
 */
class UpdateChecker(
    context: Context,
    private val autoCheck: () -> Boolean,
    private val abis: List<String> = Build.SUPPORTED_ABIS.toList(),
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val state = MutableStateFlow(load())
    val status: StateFlow<UpdateState> = state.asStateFlow()

    /** Where the release comes from: GitHub; the tests replace it with an answer of their own. */
    @Volatile
    internal var feed: ReleaseFeed = GitHubReleases.feed

    private val worker = Executors.newSingleThreadExecutor { Thread(it, "update-check") }

    /** Checks when the user lets the app do so and the last check is old enough ([UpdateRules.isDue]). Its failure says nothing. */
    fun checkIfDue() {
        val current = state.value
        if (autoCheck() && UpdateRules.isDue(now(), current.lastAttempt, current.lastFailed)) check(manual = false)
    }

    /**
     * Checks now, for the user who asked. A version the user skipped is offered again when this
     * finds it: they asked.
     */
    fun checkNow() = check(manual = true)

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
    }

    private fun check(manual: Boolean) {
        // One check at a time: the one that is running answers for this one too.
        val before = state.value
        if (before.checking || !state.compareAndSet(before, before.copy(checking = true, failure = null))) return
        val startedAt = now()
        val source = feed
        worker.execute {
            val result = runCatching { UpdateRules.found(GitHubReleases.parse(source.fetch()), abis) }
            // Applied to the state as it is now: the user may have skipped a version meanwhile.
            state.update { current ->
                result.fold(
                    onSuccess = { found -> succeeded(current, found, startedAt, manual) },
                    onFailure = { error -> failed(current, error, startedAt, manual) },
                )
            }
            save(state.value)
        }
    }

    private fun succeeded(current: UpdateState, found: FoundRelease, startedAt: Long, manual: Boolean): UpdateState = current.copy(
        checking = false,
        latest = found,
        skipped = if (manual) "" else current.skipped,
        lastAttempt = startedAt,
        lastFailed = false,
        lastSuccess = startedAt,
        failure = null,
    )

    private fun failed(current: UpdateState, error: Throwable, startedAt: Long, manual: Boolean): UpdateState {
        val httpStatus = when (error) {
            is HttpStatusException -> error.status
            is IOException -> null
            // Not JSON, or not the description of a release.
            else -> UpdateRules.HTTP_OK
        }
        return current.copy(
            checking = false,
            lastAttempt = startedAt,
            lastFailed = true,
            failure = if (manual) UpdateRules.failureText(httpStatus) else null,
        )
    }

    private fun load(): UpdateState {
        val version = preferences.getString(KEY_VERSION, null)
        return UpdateState(
            latest = version?.let {
                FoundRelease(it, preferences.getString(KEY_NOTES, null).orEmpty(), preferences.getString(KEY_URL, null) ?: UpdateRules.RELEASES_PAGE)
            },
            skipped = preferences.getString(KEY_SKIPPED, null).orEmpty(),
            lastAttempt = preferences.getLong(KEY_LAST_ATTEMPT, 0),
            lastFailed = preferences.getBoolean(KEY_LAST_FAILED, false),
            lastSuccess = preferences.getLong(KEY_LAST_SUCCESS, 0),
        )
    }

    private fun save(value: UpdateState) = preferences.edit {
        val latest = value.latest
        if (latest == null) {
            remove(KEY_VERSION)
            remove(KEY_NOTES)
            remove(KEY_URL)
        } else {
            putString(KEY_VERSION, latest.version)
            putString(KEY_NOTES, latest.notes)
            putString(KEY_URL, latest.url)
        }
        putString(KEY_SKIPPED, value.skipped)
        putLong(KEY_LAST_ATTEMPT, value.lastAttempt)
        putBoolean(KEY_LAST_FAILED, value.lastFailed)
        putLong(KEY_LAST_SUCCESS, value.lastSuccess)
    }

    private companion object {
        const val PREFERENCES = "update_check"
        const val KEY_VERSION = "latest_version"
        const val KEY_NOTES = "latest_notes"
        const val KEY_URL = "latest_url"
        const val KEY_SKIPPED = "skipped_version"
        const val KEY_LAST_ATTEMPT = "last_attempt"
        const val KEY_LAST_FAILED = "last_failed"
        const val KEY_LAST_SUCCESS = "last_success"
    }
}
