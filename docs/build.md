<p align="right"><sub><a href="zh-CN/build.md">中文</a> | English</sub></p>

# Building

## Environment

| Item | Requirement |
| --- | --- |
| JDK | 21; source and bytecode compatibility level Java 17 (`android/app/build.gradle.kts`) |
| Android SDK | Platform 37 (`compileSdk = 37`); `targetSdk = 36`, `minSdk = 28` |
| Gradle | The wrapper in the repository (`android/gradle/wrapper/gradle-wrapper.properties`) |
| Network | The first build needs Google Maven, Maven Central and the official Linphone Maven repository `https://download.linphone.org/maven_repository` |

The Android SDK path comes from `ANDROID_HOME` or from `android/local.properties` (`sdk.dir`). The commands below run in the repository root; `-p android` points at the Android project. Dependency versions are kept in `android/gradle/libs.versions.toml`.

## Debug build

```bash
android/gradlew -p android :app:assembleDebug
```

Output: `android/app/build/outputs/apk/debug/app-debug.apk`. The debug build writes more detailed SIP logs and allows loading remote configuration from `http://` addresses.

## Release build and signing

In the Android project directory `android/` (the directory with `settings.gradle.kts`), create `keystore.properties` with these four keys:

| Key | Meaning |
| --- | --- |
| `storeFile` | Path of the keystore file, relative to `android/` |
| `storePassword` | Keystore password |
| `keyAlias` | Key alias |
| `keyPassword` | Key password |

`keystore.properties`, `*.jks` and `*.keystore` are ignored by `.gitignore` and must not be committed. Then:

```bash
android/gradlew -p android :app:assembleRelease
```

The output is in `android/app/build/outputs/apk/release/`. Without `keystore.properties`, the release build is unsigned. Release builds use code and resource shrinking (R8); the rules are in `android/app/proguard-rules.pro`.

All versions of the app must be signed with the same key; otherwise they cannot be installed over each other.

## ABI

The SIP stack's native libraries are packaged per ABI and are the largest part of the APK. By default only `arm64-v8a` is packaged; the `siplink.abi` property selects other ABIs (comma-separated):

```bash
# Also support 32-bit ARM devices
android/gradlew -p android :app:assembleRelease -Psiplink.abi=arm64-v8a,armeabi-v7a

# x86_64 emulator used by the integration tests
android/gradlew -p android :app:assembleDebug -Psiplink.abi=x86_64
```

When checking for updates, the app goes through the device's supported ABIs in order and picks the `.apk` release asset whose file name contains that ABI name. When releasing APKs for several ABIs, the file names should contain the ABI name, for example `{name}-arm64-v8a.apk`, where `{name}` is a prefix of your choice.

## Version numbers

In `defaultConfig` of `android/app/build.gradle.kts`:

- `versionCode`: integer, increased with every release
- `versionName`: dotted numbers, such as `0.1.2`

GitHub release tags are written `v{versionName}`, such as `v0.1.2`. The update check compares only version numbers made of digits (tags like `0.2.0-beta` are not taken as new versions); missing parts count as 0.

## Project structure

In the repository root, `android/` is the Android project (the app is in `android/app/`, the baseline profile and startup measurement in `android/baselineprofile/`, see [Testing](testing.md#baseline-profile-and-startup-measurement)), `testenv/` the integration test environment, `platform/` the management platform (Go; for building it, see [platform/README.md](../platform/README.md)), and `docs/` the documentation. The app part:

```text
android/app/src/main/java/com/sky/siplink/
├── SipLinkApp.kt        Application: creates SipEngine, starts the background service and remote configuration
├── MainActivity.kt      Main screen
├── CallActivity.kt      Call screen (can show on the lock screen)
├── core/                SIP engine and managers
│   ├── logic/           Plain Kotlin rules, independent of Android and the SDK, covered by unit tests
│   └── model/           Data models
├── service/             Foreground service, notifications, start at boot
└── ui/                  Jetpack Compose screens
```

### core

`SipEngine` holds the Linphone `Core`; it is created on the main thread, and all SDK calls are made on the main thread. It creates and connects the managers:

| Class | Responsibility |
| --- | --- |
| `SettingsStore` | App settings, kept in a separate section of the SDK configuration file |
| `ConfigLock` | Lock state of remote configuration; the managers check it before changes |
| `AccountManager`, `AccountRouter` | Accounts; backup server switching and per-account DNS |
| `CallManager`, `CallFollower`, `VideoController` | Calls, transfer, park, merge, incoming call policy; following the core's call reports; video |
| `AppLanguage`, `AppTexts` | The app's language; texts rendered outside the screens |
| `HistoryManager` | Call history (reads the SDK's call history) |
| `DirectoryManager`, `DirectoryCache`, `NameResolver` | LDAP query queue, SQLite local cache, names for incoming and outgoing calls |
| `ShortcutManager` | Shortcut keys, stored as a CSV file |
| `CodecManager`, `TlsManager` | Codecs; CA certificates and TLS/SRTP settings |
| `RemoteConfig`, `RemoteConfigClient`, `RemoteApplier`, `RemoteConfigStore` | Remote configuration sync, HTTPS requests, applying and revoking, local state |
| `UpdateChecker`, `GitHubReleases` | Update checks |

Rule-like logic (input validation, CSV, incoming call policy, remote document parsing and validation, certificate pins, version comparison and so on) lives in `core/logic`; the managers only call the SDK and keep state.

### ui and service

`ui/AppRoot.kt` defines the navigation and the four tabs; the rest is grouped by page into packages: `accounts`, `call`, `dialer`, `directory`, `history`, `settings`, `shortcuts`, with shared components in `components` and the theme in `theme`. The UI reads manager state through `StateFlow` and changes it through manager methods.

`service/SipService` is the foreground service: when idle it keeps the process and registration alive with the `specialUse` type, and during calls it switches to the `phoneCall` / `microphone` types. `CallNotifier` handles notifications, and `BootReceiver` starts the service after boot and after app updates.

## Languages

The app's texts are string resources: English, the default, in `res/values/`, Simplified Chinese in `res/values-b+zh+Hans/`. The languages the app offers are listed in `core/model/Language.kt` and in `res/xml/locales_config.xml` (the manifest's `android:localeConfig`); `androidResources.localeFilters` in `android/app/build.gradle.kts` keeps the texts of libraries to the same languages. The texts are split by area into `strings.xml` (shared), `strings_form.xml`, `strings_call.xml`, `strings_room.xml`, `strings_account.xml`, `strings_directory.xml`, `strings_shortcut.xml`, `strings_remote.xml`, `strings_settings.xml` and `strings_notification.xml`; names are lower snake case and start with their area (`call_ended`, `remote_invalid`).

### Choosing the language

Settings › Language offers "System default", what a new installation has, and the languages by their own names. From Android 13 on the choice is the system's per-app language (`LocaleManager`), the same value as the app's language page in the system settings; before Android 13 the app keeps it in its own preferences and puts it into the configuration of its activities. Texts rendered outside the activities take the languages of that setting, or the system's (`core/AppLanguage.kt`). Which texts a language list gets is Android's own resource resolution: a language the app does not have falls through to the next one of the list, and to English. Chinese is matched by script: `zh`, `zh-CN` and `zh-SG` take the Simplified texts, `zh-TW`, `zh-HK` and `zh-MO` the Traditional ones (English while the app has none).

### How texts travel

- `core/logic` holds no text. It returns enums, `@StringRes` ids or a `Phrase` (`core/model/Phrase.kt`): a resource with its arguments, a plural with its count, or plain text that is shown as it is.
- The managers keep typed values and render the snapshots they publish (`StateFlow`) with `AppTexts`. When the language changes, `SipLinkApp` calls `SipEngine.onLanguageChanged()`, the managers publish their snapshots again, `CallNotifier` renames its channels and the service renews its notification.
- Screens render a `Phrase` with `Phrase.text()` (`ui/components/Phrases.kt`) and their own texts with `stringResource`.
- Nothing rendered is stored. What the remote configuration keeps of its errors is stored as phrases by the names of their resources (`core/logic/PhraseCodec.kt`); text an older version stored is shown as it is.
- Arguments are numbered (`%1$s`, `%2$s`); a text is never glued from parts. Numbers are written with Western digits (`Locale.ROOT`); in right-to-left text every value is isolated (U+2068 … U+2069) so that numbers and addresses keep their order. Counts use `<plurals>`. Dates follow the app's language.

### Data, not texts

These stay as they are in every language: the CSV formats of accounts and shortcut keys (including the Chinese words they accept for types and icons), settings exports, remote configuration documents, QR contents, SIP headers and reason phrases from servers, protocol and codec names, `Log.*` lines (English), and the name SipLink (`app_name`, not translatable).

### Adding a text

Add the string to the file of its area in `res/values/` and in every `res/values-*/` folder, with the same placeholders. In `core`, return `Phrase.of(R.string.name, …)` (or `Phrase.count(R.plurals.name, n, …)`); on a screen, use `stringResource`.

### Adding a language

Create `res/values-{qualifier}/` with every text of `res/values/` (`{qualifier}`: the language, such as `fr`, or `b+zh+Hant` for a script), and make sure the language is in `Language`, in `locales_config.xml` and in `localeFilters`. `PluralRules` in `android/app/src/test/java/com/sky/siplink/Texts.kt` must know its plural forms.

### Tests

- `LanguageCatalogTest` (unit test) compares every language with the default: the same keys, the same placeholders, the plural forms the language needs, no empty text; and the languages with `Language` and `locales_config.xml`.
- `SourceTextTest` fails when a string literal of the Kotlin sources holds Chinese, Japanese or Korean characters outside its allow-list: data (`Language.kt`, the CSV words of `ShortcutRules.kt`) and the screens not moved yet, a list that only shrinks.
- Unit tests assert on the real texts: `Texts` reads the resource files and renders a `Phrase` in a language (`zh(…)` for Simplified Chinese).
- The integration tests set the app's own language to Simplified Chinese before each test (`PbxTestBase.pinLanguage`), through the app's setting, and leave it so after a run; the phone's system language is not changed. `AppLanguageTest` covers choosing, following the system, falling back to English, the script of Chinese, a change made outside the app while it shows no screen, a call during a change, and texts the core puts together.

For tests, see [Testing](testing.md).
