<p align="right"><sub>中文 | <a href="../build.md">English</a></sub></p>

# 构建

## 环境

| 项 | 要求 |
| --- | --- |
| JDK | 21；源码与字节码兼容级别为 Java 17（`android/app/build.gradle.kts`） |
| Android SDK | 平台 37（`compileSdk = 37`）；`targetSdk = 36`，`minSdk = 28` |
| Gradle | 使用仓库中的 Wrapper（`android/gradle/wrapper/gradle-wrapper.properties`） |
| 网络 | 首次构建需访问 Google Maven、Maven Central 和 Linphone 官方 Maven 仓库 `https://download.linphone.org/maven_repository` |

Android SDK 路径由 `ANDROID_HOME` 或 `android/local.properties`（`sdk.dir`）指定。以下命令都在仓库根目录执行，`-p android` 指向安卓工程。依赖版本集中在 `android/gradle/libs.versions.toml`。

## 调试包

```bash
android/gradlew -p android :app:assembleDebug
```

输出 `android/app/build/outputs/apk/debug/app-debug.apk`。调试包输出更详细的 SIP 日志，并允许以 `http://` 地址载入远程配置。

## 发布包与签名

在安卓工程目录 `android/`（`settings.gradle.kts` 所在目录）创建 `keystore.properties`，写入以下四个键：

| 键 | 含义 |
| --- | --- |
| `storeFile` | 密钥库文件路径，相对于 `android/` |
| `storePassword` | 密钥库密码 |
| `keyAlias` | 密钥别名 |
| `keyPassword` | 密钥密码 |

`keystore.properties`、`*.jks`、`*.keystore` 已被 `.gitignore` 忽略，不得提交。然后：

```bash
android/gradlew -p android :app:assembleRelease
```

输出位于 `android/app/build/outputs/apk/release/`。没有 `keystore.properties` 时发布包不签名。发布包启用代码压缩与资源压缩（R8），规则见 `android/app/proguard-rules.pro`。

同一应用的各个版本必须使用同一把签名密钥，否则无法覆盖安装。

## ABI

SIP 协议栈的原生库按 ABI 打包，是安装包中最大的部分。默认只打包 `arm64-v8a`，用 `siplink.abi` 属性指定其它 ABI（逗号分隔）：

```bash
# 同时支持 32 位 ARM 设备
android/gradlew -p android :app:assembleRelease -Psiplink.abi=arm64-v8a,armeabi-v7a

# 集成测试使用的 x86_64 模拟器
android/gradlew -p android :app:assembleDebug -Psiplink.abi=x86_64
```

应用检查更新时，按设备支持的 ABI 顺序选择文件名中包含该 ABI 名称的 `.apk` 发布附件。发布多个 ABI 的安装包时，文件名应包含 ABI 名称，例如 `{名称}-arm64-v8a.apk`，其中 `{名称}` 为自定义前缀。

## 版本号

在 `android/app/build.gradle.kts` 的 `defaultConfig` 中：

- `versionCode`：整数，每次发布递增
- `versionName`：点分数字，如 `0.1.2`

GitHub 发布的标签写作 `v{versionName}`，如 `v0.1.2`。更新检查只比较由数字组成的版本号（`0.2.0-beta` 这类标签不会被视为新版本），缺少的位视为 0。

## 项目结构

仓库根目录下，`android/` 是安卓工程（应用在 `android/app/`，基线配置文件与启动测量在 `android/baselineprofile/`，见[测试](testing.md#基线配置文件与启动测量)），`testenv/` 是集成测试环境，`platform/` 是管理平台（Go，构建方法见 [platform/README.md](../../platform/README.md)），`docs/` 是文档。以下为应用部分。

```text
android/app/src/main/java/com/sky/siplink/
├── SipLinkApp.kt        Application：创建 SipEngine，启动后台服务与远程配置
├── MainActivity.kt      主界面
├── CallActivity.kt      通话界面（可显示在锁屏上）
├── core/                SIP 引擎与各管理器
│   ├── logic/           纯 Kotlin 规则，不依赖 Android 和 SDK，由单元测试覆盖
│   └── model/           数据模型
├── service/             前台服务、通知、开机启动
└── ui/                  Jetpack Compose 界面
```

### core

`SipEngine` 持有 Linphone `Core`，在主线程创建，所有 SDK 调用都在主线程进行。它创建并连接各管理器：

| 类 | 职责 |
| --- | --- |
| `SettingsStore` | 应用设置，保存在 SDK 配置文件的独立分区 |
| `ConfigLock` | 远程配置的锁定状态；各管理器修改前检查它 |
| `AccountManager`、`AccountRouter` | 账号；备用服务器切换与账号专用 DNS |
| `CallManager`、`CallFollower`、`VideoController` | 通话、转接、驻留、合并、来电策略；跟随 core 的通话报告；视频 |
| `AppLanguage`、`AppTexts` | 应用语言；在界面之外生成的文字 |
| `HistoryManager` | 通话记录（读取 SDK 的通话记录） |
| `DirectoryManager`、`DirectoryCache`、`NameResolver` | LDAP 查询队列、SQLite 本地缓存、来去电姓名 |
| `ShortcutManager` | 快捷键，保存为 CSV 文件 |
| `CodecManager`、`TlsManager` | 编码；CA 证书与 TLS/SRTP 设置 |
| `RemoteConfig`、`RemoteConfigClient`、`RemoteApplier`、`RemoteConfigStore` | 远程配置的同步、HTTPS 请求、应用与收回、本地状态 |
| `UpdateChecker`、`GitHubReleases` | 更新检查 |

规则性的逻辑（输入校验、CSV、来电策略、远程文档解析与校验、证书 pin、版本比较等）放在 `core/logic`，管理器只负责调用 SDK 和保存状态。

### ui 与 service

`ui/AppRoot.kt` 定义导航与四个标签页，其余按页面分包：`accounts`、`call`、`dialer`、`directory`、`history`、`settings`、`shortcuts`，公共组件在 `components`，主题在 `theme`。界面通过 `StateFlow` 读取管理器状态，通过管理器方法修改。

`service/SipService` 是前台服务：空闲时以 `specialUse` 类型保持进程与注册，通话时切换为 `phoneCall` / `microphone` 类型。`CallNotifier` 负责通知，`BootReceiver` 在开机和应用更新后启动服务。

## 语言

应用的文字都是字符串资源：默认的英文在 `res/values/`，简体中文在 `res/values-b+zh+Hans/`。应用提供的语言列在 `core/model/Language.kt` 和 `res/xml/locales_config.xml`（清单中的 `android:localeConfig`）；`android/app/build.gradle.kts` 中的 `androidResources.localeFilters` 让依赖库的文字也只保留这些语言。文字按领域分文件：`strings.xml` 只放各领域共用的文字（`app_name`、`list_separator` 和公共按钮 `common_*`），每个领域有自己的 `strings_{area}.xml`（`strings_call.xml`、`strings_room.xml`、`strings_settings.xml`、`strings_dialer.xml` 等），其中每个名称为小写下划线并以 `{area}_` 开头（`strings_call.xml` 中的 `call_ended`、`strings_remote.xml` 中的 `remote_invalid`）。同样的字用在两种角色（按钮与状态、标题与取值）时是两条文字，因为其他语言可能需要不同的词。

### 选择语言

设置 › 语言 中有“跟随系统”（新安装时即为此项）和以各自名称显示的语言。Android 13 起，所选语言就是系统的应用专属语言（`LocaleManager`），与系统设置中本应用的语言页是同一个值；Android 13 之前由应用保存在自己的偏好设置中，并放进各 Activity 的配置。界面之外生成的文字使用该设置中的语言，或系统语言（`core/AppLanguage.kt`）。一个语言列表得到哪套文字，由 Android 自己的资源匹配决定：应用没有的语言顺延到列表中的下一个语言，最后是英文。中文按书写体系匹配：`zh`、`zh-CN`、`zh-SG` 用简体文字，`zh-TW`、`zh-HK`、`zh-MO` 用繁体文字（应用还没有繁体时为英文）。

### 文字的流转

- `core/logic` 不含文字，只返回枚举、`@StringRes` id 或 `Phrase`（`core/model/Phrase.kt`）：带参数的资源、带数量的复数资源，或原样显示的文字。
- 各管理器保存类型化的值，用 `AppTexts` 生成发布出去的快照（`StateFlow`）。语言改变时，`SipLinkApp` 调用 `SipEngine.onLanguageChanged()`，各管理器重新发布快照，`CallNotifier` 重命名通知渠道，服务更新它的通知。
- 界面用 `Phrase.text()`（`ui/components/Phrases.kt`）显示 `Phrase`，自己的文字用 `stringResource` 和 `pluralStringResource`。组合之外需要的文字（回调中生成的提示）取 Activity 的 context 的资源，而不是 application 的。
- 生成后的文字不保存。远程配置保存的错误按资源名称存为 phrase（`core/logic/PhraseCodec.kt`）；旧版本保存的文字原样显示。
- 参数带编号（`%1$s`、`%2$s`），不拼接文字片段。数字用西文数字（`Locale.ROOT`）；在从右到左的文字中，每个值都被隔离（U+2068 … U+2069），使数字和地址保持顺序。数量用 `<plurals>`。日期跟随应用语言：语言区域取自组合的配置（`LocalConfiguration.current.locales[0]`），带文字的日期格式来自 `DateFormat.getBestDateTimePattern`（通话记录的日期标题：骨架 `MMMd` 和 `yMMMd`）；纯数字的时间戳（`yyyy-MM-dd HH:mm`、`HH:mm:ss`）在所有语言中相同。

### 是数据而不是文字的内容

以下内容在所有语言中保持原样：账号和快捷键的 CSV 格式（包括其中可用的中文类型与图标词）、设置导出、远程配置文档、二维码内容、SIP 头和服务器返回的原因短语、协议与编码名称、`Log.*` 日志（英文），以及名称 SipLink（`app_name`，不翻译）。

### 添加一条文字

把字符串加到 `res/values/` 和每个 `res/values-*/` 目录中对应领域的文件，占位符相同。在 `core` 中返回 `Phrase.of(R.string.name, …)`（或 `Phrase.count(R.plurals.name, n, …)`）；在界面中用 `stringResource`（或 `pluralStringResource`）。

可能被保存的文字，其名称在发布后就是保存格式的一部分：远程配置的错误及其中可能包含的所有文字按名称保存（`PhraseCodec`），之后改名会丢掉按旧名称保存的内容。

### 添加一种语言

新建 `res/values-{qualifier}/`，包含 `res/values/` 的全部文字（`{qualifier}`：语言，如 `fr`；按书写体系区分时如 `b+zh+Hant`），并确认该语言已在 `Language`、`locales_config.xml` 和 `localeFilters` 中。`android/app/src/test/java/com/sky/siplink/Texts.kt` 中的 `PluralRules` 需要知道它的复数形式。

### 测试

- `LanguageCatalogTest`（单元测试）把每种语言与默认语言比较：键相同、占位符相同、具备该语言需要的复数形式、没有空文字；检查每条文字都在其领域的文件中；并把语言与 `Language` 和 `locales_config.xml` 比较。
- `SourceTextTest`：Kotlin 源码的字符串字面量中出现中日韩字符、且不在允许列表中时失败。允许列表只有数据（`Language.kt`、`ShortcutRules.kt` 中 CSV 的词）。
- 单元测试断言真实文字：`Texts` 读取资源文件，以某种语言生成 `Phrase`（简体中文用 `zh(…)`）。
- 集成测试在每个测试前通过应用自己的设置把应用语言设为简体中文（`PbxTestBase.pinLanguage`），跑完后保持简体中文，不改手机的系统语言。`AppLanguageTest` 覆盖选择语言、跟随系统、回退到英文、中文的书写体系、应用没有界面时在应用外改语言、通话中切换语言、core 生成的文字，以及通话记录的日期标题。`EnglishScreensTest` 以英文走遍四个标签页、各设置页面、通话界面和会议室，界面上出现任何中文字符即失败（语言自身的名称和数据除外）。

测试见[测试](testing.md)。
