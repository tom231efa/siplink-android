package com.sky.siplink.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.linphone.core.Config

/**
 * Whether the configuration is managed: handed out by a remote side, and then not to be changed
 * on the phone. The settings store and every manager ask [refuses] before they change anything,
 * so a control that should not have been offered still changes nothing.
 *
 * The state is kept in the app's section of the SIP core configuration file. Nothing in the app
 * switches it on yet: [setManaged] and [fromRemote] are for the loader of remote configurations.
 * Main thread only, like the SIP core.
 */
class ConfigLock(private val config: Config) {
    private val state = MutableStateFlow(config.getBool(SettingsStore.SECTION, KEY_MANAGED, false))

    /** True while the configuration is managed. */
    val managed: StateFlow<Boolean> = state.asStateFlow()

    /** How many [fromRemote] calls are running, one inside the other. */
    private var remoteChanges = 0

    /** True when a change has to be refused: the configuration is managed, and it is not the remote side changing it. */
    val refuses: Boolean get() = state.value && remoteChanges == 0

    /**
     * Makes the configuration managed, or the phone's own again. Neither changes what is
     * configured: switched off, what was handed out stays and can be changed on the phone.
     */
    internal fun setManaged(on: Boolean) {
        if (state.value == on) return
        config.setBool(SettingsStore.SECTION, KEY_MANAGED, on)
        config.sync()
        state.value = on
    }

    /** Runs [changes] as the remote side: nothing they change is refused, managed or not. */
    internal fun <T> fromRemote(changes: () -> T): T {
        remoteChanges++
        try {
            return changes()
        } finally {
            remoteChanges--
        }
    }

    private companion object {
        const val KEY_MANAGED = "managed"
    }
}
