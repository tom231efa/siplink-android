<p align="right"><sub>中文 | <a href="en/testing.md">English</a></sub></p>

# 测试

## 单元测试

```bash
./gradlew :app:testDebugUnitTest
```

单元测试位于 `app/src/test`，覆盖 `core/logic` 中的规则（账号与快捷键校验、CSV、来电策略、远程配置文档与地址、证书 pin、更新版本比较、视频规则等）以及主题。远程配置的示例文档在 `app/src/test/resources/`。

## Lint

```bash
./gradlew :app:lintDebug
```

Lint 发现问题时不中断构建（`abortOnError = false`），报告位于 `app/build/reports/`。

## 集成测试

集成测试位于 `app/src/androidTest`，在模拟器或真机上运行，连接 `testenv/` 中用 Docker 启动的测试环境。

> **警告**：集成测试接管已安装的 SipLink 调试版。每个测试类运行前都会清空应用数据，测试会删除全部账号、设置和导入的 CA。不要在保存有需要保留的调试版配置的设备上运行。

### 测试环境

| 服务 | 内容 |
| --- | --- |
| `asterisk` | 被测 PBX（Asterisk），含示例拨号计划与功能码 |
| `phones` | 第二个 Asterisk 实例，模拟向 PBX 注册的桌面话机（分机 1002–1005），由测试通过 AMI 控制 |
| `ldap` | OpenLDAP，供通讯录测试 |
| `dns` | DNS 服务器，解析测试环境的域名 |

首次启动时生成 `testenv/.env`（随机口令）和 `testenv/certs/`（一次性测试 CA 与服务器证书），两者均被 git 忽略，不得提交。

```bash
testenv/up.sh      # 构建并启动
testenv/down.sh    # 停止并删除容器与网络
```

### 要求

- 已设置 `ANDROID_HOME` 和 `JAVA_HOME`
- adb 能看到一台设备；有多台时用 `ANDROID_SERIAL` 指定
- `run-tests.sh` 按设备的 ABI 构建并安装调试包和测试包；设置 `SKIP_INSTALL=1` 时沿用已安装的包

### 模拟器

测试环境运行在不发布端口的私有 Docker 网络中，本机上的模拟器可直接访问。启动模拟器时须用 `-dns-server` 指定测试环境的 DNS 服务器，地址见 `testenv/run-tests.sh` 开头的说明。

```bash
testenv/up.sh
testenv/run-tests.sh
```

### 真机（局域网模式）

把测试环境发布到本机的一个局域网地址，启动和运行测试时使用相同的变量：

```bash
LAN_ADDRESS={本机局域网地址} LAN_NAME={解析到该地址的域名} testenv/up.sh
LAN_ADDRESS={本机局域网地址} LAN_NAME={解析到该地址的域名} testenv/run-tests.sh
```

- `{本机局域网地址}`：运行 Docker 的主机在局域网中的 IP 地址，设备须能访问
- `{解析到该地址的域名}`：设备能解析到上述地址的 DNS 名称。可省略，但 LDAP StartTLS 只能使用域名，省略时相关测试被跳过

地址或域名变化时，`up.sh` 会重新生成证书使其包含新的地址和名称，并重建容器。

### 运行指定测试

```bash
testenv/run-tests.sh                                                   # 全部测试类
testenv/run-tests.sh com.sky.siplink.itest.BasicCallTest               # 一个类
testenv/run-tests.sh 'com.sky.siplink.itest.BasicCallTest#outgoingCallSendsDtmf'   # 一个方法
SKIP_INSTALL=1 testenv/run-tests.sh com.sky.siplink.itest.CodecTest    # 不重新安装
```

运行全部测试时，每个测试类在单独的进程中运行，结束时列出失败的类。测试期间脚本会定时唤醒设备屏幕，防止熄屏后后台进程失去网络。测试所需的口令和地址以单个 instrumentation 参数传入，不写入测试 APK 或仓库。

以下测试类不在默认运行之列，需单独运行：

| 类 | 原因 |
| --- | --- |
| `PbxReferTest` | PBX 发起的 REFER 会使 Asterisk 残留通道，影响其它测试；须在测试网络内的模拟器上单独运行，之后在 `testenv/` 中执行 `docker compose restart asterisk` 重启 PBX |
| `RealAudioTest` | 检查真实麦克风是否送出声音，需要真机且环境不能完全安静 |
