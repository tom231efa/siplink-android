<p align="right"><sub><a href="../build.md">中文</a> | English</sub></p>

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

In the repository root, `android/` is the Android project (the app is in `android/app/`), `testenv/` the integration test environment, `platform/` the management platform (Go; for building it, see [platform/README.md](../../platform/README.md)), and `docs/` the documentation. The app part:

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
| `CallManager`, `VideoController` | Calls, transfer, park, merge, incoming call policy; video |
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

For tests, see [Testing](testing.md).
