<p align="right"><sub>中文 | <a href="README.md">English</a></sub></p>

# SipLink 管理平台（源码）

为 SipLink 安卓客户端集中下发配置并提供 LDAP 通讯录的服务端：一个 Go 程序，带网页控制台，数据保存在 SQLite 中。

- 部署与使用：[docs/zh-CN/platform.md](../docs/zh-CN/platform.md)
- 设计：[docs/DESIGN.md](docs/DESIGN.md)
- 下发给手机的配置文档格式：[docs/zh-CN/remote-configuration.md](../docs/zh-CN/remote-configuration.md)，样例见 [docs/examples/provisioning-document.json](docs/examples/provisioning-document.json)（由测试从固定输入生成，其中的密码和证书都是测试数据）

## 测试数据

`siplink seed {规格文件}` 按规格创建或更新区域、配置模板（一个账号、CA、托管通讯录、外部目录）、功能键模板、客户端模板和客户端，并打印客户端的绑定地址。例子见 [docs/examples/seed.example.json](docs/examples/seed.example.json)，密码可以用 `passwordEnv` 从环境变量读取，不必写进文件。对象按名称查找，重复运行会更新它们，并给客户端生成新的二维码。

## 开发

本机不需要安装 Go，所有命令在官方镜像中运行（在本目录下）：

```sh
docker run --rm -v "$PWD":/src -w /src -v siplink-gomod:/go/pkg/mod golang:1.27.3-trixie go test ./...
docker run --rm -v "$PWD":/src -w /src -v siplink-gomod:/go/pkg/mod golang:1.27.3-trixie go vet ./...
docker build -t ghcr.io/tom231efa/siplink:dev .
```

`go test ./internal/provision -update` 重新生成配置文档的标准样例（`internal/provision/testdata/` 和 `docs/examples/`）。

命令：`siplink serve | admin | pki | seed | backup | health | migrate | version`，`siplink help` 显示用法。
