package web

import (
	"bytes"
	"io"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"filippo.io/age"

	"siplink/internal/backup"
	"siplink/internal/store"
)

// Downloading a backup (design §6.6): super administrators, password typed again, the
// file as it is or encrypted in the age format with a passphrase.
func TestBackupDownload(t *testing.T) {
	h := newHarness(t)
	east := h.region("华东")
	root, _ := h.loggedIn("root", store.RoleSuper)
	local, _ := h.loggedIn("east", store.RoleRegion, east)
	if r := root.post("/system/backup", nil); r.code != http.StatusSeeOther {
		t.Fatalf("backup: %d", r.code)
	}
	dir := backup.Dir(h.srv.Cfg.DataDir)
	list, _ := backup.List(dir)
	if len(list) != 1 {
		t.Fatal("no backup")
	}
	name := list[0].Name
	onDisk, err := os.ReadFile(filepath.Join(dir, name))
	if err != nil {
		t.Fatal(err)
	}
	const pass = "backup-passphrase-1"
	form := func(extra ...string) url.Values {
		v := url.Values{"name": {name}, "password": {"password-root"}}
		for i := 0; i+1 < len(extra); i += 2 {
			v.Set(extra[i], extra[i+1])
		}
		return v
	}

	// the list offers it, the form opens for a listed name only
	if r := root.get("/system"); !strings.Contains(r.body, `href="/system?backup=`+name+`#download"`) {
		t.Error("no download link")
	}
	if r := root.get("/system?backup=" + name); !strings.Contains(r.body, `action="/system/backups/download"`) {
		t.Error("download form not opened")
	}
	if r := root.get("/system?backup=../siplink.db"); strings.Contains(r.body, `action="/system/backups/download"`) {
		t.Error("download form opened for a crafted name")
	}

	// permissions: region administrators and waiting sessions get nothing
	if r := local.post("/system/backups/download", form()); r.code != http.StatusNotFound {
		t.Errorf("region admin: %d", r.code)
	}
	if r := local.get("/system?backup=" + name); r.code != http.StatusNotFound {
		t.Errorf("region admin page: %d", r.code)
	}

	// unknown or crafted names
	for _, n := range []string{"", "siplink-1999-01-01.db", "../siplink.db", "/data/siplink.db", name + "/", "backups/" + name, "key"} {
		v := form()
		v.Set("name", n)
		if r := root.post("/system/backups/download", v); r.code != http.StatusNotFound {
			t.Errorf("name %q: %d", n, r.code)
		}
	}

	// plain: byte for byte the file on disk
	r := root.post("/system/backups/download", form())
	if r.code != 200 || r.body != string(onDisk) || !strings.Contains(r.header.Get("Content-Disposition"), `attachment; filename="`+name+`"`) {
		t.Fatalf("plain download: %d %s", r.code, r.header.Get("Content-Disposition"))
	}

	// encrypted: opens with the passphrase only
	r = root.post("/system/backups/download", form("encrypt", "1", "passphrase", pass, "passphrase2", pass))
	if r.code != 200 || !strings.HasSuffix(r.header.Get("Content-Disposition"), `filename="`+name+`.age"`) || strings.Contains(r.body, "SQLite format") {
		t.Fatalf("encrypted download: %d", r.code)
	}
	open := func(p string) ([]byte, error) {
		id, err := age.NewScryptIdentity(p)
		if err != nil {
			return nil, err
		}
		rd, err := age.Decrypt(strings.NewReader(r.body), id)
		if err != nil {
			return nil, err
		}
		return io.ReadAll(rd)
	}
	if got, err := open(pass); err != nil || !bytes.Equal(got, onDisk) {
		t.Errorf("decrypt: %v", err)
	}
	if _, err := open("another-passphrase"); err == nil {
		t.Error("opened with another passphrase")
	}

	// passphrase rules, with the strength check on and off
	for _, c := range []struct{ p, p2, want string }{
		{"", "", "请输入加密口令"}, {"short", "short", "加密口令至少为 10 个字符"},
		{pass, pass + "x", "两次输入的加密口令不一致"}, {strings.Repeat("x", 257), strings.Repeat("x", 257), "不得超过 256 个字符"},
	} {
		if r := root.post("/system/backups/download", form("encrypt", "1", "passphrase", c.p, "passphrase2", c.p2)); r.code != http.StatusBadRequest || !strings.Contains(r.body, c.want) {
			t.Errorf("passphrase %q: %d", c.want, r.code)
		}
	}
	root.post("/system/password-check", nil)
	if r := root.post("/system/backups/download", form("encrypt", "1", "passphrase", "short", "passphrase2", "short")); r.code != 200 {
		t.Errorf("short passphrase with the check off: %d", r.code)
	}
	if r := root.post("/system/backups/download", form("encrypt", "1", "passphrase", "", "passphrase2", "")); r.code != http.StatusBadRequest {
		t.Errorf("empty passphrase with the check off: %d", r.code)
	}

	// audit: name and whether it was encrypted, nothing of the passphrase; nor in the log
	entries, _ := h.st.ListAudit(t.Context(), store.AuditFilter{AllRegions: true, Limit: 500})
	n := 0
	for _, e := range entries {
		if e.Action == "backup.download" {
			n++
			if e.ObjectName != name {
				t.Errorf("audit object: %s", e.ObjectName)
			}
		}
	}
	if n != 3 {
		t.Errorf("audit entries: %d", n)
	}
	audit := root.get("/audit").body
	for _, secret := range []string{pass, "short"} {
		if strings.Contains(audit, secret) || strings.Contains(h.logs.String(), secret) {
			t.Errorf("passphrase %q in the audit trail or the log", secret)
		}
	}

	// a wrong password is counted by the limiter of §6.8
	for i := 0; i < 5; i++ {
		if r := root.post("/system/backups/download", form("password", "wrong")); r.code != http.StatusBadRequest || !strings.Contains(r.body, wrongPassword) {
			t.Fatalf("wrong password %d: %d", i, r.code)
		}
	}
	if r := root.post("/system/backups/download", form()); r.code != http.StatusBadRequest || !strings.Contains(r.body, tooManySteps) {
		t.Errorf("after five wrong passwords: %d", r.code)
	}

	// a session waiting for the second step cannot reach it
	boss, _ := h.loggedIn("boss", store.RoleSuper)
	enableTOTP(t, boss)
	pending := h.client()
	signInPending(t, pending, "boss")
	if r := pending.post("/system/backups/download", form("password", "password-boss")); r.code != http.StatusSeeOther || r.location != "/login/step" {
		t.Error("pending session downloaded a backup")
	}
}
