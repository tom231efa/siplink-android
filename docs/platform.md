# 管理平台

[返回首页](../README.md) · [功能特性](features.md) · [远程配置](remote-configuration.md)

SipLink 管理平台为 SipLink 安卓客户端集中下发配置：账号、通话设置、编解码器、CA 证书、通讯录和功能键。手机在 SipLink → 设置 → 远程配置 中扫描二维码完成绑定，之后定期同步。平台同时提供 LDAP 通讯录服务。

平台是一个 Go 程序，带网页控制台，数据保存在 SQLite 中，以 Docker 镜像发布。源码在本仓库的 [`platform/`](../platform) 目录，设计见 [platform/docs/DESIGN.md](../platform/docs/DESIGN.md)。

**先备份密钥文件。** 平台第一次启动时生成一个 64 字节的密钥文件（`SIPLINK_KEY_FILE`，Compose 部署时是 `/run/secrets/siplink-key`）。数据库里的 SIP 密码、LDAP 密码、内置 CA 的私钥都用它加密。数据库备份**不含**密钥文件；没有密钥文件，备份无法使用。请把密钥文件复制到与服务器、数据库备份都不同的地方，并且只让管理员能读到。

## 部署（Docker）

镜像：`ghcr.io/tom231efa/siplink`（linux/amd64、linux/arm64），标签为版本号和 `latest`。

1. 新建一个目录，把 [`platform/compose.yaml`](../platform/compose.yaml) 下载到其中，并准备数据目录和密钥文件（密钥文件放在该目录之外）：

   ```sh
   mkdir -p data
   head -c 64 /dev/urandom > {密钥文件路径}
   chmod 400 {密钥文件路径}
   sudo chown 10001:10001 data {密钥文件路径}
   ```

   `{密钥文件路径}` 换成实际路径，例如 `/srv/siplink/siplink-key`。容器内的用户是 uid 10001。

2. 在 `compose.yaml` 旁边写 `.env`：

   ```sh
   SIPLINK_PUBLIC_URL=https://{平台域名}
   SIPLINK_KEY_PATH={密钥文件路径}
   # 可选：固定镜像版本，默认 latest
   # SIPLINK_TAG={版本号}
   # 可选：手机访问 LDAP 用的名称，默认取 SIPLINK_PUBLIC_URL 的主机名
   # SIPLINK_LDAP_PUBLIC_HOST={平台域名}
   ```

   `{平台域名}` 换成手机能访问到的域名，`{版本号}` 换成要使用的镜像版本。

3. 启动：`docker compose up -d`。HTTPS 在 443 端口，LDAP 在 389 端口。

4. 建第一个超级管理员（一次性密码只打印在终端，首次登录必须修改）：

   ```sh
   docker compose exec siplink siplink admin create --super {用户名}
   ```

平台没有初始化网页：在建好管理员之前，网页上没有任何可以抢先占用的入口。

### 升级

```sh
docker compose pull
docker compose up -d
```

升级前先备份（见[备份与恢复](#备份与恢复)）。

### 从源码构建镜像

```sh
docker build -t ghcr.io/tom231efa/siplink:dev platform
```

在仓库根目录运行；然后在 `.env` 中设置 `SIPLINK_TAG=dev`。

### 配置项

常用的环境变量（完整列表见 [platform/docs/DESIGN.md](../platform/docs/DESIGN.md) §7.4）：

| 变量 | 默认值 | 作用 |
|---|---|---|
| `SIPLINK_PUBLIC_URL` | 必填 | 二维码和同步地址使用的公开地址 |
| `SIPLINK_TLS` | `auto` | `auto`：平台自己提供 HTTPS；`off`：由反向代理提供 HTTPS（见 `SIPLINK_TRUSTED_PROXIES`） |
| `SIPLINK_TLS_CERT_FILE` / `SIPLINK_TLS_KEY_FILE` | 空 | 使用自有证书文件（优先于在「系统」页面上传的证书和内置 CA） |
| `SIPLINK_TLS_EXTRA_NAMES` | 空 | 内置 CA 签发的服务器证书额外包含的名称（逗号分隔） |
| `SIPLINK_LDAP_PUBLIC_HOST` / `_PORT` | 公开地址的主机名 / `389` | 下发给手机的 LDAP 地址 |
| `SIPLINK_LDAP_REQUIRE_TLS` | `true` | LDAP 登录前必须先 StartTLS |
| `SIPLINK_ENROLL_DAYS` | `30` | 二维码的有效天数 |
| `SIPLINK_BACKUP_TIME` / `SIPLINK_BACKUP_KEEP` | `03:30` / `14` | 每日自动备份的时间和保留份数 |

## 证书

每个服务（HTTPS、LDAP）按顺序选用证书：环境变量指定的证书文件 → 在「系统」页面上传的自有证书（证书须包含该服务的名称：HTTPS 是 `SIPLINK_PUBLIC_URL` 的主机名，LDAP 是 `SIPLINK_LDAP_PUBLIC_HOST`）→ 内置 CA 签发的证书。没有服务使用内置 CA 的证书时，自动签发暂停；「系统」页面显示当前模式。

### 内置 CA 与浏览器

不配置证书时，平台用内置 CA 给自己签发 HTTPS 和 LDAP 证书。CA 在第一次启动时生成，有效期 20 年，以后不会重新生成（否则所有手机都要重新扫码）；服务器证书剩余不足 30 天或名称变化时自动续签。

- **手机**不需要导入 CA：二维码里带有 CA 公钥的指纹（`pin=`），手机第一次扫码时据此信任平台。
- **浏览器**在导入 CA 之前会提示连接不安全。CA 证书可以在「系统 → 证书 → 下载 CA 证书」下载，或用命令导出：

  ```sh
  docker compose exec siplink siplink pki export-ca /tmp/siplink-ca.pem
  docker compose cp siplink:/tmp/siplink-ca.pem .
  ```

  导入方法：
  - Windows（Edge、Chrome）：双击证书 → 安装证书 → 选择「受信任的根证书颁发机构」。
  - macOS：用「钥匙串访问」导入到「系统」钥匙串，打开证书，把「使用此证书时」设为「始终信任」。
  - Firefox：设置 → 隐私与安全 → 证书 → 查看证书 → 证书颁发机构 → 导入，勾选「信任由此证书颁发机构来标识网站」。
  - Debian / Ubuntu：复制到 `/usr/local/share/ca-certificates/siplink-ca.crt`，运行 `sudo update-ca-certificates`。

  导入前请核对「系统」页面上显示的 SHA-256 指纹。
- 「系统」页面的「重新签发服务器证书」只换服务器证书，CA 不变，手机无需重新扫码。命令行：`siplink pki show`、`siplink pki renew`。

### 使用自有证书

超级管理员在「系统 → 证书 → 使用自有证书」上传证书链文件（PEM，服务器证书在前，中间证书随后；私有 CA 请把根证书附在最后）和**未加密**的私钥文件（PEM）。上传后立即生效，无需重启；私钥加密保存在数据库里（随备份一起），以后不能再查看或下载。命令行：

```sh
docker compose exec -T siplink sh -c 'cat > /tmp/chain.pem' < chain.pem
docker compose exec -T siplink sh -c 'umask 077; cat > /tmp/key.pem' < key.pem
docker compose exec siplink siplink pki import /tmp/chain.pem /tmp/key.pem
docker compose exec siplink rm /tmp/key.pem /tmp/chain.pem
```

（容器的根文件系统只读，`/tmp` 是内存盘，所以用标准输入写入文件。有提示时确认后在 `pki import` 后加 `--yes` 重新运行；运行中的平台在一分钟内启用。）

- **检查**：证书须能解析、私钥与服务器证书匹配、每个证书由下一个签发、当前有效、允许用于服务器身份验证、包含公开地址的主机名。不符合时页面说明原因，证书不会启用。
- **手机如何信任**：证书链被公共 CA 信任时，二维码不带 `pin`，手机按安卓系统的 CA 校验；链以私有根证书结尾时，二维码带这个根证书的 `pin`，配置文档也下发它，和内置 CA 一样。既不被公共 CA 信任、又不带根证书的链会被拒绝。
- **已绑定的手机**只信任第一次扫码时记住的 CA 和安卓系统的 CA。换成既不由它签发、也不被公共 CA 信任的证书（或从这样的证书回到内置 CA）后，这些手机无法同步，必须重新生成二维码并重新扫码；平台会先说明受影响的手机数量，确认后才生效。二维码中的 `pin` 改变时，已打印的二维码也需要重新打印。
- **LDAP**：证书不包含 LDAP 地址时，LDAP 继续使用内置 CA 的证书，自动签发不暂停。证书链中有「授权密钥标识符（AuthorityKeyIdentifier）为空」的证书时平台会提示：手机的 SIP/LDAP TLS 库（mbedTLS 3.6.0）会拒绝这样的证书。
- **到期提醒**：「到期前提醒天数」默认 30 天（1–365）。自有证书（含证书文件）进入提醒期后，「系统」页面和超级管理员的「概览」显示提醒，日志每天记一条警告；过期后显示「证书已过期，手机将无法同步」。`siplink health`（Docker 健康检查）在提醒期内仍为健康，**过期后为不健康**。内置 CA 的证书会自动续签，不提醒。
- **停用**：「停用自有证书」或 `siplink pki use-builtin` 回到内置 CA，自动签发立即恢复。

## 托管通讯录与 LDAP 地址

手机上的 LDAP 库只能按**域名**校验服务器证书，所以：

| `SIPLINK_LDAP_PUBLIC_HOST` | `SIPLINK_LDAP_REQUIRE_TLS` | 托管通讯录 |
|---|---|---|
| 域名 | 任意 | 以 StartTLS 下发 |
| IP 地址 | `true`（默认） | 不能加入配置模板；页面和启动日志会说明原因 |
| IP 地址 | `false` | 以不加密方式下发，配置模板页标明「不加密」 |

要加密的托管通讯录，请给平台一个 DNS 名称并设置 `SIPLINK_LDAP_PUBLIC_HOST`。外部 LDAP 目录不受此限制，但同样遵守手机的规则：StartTLS 需要域名。

## 备份与恢复

- 每天 `SIPLINK_BACKUP_TIME`（容器时区）自动备份到 `data/backups/siplink-{日期}.db`，保留 `SIPLINK_BACKUP_KEEP` 份。「系统」页面可以「立即备份」。
- 命令行：`docker compose exec siplink siplink backup /data/backups/{文件名}.db`。
- 恢复：停止容器，把备份文件复制为 `data/siplink.db`（删除旧的 `siplink.db-wal`、`siplink.db-shm`），用**同一个密钥文件**启动。
- 密钥文件不在备份里，见本文第一段。

## 日常使用

- 顺序：区域 → 通讯录（可选）→ 配置模板（账号、设置、通讯录）→ 功能键模板（可选）→ 客户端模板 → 客户端 → 打印或展示二维码。
- 客户端可以批量导入：「客户端 → 批量导入」可下载每个客户端模板的 CSV 表头，先检查再确认。
- 名称排序按 Unicode 码位，**不是按拼音**；同音或常用字的先后可能与习惯不同。
- 管理员查看客户端保存的密码前须再次输入自己的密码，每次查看都记入审计日志。
