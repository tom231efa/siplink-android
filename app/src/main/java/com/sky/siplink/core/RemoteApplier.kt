package com.sky.siplink.core

import com.sky.siplink.core.logic.RemoteDocument
import com.sky.siplink.core.logic.RemotePlan
import java.util.UUID

/**
 * Makes a checked remote configuration the phone's, and takes it back again. Everything goes
 * through the managers as the remote side ([ConfigLock.fromRemote]); the configuration is
 * managed from the first change on. Main thread only, like the managers.
 */
internal class RemoteApplier(private val engine: SipEngine) {
    /**
     * @param accountIds local ids of the accounts that exist now, by their keys
     * @param directoryIds the same for the directories
     * @param errors what could not be applied; the rest was
     * @param trustChanged the trusted CAs changed
     */
    class Outcome(
        val accountIds: Map<String, String>,
        val directoryIds: Map<String, String>,
        val errors: List<String>,
        val trustChanged: Boolean,
    )

    /**
     * Applies [document], which [RemoteDocument.parse] accepted. It replaces the whole
     * configuration: an account, directory, CA or handed-out key that it does not name goes.
     *
     * The accounts that go are removed first, and the rest waits until they have unregistered
     * ([AccountManager.whenUnregistered]): other CAs or TLS settings drop the SIP connections,
     * and an account over TLS needs the CA it was trusted with to say goodbye.
     *
     * @param knownAccounts local account ids by key, as the last sync left them
     * @param knownDirectories the same for the directories
     * @param onDone called on the main thread once everything is applied
     */
    fun apply(
        document: RemoteDocument,
        knownAccounts: Map<String, String>,
        knownDirectories: Map<String, String>,
        onDone: (Outcome) -> Unit,
    ) {
        val accounts = engine.accounts
        val planned = engine.lock.fromRemote {
            engine.lock.setManaged(true, document.configPasswordHash)
            val existing = accounts.accounts.value.mapTo(HashSet()) { it.id }
            val planned = RemotePlan.ids(document.accounts.map { it.key }, knownAccounts, existing, ::newId)
            val keep = planned.values.toSet()
            existing.filter { it !in keep }.forEach(accounts::remove)
            planned
        }
        accounts.whenUnregistered { onDone(engine.lock.fromRemote { applyRest(document, planned, knownDirectories) }) }
    }

    /** Everything of [document] but the removal of the accounts it no longer has. */
    private fun applyRest(document: RemoteDocument, planned: Map<String, String>, knownDirectories: Map<String, String>): Outcome {
        val errors = ArrayList<String>()
        engine.updateSettings { RemotePlan.settings(document.settings, it) }

        val accounts = engine.accounts
        val accountIds = LinkedHashMap<String, String>()
        for (account in document.accounts) {
            val id = planned.getValue(account.key)
            val refused = accounts.put(id, account.draft)
            if (refused.isEmpty()) {
                accountIds[account.key] = id
            } else {
                errors += refused.map { "账号“${account.draft.name.ifEmpty { account.draft.username }}”：${it.full}" }
            }
        }
        accountIds[document.defaultAccount]?.let(accounts::setDefault)

        val trustChanged = engine.tls.replaceAll(document.caCertificates)

        val codecs = document.codecs
        if (codecs == null) {
            engine.codecs.restoreDefaults()
        } else if (!engine.codecs.apply(codecs.audio, codecs.video)) {
            errors += "无可用的音频编码，已恢复默认编码"
        }

        val directories = engine.directory
        val existingDirectories = directories.directories.value.mapTo(HashSet()) { it.id }
        val directoryIds = RemotePlan.ids(document.directories.map { it.key }, knownDirectories, existingDirectories, ::newId)
        directories.replaceAll(
            RemotePlan.directories(document.directories, directoryIds, accountIds),
            RemotePlan.directoryPasswords(document.directories, directoryIds),
        )

        if (!engine.shortcuts.replaceHandedOut(document.shortcuts)) errors += "快捷键尚未加载，未能更新"
        return Outcome(accountIds, directoryIds, errors, trustChanged)
    }

    /**
     * Takes back everything that was handed out (accounts with their passwords, directories, CAs,
     * codecs, settings, shortcut keys) and opens the configuration to the phone again, which
     * takes the user's own keys with it ([ConfigLock.onReleased]). The call history and the
     * user's own settings stay.
     *
     * The accounts go first; the CAs and settings only once they have unregistered at their
     * servers, as in [apply].
     *
     * @param onDone called on the main thread once it is all done
     */
    fun wipe(onDone: () -> Unit) {
        val accounts = engine.accounts
        engine.lock.fromRemote { accounts.accounts.value.map { it.id }.forEach(accounts::remove) }
        accounts.whenUnregistered {
            engine.lock.fromRemote {
                engine.directory.replaceAll(emptyList(), emptyMap())
                engine.tls.replaceAll(emptyList())
                engine.codecs.restoreDefaults()
                engine.shortcuts.replaceHandedOut(emptyList())
                engine.updateSettings(RemotePlan::wipedSettings)
            }
            engine.lock.setManaged(false)
            onDone()
        }
    }

    private fun newId(): String = UUID.randomUUID().toString()
}
