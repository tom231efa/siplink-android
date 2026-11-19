<p align="right"><sub><a href="../privacy.md">中文</a> | English</sub></p>

# Privacy and permissions

## Permissions

| Permission | Purpose | When requested |
| --- | --- | --- |
| `INTERNET` | SIP signaling and media, LDAP, remote configuration, update checks | Granted at installation |
| `ACCESS_NETWORK_STATE` | When the network comes back, lets the SIP stack check the network again and retries a failed remote configuration sync | Granted at installation |
| `RECORD_AUDIO` | Calls; call recording uses the same permission and does not ask separately | At start |
| `MODIFY_AUDIO_SETTINGS` | Call audio and switching between earpiece, speaker, Bluetooth and headset | Granted at installation |
| `BLUETOOTH_CONNECT` | Bluetooth headsets (Android 12 and later) | At start |
| `POST_NOTIFICATIONS` | Notifications for running in the background, incoming calls, ongoing calls and missed calls (Android 13 and later) | At start |
| `USE_FULL_SCREEN_INTENT` | Showing the call screen on the lock screen; from Android 14 a special permission the user can turn off | Reminder at app start when not granted |
| `WAKE_LOCK` | Turning the screen off when held to the ear during a call (proximity sensor) | Granted at installation |
| `VIBRATE` | Vibration for incoming calls | Granted at installation |
| `RECEIVE_BOOT_COMPLETED` | Starting the background service after boot and after app updates, to register again | Granted at installation |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` | Staying registered in the background, to receive calls without push | Granted at installation |
| `FOREGROUND_SERVICE_PHONE_CALL`, `FOREGROUND_SERVICE_MICROPHONE` | Foreground service during calls | Granted at installation |
| `MANAGE_OWN_CALLS` | Required for a foreground service of type `phoneCall` | Granted at installation |
| `CAMERA` | Video calls; scanning remote configuration QR codes | At the first video call or first scan |
| `REQUEST_INSTALL_PACKAGES` | Handing an update downloaded in the app to the system installer; Android requires the user to allow "Install unknown apps" in system settings | The first tap on "Install" opens system settings |

`FOREGROUND_SERVICE_CAMERA` and `FOREGROUND_SERVICE_DATA_SYNC`, merged in by the SIP SDK, are removed in the manifest: the camera is used only while the call screen is in the foreground.

## Network connections

The app connects only to the following:

| Destination | When | What |
| --- | --- | --- |
| SIP servers configured by the user (and proxies, backup proxies) | During registration and calls | SIP signaling and RTP media |
| DNS servers set for individual accounts | When resolving that account's server addresses | DNS queries |
| LDAP servers configured by the user | When searching directories and looking up names for incoming and outgoing calls | LDAP queries |
| Management platform | Only after remote configuration is loaded; for the sync times, see [Remote configuration](remote-configuration.md#sync) | Bind, sync and unbind requests, with the app version, Android version, device model, account registration states and codec list; no numbers, contacts, call history or location |
| `api.github.com` | With "Check for updates automatically" on, at each start or return to the foreground (skipped if the last check was less than 1 minute ago; after GitHub returns a rate limit, no requests until the time it gives); or when the version row is tapped in **Settings → About** for a manual check | Asks for the latest release of `tom231efa/siplink-android`, with the header `User-Agent: SipLink/{version}` and the `If-None-Match` of the previous response |
| `github.com` and its file hosts `*.githubusercontent.com` | When the user taps "Download and install" in the release notes | Downloads the APK matching the phone's ABI over HTTPS; only files from this repository's releases are accepted, and redirects are followed only to the hosts above |
| GitHub release page, opened in the browser | When the release has no APK for the phone's ABI and the user taps "Open download page" | Opened by the browser |

The app contains no analytics, crash reporting, advertising or push service components (dependencies in `gradle/libs.versions.toml`). QR codes are decoded locally by the ZXing library, without Google Play services.

With **Settings → About → Check for updates automatically** off, the app contacts `api.github.com` only for manual checks.

A downloaded APK is checked for size before use, and for its SHA-256 digest when the release provides one, and must be this app's package; otherwise it is discarded. Installation is done by the system installer and needs the user's confirmation; the app never installs by itself. Downloaded files are kept in the app's cache directory `updates/` and deleted at the next start.

## Local storage

All data is kept in the app's private directory. The manifest sets `android:allowBackup="false"`, and `data_extraction_rules.xml` excludes all data from cloud backup and device transfer.

| Data | Location |
| --- | --- |
| Settings, SIP accounts (with passwords as entered), LDAP directory configuration (with passwords), remote configuration lock state and configuration password hash | The SIP stack's configuration file `linphonerc` |
| Call history | The SIP stack's call history store |
| Imported CA certificates | `tls/user-ca.pem`, `tls/trust-bundle.pem`, and the SHA-256 of the latter in `tls/trust-bundle.sha256` |
| Shortcut keys | `shortcuts.csv`; the user's own shortcut keys under remote configuration in `shortcuts-own.csv` |
| LDAP local cache | SQLite database `directory-cache.db`, limited to 100 MB |
| Remote configuration state (device key, sync address, platform CA, sync records) | Separate SharedPreferences `remote_config` |
| Call recordings | `recordings/`: recording files (`.m4a`) and the list `index.json`; `.wav` temporary files during calls |
| Update check results, skipped version, ETag, rate limit wait time | Separate SharedPreferences `update_check` |
| Downloaded update APKs | Cache directory `updates/`, deleted at the next start |

Account passwords are stored as entered (not as digests), so that they can be shown again and moved with the account to another server. Requests and responses of remote configuration are not logged.

When the remote configuration is removed or revoked by the platform, the provisioned accounts, passwords, directories, certificates and shortcut keys are deleted from the phone, see [Remote configuration](remote-configuration.md#revocation-and-removal).

Call recordings are kept only on the phone and never uploaded; recording sends no tone or signaling mark to the other party. For export, the user chooses where to save.
