package com.sky.siplink.itest.natlab

import android.Manifest
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import com.sky.siplink.SipLinkApp
import com.sky.siplink.core.LineCheck
import com.sky.siplink.core.SipEngine
import com.sky.siplink.core.model.AccountDraft
import com.sky.siplink.core.model.CallInfo
import com.sky.siplink.core.model.LineReport
import com.sky.siplink.core.model.RegState
import com.sky.siplink.core.model.Settings
import com.sky.siplink.core.model.SipAccount
import com.sky.siplink.core.model.SipTransport
import com.sky.siplink.itest.Ami
import com.sky.siplink.itest.ToneFile
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Base of the tests against the NAT lab (testenv/natlab): the app's SIP engine, in the app
 * process, against a PBX behind a NAT box whose behaviour the test switches ([box]). Run them
 * with testenv/natlab/run.sh, which hands over the lab's addresses and secrets.
 *
 * The app sends a 1 kHz tone instead of the microphone, so that the PBX's echo (600) gives it
 * something to hear.
 */
abstract class NatLabTestBase {
    protected lateinit var lab: Lab
    protected lateinit var pbx: Ami

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    protected val engine: SipEngine
        get() = (instrumentation.targetContext.applicationContext as SipLinkApp).engine

    /** What run.sh hands over in the `natlabEnv` instrumentation argument. */
    class Lab(private val values: Map<String, String>) {
        /** The NAT box's address on the client side: the PBX, its AMI and the STUN server are reached there. */
        val host: String get() = value("labHost")
        val sipPort: Int get() = value("sipPort").toInt()

        /** Where the PBX announces its own address instead of the box's (accounts [BEHIND_ACCOUNT]...). */
        val innerPort: Int get() = value("innerPort").toInt()
        val amiPort: Int get() = value("amiPort").toInt()
        val ctlPort: Int get() = value("ctlPort").toInt()
        val sipPassword: String get() = value("sipPassword")
        val amiUser: String get() = value("amiUser")
        val amiSecret: String get() = value("amiSecret")
        val stunServer: String get() = "$host:${value("stunPort")}"

        /** Whether the tests that take a quarter of an hour run as well. */
        val long: Boolean get() = values["long"] == "1"

        private fun value(name: String) = values[name] ?: throw AssertionError("'$name' is missing from '$ARGUMENT'")
    }

    @Before
    fun connect() {
        val encoded = InstrumentationRegistry.getArguments().getString(ARGUMENT)
        assumeTrue("No NAT lab: run the tests through testenv/natlab/run.sh", !encoded.isNullOrEmpty())
        lab = Lab(
            String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8).lineSequence()
                .filter { '=' in it }
                .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() },
        )
        val packageName = instrumentation.targetContext.packageName
        listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
            .forEach { instrumentation.uiAutomation.grantRuntimePermission(packageName, it) }
        box("reset")
        pbx = Ami(lab.host, lab.amiUser, lab.amiSecret, lab.amiPort)
        release()
        val tone = ToneFile.of(instrumentation.targetContext)
        onMain {
            engine.updateSettings { Settings() }
            engine.core.useFiles = true
            engine.core.playFile = tone.absolutePath
            engine.core.isEchoCancellationEnabled = false
        }
        await("the PBX to have no channel") { pbx.list("CoreShowChannels").isEmpty() }
    }

    @After
    fun disconnect() {
        if (::pbx.isInitialized) {
            runCatching { release() }
            pbx.close()
        }
        if (::lab.isInitialized) {
            runCatching { box("reset") }
            onMain { engine.updateSettings { Settings() } }
        }
    }

    private fun release() {
        onMain {
            engine.core.terminateAllCalls()
            engine.accounts.accounts.value.forEach { engine.accounts.remove(it.id) }
        }
        pbx.command("channel request hangup all")
    }

    // ------------------------------------------------------------------ the NAT box

    /** Sends one command to the NAT box (see testenv/natlab/tools/ctl.go) and checks that it took it. */
    protected fun box(command: String): String {
        val reply = Socket().use { socket ->
            socket.connect(InetSocketAddress(lab.host, lab.ctlPort), CONNECT_TIMEOUT_MS)
            socket.soTimeout = CONNECT_TIMEOUT_MS
            socket.getOutputStream().write("$command\n".toByteArray())
            socket.getInputStream().bufferedReader().readLine().orEmpty()
        }
        assertTrue("The NAT box refused '$command': $reply", reply.startsWith("ok "))
        return reply
    }

    // ------------------------------------------------------------------ the app

    protected fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    protected fun await(what: String, timeoutMs: Long = TIMEOUT_MS, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("Timed out after ${timeoutMs}ms waiting for $what")
            Thread.sleep(POLL_MS)
        }
    }

    /** Asserts that [condition] stays false for [windowMs]. */
    protected fun never(what: String, windowMs: Long, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + windowMs
        while (System.currentTimeMillis() < deadline) {
            assertTrue("Unexpected: $what", !condition())
            Thread.sleep(POLL_MS)
        }
    }

    protected fun account(extension: Int): SipAccount? = engine.accounts.accounts.value.firstOrNull { it.username == "$extension" }

    /** Adds the account of [extension] on the PBX at [port] (the lab's SIP port unless told). */
    protected fun addAccount(extension: Int, transport: SipTransport = SipTransport.UDP, expires: Int = 3600, port: Int = lab.sipPort) {
        val draft = AccountDraft(
            username = "$extension",
            password = lab.sipPassword,
            domain = lab.host,
            port = if (port == DEFAULT_SIP_PORT) "" else "$port",
            transport = transport,
            expires = expires,
        )
        val errors = onMain { engine.accounts.add(draft) }
        assertTrue("Account rejected: $errors", errors.isEmpty())
    }

    protected fun awaitRegistered(extension: Int, timeoutMs: Long = TIMEOUT_MS) =
        await("the registration of $extension", timeoutMs) { account(extension)?.state == RegState.OK }

    /** The transport the line of [extension] runs on instead of its own; null: its own. */
    protected fun adapted(extension: Int): SipTransport? = account(extension)?.let { engine.accounts.adaptedTransports.value[it.id] }

    protected fun call(): CallInfo? = engine.calls.call.value

    protected fun dial(target: String) {
        val error = onMain { engine.calls.dial(target, null) }
        assertTrue("The app refused to dial: $error", error == null)
    }

    /** Level of the audio the app receives, in dBm0 (about -120 for silence). */
    protected fun playVolume(): Float = onMain { engine.core.currentCall?.playVolume ?: SILENCE_DB }

    /** What the line checks find for the account of [extension]. */
    protected fun check(extension: Int): LineReport {
        val id = account(extension)!!.id
        val context = instrumentation.targetContext
        val done = CountDownLatch(1)
        var report: LineReport? = null
        val check = onMain { LineCheck(context, engine.core, engine.accounts) }
        try {
            onMain {
                check.run(id) {
                    report = it
                    done.countDown()
                }
            }
            assertTrue("The line checks did not finish", done.await(CHECK_TIMEOUT_MS, TimeUnit.MILLISECONDS))
        } finally {
            check.close()
        }
        return report!!
    }

    // ------------------------------------------------------------------ the PBX

    /** The contacts the PBX has for [extension], as "pjsip show contacts" lists them. */
    protected fun pbxContacts(extension: Int): List<String> =
        pbx.command("pjsip show contacts").lines().map { it.trim() }.filter { it.startsWith("Contact:") && " $extension/" in it }

    /** Has the PBX call the app's [extension]; once answered the PBX sends a tone. */
    protected fun pbxCallsApp(extension: Int) {
        val response = pbx.send(
            "Originate",
            "Channel" to "PJSIP/$extension",
            "Application" to "Milliwatt",
            "CallerID" to "Lab <600>",
            "Async" to "true",
        )
        assertTrue("Originate refused: $response", response["Response"] == "Success")
    }

    protected companion object {
        const val ARGUMENT = "natlabEnv"

        /** Accounts with the PBX's NAT handling, and without it (see testenv/natlab/pbx/entrypoint.sh). */
        const val NAT_ACCOUNT = 1001
        const val PLAIN_ACCOUNT = 3001
        const val BEHIND_ACCOUNT = 4001

        /** As [PLAIN_ACCOUNT], at a PBX that does ICE. */
        const val ICE_ACCOUNT = 5001
        const val DEFAULT_SIP_PORT = 5060
        const val ECHO = "600"
        const val TONE = "601"

        /** Generous: the emulator runs without hardware acceleration. */
        const val TIMEOUT_MS = 60_000L
        const val ADAPT_TIMEOUT_MS = 300_000L
        const val CHECK_TIMEOUT_MS = 60_000L
        const val CONNECT_TIMEOUT_MS = 10_000
        const val POLL_MS = 200L
        const val SILENCE_DB = -120f
        const val AUDIBLE_DB = -40f

        /** How long a call runs to show that something does not happen. */
        const val QUIET_WINDOW_MS = 8_000L

        /** Five seconds without sound, and the time the statistics take to come. */
        const val NO_SOUND_TIMEOUT_MS = 20_000L
    }
}
