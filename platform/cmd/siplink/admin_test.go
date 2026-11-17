package main

import (
	"bytes"
	"context"
	"path/filepath"
	"strings"
	"testing"

	"siplink/internal/store"
	"siplink/internal/testutil"
)

// TestAdminResetTwoStep: "siplink admin reset-2fa" removes every method, the recovery
// and e-mail codes and the sessions of a (super) administrator (design §6.8).
func TestAdminResetTwoStep(t *testing.T) {
	testutil.FastHashes()
	dir := t.TempDir()
	t.Setenv("SIPLINK_PUBLIC_URL", "https://platform.example.com")
	t.Setenv("SIPLINK_DATA_DIR", dir)
	t.Setenv("SIPLINK_KEY_FILE", filepath.Join(dir, "key"))
	t.Setenv("SIPLINK_LOG_LEVEL", "error")
	var out bytes.Buffer
	if err := adminCommand([]string{"create", "--super", "root"}, &out); err != nil {
		t.Fatal(err)
	}
	ctx := context.Background()
	st, err := store.Open(ctx, filepath.Join(dir, store.FileName), nil)
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()
	a, _ := st.AdminByUsername(ctx, "root")
	must := func(err error) {
		t.Helper()
		if err != nil {
			t.Fatal(err)
		}
	}
	must(st.PutPendingTOTP(ctx, a.ID, "sealed"))
	must(st.ConfirmTOTP(ctx, a.ID, 1, "sealed"))
	must(st.AddPasskey(ctx, &store.Passkey{AdminID: a.ID, Name: "key", CredentialID: "abc", CredentialJSON: "{}"}))
	must(st.ReplaceRecoveryCodes(ctx, a.ID, []string{"h1", "h2"}))
	must(st.SetAdminEmail(ctx, a.ID, "root@example.com", nil))
	must(st.ConfirmAdminEmail(ctx, a.ID, "root@example.com"))
	must(st.EnableTwoStepEmail(ctx, a.ID))
	must(st.CreateSession(ctx, &store.Session{TokenHash: "t", AdminID: a.ID, CreatedAt: st.NowText(), ExpiresAt: "2999-01-01T00:00:00Z", LastSeenAt: st.NowText()}))
	if ts, _ := st.TwoStepState(ctx, a.ID); ts.Methods() != 3 {
		t.Fatalf("set-up: %+v", ts)
	}

	out.Reset()
	if err := adminCommand([]string{"reset-2fa", "ROOT"}, &out); err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(out.String(), "已重置 root 的两步验证") {
		t.Errorf("output %q", out.String())
	}
	ts, _ := st.TwoStepState(ctx, a.ID)
	got, _ := st.AdminByID(ctx, a.ID)
	if _, err := st.TOTP(ctx, a.ID); ts.Methods() != 0 || ts.RecoveryLeft != 0 || err == nil || got.EmailConfirmed || got.Email != "root@example.com" {
		t.Errorf("after reset: %+v %+v", ts, got)
	}
	if _, err := st.SessionByHash(ctx, "t"); err == nil {
		t.Error("session survived the reset")
	}
	entries, _ := st.ListAudit(ctx, store.AuditFilter{AllRegions: true, Action: "two_step.reset"})
	if len(entries) != 1 || entries[0].AdminName != "CLI" {
		t.Errorf("audit %+v", entries)
	}
	if err := adminCommand([]string{"reset-2fa", "nobody"}, &out); err == nil {
		t.Error("unknown administrator accepted")
	}
}
