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
	"siplink/internal/i18n"
	"siplink/internal/secret"
	"siplink/internal/store"
	"siplink/internal/web"
)

// cli is the translator of the command line: the language of the POSIX locale (LC_ALL,
// LC_MESSAGES, LANG), English when it names none of the console's languages (§5.5).
func cli() *i18n.Tr { return i18n.For(i18n.FromEnv(os.Getenv)) }

// version is set at build time with -ldflags "-X main.version=...".
var version = "dev"

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
		fmt.Println(cli().T("cli.usage"))
	default:
		fmt.Fprintln(os.Stderr, cli().T("cli.usage"))
		os.Exit(2)
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, cli().T("cli.error", "err", err))
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
	fmt.Fprintln(out, cli().T("cli.migrate.version", "version", v))
	return nil
}
