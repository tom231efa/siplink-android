<p align="center">
  <img src="docs/logo.svg" width="112" alt="SipLink">
</p>

<h1 align="center">SipLink</h1>

<p align="center">Android 上的 SIP 语音软电话，面向接入 IP PBX 的日常办公使用。</p>

基于 [Linphone SDK](https://www.linphone.org/en/liblinphone-voip-sdk)，界面用 Jetpack Compose 编写。只做语音通话，不含视频和即时消息。

## 功能

- **线路**：最多 100 个 SIP 账号，UDP / TCP / TLS，SRTP；每条线路可单独设置代理、备用代理和 DNS 服务器，支持批量导入
- **通话**：保留、盲转、咨询转、合并、呼叫等待、呼叫前转、免打扰、对讲自动接听、语音留言订阅
- **功能码**：驻留、代接、监听、耳语、强插、强拆等，按 PBX 的功能码配置
- **快捷键**：速拨、转接、发送按键等，可分类，支持 CSV 导入导出
- **通讯录**：最多 50 个 LDAP 通讯录，来电和去电显示姓名
- **其它**：通话记录、音频编码选择、CA 证书管理、后台保持注册（不依赖推送）

## 运行要求

Android 9 及以上，64 位 ARM 设备。目前只在 Android 16 上测试过。

## 构建

需要 JDK 21 和 Android SDK（平台 37）。

```bash
./gradlew :app:assembleDebug
```

生成 `app/build/outputs/apk/debug/app-debug.apk`。

发布包需要签名：在项目根目录放一个 `keystore.properties`（已被 git 忽略），写入 `storeFile`、`storePassword`、`keyAlias`、`keyPassword`，再执行：

```bash
./gradlew :app:assembleRelease
```

默认只打包 `arm64-v8a`。要同时支持 32 位手机，加上 `-Psiplink.abi=arm64-v8a,armeabi-v7a`。

## 测试

```bash
./gradlew :app:testDebugUnitTest
```

集成测试在真机或模拟器上运行，需要 `testenv/` 里用 Docker 启动的测试环境（Asterisk、OpenLDAP、DNS），用法见 `testenv/up.sh` 和 `testenv/run-tests.sh` 开头的说明。

## 许可证

[GNU AGPL-3.0](LICENSE)。本项目使用 Linphone SDK，它以 AGPLv3 或商业许可提供。
