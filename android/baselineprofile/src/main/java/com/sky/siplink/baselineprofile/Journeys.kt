package com.sky.siplink.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until

/** The app whose profile is made and whose start is measured. */
internal const val PACKAGE = "com.sky.siplink"

/**
 * The permissions the app asks for as it starts (StartupPermissions): granted beforehand, so that
 * no dialog of the system covers the app.
 */
private val RUNTIME_PERMISSIONS = listOf(
    "android.permission.RECORD_AUDIO",
    "android.permission.POST_NOTIFICATIONS",
    "android.permission.BLUETOOTH_CONNECT",
)

/** How long a screen gets to show what is looked for on it. */
private const val WAIT_MS = 5_000L

/** Starts the app from the home screen, as the user does, and waits for its first frame. */
internal fun MacrobenchmarkScope.startApp() {
    grantPermissions()
    pressHome()
    startActivityAndWait()
}

internal fun MacrobenchmarkScope.grantPermissions() {
    RUNTIME_PERMISSIONS.forEach { device.executeShellCommand("pm grant $PACKAGE $it") }
}

/**
 * Closes the question whether calls may show on the lock screen, which the app asks as it starts
 * while it may not; closed with the back key it comes again only at the next start.
 */
internal fun MacrobenchmarkScope.dismissLockScreenPrompt() {
    if (device.wait(Until.hasObject(By.text("允许在锁屏时显示来电")), WAIT_MS) == true) {
        device.pressBack()
        device.waitForIdle()
    }
}

/** Types [number] on the dial pad, the first tab, and deletes it again. */
internal fun MacrobenchmarkScope.typeOnDialPad(number: String) {
    // The keys are found before anything is typed: the typed number shows the same figures.
    val keys = number.toSet().associateWith { digit ->
        device.wait(Until.findObject(By.text(digit.toString())), WAIT_MS) ?: error("No key $digit on the dial pad")
    }
    number.forEach { keys.getValue(it).click() }
    device.waitForIdle()
    repeat(number.length) {
        device.wait(Until.findObject(By.desc("删除")), WAIT_MS)?.click()
        device.waitForIdle()
    }
}

/** Opens the tab labelled [label] in the bar at the bottom. */
internal fun MacrobenchmarkScope.openTab(label: String) {
    val tab = device.wait(Until.findObject(By.text(label)), WAIT_MS) ?: error("No tab $label")
    tab.click()
    device.waitForIdle()
}
