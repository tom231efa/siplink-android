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

The app speaks eight languages. Its texts are string resources: English, the default, in `res/values/`; Simplified Chinese in `res/values-b+zh+Hans/`, Traditional Chinese in `res/values-b+zh+Hant/`, Japanese in `res/values-ja/`, Arabic in `res/values-ar/`, French in `res/values-fr/`, Russian in `res/values-ru/` and Spanish in `res/values-es/`. The languages the app offers are listed in `core/model/Language.kt` and in `res/xml/locales_config.xml` (the manifest's `android:localeConfig`); `androidResources.localeFilters` in `android/app/build.gradle.kts` keeps the texts of libraries to the same languages. The texts are split by area: `strings.xml` holds only what every area shares (`app_name`, `list_separator` and the common buttons `common_*`), and each area has its own `strings_{area}.xml` (`strings_call.xml`, `strings_room.xml`, `strings_settings.xml`, `strings_dialer.xml`, …) in which every name is lower snake case and starts with `{area}_` (`call_ended` in `strings_call.xml`, `remote_invalid` in `strings_remote.xml`). The same words in two roles (a button and a state, a title and a value) are two texts, since other languages may need different words.

### Choosing the language

Settings › Language offers "System default", what a new installation has, and the languages by their own names. From Android 13 on the choice is the system's per-app language (`LocaleManager`), the same value as the app's language page in the system settings; before Android 13 the app keeps it in its own preferences and puts it into the configuration of its activities. Texts rendered outside the activities take the languages of that setting, or the system's (`core/AppLanguage.kt`). Which texts a language list gets is Android's own resource resolution: a language the app does not have falls through to the next one of the list, and to English. Chinese is matched by script: `zh`, `zh-CN` and `zh-SG` take the Simplified texts, `zh-TW`, `zh-HK` and `zh-MO` the Traditional ones.

### How texts travel

- `core/logic` holds no text. It returns enums, `@StringRes` ids or a `Phrase` (`core/model/Phrase.kt`): a resource with its arguments, a plural with its count, or plain text that is shown as it is.
- The managers keep typed values and render the snapshots they publish (`StateFlow`) with `AppTexts`. When the language changes, `SipLinkApp` calls `SipEngine.onLanguageChanged()`, the managers publish their snapshots again, `CallNotifier` renames its channels and the service renews its notification.
- Screens render a `Phrase` with `Phrase.text()` (`ui/components/Phrases.kt`), their own texts without arguments with `stringResource`, and those with arguments with `stringOf(id, …)` and `pluralOf(id, count, …)` (the same file), which render through `PhraseFormat` as the core's phrases do, never with `stringResource(id, args)` or `pluralStringResource`. A text needed outside composition (a notice built in a callback) is rendered from the resources of the activity's context, not of the application: `resources.render(Phrase.of(…))`.
- Nothing rendered is stored. What the remote configuration keeps of its errors is stored as phrases by the names of their resources (`core/logic/PhraseCodec.kt`); text an older version stored is shown as it is.
- Arguments are numbered (`%1$s`, `%2$s`); a text is never glued from parts. Numbers are written with Western digits (`Locale.ROOT`); in right-to-left text every value is isolated (U+2068 … U+2069) so that numbers and addresses keep their order. Counts use `<plurals>`. Dates follow the app's language: the locale is taken from the composition's configuration (`LocalConfiguration.current.locales[0]`), and dates with words come from `DateFormat.getBestDateTimePattern` (the day headers of the history: skeletons `MMMd` and `yMMMd`); purely numeric stamps (`yyyy-MM-dd HH:mm`, `HH:mm:ss`) are the same in every language.

### Right to left

Arabic is written right to left (`android:supportsRtl="true"`): the screens mirror, rows, lists and the navigation run from the right, paddings are start and end, and icons that point (back arrows, chevrons) are the auto-mirrored ones. What keeps its physical place or its left-to-right order in every language:

- the keys of the dial pad and of the keypad of a call (1 2 3 from left to right), and the icon of the dialer's delete key, which deletes from the end of a number: `LeftToRight` (`ui/components/Direction.kt`);
- the slide of an incoming call (right answers, left declines, as its text says) and the video pictures with their "move left / right" actions, placed from the left edge (`LeftToRight`); in landscape the small picture keeps clear of the side the controls stand on;
- the fields that take a number, an address, a host name, a port or a password: `FormField(machineValue = true)`, the default for every keyboard type but plain text, or `machineValueStyle()` on a text field of its own; they are written left to right and stand on the side the language starts from;
- values inside sentences: `PhraseFormat` isolates them (U+2068 … U+2069), for the core's phrases and the screens' `stringOf` / `pluralOf` alike;
- a value alone (an address, a number, the call timer): the app's typography (`ui/theme/Theme.kt`) gives every text the direction of its first letter and writes one without any letter left to right (`TextDirection.ContentOrLtr`); left to the screen's direction, "2501@host" read "host@2501" in Arabic. A pattern without a letter of its own ("%1$s · %2$s") is then started with a right-to-left mark by `PhraseFormat`, so that in Arabic its values follow one another from the right.

Where a text starts or ends is decided by the direction of the screen, never by what the text is made of; the order of the characters inside a text stays its own. The theme's typography aligns every text to the start of the screen (`Typography.alignedTo`, left, or right in a right-to-left language), the texts inside a `LeftToRight` block (digits and the letters under them) are centred and as wide as themselves, so they need no alignment of their own, and a column of values at the end of its rows takes `endAlign()`; `TextAlign.Start` and `TextAlign.End`, which follow a text's own direction, are not used on the screens (`FilledTextTest`). `TextAlign.Center` stays where it is set. A `FadingText` whose one line runs on keeps its beginning in sight. Horizontal motion follows the screen too: a new screen comes in from the end, the keypad of a call on its side comes from and goes back to the start, where it stands (`Motion.forward`).

Labels have room for the longer languages: the titles of the rows of a list take two lines, a label under a key of the call screen is as wide as its key's share of the row (`KeyRow`) and takes a second line. Where a text of the app still does not fit, a shorter wording in that language that keeps the glossary's terms and the meaning is chosen, not smaller type or more lines, and the glossary notes it.

### Data, not texts

These stay as they are in every language: the CSV formats of accounts and shortcut keys (including the Chinese words they accept for types and icons), settings exports, remote configuration documents, QR contents, SIP headers and reason phrases from servers, protocol and codec names, `Log.*` lines (English), and the name SipLink (`app_name`, not translatable).

### Adding a text

Add the string to the file of its area in `res/values/` and in every `res/values-*/` folder, with the same placeholders. The words the translations use for the app's terms (account, line, shortcut key, conference room, …) are in the glossaries `docs/glossary/{language}.md` (`ja`, `ar`, `fr`, `ru`, `es`, `zh-Hant`); a new text uses them, and a new term goes into them. In `core`, return `Phrase.of(R.string.name, …)` (or `Phrase.count(R.plurals.name, n, …)`); on a screen, use `stringResource` for a text without arguments, `stringOf` / `pluralOf` for one with.

The names of the texts a store may hold are part of the stored format once released: the errors of the remote configuration and every text they can contain are stored by name (`PhraseCodec`), so a later rename drops what was stored under the old name.

### Adding a language

Create `res/values-{qualifier}/` with every text of `res/values/` (`{qualifier}`: the language, such as `fr`, or `b+zh+Hant` for a script), and make sure the language is in `Language`, in `locales_config.xml` and in `localeFilters`. `PluralRules` in `android/app/src/test/java/com/sky/siplink/Texts.kt` must know its plural forms.

### Tests

- `LanguageCatalogTest` (unit test) compares every language with the default: the same keys, the same placeholders, the plural forms the language needs, no empty text; checks that every text is in the file of its area; and compares the languages with `Language` and `locales_config.xml`.
- `FilledTextTest` fails when a text with arguments is filled by Android's own formatting (`stringResource(id, args)`, `pluralStringResource`, `getString(id, args)`, `getQuantityString`) instead of `PhraseFormat`, and checks the isolates and the right-to-left mark in Arabic.
- `SourceTextTest` fails when a string literal of the Kotlin sources holds Chinese, Japanese or Korean characters outside its allow-list of data (`Language.kt`, the CSV words of `ShortcutRules.kt`).
- Unit tests assert on the real texts: `Texts` reads the resource files and renders a `Phrase` in a language (`zh(…)` for Simplified Chinese).
- The integration tests set the app's own language to Simplified Chinese before each test (`PbxTestBase.pinLanguage`), through the app's setting, and leave it so after a run; the phone's system language is not changed. `AppLanguageTest` covers choosing, following the system, falling back to English, the script of Chinese, a change made outside the app while it shows no screen, a call during a change, texts the core puts together, and the day headers of the history. `AppLanguageTest` also chooses each of the eight languages in the settings and checks a text of a screen and one of the core. `EnglishScreensTest` walks the four tabs, the settings pages, the call screen and the conference room in English and fails on any Chinese character a screen shows, but the languages' own names and data. `LanguageScreensTest` walks every language through the tabs (with two calls in the history and the test directory), the settings, the account list, an account form, the line diagnosis, the advanced settings, the language, the remote configuration and the about page, and in a call the call screen with its keypad (upright and on its side), the transfer panel, the conference room with its sheet and an incoming call. It fails on a crash and on any text of the app that a screen cuts off, leaves with one character on its last line or breaks inside a word (`CutTexts`: read from the Compose text layouts, the app's texts told from data by the language's resources; it covers the phone's width only), and saves a picture of each stop under `shots/{language}/` in the app's cache directory, taken once the screen is at rest and drawn. In Arabic it checks that the tabs run right to left, that both keypads keep 1 left of 3, that the keypad on its side stands at the right and that the slide answers to the right.

For tests, see [Testing](testing.md).
