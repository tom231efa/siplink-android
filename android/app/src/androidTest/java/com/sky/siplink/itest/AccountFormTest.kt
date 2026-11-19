package com.sky.siplink.itest

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The form of a new account as the user gets it: the order of its fields, and fields that keep
 * their height. The domain field used to grow to almost twice its height the moment it had the
 * focus while empty, because its placeholder wrapped in the narrow box, and to shrink back with
 * the first character typed; the fields below it jumped with it.
 *
 * Pictures of the form are saved in the cache directory of the app (itest-forms/).
 */
@RunWith(AndroidJUnit4::class)
class AccountFormTest : ScreenTestBase("itest-forms") {
    /** The editable field with the label [label]. */
    private fun field(label: String): AccessibilityNodeInfo = chain(see(label)).first { it.isEditable }

    private fun boundsOf(label: String): Rect = Rect().also { field(label).getBoundsInScreen(it) }

    @Test
    fun theDomainFieldKeepsItsHeightWhenItHasTheFocus() {
        // The question about the lock screen would stand in front of the form.
        onMain { assertTrue(engine.updateSettings { it.copy(lockScreenPromptDeclined = true) }) }
        showApp()
        // Without an account the dial pad leads to the form for one.
        tap("添加 SIP 账号")
        see("添加账号")

        // User name and port share a row, the domain has one of its own below them, the password follows.
        val user = boundsOf(USER)
        val port = boundsOf(PORT)
        val domain = boundsOf(DOMAIN)
        assertEquals("User name and port are not side by side", user.top, port.top)
        assertTrue("The port is not right of the user name", port.left >= user.right)
        assertTrue("The domain is not below the user name", domain.top >= user.bottom)
        assertTrue("The domain does not take the width of the form", domain.width() >= user.width() + port.width())
        assertTrue("The password is not below the domain", boundsOf(PASSWORD).top >= domain.bottom)
        val gap = boundsOf(PASSWORD).top - domain.top
        picture("account-new")

        // Focused while empty: the placeholder shows, and the field stays as it is.
        tap(DOMAIN)
        await("the domain field to have the focus") { field(DOMAIN).isFocused }
        settle()
        picture("account-domain-focused")
        val focused = boundsOf(DOMAIN)
        assertEquals("The domain field changed its height with the focus", domain.height(), focused.height())
        assertEquals("The password field moved against the domain field", gap, boundsOf(PASSWORD).top - focused.top)

        // The first character typed changes nothing either.
        typeInto(DOMAIN, "p")
        assertEquals("The domain field changed its height with the first character", domain.height(), boundsOf(DOMAIN).height())
        assertEquals("The password field moved against the domain field", gap, boundsOf(PASSWORD).top - boundsOf(DOMAIN).top)
    }

    private companion object {
        const val USER = "用户名"
        const val PORT = "端口"
        const val DOMAIN = "域名 / 服务器"
        const val PASSWORD = "密码"
    }
}
