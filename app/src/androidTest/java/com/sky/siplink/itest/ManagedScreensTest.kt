package com.sky.siplink.itest

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sky.siplink.core.TlsManager
import com.sky.siplink.core.logic.ManagedConfig
import com.sky.siplink.core.model.BannerPosition
import com.sky.siplink.core.model.LdapDirectory
import com.sky.siplink.core.model.Shortcut
import com.sky.siplink.core.model.ShortcutAction
import com.sky.siplink.core.model.ThemeMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The screens of a managed configuration, as the user gets them. The tests hand out a
 * configuration, switch the managed state on and go through the app by its accessibility tree,
 * the way a screen reader does: every screen that edits what is locked has to show it without
 * taking input, and what stays the user's own has to work.
 *
 * Each screen is also saved as a picture in the cache directory of the app (itest-managed/),
 * for a look at how it is drawn.
 */
@RunWith(AndroidJUnit4::class)
class ManagedScreensTest : PbxTestBase() {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation

    @After
    fun takeBack() {
        onMain {
            engine.lock.setManaged(false)
            engine.shortcuts.remove(engine.shortcuts.shortcuts.value.map { it.id })
            engine.history.clear()
        }
    }

    // ------------------------------------------------------------------ the screen, as nodes

    /**
     * What is on the screen now. The nodes the system remembers are dropped first: it was seen
     * to go on handing out a screen as it was before a notice left it.
     */
    private fun nodes(): List<AccessibilityNodeInfo> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) automation.clearCache()
        val all = ArrayList<AccessibilityNodeInfo>()
        fun walk(node: AccessibilityNodeInfo) {
            all += node
            for (index in 0 until node.childCount) node.getChild(index)?.let(::walk)
        }
        automation.rootInActiveWindow?.let(::walk)
        return all
    }

    private fun says(node: AccessibilityNodeInfo, text: String): Boolean =
        node.text?.toString() == text || node.contentDescription?.toString() == text

    private fun shown(text: String): List<AccessibilityNodeInfo> = nodes().filter { says(it, text) }

    /** Everything the screen says, for the message of a failure. */
    private fun screenText(): String = nodes().mapNotNull { it.text ?: it.contentDescription }.joinToString(" | ")

    /**
     * Lets a tap or a scroll show: waits until two looks at the screen find everything where it
     * was. (The system's own "wait for idle" returns in the middle of a scroll, and what is
     * caught passing by takes no tap.)
     */
    private fun settle() {
        val bounds = Rect()
        val deadline = System.currentTimeMillis() + SETTLE_TIMEOUT_MS
        var before = ""
        var unchanged = 0
        while (unchanged < 2 && System.currentTimeMillis() < deadline) {
            Thread.sleep(LOOK_INTERVAL_MS)
            val now = nodes().joinToString("|") { node ->
                node.getBoundsInScreen(bounds)
                "${node.text}/${node.contentDescription}/${bounds.toShortString()}"
            }
            if (now == before) unchanged++ else unchanged = 0
            before = now
        }
    }

    /** Scrolls what is on screen one step; false when nothing moved. */
    private fun scroll(forward: Boolean): Boolean {
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        val moved = nodes().filter { it.isScrollable }.map { it.performAction(action) }.any { it }
        if (moved) settle()
        return moved
    }

    /** The node that says [text], looked for through the whole length of the screen; null when there is none. */
    private fun find(text: String): AccessibilityNodeInfo? {
        shown(text).firstOrNull()?.let { return it }
        // Down to the end, then back up: one of the two passes what is off the screen at the moment.
        for (forward in listOf(true, false)) {
            var steps = 0
            while (steps++ < MAX_SCROLLS && scroll(forward)) shown(text).firstOrNull()?.let { return it }
        }
        return null
    }

    private fun see(text: String): AccessibilityNodeInfo {
        var node: AccessibilityNodeInfo? = null
        if (!settles(SCREEN_TIMEOUT_MS) { find(text)?.also { node = it } != null }) {
            throw AssertionError("\"$text\" is not on the screen, which says: ${screenText()}")
        }
        return node!!
    }

    private fun absent(vararg texts: String) = texts.forEach { assertTrue("\"$it\" is on the screen", find(it) == null) }

    /** The node itself and what it is inside of, innermost first. */
    private fun chain(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = generateSequence(node) { it.parent }.toList()

    /** Whether the control that says [text] takes input: no part of it is disabled, and some part acts on a tap or on typing. */
    private fun takesInput(text: String): Boolean {
        val chain = chain(see(text))
        return chain.all { it.isEnabled } && chain.any { it.isClickable || it.isEditable }
    }

    private fun locked(vararg texts: String) = texts.forEach { assertFalse("\"$it\" takes input", takesInput(it)) }

    private fun usable(vararg texts: String) = texts.forEach { assertTrue("\"$it\" takes no input", takesInput(it)) }

    /**
     * The buttons that say nothing about themselves. "Add account" is one: its caption is not
     * part of the accessibility tree, so it can only be told by being there.
     */
    private fun namelessButtons(): List<AccessibilityNodeInfo> {
        fun named(node: AccessibilityNodeInfo): Boolean =
            node.text != null || node.contentDescription != null || (0 until node.childCount).any { index -> node.getChild(index)?.let(::named) == true }
        return nodes().filter { it.isClickable && !it.isCheckable && !named(it) }
    }

    /** Every switch of the screen is there to look at only. */
    private fun switchesAreLocked() {
        val switches = nodes().filter { it.isCheckable }
        assertTrue("No switch on the screen", switches.isNotEmpty())
        assertTrue("A switch takes input", switches.none { it.isEnabled })
    }

    /** Performs [action] on the control that says [text]. A screen that is still moving may not take it at once. */
    private fun act(text: String, action: Int) {
        val done = settles(SCREEN_TIMEOUT_MS) {
            val target = chain(see(text)).firstOrNull { node -> node.isEnabled && node.actionList.any { it.id == action } }
            target?.performAction(action) == true
        }
        assertTrue("\"$text\" does not take the action. The screen says: ${screenText()}", done)
        settle()
    }

    private fun tap(text: String) = act(text, AccessibilityNodeInfo.ACTION_CLICK)

    private fun back() {
        automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        settle()
    }

    /** The password field of the form on screen shows a row of dots that is not the password, and no way to reveal it. */
    private fun passwordIsHidden(password: String) {
        val field = chain(see("密码")).first { it.isPassword }
        assertEquals("The field shows the password, or how long it is", "•".repeat(MASK_LENGTH), field.text?.toString())
        assertTrue("The password is on the screen", nodes().none { it.text?.contains(password) == true })
        absent("显示密码", "隐藏密码")
    }

    /** Saves what the display shows as [name].png. */
    private fun picture(name: String) {
        val bitmap = automation.takeScreenshot() ?: return
        val directory = File(instrumentation.targetContext.cacheDir, PICTURES).apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    // ------------------------------------------------------------------ the tests

    /** Something of everything that can be configured, as a remote side could hand it out, and two calls in the history. */
    private fun handOut() {
        await("the shortcut keys to be read") { engine.shortcuts.loaded.value }
        addAccount(draft(APP).copy(name = LINE))
        awaitRegistered(APP)
        repeat(2) {
            dial("2000")
            awaitConnected("2000")
            onMain { engine.calls.hangUp() }
            awaitNoCall()
        }
        await("both calls in the history") { engine.history.entries.value.size >= 2 }
        onMain {
            // The question about the lock screen would stand in front of the screens.
            assertTrue(engine.updateSettings { it.copy(lockScreenPromptDeclined = true, forwardBusy = "$CUSTOMER") })
            val directory = LdapDirectory(name = DIRECTORY, host = env.ldapHost, bindDn = env.ldapBindDn, password = env.ldapPassword, baseDn = env.ldapBaseDn)
            assertTrue(engine.directory.save(directory).isEmpty())
            assertEquals(TlsManager.ImportResult.Added(1), engine.tls.import(env.caPem))
            engine.shortcuts.remove(engine.shortcuts.shortcuts.value.map { it.id })
            assertTrue(engine.shortcuts.add(Shortcut(0, SPEED_KEY, "$AGENT", category = "常用")).isEmpty())
            assertTrue(engine.shortcuts.add(Shortcut(0, FORWARD_KEY, "$CUSTOMER", ShortcutAction.FORWARD, "常用")).isEmpty())
        }
    }

    @Test
    fun aManagedConfigurationIsShownAndTakesNoInput() {
        handOut()
        onMain { engine.lock.setManaged(true) }
        showApp()

        // Settings: the locked switches are there to look at, the user's own ones work.
        tap("设置")
        see(ManagedConfig.NOTICE)
        locked("视频通话", "自动接听对讲呼叫", "对讲使用扬声器", "后台保持在线")
        usable("免打扰", "深色", "顶部", "电池优化")
        tap("免打扰")
        await("do not disturb to be switched on") { engine.settings.current.doNotDisturb }
        tap("免打扰")
        await("do not disturb to be switched off") { !engine.settings.current.doNotDisturb }
        tap("深色")
        await("the dark theme") { engine.settings.current.themeMode == ThemeMode.DARK }
        tap("跟随系统")
        tap("顶部")
        await("the call banner at the top") { engine.settings.current.callBannerPosition == BannerPosition.TOP }
        tap("底部")
        see("SIP 账号")
        picture("settings")

        // The accounts: listed, with nothing to add, import or switch; one of them opens to be read.
        tap("SIP 账号")
        see(LINE)
        see(ManagedConfig.NOTICE)
        absent("批量导入账号")
        assertTrue("There is a button to add an account", namelessButtons().isEmpty())
        switchesAreLocked()
        picture("accounts")
        tap(LINE)
        see("账号详情")
        see(ManagedConfig.NOTICE)
        locked("账号名称（可选）", "用户名", "密码", "域名 / 服务器", "端口", "显示名称（可选）", "UDP", "TLS", "启用此账号")
        passwordIsHidden(env.sipPassword)
        absent("保存", "删除账号", "设为默认账号", "当前默认账号")
        see("账号详情")
        picture("account")
        back()
        back()

        // Call handling, feature codes, codecs.
        tap("呼叫处理")
        see(ManagedConfig.NOTICE)
        locked("软件", "服务器", "呼叫等待", "拒接匿名来电", "RFC 2833", "SIP INFO", "无条件前转", "遇忙前转", "无应答前转", "无应答等待时间（秒）")
        see("免打扰方式")
        picture("call-handling")
        back()
        tap("功能码")
        see(ManagedConfig.NOTICE)
        locked("对讲", "驻留", "语音信箱", "关闭免打扰")
        absent("恢复默认")
        picture("feature-codes")
        back()
        tap("编码")
        see(ManagedConfig.NOTICE)
        see("音频")
        switchesAreLocked()
        val arrows = nodes().filter { it.contentDescription?.startsWith("提高") == true || it.contentDescription?.startsWith("降低") == true }
        assertTrue("No arrows on the screen", arrows.isNotEmpty())
        assertTrue("An arrow takes input", arrows.all { arrow -> chain(arrow).any { !it.isEnabled } })
        absent("恢复默认")
        picture("codecs")
        back()

        // TLS: the CA is listed and stays, verification and media encryption are not to be touched.
        tap("TLS 与 CA 证书")
        see(ManagedConfig.NOTICE)
        locked("校验服务器证书", "校验主机名", "关闭", "优先", "强制")
        assertTrue("The CA is not listed", nodes().any { it.text?.startsWith("签发者") == true })
        absent("导入 CA 证书", "移除证书")
        picture("tls")
        back()

        // The directories: none to add, the one there is opens to be read. It is searched as ever,
        // and the local copy of what it answered is the user's to empty.
        val directory = engine.directory.directories.value.single()
        onMain { engine.directory.search(directory.id, "100") }
        await("the answer of the directory in the local copy") { engine.directory.cacheStats.value.entries > 0 }
        tap("LDAP 通讯录")
        see(ManagedConfig.NOTICE)
        usable("清空缓存")
        absent("添加通讯录")
        picture("directories")
        tap("清空缓存")
        await("the local copy to be emptied") { engine.directory.cacheStats.value.entries == 0L }
        tap(DIRECTORY)
        see("通讯录详情")
        see(ManagedConfig.NOTICE)
        locked("名称（可选）", "服务器地址", "搜索基准", "不加密", "StartTLS", "匿名", "用户名", "密码", "线路", "来电显示姓名", "启用此通讯录")
        passwordIsHidden(env.ldapPassword)
        absent("保存", "删除通讯录")
        see("通讯录详情")
        picture("directory")
        back()
        back()

        // The history is the user's own: an entry can be deleted, but not be made a shortcut key.
        tap("记录")
        val entries = engine.history.entries.value.size
        assertTrue("No call in the history", entries > 0)
        act(engine.history.entries.value.first().name, AccessibilityNodeInfo.ACTION_LONG_CLICK)
        see("话务功能…")
        absent("添加到快捷键")
        picture("history-menu")
        tap("删除记录")
        await("the entry to be deleted") { engine.history.entries.value.size == entries - 1 }

        // The shortcut keys run, and can be exported; nothing edits, adds or imports one.
        tap("拨号")
        tap("快捷键")
        see(SPEED_KEY)
        see(ManagedConfig.NOTICE)
        absent("编辑", "新建快捷键")
        picture("shortcuts")
        tap("更多")
        see("导出为 CSV")
        absent("批量导入")
        back()
        // Forwarding is part of what was handed out: its key says so and switches nothing.
        tap(FORWARD_KEY)
        await("the key to say why it does nothing") { shown(ManagedConfig.NOTICE).size == 2 }
        assertEquals("", engine.settings.current.forwardAlways)
        back()

        // Switched off while the app is shown, the screens take input again and everything that
        // was left out is back: none of it was missing above for being called something else.
        tap("设置")
        see(ManagedConfig.NOTICE)
        onMain { engine.lock.setManaged(false) }
        await("the notice to go") { shown(ManagedConfig.NOTICE).isEmpty() }
        usable("视频通话", "自动接听对讲呼叫", "后台保持在线")
        tap("SIP 账号")
        see("批量导入账号")
        assertEquals("The button to add an account", 1, namelessButtons().size)
        tap(LINE)
        see("编辑账号")
        usable("用户名", "密码", "启用此账号")
        listOf("保存", "显示密码", "当前默认账号", "删除账号").forEach(::see)
        back()
        back()
        tap("呼叫处理")
        usable("服务器", "呼叫等待", "SIP INFO", "无条件前转")
        back()
        tap("功能码")
        see("恢复默认")
        back()
        tap("编码")
        see("恢复默认")
        back()
        tap("TLS 与 CA 证书")
        usable("校验服务器证书", "强制")
        listOf("导入 CA 证书", "移除证书").forEach(::see)
        back()
        tap("LDAP 通讯录")
        see("添加通讯录")
        tap(DIRECTORY)
        see("编辑通讯录")
        listOf("保存", "显示密码", "删除通讯录").forEach(::see)
        back()
        back()
        tap("记录")
        act(engine.history.entries.value.first().name, AccessibilityNodeInfo.ACTION_LONG_CLICK)
        see("添加到快捷键")
        back()
        tap("拨号")
        tap("快捷键")
        listOf("编辑", "新建快捷键").forEach(::see)
        tap("更多")
        see("批量导入")
    }

    @Test
    fun anEmptyManagedConfigurationOffersNothingToAdd() {
        await("the shortcut keys to be read") { engine.shortcuts.loaded.value }
        onMain {
            assertTrue(engine.updateSettings { it.copy(lockScreenPromptDeclined = true) })
            engine.shortcuts.remove(engine.shortcuts.shortcuts.value.map { it.id })
            engine.lock.setManaged(true)
        }
        showApp()

        // The dial pad says that there is no account instead of offering to add one.
        see("没有 SIP 账号")
        absent("添加 SIP 账号")
        picture("empty-dialer")
        tap("没有 SIP 账号")
        await("the dial pad to say why") { shown(ManagedConfig.NOTICE).isNotEmpty() }
        tap("快捷键")
        see("还没有快捷键")
        see(ManagedConfig.NOTICE)
        absent("新建快捷键", "批量导入")
        picture("empty-shortcuts")
        back()

        tap("设置")
        tap("SIP 账号")
        see("没有 SIP 账号")
        see(ManagedConfig.NOTICE)
        absent("添加账号", "添加第一个 SIP 账号", "批量导入账号")
        picture("empty-accounts")
        back()
        tap("LDAP 通讯录")
        see("尚未配置 LDAP 通讯录")
        see(ManagedConfig.NOTICE)
        absent("添加通讯录")
        picture("empty-directories")
        back()
        tap("TLS 与 CA 证书")
        see("尚未导入证书")
        absent("导入 CA 证书")

        // Switched off, each of them offers again what it left out.
        onMain { engine.lock.setManaged(false) }
        see("导入 CA 证书")
        back()
        tap("LDAP 通讯录")
        see("添加通讯录")
        back()
        tap("SIP 账号")
        listOf("添加第一个 SIP 账号", "添加账号", "批量导入账号").forEach(::see)
        back()
        tap("拨号")
        see("添加 SIP 账号")
        tap("快捷键")
        listOf("新建快捷键", "批量导入").forEach(::see)
    }

    private companion object {
        const val PICTURES = "itest-managed"
        const val LINE = "总机"
        const val DIRECTORY = "总部"
        const val SPEED_KEY = "前台"
        const val FORWARD_KEY = "转总机"

        /** How many dots a locked password field shows, whatever the password. */
        const val MASK_LENGTH = 8

        /** How far apart the looks are that have to find the screen unchanged, and how long it gets to come to rest. */
        const val LOOK_INTERVAL_MS = 120L
        const val SETTLE_TIMEOUT_MS = 4_000L
        const val SCREEN_TIMEOUT_MS = 10_000L
        const val MAX_SCROLLS = 12
    }
}
