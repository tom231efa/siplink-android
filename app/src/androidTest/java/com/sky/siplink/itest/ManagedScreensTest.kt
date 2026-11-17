package com.sky.siplink.itest

import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sky.siplink.BuildConfig
import com.sky.siplink.core.TlsManager
import com.sky.siplink.core.logic.ManagedConfig
import com.sky.siplink.core.logic.ShortcutRules
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
import java.security.MessageDigest

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
class ManagedScreensTest : ScreenTestBase(PICTURES) {
    @After
    fun takeBack() {
        onMain {
            engine.lock.setManaged(false)
            engine.shortcuts.remove(engine.shortcuts.shortcuts.value.map { it.id })
            engine.history.clear()
        }
    }

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

    private fun passwordOnScreen(password: String): Boolean = nodes().any { it.text?.contains(password) == true }

    /** The password field of the form on screen shows a row of dots that says nothing about the password, and an eye to ask for it. */
    private fun passwordIsKeptBack(password: String) {
        val field = chain(see("密码")).first { it.isPassword }
        assertEquals("The field shows the password, or how long it is", "•".repeat(MASK_LENGTH), field.text?.toString())
        assertFalse("The password is on the screen", passwordOnScreen(password))
        see("显示密码")
    }

    /** The eye of the form on screen shows [password] in the clear. */
    private fun passwordIsShown(password: String) {
        if (!settles(SCREEN_TIMEOUT_MS) { passwordOnScreen(password) }) {
            throw AssertionError("The password is not shown. The screen says: ${screenText().replace(password, "<the password>")}")
        }
        see("隐藏密码")
    }

    /** The counter under the title of the shortcut screen while the configuration is managed and the user has [count] keys of their own. */
    private fun ownKeysCounter(count: Int): String = "$count / ${ShortcutRules.MAX_OWN}"

    /** The counter under the title of the shortcut screen while the configuration is not managed. */
    private fun allKeysCounter(): String = "${engine.shortcuts.shortcuts.value.size} / ${ShortcutRules.MAX_SHORTCUTS}"

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

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
        see(ManagedConfig.LOCKED)
        absent(ManagedConfig.NOTICE)
        // The lock says why when tapped.
        tap(ManagedConfig.LOCKED)
        await("the lock to say why") { shown(ManagedConfig.NOTICE).isNotEmpty() }
        await("the notice to go") { shown(ManagedConfig.NOTICE).isEmpty() }
        locked("视频通话", "自动接听对讲呼叫", "对讲使用扬声器", "后台保持在线", "自动检查更新")
        // Checking for a newer version is an action, not a setting.
        usable("免打扰", "深色", "顶部", "电池优化", VERSION_ROW)
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
        see(ManagedConfig.LOCKED)
        absent("批量导入账号")
        assertTrue("There is a button to add an account", namelessButtons().isEmpty())
        switchesAreLocked()
        picture("accounts")
        tap(LINE)
        see("账号详情")
        see(ManagedConfig.LOCKED)
        locked("账号名称（可选）", "用户名", "密码", "域名 / 服务器", "端口", "显示名称（可选）", "UDP", "TLS", "启用此账号")
        // Without a password of its own the configuration lets the stored ones be looked at.
        assertFalse("The password is on the screen", passwordOnScreen(env.sipPassword))
        see("显示密码")
        absent("保存", "删除账号", "设为默认账号", "当前默认账号")
        see("账号详情")
        picture("account")
        back()
        back()

        // Call handling, feature codes, codecs.
        tap("高级设置")
        see(ManagedConfig.LOCKED)
        locked("软件", "服务器", "呼叫等待", "拒接匿名来电", "RFC 2833", "SIP INFO", "无条件前转", "遇忙前转", "无应答前转", "无应答等待时间（秒）")
        locked("自适应速率控制", "回声消除", "允许 IPv6", "客户端名称（User-Agent）")
        see("免打扰方式")
        picture("advanced")
        back()
        tap("功能码")
        see(ManagedConfig.LOCKED)
        locked("对讲", "驻留", "语音信箱", "关闭免打扰")
        absent("恢复默认")
        picture("feature-codes")
        back()
        tap("编码")
        see(ManagedConfig.LOCKED)
        see("音频")
        switchesAreLocked()
        val arrows = nodes().filter { it.contentDescription?.startsWith("提高") == true || it.contentDescription?.startsWith("降低") == true }
        assertTrue("No arrows on the screen", arrows.isNotEmpty())
        assertTrue("An arrow takes input", arrows.all { arrow -> chain(arrow).any { !it.isEnabled } })
        absent("恢复默认")
        picture("codecs")
        // The size and the rate of the video stand above the video codecs.
        locked("240p", "720p", "自动", "30")
        back()

        // TLS: the CA is listed and stays, verification and media encryption are not to be touched.
        tap("TLS 与 CA 证书")
        see(ManagedConfig.LOCKED)
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
        see(ManagedConfig.LOCKED)
        usable("清空缓存")
        absent("添加通讯录")
        picture("directories")
        tap("清空缓存")
        await("the local copy to be emptied") { engine.directory.cacheStats.value.entries == 0L }
        tap(DIRECTORY)
        see("通讯录详情")
        see(ManagedConfig.LOCKED)
        locked("名称（可选）", "服务器地址", "搜索基准", "不加密", "StartTLS", "匿名", "用户名", "密码", "线路", "来电显示姓名", "启用此通讯录")
        assertFalse("The password is on the screen", passwordOnScreen(env.ldapPassword))
        see("显示密码")
        absent("保存", "删除通讯录")
        see("通讯录详情")
        picture("directory")
        back()
        back()

        // The history is the user's own: an entry can be deleted, and be made a shortcut key of their own.
        tap("记录")
        val entries = engine.history.entries.value.size
        assertTrue("No call in the history", entries > 0)
        act(engine.history.entries.value.first().name, AccessibilityNodeInfo.ACTION_LONG_CLICK)
        see("话务功能…")
        see("添加到快捷键")
        picture("history-menu")
        tap("删除记录")
        await("the entry to be deleted") { engine.history.entries.value.size == entries - 1 }

        // The shortcut keys that were handed out run, and can be exported; nothing opens or imports one.
        tap("拨号")
        tap("快捷键")
        see(SPEED_KEY)
        see(ownKeysCounter(0))
        locked("编辑")
        picture("shortcuts")
        tap("更多")
        see("导出为 CSV")
        absent("批量导入")
        back()
        // Forwarding is part of what was handed out: its key says so and switches nothing.
        tap(FORWARD_KEY)
        await("the key to say why it does nothing") { shown(ManagedConfig.NOTICE).isNotEmpty() }
        assertEquals("", engine.settings.current.forwardAlways)
        await("the notice to go") { shown(ManagedConfig.NOTICE).isEmpty() }

        // Next to them the user makes keys of their own, which stay theirs to open and to remove.
        tap("新建快捷键")
        typeInto("名称", OWN_KEY)
        typeInto("号码或功能码", "$AGENT")
        tap("保存")
        await("the key of the user's own") { engine.shortcuts.shortcuts.value.any { it.own && it.label == OWN_KEY } }
        see(OWN_KEY)
        see(ownKeysCounter(1))
        picture("shortcuts-own")
        tap("编辑")
        tap(SPEED_KEY)
        await("the key that was handed out to say why it does not open") { shown(ManagedConfig.NOTICE).isNotEmpty() }
        absent("编辑快捷键")
        tap(OWN_KEY)
        see("编辑快捷键")
        usable("名称")
        tap("删除")
        tap("再次点按以确认删除")
        await("the key to be removed") { engine.shortcuts.shortcuts.value.none { it.own } }
        see(ownKeysCounter(0))
        tap("完成")
        back()

        // Switched off while the app is shown, the screens take input again and everything that
        // was left out is back: none of it was missing above for being called something else.
        tap("设置")
        see(ManagedConfig.LOCKED)
        onMain { engine.lock.setManaged(false) }
        await("the lock to go") { shown(ManagedConfig.LOCKED).isEmpty() }
        usable("视频通话", "自动接听对讲呼叫", "后台保持在线", "自动检查更新")
        tap("SIP 账号")
        see("批量导入账号")
        assertEquals("The button to add an account", 1, namelessButtons().size)
        tap(LINE)
        see("编辑账号")
        usable("用户名", "密码", "启用此账号")
        listOf("保存", "显示密码", "当前默认账号", "删除账号").forEach(::see)
        back()
        back()
        tap("高级设置")
        usable("服务器", "呼叫等待", "SIP INFO", "无条件前转", "自适应速率控制", "回声消除", "允许 IPv6", "客户端名称（User-Agent）")
        back()
        tap("功能码")
        see("恢复默认")
        back()
        tap("编码")
        see("恢复默认")
        usable("720p", "30")
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
        see(allKeysCounter())
        listOf("编辑", "新建快捷键").forEach(::see)
        tap("更多")
        see("批量导入")
    }

    @Test
    fun theStoredPasswordsAreShownAsTheConfigurationHasIt() {
        addAccount(draft(APP).copy(name = LINE))
        onMain {
            assertTrue(engine.updateSettings { it.copy(lockScreenPromptDeclined = true) })
            val directory = LdapDirectory(name = DIRECTORY, host = env.ldapHost, bindDn = env.ldapBindDn, password = env.ldapPassword, baseDn = env.ldapBaseDn)
            assertTrue(engine.directory.save(directory).isEmpty())
            // With a password of its own the configuration keeps the stored ones back.
            engine.lock.setManaged(true, sha256(CONFIG_PASSWORD))
        }
        showApp()

        // The account: dots that say nothing, until the password of the configuration is typed in.
        tap("设置")
        tap("SIP 账号")
        tap(LINE)
        see("账号详情")
        passwordIsKeptBack(env.sipPassword)
        tap("显示密码")
        see("配置密码")
        picture("config-password")
        type("$CONFIG_PASSWORD?")
        tap("查看")
        see("密码不正确")
        tap("取消")
        passwordIsKeptBack(env.sipPassword)
        tap("显示密码")
        type(CONFIG_PASSWORD)
        tap("查看")
        passwordIsShown(env.sipPassword)
        // Shown is not changeable.
        locked("用户名", "密码")
        absent("保存")
        back()
        back()

        // The directory likewise.
        tap("LDAP 通讯录")
        tap(DIRECTORY)
        see("通讯录详情")
        passwordIsKeptBack(env.ldapPassword)
        tap("显示密码")
        type(CONFIG_PASSWORD)
        tap("查看")
        passwordIsShown(env.ldapPassword)
        absent("保存")
        back()
        back()

        // Without a password of its own they are there to be looked at like the rest: the eye just shows them.
        onMain { engine.lock.setManaged(true) }
        tap("SIP 账号")
        tap(LINE)
        see("账号详情")
        assertFalse("The password is on the screen", passwordOnScreen(env.sipPassword))
        tap("显示密码")
        passwordIsShown(env.sipPassword)
        locked("用户名", "密码")
        absent("保存")
        tap("隐藏密码")
        await("the password to be hidden again") { !passwordOnScreen(env.sipPassword) }
    }

    @Test
    fun keysOfTheUsersOwnAreDraggedIntoOrderAboveThoseHandedOut() {
        await("the shortcut keys to be read") { engine.shortcuts.loaded.value }
        onMain {
            assertTrue(engine.updateSettings { it.copy(lockScreenPromptDeclined = true) })
            engine.shortcuts.remove(engine.shortcuts.shortcuts.value.map { it.id })
            assertTrue(engine.shortcuts.add(Shortcut(0, SPEED_KEY, "$AGENT")).isEmpty())
            engine.lock.setManaged(true)
            // Made next to the managed configuration, after the key that was handed out, and still shown ahead of it.
            assertTrue(engine.shortcuts.add(Shortcut(0, OWN_KEY, "$AGENT")).isEmpty())
            assertTrue(engine.shortcuts.add(Shortcut(0, SECOND_OWN_KEY, "$CUSTOMER")).isEmpty())
        }
        fun order() = engine.shortcuts.shortcuts.value.map { it.label }
        assertEquals(listOf(OWN_KEY, SECOND_OWN_KEY, SPEED_KEY), order())
        showApp()
        tap("快捷键")
        tap("编辑")

        // A key of the user's own takes the place of the other one it is dropped on.
        drag(see(SECOND_OWN_KEY), see(OWN_KEY))
        await("the keys to be in their new order") { order() == listOf(SECOND_OWN_KEY, OWN_KEY, SPEED_KEY) }
        picture("shortcuts-dragged")
        // The key that was let go is not opened for editing.
        absent("编辑快捷键")

        // A key that was handed out stays where the platform put it, and nothing moves past it.
        drag(see(SPEED_KEY), see(SECOND_OWN_KEY))
        drag(see(OWN_KEY), see(SPEED_KEY))
        settle()
        assertEquals(listOf(SECOND_OWN_KEY, OWN_KEY, SPEED_KEY), order())
        absent("编辑快捷键")
        tap("完成")
    }

    @Test
    fun anEmptyManagedConfigurationOffersOnlyKeysOfTheUsersOwnToAdd() {
        await("the shortcut keys to be read") { engine.shortcuts.loaded.value }
        onMain {
            assertTrue(engine.updateSettings { it.copy(lockScreenPromptDeclined = true) })
            engine.shortcuts.remove(engine.shortcuts.shortcuts.value.map { it.id })
            engine.lock.setManaged(true)
        }
        showApp()

        // The dial pad says that there is no account instead of offering to add one.
        see("暂无 SIP 账号")
        absent("添加 SIP 账号")
        picture("empty-dialer")
        tap("暂无 SIP 账号")
        await("the dial pad to say why") { shown(ManagedConfig.NOTICE).isNotEmpty() }
        tap("快捷键")
        see("暂无快捷键")
        see(ownKeysCounter(0))
        // Keys of the user's own are made one by one; nothing is imported.
        see("新建快捷键")
        absent("批量导入")
        picture("empty-shortcuts")
        back()

        tap("设置")
        tap("SIP 账号")
        see("暂无 SIP 账号")
        see(ManagedConfig.LOCKED)
        absent("添加账号", "批量导入账号")
        picture("empty-accounts")
        back()
        tap("LDAP 通讯录")
        see("尚未配置 LDAP 通讯录")
        see(ManagedConfig.LOCKED)
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
        listOf("暂无 SIP 账号", "添加账号", "批量导入账号").forEach(::see)
        back()
        tap("拨号")
        see("添加 SIP 账号")
        tap("快捷键")
        see(allKeysCounter())
        listOf("新建快捷键", "批量导入").forEach(::see)
    }

    private companion object {
        const val PICTURES = "itest-managed"
        const val LINE = "总机"
        const val DIRECTORY = "总部"
        const val SPEED_KEY = "前台"
        const val FORWARD_KEY = "转总机"
        const val OWN_KEY = "我的自建键"
        const val SECOND_OWN_KEY = "第二个自建键"
        const val VERSION_ROW = "版本 ${BuildConfig.VERSION_NAME}"

        /** How many dots a password field shows that keeps its password back, whatever the password. */
        const val MASK_LENGTH = 8

        /** The password a managed configuration of the tests has: made up here, and only its hash is handed out. */
        const val CONFIG_PASSWORD = "itest-config-password"
    }
}
