# 构建

## 环境

| 项 | 要求 |
| --- | --- |
| JDK | 21；源码与字节码兼容级别为 Java 17（`app/build.gradle.kts`） |
| Android SDK | 平台 37（`compileSdk = 37`）；`targetSdk = 36`，`minSdk = 28` |
| Gradle | 使用仓库中的 Wrapper（`gradle/wrapper/gradle-wrapper.properties`） |
| 网络 | 首次构建需访问 Google Maven、Maven Central 和 Linphone 官方 Maven 仓库 `https://download.linphone.org/maven_repository` |

Android SDK 路径由 `ANDROID_HOME` 或项目根目录的 `local.properties`（`sdk.dir`）指定。依赖版本集中在 `gradle/libs.versions.toml`。

## 调试包

```bash
./gradlew :app:assembleDebug
```

输出 `app/build/outputs/apk/debug/app-debug.apk`。调试包输出更详细的 SIP 日志，并允许以 `http://` 地址载入远程配置。

## 发布包与签名

在项目根目录（`settings.gradle.kts` 所在目录）创建 `keystore.properties`，写入以下四个键：

| 键 | 含义 |
| --- | --- |
| `storeFile` | 密钥库文件路径，相对于项目根目录 |
| `storePassword` | 密钥库密码 |
| `keyAlias` | 密钥别名 |
| `keyPassword` | 密钥密码 |

`keystore.properties`、`*.jks`、`*.keystore` 已被 `.gitignore` 忽略，不得提交。然后：

```bash
./gradlew :app:assembleRelease
```

输出位于 `app/build/outputs/apk/release/`。没有 `keystore.properties` 时发布包不签名。发布包启用代码压缩与资源压缩（R8），规则见 `app/proguard-rules.pro`。

同一应用的各个版本必须使用同一把签名密钥，否则无法覆盖安装。

## ABI

SIP 协议栈的原生库按 ABI 打包，是安装包中最大的部分。默认只打包 `arm64-v8a`，用 `siplink.abi` 属性指定其它 ABI（逗号分隔）：

```bash
# 同时支持 32 位 ARM 设备
./gradlew :app:assembleRelease -Psiplink.abi=arm64-v8a,armeabi-v7a

# 集成测试使用的 x86_64 模拟器
./gradlew :app:assembleDebug -Psiplink.abi=x86_64
```

应用检查更新时，按设备支持的 ABI 顺序选择文件名中包含该 ABI 名称的 `.apk` 发布附件。发布多个 ABI 的安装包时，文件名应包含 ABI 名称，例如 `{名称}-arm64-v8a.apk`，其中 `{名称}` 为自定义前缀。

## 版本号

在 `app/build.gradle.kts` 的 `defaultConfig` 中：

- `versionCode`：整数，每次发布递增
- `versionName`：点分数字，如 `0.1.2`

GitHub 发布的标签写作 `v{versionName}`，如 `v0.1.2`。更新检查只比较由数字组成的版本号（`0.2.0-beta` 这类标签不会被视为新版本），缺少的位视为 0。

## 项目结构

```text
app/src/main/java/com/sky/siplink/
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
| `CallManager`、`VideoController` | 通话、转接、驻留、合并、来电策略；视频 |
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

测试见[测试](testing.md)。
