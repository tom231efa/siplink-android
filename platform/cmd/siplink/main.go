// Command siplink is the SipLink management platform: web UI, LDAP service and CLI.
package main

import (
	"context"
	"fmt"
	"io"
	"log/slog"
	"os"
	"path/filepath"

	"siplink/internal/config"
	"siplink/internal/secret"
	"siplink/internal/store"
	"siplink/internal/web"
)

// version is set at build time with -ldflags "-X main.version=...".
var version = "dev"

const usage = `用法: siplink <命令> [参数]

命令:
  serve                          启动平台（默认）
  admin create [--super] [--region 名称]... [--name 显示名] <用户名>
                                 新建管理员，一次性密码打印到终端
  admin reset-password <用户名>  重置管理员密码，一次性密码打印到终端
  admin reset-2fa <用户名>       删除管理员的全部两步验证方式和恢复码
  pki show                       显示证书模式、内置 CA、服务器证书和自有证书
  pki export-ca <路径>           导出内置 CA 证书（PEM）
  pki renew                      立即重新签发服务器证书（自动签发暂停时不可用）
  pki import [--yes] <证书链文件> <私钥文件>
                                 使用自有证书（PEM，服务器证书在前）；有提示时需加 --yes
  pki use-builtin [--yes]        停用自有证书，回到内置 CA
  seed <规格文件.json>            按规格创建或更新测试数据，打印客户端的绑定地址
  backup <目标文件>              把数据库备份到文件（密钥文件需另行备份）
  health                         检查本机的 HTTP(S) 服务（Docker 健康检查）
  migrate                        只执行数据库迁移
  version                        显示版本
`

func main() {
	web.Version = version
	args := os.Args[1:]
	cmd := "serve"
	if len(args) > 0 {
		cmd, args = args[0], args[1:]
	}
	var err error
	switch cmd {
	case "serve":
		err = serve()
	case "admin":
		err = adminCommand(args, os.Stdout)
	case "pki":
		err = pkiCommand(args, os.Stdout)
	case "seed":
		err = seedCommand(args, os.Stdout)
	case "backup":
		err = backupCommand(args, os.Stdout)
	case "health":
		err = healthCommand(os.Stdout)
	case "migrate":
		err = migrateCommand(os.Stdout)
	case "version":
		fmt.Println("siplink", version)
	case "help", "-h", "--help":
		fmt.Print(usage)
	default:
		fmt.Fprint(os.Stderr, usage)
		os.Exit(2)
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, "错误:", err)
		os.Exit(1)
	}
}

// env bundles what every command opens.
type env struct {
	cfg  *config.Config
	st   *store.Store
	keys *secret.Keys
	box  *secret.Box
	log  *slog.Logger
}

func newLogger(cfg *config.Config, out io.Writer) *slog.Logger {
	level := slog.LevelInfo
	switch cfg.LogLevel {
	case "debug":
		level = slog.LevelDebug
	case "warn":
		level = slog.LevelWarn
	case "error":
		level = slog.LevelError
	}
	return slog.New(slog.NewJSONHandler(out, &slog.HandlerOptions{Level: level}))
}

// open loads the configuration, the key file and the database.
func open(ctx context.Context) (*env, error) {
	cfg, err := config.Load()
	if err != nil {
		return nil, err
	}
	e := &env{cfg: cfg, log: newLogger(cfg, os.Stdout)}
	keys, created, err := secret.LoadOrCreate(cfg.KeyFile)
	if err != nil {
		return nil, err
	}
	if created {
		e.log.Warn("new key file created; back it up separately from the database", "path", cfg.KeyFile)
	}
	e.keys = keys
	if e.box, err = secret.NewBox(keys.Data); err != nil {
		return nil, err
	}
	if e.st, err = store.Open(ctx, filepath.Join(cfg.DataDir, store.FileName), e.box); err != nil {
		return nil, err
	}
	return e, nil
}

func migrateCommand(out io.Writer) error {
	e, err := open(context.Background())
	if err != nil {
		return err
	}
	defer e.st.Close()
	v, err := e.st.SchemaVersion(context.Background())
	if err != nil {
		return err
	}
	fmt.Fprintln(out, "数据库结构版本:", v)
	return nil
}
