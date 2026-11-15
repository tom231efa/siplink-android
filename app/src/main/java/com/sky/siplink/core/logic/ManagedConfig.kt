package com.sky.siplink.core.logic

import com.sky.siplink.core.model.Settings

/**
 * Rules of a managed configuration: one that a remote side hands out, and that can be looked at
 * on the phone but not changed there. A few settings stay the user's own all the same.
 */
object ManagedConfig {
    /** What a change tried on the phone is answered with, and what the locked screens say at their top. */
    const val NOTICE = "配置由远程下发，不能在本机修改"

    /**
     * What a settings change made on the phone comes to while the configuration is managed. The
     * user's own settings are taken from [wanted]: the do not disturb switch (not who keeps it),
     * the appearance, and the answer to the lock screen question. Everything else stays as it
     * is in [current].
     *
     * The change was taken in full when the result equals [wanted].
     */
    fun localUpdate(current: Settings, wanted: Settings): Settings = current.copy(
        doNotDisturb = wanted.doNotDisturb,
        themeMode = wanted.themeMode,
        dynamicColor = wanted.dynamicColor,
        callBannerPosition = wanted.callBannerPosition,
        lockScreenPromptDeclined = wanted.lockScreenPromptDeclined,
    )
}
