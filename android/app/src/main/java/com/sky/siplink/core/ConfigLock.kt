package com.sky.siplink.core

import com.sky.siplink.core.logic.ManagedConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.linphone.core.Config

/**
 * Whether the configuration is managed: handed out by a remote side, and then not to be changed
 * on the phone. The settings store and every manager ask [refuses] before they change anything,
 * so a control that should not have been offered still changes nothing.
 *
 * A managed configuration may bring the hash of a password of its own. With one, the passwords
 * stored in it (of the accounts, of the directories) are shown only to who types that password
 * ([showsPasswords]); without one they can be looked at like everything else.
 *
 * The state is kept in the app's section of the SIP core configuration file. [setManaged] and
 * [fromRemote] are for the loader of remote configurations ([RemoteApplier]). Core thread only,
 * like the SIP core.
 */
class ConfigLock(private val config: Config) {
    private val state = MutableStateFlow(config.getBool(SettingsStore.SECTION, KEY_MANAGED, false))

    /** True while the configuration is managed. */
    val managed: StateFlow<Boolean> = state.asStateFlow()

    /** Hash of the password of the managed configuration ([ManagedConfig.isPassword]); empty: it has none. */
    private var passwordHash = if (state.value) config.getString(SettingsStore.SECTION, KEY_PASSWORD_HASH, "").orEmpty() else ""

    private val protectedState = MutableStateFlow(passwordHash.isNotEmpty())

    /** True while the stored passwords are kept from being shown: the configuration is managed and has a password. */
    val passwordsProtected: StateFlow<Boolean> = protectedState.asStateFlow()

    /** Called when a managed configuration is gone: what belonged to it and was not handed out goes as well. */
    var onReleased: (() -> Unit)? = null

    /** How many [fromRemote] calls are running, one inside the other. */
    private var remoteChanges = 0

    /** True when a change has to be refused: the configuration is managed, and it is not the remote side changing it. */
    val refuses: Boolean get() = state.value && remoteChanges == 0

    /**
     * Whether a stored password may be handed out: always, unless the configuration is managed
     * with a password of its own, which [typed] then has to be (null: nobody typed one). The
     * remote side is handed them in any case.
     */
    fun showsPasswords(typed: String? = null): Boolean = CoreThread.wait("ConfigLock.showsPasswords") {
        passwordHash.isEmpty() || remoteChanges > 0 || (typed != null && ManagedConfig.isPassword(passwordHash, typed))
    }

    /**
     * Makes the configuration managed, or the phone's own again. Neither changes what was
     * handed out: switched off, it stays and can be changed on the phone. What the user added
     * of their own next to it (the shortcut keys of [com.sky.siplink.core.model.Shortcut.own])
     * goes with it ([onReleased]).
     *
     * @param passwordHash hash of the password that the stored passwords are shown to
     *   ([ManagedConfig.isPassword]); empty: they are not kept from being shown. It goes with
     *   the managed state: switched off, there is none.
     */
    internal fun setManaged(on: Boolean, passwordHash: String = "") {
        CoreThread.check("The managed state")
        val hash = if (on) passwordHash.trim() else ""
        if (state.value == on && this.passwordHash == hash) return
        val released = state.value && !on
        config.setBool(SettingsStore.SECTION, KEY_MANAGED, on)
        config.setString(SettingsStore.SECTION, KEY_PASSWORD_HASH, hash)
        config.sync()
        this.passwordHash = hash
        state.value = on
        protectedState.value = hash.isNotEmpty()
        if (released) onReleased?.invoke()
    }

    /** Runs [changes] as the remote side: nothing they change is refused, managed or not. */
    internal fun <T> fromRemote(changes: () -> T): T {
        CoreThread.check("The managed state")
        remoteChanges++
        try {
            return changes()
        } finally {
            remoteChanges--
        }
    }

    private companion object {
        const val KEY_MANAGED = "managed"
        const val KEY_PASSWORD_HASH = "managed_password_hash"
    }
}
