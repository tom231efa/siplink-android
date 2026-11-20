package provision_test

import (
	"context"
	"encoding/json"
	"strings"
	"testing"

	"siplink/internal/catalog"
	"siplink/internal/i18n"
	"siplink/internal/provision"
	"siplink/internal/rules"
	"siplink/internal/store"
)

// TestGoldenServerTimeout: a line with a backup server and a fast server timeout carries
// serverTimeoutMs next to backupServer; a line without the timeout does not, and the other
// golden files, whose lines have none, stay as they were.
func TestGoldenServerTimeout(t *testing.T) {
	ctx := context.Background()
	w := newWorld(t, "platform.example.com", true)
	cfg := w.config("快速超时", nil)
	lan := &store.Profile{TemplateID: cfg.ID, Name: "内网", Domain: "pbx.example.com", Server: "192.0.2.30",
		BackupServer: "sbc.example.com:5061", ServerTimeoutMs: 1500, Transport: "UDP", Expires: 3600, Enabled: true, IsDefault: true}
	w.must(w.st.SaveProfile(ctx, lan, rules.MaxAccounts))
	plain := w.profile(cfg, "外网", "sbc.example.com", "UDP", false)
	tpl := &store.ClientTemplate{Name: "快速超时", ConfigTemplateID: cfg.ID}
	w.must(w.st.CreateClientTemplate(ctx, tpl))
	c := w.client("fast", tpl, []store.CredentialInput{{ProfileID: lan.ID, Username: "1001", Password: "test-password-1001"},
		{ProfileID: plain.ID, Username: "1002", Password: "test-password-1002"}}, nil, nil)
	d, problems := w.document(c)
	if len(problems) > 0 {
		t.Fatal(problems)
	}
	golden(t, "testdata/server-timeout.json", d)
	if d.Accounts[0].ServerTimeoutMs != 1500 || d.Accounts[1].ServerTimeoutMs != 0 {
		t.Errorf("accounts: %+v", d.Accounts)
	}
}

// TestServerTimeoutOnlyWhenSet: the key is in an account only while the timeout is on.
func TestServerTimeoutOnlyWhenSet(t *testing.T) {
	for ms, want := range map[int]any{0: nil, 200: float64(200), 1500: float64(1500), 10000: float64(10000)} {
		raw, err := json.Marshal(provision.Account{Key: "p1", ServerTimeoutMs: ms})
		if err != nil {
			t.Fatal(err)
		}
		var m map[string]any
		if err := json.Unmarshal(raw, &m); err != nil {
			t.Fatal(err)
		}
		if v, ok := m["serverTimeoutMs"]; v != want || ok != (want != nil) {
			t.Errorf("%d: %v %v", ms, v, ok)
		}
		if ms != 0 && !strings.Contains(string(raw), `"backupServer":"","serverTimeoutMs":`) {
			t.Errorf("%d: not next to backupServer: %s", ms, raw)
		}
	}
}

// TestServerTimeoutBounds: 0 is off, 200–10000 ms is on, anything else is refused by the
// line's form and by the document; a timeout without a backup server is stored and sent.
func TestServerTimeoutBounds(t *testing.T) {
	zh := i18n.For(i18n.Source)
	const want = "快速超时应为 200–10000 毫秒"
	for ms, ok := range map[int]bool{-1: false, 0: true, 1: false, 199: false, 200: true, 1500: true, 10000: true, 10001: false, 60000: false} {
		if rules.IsValidServerTimeoutMs(ms) != ok {
			t.Errorf("IsValidServerTimeoutMs(%d) = %v", ms, !ok)
		}
		p := &store.Profile{Name: "总部", Domain: "pbx.example.com", BackupServer: "sbc.example.com", ServerTimeoutMs: ms,
			Transport: "UDP", Expires: 3600, Enabled: true}
		got := strings.Join(zh.Msgs(catalog.ProfileProblems(p, nil)), "|")
		if (got == "") != ok || !ok && got != want {
			t.Errorf("profile %d: %q", ms, got)
		}
		in := provision.Input{RefreshMinutes: 60, Settings: provision.DefaultSettings(), Accounts: []provision.KeyedAccount{
			{Key: "p1", Draft: catalog.CredentialDraft(p, store.CredentialInput{Username: "1001"})}}}
		_, problems := provision.Build(in)
		got = strings.Join(zh.Msgs(problems), "|")
		if (got == "") != ok || !ok && !strings.Contains(got, want) {
			t.Errorf("document %d: %q", ms, got)
		}
	}
	noBackup := &store.Profile{Name: "总部", Domain: "pbx.example.com", ServerTimeoutMs: 500, Transport: "UDP", Expires: 3600, Enabled: true}
	if got := catalog.ProfileProblems(noBackup, nil); len(got) > 0 {
		t.Errorf("without a backup server: %v", zh.Msgs(got))
	}
	d, problems := provision.Build(provision.Input{RefreshMinutes: 60, Settings: provision.DefaultSettings(), Accounts: []provision.KeyedAccount{
		{Key: "p1", Draft: catalog.CredentialDraft(noBackup, store.CredentialInput{Username: "1001"})}}})
	if len(problems) > 0 || d.Accounts[0].ServerTimeoutMs != 500 {
		t.Errorf("without a backup server: %v %+v", problems, d.Accounts)
	}
}
