package com.sky.siplink.itest

import android.Manifest
import android.app.Activity
import android.os.Build
import android.os.ParcelFileDescriptor
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.sky.siplink.CallActivity
import com.sky.siplink.MainActivity
import com.sky.siplink.R
import com.sky.siplink.SipLinkApp
import com.sky.siplink.core.CoreThread
import com.sky.siplink.core.ReleaseFeed
import com.sky.siplink.core.SipEngine
import com.sky.siplink.core.model.AccountDraft
import com.sky.siplink.core.model.CallInfo
import com.sky.siplink.core.model.CallPhase
import com.sky.siplink.core.model.Language
import com.sky.siplink.core.model.RegState
import com.sky.siplink.core.model.Settings
import com.sky.siplink.core.model.SipAccount
import com.sky.siplink.core.model.SipTransport
import com.sky.siplink.core.model.SupervisionMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import java.io.File
import java.io.IOException

/**
 * Base of the integration tests. They drive the real SIP engine of the app (inside the app
 * process, on a device or emulator) against the Asterisk PBX of testenv/, and check the result
 * on both sides: in the app's call state and, through AMI, on the PBX and on the scripted desk
 * phones 1002-1005.
 *
 * The app is switched to file audio: it sends a 1 kHz tone instead of the microphone (an
 * emulator has no sound hardware anyway). That makes "who hears the app" observable on the
 * phones.
 */
abstract class PbxTestBase {
    protected lateinit var env: TestEnv
    protected lateinit var pbx: Ami
    protected lateinit var phones: Ami
    private var scenario: ActivityScenario<MainActivity>? = null

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    protected val engine: SipEngine
        get() = (instrumentation.targetContext.applicationContext as SipLinkApp).engine

    @Before
    fun connect() {
        env = TestEnv.load()
        val packageName = instrumentation.targetContext.packageName
        listOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.BLUETOOTH_CONNECT,
        ).forEach { instrumentation.uiAutomation.grantRuntimePermission(packageName, it) }

        // The tests' texts are the user's Chinese ones; the phone may speak another language.
        pinLanguage(Language.CHINESE_SIMPLIFIED)

        pbx = Ami(env.pbxHost, env.amiUser, env.amiSecret)
        phones = Ami(env.phonesAmiHost, env.amiUser, env.amiSecret, env.phonesAmiPort)
        release()
        // Start from an idle, unconfigured state, whatever an earlier test left behind.
        await("the app to have no call") { call() == null }
        val tone = ToneFile.of(instrumentation.targetContext)
        onCore {
            engine.updateSettings { Settings() }
            // The tests do not ask GitHub: a check the app makes by itself when it is shown fails, quietly.
            engine.updates.feed = NO_RELEASES
            engine.tls.certificates.value.forEach { engine.tls.remove(it.fingerprint) }
            engine.directory.directories.value.forEach { engine.directory.remove(it.id) }
            engine.directory.serverDelayMs = DIRECTORY_DELAY_MS
            engine.core.useFiles = true
            engine.core.playFile = tone.absolutePath
            // There is nothing to cancel with file audio, and the SDK's echo canceller cannot
            // run on every emulator (see sdkAudioProcessingSupported).
            engine.core.isEchoCancellationEnabled = false
        }
        await("the PBX to have no channel") { pbxChannels().isEmpty() }
        // The app unregisters within moments. What an aborted run left behind can only be
        // deleted on the PBX itself.
        if (!settles(UNREGISTER_WAIT_MS) { appContacts() == 0 }) purgeStaleContacts()
        await("the PBX to have no registration of the app") { appContacts() == 0 }
    }

    @After
    fun disconnect() {
        if (::pbx.isInitialized && ::phones.isInitialized) {
            runCatching { release() }
            pbx.close()
            phones.close()
        }
        scenario?.let { shown ->
            // The call screen stays a moment after the calls ended and fades out, see-through again
            // (CallActivity.coverBehind): the app behind it is then started, but not in front, a
            // stage from which ActivityScenario cannot close it. It is closed once that is over.
            val settled = settles(TIMEOUT_MS) { callScreenClosed() }
            try {
                shown.close()
            } finally {
                assertTrue("The call screen was still open ${TIMEOUT_MS}ms after the calls ended", settled)
            }
        }
    }

    /** No call screen is on the screen any more, and the app is at rest: in front, behind something, or gone. */
    private fun callScreenClosed(): Boolean = onMain {
        val monitor = ActivityLifecycleMonitorRegistry.getInstance()
        val callScreenShown = SHOWN_STAGES.any { stage -> monitor.getActivitiesInStage(stage).any { it is CallActivity } }
        val appMoving = MOVING_STAGES.any { stage -> monitor.getActivitiesInStage(stage).any { it is MainActivity } }
        !callScreenShown && !appMoving
    }

    /** Ends every call and removes every account of the app. */
    private fun release() {
        onCore {
            // A configuration that a test left managed would refuse to be cleared.
            engine.lock.setManaged(false)
            engine.core.terminateAllCalls()
            engine.accounts.accounts.value.forEach { engine.accounts.remove(it.id) }
        }
        pbx.command("channel request hangup all")
        phones.command("channel request hangup all")
    }

    /**
     * Brings the app to the foreground, like a user who has it open. Only the tests about
     * behaviour that depends on being visible do this: without hardware acceleration, drawing
     * the UI keeps the main thread (which also runs the SIP stack) busy for seconds at a time.
     */
    protected fun showApp() {
        // A real device may have switched its screen off: wake it and keep it on while shown.
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
        scenario = ActivityScenario.launch(MainActivity::class.java).onActivity {
            it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    /** The call screen, once it is in front. */
    protected fun callScreen(): Activity {
        fun find() = onMain {
            ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull { it is CallActivity }
        }
        await("the call screen to be in front") { find() != null }
        return find()!!
    }

    /**
     * Turns the call screen the way turning the phone does, without a change to the settings
     * of the phone or a hand on it.
     *
     * @param orientation one of the ActivityInfo.SCREEN_ORIENTATION_ values
     */
    protected fun turnCallScreen(orientation: Int) {
        val screen = callScreen()
        onMain { screen.requestedOrientation = orientation }
    }

    /** Runs a command as the shell user, like `adb shell`. */
    protected fun shell(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .use { it.readBytes() }
    }

    // ------------------------------------------------------------------ generic helpers

    /** Runs [block] on the thread of the SIP core and waits for it; what it threw is thrown here. */
    protected fun <T> onCore(block: () -> T): T = CoreThread.wait("test", block)

    /** Runs [block] on the main thread, for what touches the activities and their views. */
    protected fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    protected fun await(what: String, timeoutMs: Long = TIMEOUT_MS, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError("Timed out after ${timeoutMs}ms waiting for $what. App call: ${call()}")
            }
            Thread.sleep(POLL_MS)
        }
    }

    /**
     * Sets the app's own language to [language] (null: the system's) and waits until its texts
     * are in it. The phone's language stays as it is.
     */
    protected fun pinLanguage(language: Language?) {
        if (onCore { engine.language.chosen != language || (language == null) != engine.language.followsSystem }) {
            onMain { engine.language.choose(language) }
        }
        val expected = textsIn(language?.tag ?: systemLanguages()).getString(R.string.call_ended)
        await("the app to speak ${language?.tag ?: "the system's language"}") { onCore { engine.texts.get(R.string.call_ended) } == expected }
    }

    /** Like [await], but reports a timeout as false instead of failing. */
    protected fun settles(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) return false
            Thread.sleep(POLL_MS)
        }
        return true
    }

    /** Asserts that [condition] stays false for [windowMs]. */
    protected fun never(what: String, windowMs: Long, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + windowMs
        while (System.currentTimeMillis() < deadline) {
            assertTrue("Unexpected: $what. App call: ${call()}", !condition())
            Thread.sleep(POLL_MS)
        }
    }

    protected fun ok(error: String?) = assertNull("The app refused the request", error)

    // ------------------------------------------------------------------ app side

    protected fun call(): CallInfo? = engine.calls.call.value

    protected fun awaitCall(what: String, timeoutMs: Long = TIMEOUT_MS, match: (CallInfo) -> Boolean): CallInfo {
        var found: CallInfo? = null
        await(what, timeoutMs) { call()?.takeIf(match)?.also { found = it } != null }
        return found!!
    }

    /** The PBX calls the app and presents [callerId], written as `"name" <number>`. */
    protected fun ringing(callerId: String): CallInfo {
        val response = pbx.send(
            "Originate",
            "Channel" to "PJSIP/$APP",
            "CallerID" to callerId,
            "Application" to "Wait",
            "Data" to "30",
            "Async" to "true",
        )
        assertEquals("Originate refused: $response", "Success", response["Response"])
        return awaitCall("the call of $callerId to ring") { it.phase == CallPhase.INCOMING }
    }

    protected fun awaitConnected(peer: String? = null): CallInfo =
        awaitCall("a connected call" + peer?.let { " with $it" }.orEmpty()) {
            it.phase == CallPhase.CONNECTED && (peer == null || it.remoteAddress.startsWith("$peer@"))
        }

    /** Waits for the "call ended" screen and returns it (it carries the end reason). */
    protected fun awaitEnded(timeoutMs: Long = TIMEOUT_MS): CallInfo =
        awaitCall("the call to end", timeoutMs) { it.phase == CallPhase.ENDED }

    protected fun awaitNoCall(timeoutMs: Long = TIMEOUT_MS) = await("the call to be gone", timeoutMs) { call() == null }

    protected fun accounts(): List<SipAccount> = engine.accounts.accounts.value

    protected fun account(extension: Int): SipAccount? = accounts().firstOrNull { it.username == "$extension" }

    /** @param domain SIP domain of the account: the address of the PBX, or its DNS name */
    protected fun draft(
        extension: Int,
        transport: SipTransport = SipTransport.UDP,
        domain: String = env.pbxHost,
    ) = AccountDraft(username = "$extension", password = env.sipPassword, domain = domain, transport = transport)

    /** Adds the account of [extension] without waiting for the registration. */
    protected fun addAccount(
        extension: Int = APP,
        transport: SipTransport = SipTransport.UDP,
        domain: String = env.pbxHost,
    ) {
        val errors = onCore { engine.accounts.add(draft(extension, transport, domain)) }
        assertTrue("Account rejected: $errors", errors.isEmpty())
    }

    /** Adds an account from a draft of the test, for what [addAccount] has no parameter for. */
    protected fun addAccount(draft: AccountDraft) {
        val errors = onCore { engine.accounts.add(draft) }
        assertTrue("Account rejected: $errors", errors.isEmpty())
    }

    protected fun awaitRegistered(extension: Int = APP, timeoutMs: Long = TIMEOUT_MS): SipAccount {
        await("the registration of $extension", timeoutMs) { account(extension)?.state == RegState.OK }
        return account(extension)!!
    }

    protected fun register(
        extension: Int = APP,
        transport: SipTransport = SipTransport.UDP,
        domain: String = env.pbxHost,
    ): SipAccount {
        addAccount(extension, transport, domain)
        await("the registration of $extension over $transport") { account(extension)?.state == RegState.OK }
        return account(extension)!!
    }

    protected fun dial(target: String, supervision: SupervisionMode? = null) =
        ok(onCore { engine.calls.dial(target, null, supervision) })

    protected fun settings(change: (Settings) -> Settings) = onCore { engine.updateSettings(change) }

    /** Level of the audio the app currently receives, in dBm0 (about -120 for silence). */
    protected fun playVolume(): Float = onCore { engine.core.currentCall?.playVolume ?: SILENCE_DB }

    protected fun awaitAppHearsAudio() = await("the app to receive audio") { playVolume() > AUDIBLE_DB }

    // ------------------------------------------------------------------ PBX side

    protected fun pbxChannels(): List<Map<String, String>> = pbx.list("CoreShowChannels")

    protected fun pbxChannel(prefix: String): Map<String, String>? =
        pbxChannels().firstOrNull { it["Channel"].orEmpty().startsWith(prefix) }

    /** True when a channel of [a] and one of [b] (PBX channel name prefixes) share a bridge. */
    protected fun bridged(a: String, b: String): Boolean {
        val channels = pbxChannels()
        val first = channels.filter { it["Channel"].orEmpty().startsWith(a) }.mapNotNull { it["BridgeId"]?.ifEmpty { null } }
        val second = channels.filter { it["Channel"].orEmpty().startsWith(b) }.mapNotNull { it["BridgeId"]?.ifEmpty { null } }
        return first.any { it in second }
    }

    protected fun awaitBridged(a: Int, b: Int) =
        await("the PBX to bridge $a with $b") { bridged(pbxLeg(a), pbxLeg(b)) }

    protected fun pbxLeg(extension: Int) = "PJSIP/$extension-"

    /** The PBX leg of the app's call to [number]. */
    protected fun legOf(number: String): String? = pbxChannels()
        .firstOrNull { it["Channel"].orEmpty().startsWith(pbxLeg(APP)) && it["Exten"] == number }?.get("Channel")

    /** Contacts registered on the PBX from somewhere else than the scripted phones: the app's. */
    private fun appContacts(): Int = CONTACT.findAll(pbx.command("pjsip show contacts")).count { it.groupValues[2] != env.phonesHost }

    /** Deletes the registrations of the app straight from the registrar database of the PBX. */
    private fun purgeStaleContacts() {
        pbx.command("database show registrar/contact").lineSequence()
            .filter { "\"via_addr\":\"${env.phonesHost}\"" !in it }
            .mapNotNull { STORED_CONTACT.find(it)?.groupValues?.get(1) }
            .forEach { pbx.command("database del registrar/contact $it") }
    }

    /** The User-Agent the PBX stored with the registration of [extension]; null while it has none. */
    protected fun pbxUserAgent(extension: Int): String? =
        pbx.command("database show registrar/contact").lineSequence().firstOrNull { it.startsWith("/registrar/contact/$extension;") }
            ?.let { STORED_USER_AGENT.find(it)?.groupValues?.get(1) }

    protected fun pbxHasContact(extension: Int): Boolean = CONTACT.findAll(pbx.command("pjsip show contacts"))
        .any { it.groupValues[1] == "$extension" && it.groupValues[2] != env.phonesHost }

    // ------------------------------------------------------------------ scripted phones

    /**
     * Makes desk phone [from] call [number] through the PBX. Once answered the phone is
     * "silent" (and reports what it hears), plays a "tone", or does an "echo".
     */
    protected fun phoneCalls(from: Int, number: String, behaviour: String = "silent") {
        val response = phones.send(
            "Originate",
            "Channel" to "PJSIP/$number@as-$from",
            "Context" to "apps",
            "Exten" to behaviour,
            "Priority" to "1",
            "Async" to "true",
        )
        assertTrue("Originate refused: $response", response["Response"] == "Success")
    }

    /** Sets up the call the supervisor tests look at: agent 1002 talking to customer 1003. */
    protected fun agentCall(agentBehaviour: String = "silent") {
        phoneCalls(AGENT, "$CUSTOMER", agentBehaviour)
        awaitBridged(AGENT, CUSTOMER)
    }

    /** Whether an AMI packet of the phones instance belongs to a call leg of desk phone [phone]. */
    private fun isLegOf(packet: Map<String, String>, phone: Int): Boolean =
        packet["Channel"].orEmpty().startsWith("PJSIP/as-$phone-") ||
            (packet["Context"] == "phones" && packet["Exten"] == "$phone")

    /** Makes desk phone [phone], in a call it has answered, talk: it sends a tone from now on. */
    protected fun phoneTalks(phone: Int) {
        val channel = phones.list("CoreShowChannels")
            .first { it["Context"] == "phones" && it["Exten"] == "$phone" }["Channel"].orEmpty()
        val response = phones.send(
            "Redirect",
            "Channel" to channel,
            "Context" to "phones",
            "Exten" to "$phone",
            "Priority" to "talk",
        )
        assertTrue("Redirect refused: $response", response["Response"] == "Success")
    }

    protected fun awaitPhoneHears(phone: Int, from: Int) {
        phones.awaitEvent("ChannelTalkingStart", from) { isLegOf(it, phone) }
    }

    protected fun awaitPhoneHearsSilence(phone: Int, from: Int) {
        phones.awaitEvent("ChannelTalkingStop", from) { isLegOf(it, phone) }
    }

    protected fun phoneHearsWithin(phone: Int, from: Int, windowMs: Long = QUIET_WINDOW_MS): Boolean =
        phones.sees("ChannelTalkingStart", from, windowMs) { isLegOf(it, phone) }

    /** True once desk phone [phone] has answered a call from [caller]. */
    protected fun phoneAnswered(phone: Int, caller: Int): Boolean = phones.list("CoreShowChannels").any {
        it["Context"] == "phones" && it["Exten"] == "$phone" && it["ChannelStateDesc"] == "Up" &&
            it["CallerIDNum"] == "$caller"
    }

    /** Waits until a call placed by desk phone [phone] after [from] has been torn down. */
    protected fun awaitPhoneCallReleased(phone: Int, from: Int) {
        phones.awaitEvent("Hangup", from) { it["Channel"].orEmpty().startsWith("PJSIP/as-$phone-") }
    }

    protected fun phoneHasCall(phone: Int): Boolean = phones.list("CoreShowChannels").any { isLegOf(it, phone) }

    // ------------------------------------------------------------------ audio

    /**
     * The x86_64 build of the SDK's WebRTC plugin (echo canceller, voice activity detection) is
     * compiled for AVX. An emulator running without hardware acceleration has no AVX, and the
     * plugin then crashes the process with SIGILL. Three-way calls always load it.
     */
    protected fun sdkAudioProcessingSupported(): Boolean {
        if (Build.SUPPORTED_ABIS.firstOrNull()?.startsWith("x86") != true) return true
        return File("/proc/cpuinfo").useLines { lines ->
            lines.any { it.startsWith("flags") && " avx" in it }
        }
    }

    protected companion object {
        /** What the app is told when it asks for the latest release: nothing, as if it were offline. */
        val NO_RELEASES = ReleaseFeed { throw IOException("The tests do not ask GitHub") }

        /** Extension the app registers as. */
        const val APP = 1001

        /** Scripted desk phones: 1002 and 1003 answer silently, 1004 echoes, 1005 never answers. */
        const val AGENT = 1002
        const val CUSTOMER = 1003
        const val ECHO_PHONE = 1004
        const val RINGING_PHONE = 1005

        /** Generous timeouts: the emulator runs without hardware acceleration. */
        const val TIMEOUT_MS = 60_000L
        const val LONG_TIMEOUT_MS = 300_000L
        const val QUIET_WINDOW_MS = 5_000L

        /** What the directory waits before it asks a server, unless a test makes it slow. */
        private const val DIRECTORY_DELAY_MS = 100
        private const val UNREGISTER_WAIT_MS = 20_000L
        const val POLL_MS = 200L

        /** The stages of an activity that is on the screen, or on its way there or away from it. */
        private val SHOWN_STAGES = listOf(Stage.PRE_ON_CREATE, Stage.CREATED, Stage.STARTED, Stage.RESTARTED, Stage.RESUMED, Stage.PAUSED)

        /** The stages that ActivityScenario does not take as an activity at rest (only resumed, paused, stopped, destroyed). */
        private val MOVING_STAGES = listOf(Stage.PRE_ON_CREATE, Stage.CREATED, Stage.STARTED, Stage.RESTARTED)

        const val SILENCE_DB = -120f
        const val AUDIBLE_DB = -40f

        /** A row of "pjsip show contacts": extension and contact host. */
        private val CONTACT = Regex("""Contact:\s+(\d+)/sips?:[^@\s]+@([0-9.]+)""")

        /** What a stored registration says of the client that made it. */
        private val STORED_USER_AGENT = Regex(""""user_agent":"([^"]*)"""")

        /** A row of "database show registrar/contact": the key of a stored registration. */
        private val STORED_CONTACT = Regex("""^/registrar/contact/(\S+?)\s*:""")
    }
}
