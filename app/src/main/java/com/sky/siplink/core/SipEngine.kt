package com.sky.siplink.core

import android.content.Context
import com.sky.siplink.BuildConfig
import com.sky.siplink.core.model.Settings
import org.linphone.core.Account
import org.linphone.core.AudioDevice
import org.linphone.core.Call
import org.linphone.core.CallLog
import org.linphone.core.Core
import org.linphone.core.CoreListenerStub
import org.linphone.core.Factory
import org.linphone.core.RegistrationState
import java.io.File

/**
 * Owns the Linphone SIP core and the managers built on top of it. Created once by the
 * Application on the main thread, which then is the thread every core call must be made from.
 */
class SipEngine(context: Context) {
    val core: Core
    val settings: SettingsStore
    val tls: TlsManager
    val accounts: AccountManager
    val calls: CallManager
    val history: HistoryManager
    val directory: DirectoryManager by lazy { DirectoryManager(core) }

    val sdkVersion: String get() = core.version

    /** Invoked with (name, address) when an incoming call was missed. */
    var onMissedCall: ((String, String) -> Unit)? = null

    /** Invoked when the "stay connected in background" preference changes. */
    var onKeepAliveChanged: (() -> Unit)? = null

    private val listener = object : CoreListenerStub() {
        override fun onAccountRegistrationStateChanged(
            core: Core,
            account: Account,
            state: RegistrationState?,
            message: String,
        ) = accounts.onRegistrationChanged(account)

        override fun onCallStateChanged(core: Core, call: Call, state: Call.State?, message: String) =
            calls.onCallStateChanged(call, state, message)

        override fun onAudioDeviceChanged(core: Core, audioDevice: AudioDevice) = calls.onAudioChanged()

        override fun onAudioDevicesListUpdated(core: Core) = calls.onAudioChanged()

        override fun onCallLogUpdated(core: Core, callLog: CallLog) {
            history.refresh()
            if (history.isMissed(callLog)) {
                val remote = callLog.remoteAddress
                val number = remote.username.orEmpty()
                onMissedCall?.invoke(remote.displayName?.takeIf { it.isNotBlank() } ?: number, number)
            }
        }
    }

    init {
        val factory = Factory.instance()
        factory.setDebugMode(BuildConfig.DEBUG, LOG_TAG)

        val appContext = context.applicationContext
        core = factory.createCore(File(appContext.filesDir, CONFIG_FILE).absolutePath, null, appContext)
        configure()

        settings = SettingsStore(core.config)
        tls = TlsManager(appContext, core)
        tls.applySettings(settings.current)
        accounts = AccountManager(core)
        calls = CallManager(core, settings, accounts)
        history = HistoryManager(core)

        core.addListener(listener)
        core.start()
    }

    /** Persists a settings change and applies its side effects to the SIP core. */
    fun updateSettings(transform: (Settings) -> Settings) {
        val before = settings.current
        settings.update(transform)
        val after = settings.current
        if (before == after) return

        val verificationChanged = before.verifyTlsCertificate != after.verifyTlsCertificate ||
            before.verifyTlsHostname != after.verifyTlsHostname
        if (verificationChanged || before.mediaSecurity != after.mediaSecurity) tls.applySettings(after)
        if (verificationChanged) tls.reconnect()
        if (before.keepAlive != after.keepAlive) onKeepAliveChanged?.invoke()
    }

    private fun configure() {
        core.setUserAgent(USER_AGENT, BuildConfig.VERSION_NAME)
        core.isAutoIterateEnabled = true
        core.isKeepAliveEnabled = true

        // Audio-only client that handles one call at a time.
        core.isVideoCaptureEnabled = false
        core.isVideoDisplayEnabled = false
        core.maxCalls = 1

        // Let the SDK ring with the device ringtone and vibrate on incoming calls.
        core.isNativeRingingEnabled = true
        core.isVibrationOnIncomingCallEnabled = true

        // A client has no reason to listen on the well-known SIP ports: use random ones.
        val transports = core.transports
        transports.udpPort = RANDOM_PORT
        transports.tcpPort = RANDOM_PORT
        transports.tlsPort = RANDOM_PORT
        core.transports = transports
    }

    private companion object {
        const val LOG_TAG = "SipLink"
        const val USER_AGENT = "SipLink"
        const val CONFIG_FILE = "linphonerc"

        /** liblinphone's LC_SIP_TRANSPORT_RANDOM. */
        const val RANDOM_PORT = -1
    }
}
