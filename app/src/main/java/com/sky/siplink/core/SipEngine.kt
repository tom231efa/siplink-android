package com.sky.siplink.core

import android.content.Context
import com.sky.siplink.BuildConfig
import com.sky.siplink.core.model.DtmfMode
import com.sky.siplink.core.model.Settings
import com.sky.siplink.core.model.SipAccount
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.linphone.core.Account
import org.linphone.core.AudioDevice
import org.linphone.core.Call
import org.linphone.core.CallLog
import org.linphone.core.Core
import org.linphone.core.CoreListenerStub
import org.linphone.core.Event
import org.linphone.core.Factory
import org.linphone.core.LogLevel
import org.linphone.core.MessageWaitingIndication
import org.linphone.core.RegistrationState
import java.io.File

/**
 * Owns the Linphone SIP core and the managers built on top of it. Created once by the
 * Application on the main thread, which then is the thread every core call must be made from.
 */
class SipEngine(context: Context) {
    private val appContext: Context = context.applicationContext
    val core: Core

    /** Whether the configuration is managed, and with that closed to changes made on the phone. */
    val lock: ConfigLock
    val settings: SettingsStore
    val tls: TlsManager
    val accounts: AccountManager
    val calls: CallManager
    val history: HistoryManager
    val directory: DirectoryManager by lazy {
        DirectoryManager(core, DirectoryCache(appContext), lock).also { it.onChanged = names::clear }
    }

    /** Directory names for the numbers of calls; sets the directory up when the first call needs it. */
    val names = NameResolver { directory }
    val codecs: CodecManager by lazy { CodecManager(core, lock) }
    val shortcuts: ShortcutManager

    val sdkVersion: String get() = core.version

    /** Lives as long as the engine, which is as long as the process. */
    private val networkWatch: NetworkWatch

    private val voicemailState = MutableStateFlow(0)

    /** Number of new voicemail messages announced by the PBX (MWI), summed over all accounts. */
    val voicemail: StateFlow<Int> = voicemailState.asStateFlow()

    /** New messages of one mailbox; [user] is the user part of the address the PBX names it with. */
    private class Mailbox(val user: String, val count: Int)

    private val mailboxes = HashMap<String, Mailbox>()

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

        override fun onNetworkReachable(core: Core, reachable: Boolean) {
            if (reachable) accounts.onNetworkReachable()
        }

        override fun onCallStateChanged(core: Core, call: Call, state: Call.State?, message: String) =
            calls.onCallStateChanged(call, state, message)

        override fun onTransferStateChanged(core: Core, transfered: Call, callState: Call.State?) =
            calls.onTransferStateChanged(transfered, callState)

        override fun onMessageWaitingIndicationChanged(core: Core, lev: Event, mwi: MessageWaitingIndication) {
            val voice = mwi.getSummary(MessageWaitingIndication.ContextClass.Voice)
            val count = voice?.nbNew ?: if (mwi.hasMessageWaiting()) 1 else 0
            val address = mwi.accountAddress ?: lev.toAddress
            mailboxes[address.asStringUriOnly()] = Mailbox(address.username.orEmpty(), count)
            voicemailState.value = mailboxes.values.sumOf { it.count }
        }

        override fun onAudioDeviceChanged(core: Core, audioDevice: AudioDevice) = calls.onAudioChanged()

        override fun onAudioDevicesListUpdated(core: Core) = calls.onAudioChanged()

        override fun onCallLogUpdated(core: Core, callLog: CallLog) {
            history.onLogUpdated(callLog)
            if (history.isMissed(callLog)) {
                val remote = callLog.remoteAddress
                val number = remote.username.orEmpty()
                onMissedCall?.invoke(names.known(number) ?: remote.displayName?.takeIf { it.isNotBlank() } ?: number, number)
            }
        }
    }

    init {
        val factory = Factory.instance()
        factory.setLoggerDomain(LOG_TAG)
        factory.enableLogcatLogs(true)
        factory.loggingService.setLogLevel(if (BuildConfig.DEBUG) LogLevel.Message else LogLevel.Warning)

        core = factory.createCore(File(appContext.filesDir, CONFIG_FILE).absolutePath, null, appContext)
        configure()

        lock = ConfigLock(core.config)
        settings = SettingsStore(core.config, lock)
        applyDtmfMode(settings.current.dtmfMode)
        CallVideoSupport.configure(core, settings.current.videoCalls)
        tls = TlsManager(appContext, core, lock)
        tls.applySettings(settings.current)
        accounts = AccountManager(core, lock)
        calls = CallManager(core, settings, accounts, names)
        history = HistoryManager(core, names) { settings.current.features }
        shortcuts = ShortcutManager(File(appContext.filesDir, SHORTCUTS_FILE), lock)
        accounts.onEdited = ::onAccountsEdited

        core.addListener(listener)
        core.start()
        networkWatch = NetworkWatch(appContext)
    }

    /** What refers to an account follows after the user changed, switched off or removed one. */
    private fun onAccountsEdited(before: List<SipAccount>, after: List<SipAccount>) {
        shortcuts.followAccounts(before, after)
        if (before.size > after.size) directory.followAccounts(after.mapTo(HashSet()) { it.id })
        // The PBX no longer tells an account that is gone when its mailbox empties: forget the count.
        val users = after.filter { it.enabled }.flatMapTo(HashSet()) { listOf(it.username, it.authUser) }
        if (mailboxes.values.removeAll { it.user !in users }) voicemailState.value = mailboxes.values.sumOf { it.count }
    }

    /**
     * Persists a settings change and applies its side effects to the SIP core.
     *
     * @return false when part of the change was refused because the configuration is managed
     *   ([SettingsStore.update])
     */
    fun updateSettings(transform: (Settings) -> Settings): Boolean {
        val before = settings.current
        val taken = settings.update(transform)
        val after = settings.current
        if (before == after) return taken

        val verificationChanged = before.verifyTlsCertificate != after.verifyTlsCertificate ||
            before.verifyTlsHostname != after.verifyTlsHostname
        if (verificationChanged || before.mediaSecurity != after.mediaSecurity) tls.applySettings(after)
        if (verificationChanged) tls.reconnect()
        if (before.keepAlive != after.keepAlive) onKeepAliveChanged?.invoke()
        if (before.dtmfMode != after.dtmfMode) applyDtmfMode(after.dtmfMode)
        if (before.videoCalls != after.videoCalls) {
            CallVideoSupport.setEnabled(core, after.videoCalls)
            calls.onVideoAllowedChanged()
        }
        return taken
    }

    private fun applyDtmfMode(mode: DtmfMode) {
        core.useRfc2833ForDtmf = mode == DtmfMode.RFC2833
        core.useInfoForDtmf = mode == DtmfMode.SIP_INFO
    }

    private fun configure() {
        // The SIP stack is named first and the app after it. Some services answer only clients
        // whose name begins with one they know: a relay in front of hosted PBXs was seen to
        // refuse "SipLink/…" with 403 before asking for any password, and to let "Linphone…" in.
        core.setUserAgent(SDK_NAME, "${core.version} $APP_NAME/${BuildConfig.VERSION_NAME}")
        core.isAutoIterateEnabled = true
        // With the app off the screen the core looks at the network less often, and was seen to
        // take about one SIP message each time it does. At its usual half second an answer is
        // read later than the 500 ms after which a request over UDP is repeated, so requests go
        // out twice, and a hundred accounts do not get registered within five minutes.
        core.autoIterateBackgroundSchedule = BACKGROUND_ITERATE_MS
        core.isKeepAliveEnabled = true

        // Two calls are shown to the user (current + held or waiting); the extra room is for the
        // short overlaps while the PBX rearranges calls (REFER, replaces). Video follows the
        // settings, see CallVideoSupport.configure.
        core.maxCalls = MAX_CALLS

        // CallManager ends transferred calls itself: a parked call has to stay up until the PBX
        // has announced the parking slot, which the core's automatic hang-up would cut off.
        core.config.setInt("sip", "terminate_call_upon_transfer_completion", 0)

        // The password of an account is kept as it was entered, so that it can be shown again and
        // goes along when the account moves to another server. Left to itself the core keeps a
        // digest of it only, which fits no other user name and no other server.
        core.config.setInt("sip", "store_ha1_passwd", 0)

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
        const val SDK_NAME = "LinphoneSDK"
        const val APP_NAME = "SipLink"
        const val CONFIG_FILE = "linphonerc"
        const val SHORTCUTS_FILE = "shortcuts.csv"
        const val MAX_CALLS = 4
        const val BACKGROUND_ITERATE_MS = 100

        /** liblinphone's LC_SIP_TRANSPORT_RANDOM. */
        const val RANDOM_PORT = -1
    }
}
