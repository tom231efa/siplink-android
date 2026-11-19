package main

import (
	"bytes"
	"context"
	"encoding/pem"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"siplink/internal/pki"
	"siplink/internal/store"
	"siplink/internal/testutil"
)

// TestPKICommands imports a certificate made at test time, shows it, and returns to the
// built-in CA, the way an operator does it on the command line.
func TestPKICommands(t *testing.T) {
	t.Setenv("LANG", "zh_CN.UTF-8") // the texts below are the Simplified Chinese ones
	dir := t.TempDir()
	t.Setenv("SIPLINK_PUBLIC_URL", "https://platform.example.com")
	t.Setenv("SIPLINK_DATA_DIR", dir)
	t.Setenv("SIPLINK_KEY_FILE", filepath.Join(dir, "key"))
	t.Setenv("SIPLINK_LOG_LEVEL", "error")
	run := func(args ...string) (string, error) {
		var out bytes.Buffer
		err := pkiCommand(args, &out)
		return out.String(), err
	}
	write := func(name string, data []byte) string {
		p := filepath.Join(dir, name)
		if err := os.WriteFile(p, data, 0o600); err != nil {
			t.Fatal(err)
		}
		return p
	}
	if out, err := run("show"); err != nil || !strings.Contains(out, "模式: 内置 CA 自动签发") || !strings.Contains(out, "运行中") {
		t.Fatalf("show: %v\n%s", err, out)
	}

	root := testutil.MakeCert(t, testutil.CertSpec{CN: "CLI Root", CA: true}, nil)
	leaf := testutil.MakeCert(t, testutil.CertSpec{CN: "platform.example.com", Names: []string{"platform.example.com"}}, root)
	chain, key := write("chain.pem", testutil.ChainPEM(leaf, root)), write("key.pem", testutil.KeyPEM(t, leaf))
	block, _ := pem.Decode(testutil.KeyPEM(t, leaf))
	encrypted := write("enc.pem", pem.EncodeToMemory(&pem.Block{Type: "ENCRYPTED PRIVATE KEY", Bytes: block.Bytes}))
	if _, err := run("import", chain, encrypted); err == nil || !strings.Contains(cli().Err(err), "私钥已加密") {
		t.Errorf("encrypted key: %v", err)
	}
	if out, err := run("import", chain, key); err != nil || !strings.Contains(out, "使用自有证书（自动签发已暂停）") {
		t.Fatalf("import: %v\n%s", err, out)
	}
	out, err := run("show")
	if err != nil || !strings.Contains(out, "内置 CA 自动签发: 已暂停") || !strings.Contains(out, "自有证书（已上传）: CN=platform.example.com") ||
		!strings.Contains(out, "签发者: CN=CLI Root") || !strings.Contains(out, "私有根证书 CN=CLI Root") || !strings.Contains(out, "剩余 364 天") {
		t.Fatalf("show after import: %v\n%s", err, out)
	}
	if _, err := run("renew"); err == nil || !strings.Contains(cli().Err(err), "自动签发已暂停") {
		t.Errorf("renew while paused: %v", err)
	}

	// A certificate close to its expiry: shown, and used only with --yes.
	short := testutil.MakeCert(t, testutil.CertSpec{CN: "short", Names: []string{"platform.example.com"}, Days: 10}, root)
	shortChain, shortKey := write("short.pem", testutil.ChainPEM(short, root)), write("short.key", testutil.KeyPEM(t, short))
	if out, err := run("import", shortChain, shortKey); err == nil || !strings.Contains(out, "提示: 证书将在") || !strings.Contains(err.Error(), "--yes") {
		t.Errorf("import without --yes: %v\n%s", err, out)
	}
	if _, err := run("import", "--yes", shortChain, shortKey); err != nil {
		t.Fatalf("import --yes: %v", err)
	}
	if out, _ := run("show"); !strings.Contains(out, "提醒: 自有证书将在 9 天后") {
		t.Errorf("reminder in show:\n%s", out)
	}

	if out, err := run("use-builtin"); err != nil || !strings.Contains(out, "恢复使用内置 CA") {
		t.Fatalf("use-builtin: %v\n%s", err, out)
	}
	if out, _ := run("show"); !strings.Contains(out, "模式: 内置 CA 自动签发") {
		t.Errorf("show after use-builtin:\n%s", out)
	}
	if _, err := run("use-builtin"); err == nil {
		t.Error("use-builtin without an uploaded certificate")
	}

	ctx := context.Background()
	e, err := open(ctx)
	if err != nil {
		t.Fatal(err)
	}
	defer e.st.Close()
	list, _ := e.st.ListAudit(ctx, store.AuditFilter{AllRegions: true, Limit: 10})
	var actions []string
	for _, a := range list {
		actions = append(actions, a.Action)
		if strings.Contains(a.DetailJSON, "PRIVATE") {
			t.Error("secret material in the audit trail")
		}
	}
	if got := strings.Join(actions, " "); !strings.Contains(got, "pki.own_import") || !strings.Contains(got, "pki.own_remove") {
		t.Errorf("audit: %s", got)
	}
}

// TestCopyableValuesRaw: in a right-to-left language the values people copy from the
// terminal (pins, paths, names, fingerprints) follow their label as they are, without
// the isolates that arguments get (§5.5).
func TestCopyableValuesRaw(t *testing.T) {
	t.Setenv("LANG", "ar_SA.UTF-8")
	dir := t.TempDir()
	t.Setenv("SIPLINK_PUBLIC_URL", "https://platform.example.com")
	t.Setenv("SIPLINK_DATA_DIR", dir)
	t.Setenv("SIPLINK_KEY_FILE", filepath.Join(dir, "key"))
	t.Setenv("SIPLINK_LOG_LEVEL", "error")
	root := testutil.MakeCert(t, testutil.CertSpec{CN: "CLI Root", CA: true}, nil)
	leaf := testutil.MakeCert(t, testutil.CertSpec{CN: "platform.example.com", Names: []string{"platform.example.com"}}, root)
	chain, key := filepath.Join(dir, "chain.pem"), filepath.Join(dir, "key.pem")
	if os.WriteFile(chain, testutil.ChainPEM(leaf, root), 0o600) != nil || os.WriteFile(key, testutil.KeyPEM(t, leaf), 0o600) != nil {
		t.Fatal("write")
	}
	var out bytes.Buffer
	if err := pkiCommand([]string{"import", chain, key}, &out); err != nil {
		t.Fatalf("import: %v\n%s", err, out.String())
	}
	exported, saved := filepath.Join(dir, "ca.pem"), filepath.Join(dir, "backup.db")
	for _, run := range []func() error{
		func() error { return pkiCommand([]string{"show"}, &out) },
		func() error { return pkiCommand([]string{"export-ca", exported}, &out) },
		func() error { return backupCommand([]string{saved}, &out) },
	} {
		if err := run(); err != nil {
			t.Fatalf("%v\n%s", err, out.String())
		}
	}
	text := out.String()
	for _, want := range []string{"    pin: " + pki.Pin(root.Cert) + "\n", " " + exported + "\n", " " + saved + "\n",
		" CN=CLI Root\n", " " + pki.Fingerprint(leaf.Cert) + "\n"} {
		if !strings.Contains(text, want) {
			t.Errorf("%q not printed as it is:\n%s", want, text)
		}
	}
	for _, line := range strings.Split(text, "\n") {
		if strings.Contains(line, pki.Pin(root.Cert)) || strings.Contains(line, exported) || strings.Contains(line, saved) {
			if strings.ContainsAny(line, "\u2068\u2069") {
				t.Errorf("isolates around a copyable value: %q", line)
			}
		}
	}
}

func TestHealthReport(t *testing.T) {
	var out bytes.Buffer
	if err := healthReport("ok\nwarn 自有证书将在 9 天后（2026-11-25）到期，请及时更换\n", &out); err != nil ||
		!strings.Contains(out.String(), "warn 自有证书将在 9 天后") {
		t.Errorf("warning makes it unhealthy: %v", err)
	}
	if err := healthReport("ok\nerror 证书已过期，设备将无法同步（自有证书于 2026-11-01 到期）\n", &out); err == nil ||
		!strings.Contains(err.Error(), "证书已过期") {
		t.Errorf("expired is healthy: %v", err)
	}
	if err := healthReport("ok\n", &out); err != nil {
		t.Error(err)
	}
}
