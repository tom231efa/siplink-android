package com.sky.siplink.core.logic

import com.sky.siplink.core.model.Settings
import java.security.MessageDigest

/**
 * Rules of a managed configuration: one that a remote side hands out, and that can be looked at
 * on the phone but not changed there. A few settings stay the user's own all the same.
 */
object ManagedConfig {
    /** What a change tried on the phone is answered with, and what the lock of a locked screen says when tapped. */
    const val NOTICE = "配置由远程下发，不能在本机修改"

    /** What the lock in the top bar of a locked screen is called. */
    const val LOCKED = "配置由远程下发"

    /**
     * What a settings change made on the phone comes to while the configuration is managed. The
     * user's own settings are taken from [wanted]: the do not disturb switch (not who keeps it),
     * the appearance, the answer to the lock screen question, and how many recordings are kept.
     * Everything else stays as it is in [current].
     *
     * The change was taken in full when the result equals [wanted].
     */
    fun localUpdate(current: Settings, wanted: Settings): Settings = current.copy(
        doNotDisturb = wanted.doNotDisturb,
        themeMode = wanted.themeMode,
        dynamicColor = wanted.dynamicColor,
        callBannerPosition = wanted.callBannerPosition,
        lockScreenPromptDeclined = wanted.lockScreenPromptDeclined,
        recordingsKept = wanted.recordingsKept,
    )

    /**
     * Whether [typed] is the password of a managed configuration: the one its stored passwords
     * are shown to. [hash] is what the configuration brought of it, the SHA-256 of the password
     * (as UTF-8) in hex, in either case of the letters. Anything else is the hash of no password,
     * and an empty one stands for "no password was set", which nothing typed can be.
     */
    fun isPassword(hash: String, typed: String): Boolean {
        val expected = hash.trim().lowercase()
        if (expected.isEmpty()) return false
        val actual = MessageDigest.getInstance("SHA-256").digest(typed.toByteArray())
            .joinToString("") { byte -> "%02x".format(byte) }
        return MessageDigest.isEqual(expected.toByteArray(), actual.toByteArray())
    }
}
