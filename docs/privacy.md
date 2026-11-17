# 隐私与权限

## 权限

| 权限 | 用途 | 申请时机 |
| --- | --- | --- |
| `INTERNET` | SIP 信令与媒体、LDAP、远程配置、更新检查 | 安装时授予 |
| `ACCESS_NETWORK_STATE` | 网络恢复时让 SIP 协议栈重新检查网络、重试失败的远程配置同步 | 安装时授予 |
| `RECORD_AUDIO` | 通话；通话录音使用同一权限，不另行申请 | 启动时 |
| `MODIFY_AUDIO_SETTINGS` | 通话音频与听筒、扬声器、蓝牙、耳机之间的切换 | 安装时授予 |
| `BLUETOOTH_CONNECT` | 使用蓝牙耳机（Android 12 起） | 启动时 |
| `POST_NOTIFICATIONS` | 后台运行、来电、通话中、未接来电通知（Android 13 起） | 启动时 |
| `USE_FULL_SCREEN_INTENT` | 锁屏时显示来电界面；Android 14 起为可由用户关闭的特殊权限 | 未授权时启动应用提示 |
| `WAKE_LOCK` | 通话中贴近耳朵时熄灭屏幕（距离传感器） | 安装时授予 |
| `VIBRATE` | 来电振动 | 安装时授予 |
| `RECEIVE_BOOT_COMPLETED` | 开机及应用更新后启动后台服务，恢复注册 | 安装时授予 |
| `FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_SPECIAL_USE` | 后台保持注册，以便不依赖推送接收来电 | 安装时授予 |
| `FOREGROUND_SERVICE_PHONE_CALL`、`FOREGROUND_SERVICE_MICROPHONE` | 通话进行时的前台服务 | 安装时授予 |
| `MANAGE_OWN_CALLS` | 使用 `phoneCall` 类型前台服务的前提 | 安装时授予 |
| `CAMERA` | 视频通话；扫描远程配置二维码 | 首次使用视频或扫码时 |
| `REQUEST_INSTALL_PACKAGES` | 把应用内下载的更新交给系统安装程序；Android 中需要用户在系统设置中允许“安装未知应用” | 首次点按“安装”时转到系统设置 |

SIP SDK 合并进来的 `FOREGROUND_SERVICE_CAMERA` 和 `FOREGROUND_SERVICE_DATA_SYNC` 已在清单中移除：摄像头只在通话界面处于前台时使用。

## 联网对象

应用只连接以下对象：

| 对象 | 时机 | 内容 |
| --- | --- | --- |
| 用户配置的 SIP 服务器（及代理、备用代理） | 注册、呼叫期间 | SIP 信令与 RTP 媒体 |
| 账号单独设置的 DNS 服务器 | 解析该账号的服务器地址时 | DNS 查询 |
| 用户配置的 LDAP 服务器 | 搜索通讯录、为来去电查询姓名时 | LDAP 查询 |
| 管理平台 | 仅在载入远程配置后；同步时机见[远程配置](remote-configuration.md#同步) | 绑定、同步、解除绑定请求，附带应用版本、Android 版本、设备型号、账号注册状态和编码列表，不含号码、联系人、通话记录或位置 |
| `api.github.com` | “自动检查更新”开启时，每次启动或回到前台（距上次检查不足 1 分钟时跳过；GitHub 返回频率限制后，在其给出的时间之前不再请求）；或在 **设置 → 关于** 点按版本行手动检查 | 查询 `tom231efa/siplink-android` 的最新发布版本，请求头 `User-Agent: SipLink/{版本}`，以及上次响应的 `If-None-Match` |
| `github.com` 及其文件主机 `*.githubusercontent.com` | 用户在更新说明中点按“下载并安装”时 | 通过 HTTPS 下载与本机 ABI 匹配的 APK，只接受本仓库发布中的文件，重定向只跟随到上述主机 |
| 浏览器打开的 GitHub 发布页面 | 发布中没有与本机 ABI 匹配的 APK，用户点按“前往下载页”时 | 由浏览器打开 |

应用不包含统计分析、崩溃上报、广告或推送服务的组件（依赖见 `gradle/libs.versions.toml`）。二维码由本地的 ZXing 库识别，不使用 Google Play 服务。

关闭 **设置 → 关于 → 自动检查更新** 后，应用只在手动检查时访问 `api.github.com`。

下载的 APK 在使用前核对大小，发布提供 SHA-256 摘要时同时核对摘要，并确认是本应用的安装包；不符时丢弃。安装由系统安装程序完成，须用户确认，应用不会自行安装。下载的文件保存在应用缓存目录 `updates/`，下次启动时删除。

## 本机存储

所有数据保存在应用私有目录中。清单设置 `android:allowBackup="false"`，并在 `data_extraction_rules.xml` 中把全部数据排除在云备份和设备迁移之外。

| 数据 | 位置 |
| --- | --- |
| 设置、SIP 账号（含按输入保存的密码）、LDAP 通讯录配置（含密码）、远程配置的锁定状态与配置密码哈希 | SIP 协议栈配置文件 `linphonerc` |
| 通话记录 | SIP 协议栈的通话记录存储 |
| 导入的 CA 证书 | `tls/user-ca.pem`、`tls/trust-bundle.pem` |
| 快捷键 | `shortcuts.csv`；远程配置下的自建快捷键在 `shortcuts-own.csv` |
| LDAP 本地缓存 | SQLite 数据库 `directory-cache.db`，上限 100 MB |
| 远程配置状态（设备密钥、同步地址、平台 CA、同步记录） | 独立的 SharedPreferences `remote_config` |
| 通话录音 | `recordings/`：录音文件（`.m4a`）与列表 `index.json`；通话进行中为 `.wav` 临时文件 |
| 更新检查结果、跳过的版本、ETag、频率限制的等待时间 | 独立的 SharedPreferences `update_check` |
| 下载的更新安装包 | 缓存目录 `updates/`，下次启动时删除 |

账号密码按输入原样保存（不是摘要），以便再次显示和随账号迁移到其它服务器。远程配置的请求与响应不写入日志。

删除远程配置或被平台收回时，下发的账号、密码、通讯录、证书和快捷键从本机删除，见[远程配置](remote-configuration.md#收回与删除)。

通话录音只保存在本机，不上传；录音时不向对方发送任何提示音或信令标记。导出由用户选择保存位置。
