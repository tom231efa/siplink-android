package com.sky.siplink.core

import android.content.Context
import android.os.Build
import com.sky.siplink.BuildConfig
import com.sky.siplink.R
import com.sky.siplink.core.logic.CertificateTools
import com.sky.siplink.core.logic.RemoteAddress
import com.sky.siplink.core.logic.RemoteChecks
import com.sky.siplink.core.logic.RemoteDocument
import com.sky.siplink.core.logic.RemoteSync
import com.sky.siplink.core.model.Phrase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors

/**
 * The remote configuration: binds the phone to a management platform from the address of its QR
 * code, syncs with it (at start, when the app comes to the front, when the network is back, by
 * hand, and every refresh interval of the document), applies what it hands out ([RemoteApplier])
 * and takes it all back when the platform or the user ends it.
 *
 * A sync that fails changes nothing: the configuration in force stays, and the phone tries
 * again, sooner at first ([RemoteSync.retryDelayMs]). Used from the main thread; the requests
 * run on a thread of their own.
 */
class RemoteConfig internal constructor(context: Context, private val engine: SipEngine) {
    /**
     * What the remote configuration screen shows.
     *
     * @param enrolled the phone is bound to a platform
     * @param busy a request is on its way
     * @param host where the phone syncs
     * @param generatedAt when the platform made the configuration in force, as it is shown
     * @param lastSyncMs when the last sync worked; 0: never
     * @param lastError what went wrong with the last sync or binding; empty: nothing
     * @param pinned the platform's own CA is trusted, from the first scan
     * @param restartNeeded the CAs changed after LDAP used TLS: the app has to restart for LDAP to follow
     */
    data class Status(
        val enrolled: Boolean = false,
        val busy: Boolean = false,
        val platformName: String = "",
        val clientName: String = "",
        val region: String = "",
        val host: String = "",
        val generatedAt: String = "",
        val lastSyncMs: Long = 0,
        val lastError: String = "",
        val version: String = "",
        val refreshMinutes: Int = RemoteDocument.DEFAULT_REFRESH_MINUTES,
        val pinned: Boolean = false,
        val restartNeeded: Boolean = false,
    )

    private val store = RemoteConfigStore(context)
    private val texts = engine.texts

    /** What the screen says went wrong in place of the stored error of the last sync: a binding that failed, a revocation. */
    private var notice: Phrase? = null
    private val applier = RemoteApplier(engine)
    private val handler = CoreThread.handler()
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "remote-config") }
    private val state = MutableStateFlow(Status())
    val status: StateFlow<Status> = state.asStateFlow()

    /** Syncs that failed in a row, for the time to the next try. */
    private var failures = 0
    private val timer = Runnable { sync() }

    /** Whether a plain http:// address is taken: in debug builds only. */
    val allowsHttp: Boolean get() = BuildConfig.DEBUG

    /** At app start: shows what is kept, and syncs. */
    fun start() {
        publish()
        if (store.enrolled) sync()
    }

    /** The app came to the front: syncs, unless that was done a moment ago. */
    fun onForeground() {
        if (store.enrolled && RemoteSync.dueOnFront(System.currentTimeMillis(), store.lastAttemptMs)) sync()
    }

    /** The network is back: a sync that failed is tried again at once. */
    fun onNetworkBack() {
        if (store.enrolled && store.lastError != null) sync()
    }

    /** The user asked for a sync. */
    fun syncNow() = sync()

    fun parse(text: String): RemoteAddress.Parsed = RemoteAddress.parse(text, allowsHttp)

    /** The language of the app changed: what went wrong is said again in it. */
    fun onLanguageChanged() {
        state.value = state.value.copy(lastError = (notice ?: store.lastError)?.let(texts::render).orEmpty())
    }

    /** The user saw that the app has to restart for LDAP; it is not asked again for this change. */
    fun restartHandled() {
        state.value = state.value.copy(restartNeeded = false)
    }

    /**
     * Binds the phone to the platform of [enrollment] and applies what it hands out.
     *
     * @param onDone what to tell the user about it
     */
    fun enroll(enrollment: RemoteAddress.Enrollment, onDone: (String) -> Unit) {
        if (state.value.busy || store.enrolled) return
        markBusy()
        // Coming to the front right after the binding need not sync again.
        store.attempted(System.currentTimeMillis())
        val body = RemoteSync.enrollBody(store.installId, device())
        val trust = enrollment.pin?.let { RemoteConfigClient.Trust.Pinned(it) } ?: RemoteConfigClient.Trust.System
        worker.execute {
            val result = request(enrollment.enrollUrl, body, null, trust)
            val document = result.getOrNull()?.takeIf { it.status == 200 }?.let { RemoteDocument.parse(it.body) }
            handler.post {
                val answer = result.getOrElse { return@post finishEnrollment(reasonOf(it), onDone) }
                when (RemoteSync.outcome(answer.status, enrolling = true)) {
                    RemoteSync.Outcome.DOCUMENT -> enrolled(enrollment, answer, document!!, onDone)
                    else -> finishEnrollment(RemoteSync.failureText(answer.status), onDone)
                }
            }
        }
    }

    private fun enrolled(
        enrollment: RemoteAddress.Enrollment,
        answer: RemoteConfigClient.Answer,
        document: RemoteDocument.Parsed,
        onDone: (String) -> Unit,
    ) {
        val deviceKey = RemoteDocument.deviceKeyOf(answer.body)
        if (deviceKey.isEmpty()) return finishEnrollment(Phrase.of(R.string.remote_no_device_key), onDone)
        store.enroll(deviceKey, enrollment.syncUrl, answer.pinned?.let(CertificateTools::toPem).orEmpty())
        received(document) { error ->
            finished(error)
            onDone(texts.render(error ?: Phrase.of(R.string.remote_loaded)))
        }
    }

    private fun finishEnrollment(error: Phrase, onDone: (String) -> Unit) {
        notice = error
        state.value = state.value.copy(busy = false, lastError = texts.render(error))
        onDone(texts.render(error))
    }

    /**
     * Unbinds the phone: tells the platform (as far as it can be reached) and takes back
     * everything that was handed out.
     */
    fun delete(onDone: (String) -> Unit) {
        if (state.value.busy || !store.enrolled) return
        markBusy()
        handler.removeCallbacks(timer)
        val url = RemoteAddress.leaveUrl(store.syncUrl)
        val (key, trust) = store.deviceKey to trust()
        worker.execute {
            val told = request(url, RemoteSync.LEAVE_BODY, key, trust).getOrNull()?.status?.let { it in 200..299 || it == 401 || it == 410 } == true
            handler.post {
                takeBack(null) { onDone(texts.get(if (told) R.string.remote_removed else R.string.remote_removed_untold)) }
            }
        }
    }

    private fun sync() {
        if (state.value.busy || !store.enrolled) return
        markBusy()
        handler.removeCallbacks(timer)
        val now = System.currentTimeMillis()
        store.attempted(now)
        val body = RemoteSync.syncBody(store.version, device())
        val (url, key, trust) = Triple(store.syncUrl, store.deviceKey, trust())
        worker.execute {
            val result = request(url, body, key, trust)
            // The document is read here, off the main thread; it may be large.
            val document = result.getOrNull()?.takeIf { it.status == 200 }?.let { RemoteDocument.parse(it.body) }
            // Busy until it is all done: applying or taking back may wait for accounts to unregister.
            handler.post {
                val answer = result.getOrElse { return@post finished(reasonOf(it), retryAfter = null) }
                when (RemoteSync.outcome(answer.status, enrolling = false)) {
                    RemoteSync.Outcome.DOCUMENT -> received(document!!) { error -> finished(error) }
                    RemoteSync.Outcome.UNCHANGED -> finished(null)
                    RemoteSync.Outcome.REVOKED -> takeBack(REVOKED)
                    else -> finished(RemoteSync.failureText(answer.status), answer.retryAfterSeconds)
                }
            }
        }
    }

    /** Applies a document that came, once the shortcut keys are read; [then] gets what went wrong (null: nothing). */
    private fun received(parsed: RemoteDocument.Parsed, then: (Phrase?) -> Unit) {
        if (!engine.shortcuts.loaded.value) {
            handler.postDelayed({ received(parsed, then) }, WAIT_FOR_KEYS_MS)
            return
        }
        val at = RemoteSync.timestamp(System.currentTimeMillis())
        when (parsed) {
            is RemoteDocument.Parsed.Invalid -> {
                store.tried("", at, parsed.errors)
                val first = parsed.errors.first()
                then(if (first == RemoteChecks.NEEDS_UPDATE) first else Phrase.of(R.string.remote_invalid, first))
            }
            is RemoteDocument.Parsed.Ok -> apply(parsed.document, at, then)
        }
    }

    /** Applies [document]; [then] gets what went wrong (null: nothing) once it is done. */
    private fun apply(document: RemoteDocument, at: String, then: (Phrase?) -> Unit) {
        val syncUrl = document.syncUrl.takeIf { RemoteAddress.isPlatformUrl(it, allowsHttp) } ?: store.syncUrl
        store.describe(document.platformName, document.clientName, document.region, document.generatedAt, document.refreshMinutes, syncUrl)
        if (store.applied && !RemoteSync.isNew(document.version, store.version)) return then(null)
        applier.apply(document, store.accountIds, store.directoryIds) { outcome ->
            // Applied in part, the version is not kept: the next sync brings the document again.
            store.applied(if (outcome.errors.isEmpty()) document.version else "", outcome.accountIds, outcome.directoryIds)
            store.tried(document.version, at, outcome.errors)
            if (outcome.trustChanged && engine.directory.tlsChangeNeedsRestart) state.value = state.value.copy(restartNeeded = true)
            then(outcome.errors.firstOrNull()?.let { Phrase.of(R.string.remote_partly_applied, it) })
        }
    }

    /** A sync or binding ended with [error] (null: it worked); the next one is planned. */
    private fun finished(error: Phrase?, retryAfter: Long? = null) {
        store.synced(System.currentTimeMillis(), error)
        val refresh = store.refreshMinutes.takeIf { it > 0 } ?: RemoteDocument.DEFAULT_REFRESH_MINUTES
        failures = if (error == null) 0 else failures + 1
        val delay = if (failures == 0) RemoteSync.refreshDelayMs(refresh) else RemoteSync.retryDelayMs(failures, refresh, retryAfter)
        handler.removeCallbacks(timer)
        if (store.enrolled) handler.postDelayed(timer, delay)
        publish()
    }

    /**
     * The platform ended the binding, or the user did: what was handed out goes, and the phone is
     * its own again. [why] stays on the screen to say why (null: nothing); [then] runs once it is all done.
     */
    private fun takeBack(why: Phrase?, then: () -> Unit = {}) {
        if (!engine.shortcuts.loaded.value) {
            handler.postDelayed({ takeBack(why, then) }, WAIT_FOR_KEYS_MS)
            return
        }
        handler.removeCallbacks(timer)
        val forget = {
            store.clear()
            failures = 0
            notice = why
            state.value = Status(lastError = why?.let(texts::render).orEmpty())
            then()
        }
        // A binding whose document was never applied handed nothing out: what the phone has is its own.
        if (store.applied || engine.lock.managed.value) applier.wipe(forget) else forget()
    }

    /** Why a request got no answer, for the user. */
    private fun reasonOf(failure: Throwable): Phrase = (failure as? RemoteConfigClient.Failure)?.reason ?: RemoteSync.NO_CONNECTION

    /** The answer of the platform, or a failure whose reason is for the user. */
    private fun request(url: String, body: String, deviceKey: String?, trust: RemoteConfigClient.Trust): Result<RemoteConfigClient.Answer> =
        try {
            Result.success(RemoteConfigClient.post(url, body, deviceKey, trust, System.currentTimeMillis()))
        } catch (e: RemoteConfigClient.Failure) {
            Result.failure(e)
        } catch (e: Exception) {
            // No trust store, an address the platform should not have named: nothing to tell the user but this.
            Result.failure(RemoteConfigClient.Failure(RemoteSync.NO_CONNECTION))
        }

    private fun trust(): RemoteConfigClient.Trust {
        val ca = store.platformCa.takeIf { it.isNotEmpty() }?.let { pem ->
            runCatching { CertificateTools.parse(pem.toByteArray()).first() }.getOrNull()
        }
        return if (ca == null) RemoteConfigClient.Trust.System else RemoteConfigClient.Trust.Kept(ca)
    }

    /** What the phone reports about itself with every request (§3.4); the accounts by their keys. */
    private fun device(): RemoteSync.Device {
        val keys = store.accountIds.entries.associate { (key, id) -> id to key }
        return RemoteSync.Device(
            appVersion = BuildConfig.VERSION_NAME,
            appVersionCode = BuildConfig.VERSION_CODE,
            androidRelease = Build.VERSION.RELEASE,
            androidSdk = Build.VERSION.SDK_INT,
            model = "${Build.MANUFACTURER} ${Build.MODEL}",
            // What went wrong is told in the app's language of the moment, as the screen tells it.
            applied = RemoteSync.Applied(store.appliedVersion, store.appliedAt, store.appliedErrors.joinToString(texts.get(R.string.list_separator), transform = texts::render)),
            registrations = engine.accounts.accounts.value.mapNotNull { account ->
                keys[account.id]?.let { RemoteSync.Registration(it, account.state.name, account.error.orEmpty()) }
            },
            audioCodecs = engine.codecs.codecs.value.map { it.key },
            videoCodecs = engine.codecs.videoCodecs.value.map { it.key },
        )
    }

    /** A request is on its way; [publish], [finishEnrollment] or [takeBack] end it. */
    private fun markBusy() {
        CoreThread.check("The remote configuration")
        state.value = state.value.copy(busy = true)
    }

    /** Shows what the store holds; nothing is on its way any more. */
    private fun publish() {
        CoreThread.check("The remote configuration")
        notice = null
        state.value = state.value.copy(
            busy = false,
            enrolled = store.enrolled,
            platformName = store.platformName,
            clientName = store.clientName,
            region = store.region,
            host = RemoteAddress.hostOf(store.syncUrl),
            generatedAt = store.generatedAt,
            lastSyncMs = store.lastSyncMs,
            lastError = store.lastError?.let(texts::render).orEmpty(),
            version = store.version,
            refreshMinutes = store.refreshMinutes.takeIf { it > 0 } ?: RemoteDocument.DEFAULT_REFRESH_MINUTES,
            pinned = store.platformCa.isNotEmpty(),
        )
    }

    private companion object {
        /** How often a document that came before the shortcut keys were read looks again. */
        const val WAIT_FOR_KEYS_MS = 100L

        val REVOKED = Phrase.of(R.string.remote_revoked)
    }
}
