package com.sky.siplink.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Makes the baseline profile of the app: the code it runs as it starts cold (also its startup
 * profile), and on the dial pad and the three other tabs. Run through `:app:generateBaselineProfile`
 * on a phone with Android 13 or later (docs/testing.md); the profile goes to
 * app/src/release/generated/baselineProfiles.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun startup() = rule.collect(packageName = PACKAGE, includeInStartupProfile = true) {
        startApp()
    }

    @Test
    fun dialPadAndTabs() = rule.collect(packageName = PACKAGE) {
        startApp()
        dismissLockScreenPrompt()
        typeOnDialPad("1001")
        listOf("记录", "通讯录", "设置", "拨号").forEach { openTab(it) }
    }
}
