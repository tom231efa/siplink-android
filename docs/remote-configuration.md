<p align="right"><sub>中文 | <a href="en/remote-configuration.md">English</a></sub></p>

# 远程配置

管理平台可以向 SipLink 下发账号、通讯录、证书、编码、设置和快捷键。管理平台的源码在本仓库的 `platform/` 目录，部署与使用见[管理平台](platform.md)；本文后半部分给出应用接受的接口与文档格式，也可据此实现兼容的配置服务。

## 载入

**设置 → 远程配置**：

- **扫描二维码**：首次使用时申请相机权限；不是远程配置地址的二维码不会被采用
- **输入地址**：粘贴二维码中的地址

确认对话框显示来源主机，选择 **载入** 后应用向平台绑定本机并应用配置。二维码地址只能使用一次，绑定后由平台发给本机的设备密钥维持后续同步。

### 地址格式

```text
https://{主机}[:{端口}][/{前缀}]/p/{令牌}[?pin={指纹}]
```

| 部分 | 说明 |
| --- | --- |
| `{主机}`、`{端口}`、`{前缀}` | 平台地址；同步地址默认为 `https://{主机}[:{端口}][/{前缀}]/api/device/v1/sync` |
| `{令牌}` | 一次性绑定令牌，字符限 `A-Z a-z 0-9 _ -` |
| `{指纹}` | 平台 CA 证书 SubjectPublicKeyInfo 的 SHA-256，base64url 编码、无填充（43 个字符） |

只接受 `https://`（调试版另外接受 `http://`），地址中不能含用户信息。

### 首次扫码信任（pin）

- 地址带 `pin` 时：服务器证书链中须有一张证书的公钥指纹等于 `pin`，从服务器证书到该证书逐级签名有效、均在有效期内，服务器证书的主题备用名称（SAN）须包含所访问的主机名或 IP。该证书被保存为平台 CA
- 地址不带 `pin` 时：平台证书须由 Android 系统内置 CA 签发
- 之后的每次同步信任系统内置 CA 和首次保存的平台 CA；用户自行安装的 CA 不被信任

远程配置页“详细信息”中的“平台证书”显示“扫码时已信任平台 CA”或“由系统 CA 校验”。

## 载入后的变化

远程配置替换整套配置，本机原有的以下内容被删除或覆盖：

| 内容 | 处理 |
| --- | --- |
| SIP 账号 | 全部替换为下发的账号 |
| LDAP 通讯录 | 全部替换 |
| 导入的 CA 证书 | 全部替换 |
| 编码 | 按下发的顺序与开关设置；未下发编码时恢复 SDK 默认 |
| 设置 | 除用户保留项外全部按下发值设置，未下发的字段取应用默认值 |
| 快捷键 | 原有快捷键替换为下发的快捷键 |

载入前如需保留原有快捷键，先在快捷键菜单中 **导出为 CSV**。通话记录不受影响。

下发的内容在本机只读：各页面顶栏显示锁形图标“配置由远程下发”，字段只能查看；尝试修改时提示“配置由远程下发，不能在本机修改”。快捷键的批量导入不可用，“呼叫前转”类型的快捷键无法切换。

### 用户保留的内容

| 内容 | 说明 |
| --- | --- |
| 免打扰开关 | 开关仍由用户控制；免打扰方式由平台下发 |
| 外观 | 主题、动态配色、通话横幅位置 |
| 锁屏来电提示 | “不再提醒”的选择 |
| 通话记录 | |
| 通话录音 | 录音文件页（播放、导出、删除）与保留条数 |
| 自建快捷键 | 最多 50 个，排在下发的快捷键之前，可新建、编辑、删除；编辑模式下只能在自建快捷键之间拖动排序；只有全部由自建快捷键组成的分类可以重命名 |
| 远程配置页 | 立即同步、删除远程配置 |

下发的快捷键仍可使用和导出。

### 配置密码

平台可为配置设置密码。设置后，账号和通讯录中保存的密码不直接显示，点按密码字段的显示按钮需输入配置密码；未设置时可像其它字段一样查看。

## 同步

同步在以下时机进行：应用启动；应用回到前台（距上次尝试至少 1 分钟）；网络恢复且上次同步失败；点按 **立即同步**；以及每隔平台下发的间隔（默认 60 分钟，范围 5–1440 分钟），页面上显示为“每 N 分钟自动同步”。

- 同步失败时配置保持不变，依次在 1、2、4……分钟后重试，最长不超过同步间隔；平台返回 `Retry-After` 时按其等待（同样不超过同步间隔）
- 配置有误时整份不应用，页面显示“远程配置无效：…”；配置格式版本高于应用支持的版本时显示“请升级 SipLink”
- 部分内容未能应用时显示“部分配置未能应用：…”，下次同步重新获取整份配置
- 页面显示平台、客户端、区域、上次同步时间、配置更新时间、同步结果；“详细信息”显示地址、配置版本前 12 位和平台证书来源

| 提示 | 含义 |
| --- | --- |
| 无法连接平台 | 网络或服务器不可达 |
| 平台证书与二维码不符 | 首次扫码时证书链与 `pin` 不符 |
| 平台证书不受信任 | 证书未通过系统 CA 或已保存的平台 CA 校验 |
| 远程配置认证失败 | 平台返回 401 |
| 二维码无效、已使用或已过期 | 绑定时平台返回 404 |
| 请求过于频繁，请稍后重试 | 平台返回 429 |

## 收回与删除

- **平台收回**（平台返回 410）：下发的全部内容和自建快捷键被清除，设置恢复默认值（用户保留项不变），页面显示“远程配置已被平台收回”
- **删除远程配置**：通知平台解除绑定，并执行与收回相同的清除；通话记录和外观设置保留。平台无法连接时仍在本机删除，提示“已删除远程配置（未能通知平台）”

清除后配置重新归本机所有，可正常编辑。

## 接口与文档格式

以下内容供实现配置服务参考，取自 `core/logic/RemoteAddress.kt`、`RemoteSync.kt`、`RemoteDocument.kt`、`RemoteChecks.kt` 和 `core/RemoteConfigClient.kt`。

### 请求

所有请求均为 `POST`，正文为 JSON：

| 项 | 值 |
| --- | --- |
| `Content-Type` | `application/json; charset=utf-8` |
| `Accept` | `application/json` |
| `User-Agent` | `SipLink/{应用版本}` |
| `Authorization` | `Bearer {deviceKey}`（绑定请求不带） |
| 超时 | 连接、读取各 10 秒 |
| 重定向 | 不跟随 |
| 响应大小 | 不超过 4 MiB |

| 请求 | 地址 | 正文 |
| --- | --- | --- |
| 绑定 | 二维码地址去掉查询部分 | `{"installId": "...", "device": {...}}` |
| 同步 | 同步地址 | `{"have": "{已应用的 version，未应用时为空}", "device": {...}}` |
| 解除绑定 | 同步地址末尾的 `/sync` 换成 `/leave`；同步地址不以 `/sync` 结尾时为 `/api/device/v1/leave` | `{}` |

`installId` 是每次安装生成一次的 UUID。`device` 为本机状态，不含号码、联系人、通话或位置：

```json
{
  "appVersion": "0.1.2",
  "appVersionCode": 3,
  "androidRelease": "16",
  "androidSdk": 36,
  "model": "{厂商} {型号}",
  "applied": { "version": "...", "at": "2026-11-16T08:00:00Z", "error": "" },
  "registrations": [ { "account": "{账号 key}", "state": "OK", "error": "" } ],
  "codecs": { "audio": ["opus/48000/2"], "video": ["H264/90000/1"] }
}
```

`applied` 为最近一次尝试的文档版本、时间（RFC 3339，UTC）和拒绝原因（空为成功）。`registrations[].state` 取 `DISABLED`、`NONE`、`PROGRESS`、`OK`、`FAILED`。

### 响应

| 状态码 | 绑定 | 同步 |
| --- | --- | --- |
| 200 | 返回配置文档，须含 `deviceKey` | 返回配置文档 |
| 204 | 失败 | 无变化 |
| 401 | 失败 | 认证失败，保持现有配置 |
| 404 | 令牌无效、已使用或已过期 | 失败，稍后重试 |
| 410 | 失败 | 收回配置 |
| 429 | 失败 | 失败，按 `Retry-After`（秒）推迟重试 |
| 其它 | 失败 | 失败，稍后重试 |

解除绑定请求返回 2xx、401 或 410 时视为平台已收到。

绑定响应只要含 `deviceKey`，本机即视为已绑定，即使文档本身未通过校验（之后的同步再次获取）。

### 文档

字段缺失或为 `null` 时取应用默认值（不是本机当前值）；类型不符、取值不在枚举中或未通过校验时整份文档被拒绝，所有错误一次报告。应用不认识的字段一律忽略，无论出现在哪一层。完整示例见 `android/app/src/test/resources/platform-provision-testdata-full.json`，最小示例见 `platform-provision-testdata-minimal.json`。

**顶层**

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `schema` | 整数 | 必须为 `1`。更高时提示“请升级 SipLink”，更低或缺失时“不支持的配置格式” |
| `version` | 字符串 | 必填。与已应用的版本相同时不重新应用 |
| `generatedAt` | 字符串 | RFC 3339 时间，显示为“配置更新于” |
| `deviceKey` | 字符串 | 绑定响应必填 |
| `platform.name` | 字符串 | 平台名称 |
| `platform.syncUrl` | 字符串 | 之后的同步地址；须为 `https://`（调试版可为 `http://`）且不含用户信息，否则沿用当前地址 |
| `client.name`、`client.region` | 字符串 | 客户端名称、区域，仅显示 |
| `refreshMinutes` | 整数 | 同步间隔，默认 60，超出 5–1440 时取边界值 |
| `configPasswordHash` | 字符串 | 配置密码 UTF-8 编码的 SHA-256，64 位十六进制，大小写均可；空为无密码 |
| `settings` | 对象 | 见下 |
| `accounts` | 数组 | 见下 |
| `defaultAccount` | 字符串 | 默认账号的 `key` |
| `codecs` | 对象 | `{"audio": [...], "video": [...]}`，每项 `{"key": "{mime}/{时钟频率}/{声道数}", "enabled": true}`；省略时恢复 SDK 默认 |
| `caCertificates` | 字符串数组 | PEM 格式 CA 证书，替换本机导入的 CA |
| `directories` | 数组 | 见下 |
| `shortcuts` | 数组 | 见下，最多 2000 个 |

**`settings`**（默认值同[使用指南](user-guide.md#设置)）

| 字段 | 类型 / 取值 | 默认 |
| --- | --- | --- |
| `keepAlive` | 布尔 | `true` |
| `intercomAutoAnswer` | 布尔 | `false` |
| `intercomSpeaker` | 布尔 | `true` |
| `autoAnswer` | 布尔：自动接听非对讲来电 | `false` |
| `autoAnswerSeconds` | 整数：`0` / `3` / `5` / `10` / `20` / `30`，其它值取最接近的一个（等距时取较长者） | `3` |
| `verifyTlsCertificate` | 布尔 | `true` |
| `verifyTlsHostname` | 布尔 | `true` |
| `mediaSecurity` | `NONE` / `SRTP_OPTIONAL` / `SRTP_MANDATORY` | `NONE` |
| `dndMode` | `SOFTWARE` / `SERVER` | `SOFTWARE` |
| `callWaiting` | 布尔 | `true` |
| `rejectAnonymous` | 布尔 | `false` |
| `dtmfMode` | `RFC2833` / `SIP_INFO` | `RFC2833` |
| `forwardAlways`、`forwardBusy`、`forwardNoAnswer` | 字符串，空为不前转 | `""` |
| `forwardNoAnswerSeconds` | 整数，5–120 | `20` |
| `features` | 对象：`park`、`listen`、`whisper`、`barge`、`forceHangUp`、`pickup`、`groupPickup`、`voicemail`、`intercom`、`dndOn`、`dndOff` | 见[功能码](user-guide.md#功能码) |
| `videoCalls` | 布尔 | `true` |
| `videoSize` | `QVGA` / `VGA` / `HD` / `FHD`（240p / 480p / 720p / 1080p） | `VGA` |
| `videoFrameRate` | `0`（自动）/ `15` / `24` / `30` | `0` |
| `autoUpdateCheck` | 布尔 | `true` |
| `userAgent` | 字符串，规则见[客户端名称](user-guide.md#高级设置) | `""` |
| `adaptiveRateControl` | 布尔 | `false` |
| `echoCancellation` | 布尔 | `true` |
| `ipv6` | 布尔 | `true` |
| `callRecording` | 布尔：是否允许通话录音；为 `false` 时不显示录音按钮，同步时正在进行的录音结束，已录文件保留 | `true` |
| `recordByDefault` | 布尔：每路通话接通即录音 | `false` |

免打扰开关、主题、动态配色、通话横幅位置、录音保留条数不在文档中，始终由用户决定。

**`accounts[]`**

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `key` | 字符串 | 必填，文档内唯一，跨版本不变；本机据此保持同一账号 |
| `name`、`username`、`password`、`authUser`、`displayName`、`domain` | 字符串 | 同[账号字段](user-guide.md#字段) |
| `port` | 字符串 | 空为默认 |
| `server`、`backupServer`、`dnsServer` | 字符串 | 代理、备用代理、DNS 服务器 |
| `transport` | `UDP` / `TCP` / `TLS` | 默认 `UDP` |
| `expires` | 整数 | 60–86400，默认 3600 |
| `subscribeMwi` | 布尔 | 默认 `false` |
| `enabled` | 布尔 | 默认 `true` |

账号按与手工添加相同的规则校验：最多 100 个，名称与“用户名@域名”不能重复。

**`directories[]`**

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `key` | 字符串 | 必填，文档内唯一 |
| `line` | 字符串 | 所属账号的 `key`；空或指向不存在的账号时用于全部线路 |
| `name`、`host`、`bindDn`、`password`、`baseDn` | 字符串 | 同[通讯录字段](user-guide.md#ldap-通讯录) |
| `port` | 整数 | 默认 389 |
| `security` | `NONE` / `START_TLS` | 默认 `NONE` |
| `enabled`、`lookupIncoming`、`lookupOutgoing` | 布尔 | 默认 `true` |
| `maxResults` | 整数 | 1–1000，默认 50 |
| `nameAttributes`、`numberAttributes` | 字符串 | 默认 `cn sn`、`telephoneNumber mobile` |
| `nameFilter`、`numberFilter` | 字符串 | 空为自动；非空时须含 `%`（也接受 SIP 协议栈的 `%s` 写法） |

最多 50 个，名称不能重复。

**`shortcuts[]`**

| 字段 | 说明 |
| --- | --- |
| `label`、`number`、`category` | 同[快捷键字段](user-guide.md#字段与限制) |
| `action` | CSV 中的类型写法：`speed` / `bxfer` / `axfer` / `dtmf` / `forward` / `dnd`；空为 `speed` |
| `line` | 账号名称或 `user@domain`（不是账号 `key`）；空为拨出时选择 |
| `icon` | CSV 中的图标写法；空为随类型 |

### 版本兼容

- 文档只增加字段。应用忽略不认识的字段，缺失的字段取默认值，所以配置服务对所有版本的客户端发送同一份文档，不需要按版本区分
- 已有字段的含义和取值范围不改变；必须改变时提高 `schema`，旧版本应用会提示“请升级 SipLink”
- 应用在每次请求的 `device.appVersion`、`device.appVersionCode` 和 `User-Agent` 中报告自己的版本，配置服务可据此记录和显示

### 应用结果

- 文档中未出现的账号、通讯录、CA 和下发快捷键被删除；`key` 相同的账号和通讯录保持本机标识
- 编码：文档列出且本机具备的编码按文档顺序与开关排在前面，其余本机编码排在后面并关闭；本机不具备的编码被忽略。结果中没有启用的音频编码时恢复 SDK 默认编码并报告“部分配置未能应用”
- 账号先注销再应用其余内容，以便 TLS 账号用原有证书完成注销
