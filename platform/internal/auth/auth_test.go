package auth_test

import (
	"context"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	. "siplink/internal/auth"
	"siplink/internal/store"
	"siplink/internal/testutil"
)

func TestPasswordDefaultParams(t *testing.T) {
	h, err := HashPassword("correct horse battery")
	if err != nil {
		t.Fatal(err)
	}
	if !strings.HasPrefix(h, "$argon2id$v=19$m=65536,t=3,p=2$") {
		t.Errorf("PHC prefix: %s", h)
	}
	if ok, err := VerifyPassword(context.Background(), h, "correct horse battery"); !ok || err != nil {
		t.Error("right password refused")
	}
	if ok, _ := VerifyPassword(context.Background(), h, "correct horse batterY"); ok {
		t.Error("wrong password accepted")
	}
	for _, bad := range []string{"", "$argon2i$v=19$m=1,t=1,p=1$AA$AA", "$argon2id$v=19$m=x$AA$AA", "plain"} {
		if _, err := VerifyPassword(context.Background(), bad, "x"); err == nil {
			t.Errorf("malformed %q accepted", bad)
		}
	}
	h2, _ := HashPassword("correct horse battery")
	if h == h2 {
		t.Error("salt not random")
	}
}

func TestPasswordRules(t *testing.T) {
	if PasswordProblem("short", "a", true) == nil || PasswordProblem("adminadmin", "adminadmin", true) == nil {
		t.Error("weak password accepted")
	}
	if PasswordProblem("一二三四五六七八九十", "admin", true) != nil {
		t.Error("10 Chinese characters refused")
	}
	// without the strength check: non-empty and at most 256 characters
	if PasswordProblem("a", "a", false) != nil || PasswordProblem("", "a", false) == nil ||
		PasswordProblem(strings.Repeat("x", 257), "a", false) == nil || PasswordProblem(strings.Repeat("x", 257), "a", true) == nil {
		t.Error("password rules without the strength check")
	}
	for name, want := range map[string]bool{"admin": true, "a.b_c-d": true, "a": false, "管理员": false, "a b": false,
		strings.Repeat("x", 33): false} {
		if ValidUsername(name) != want {
			t.Errorf("ValidUsername(%q) = %v", name, !want)
		}
	}
}

func TestLoginLimiter(t *testing.T) {
	clock := &testutil.Clock{T: time.Unix(1_000_000, 0)}
	l := NewLoginLimiter(clock.Now)
	for i := 0; i < 5; i++ {
		if !l.Attempt("alice", "192.0.2.1") {
			t.Fatalf("attempt %d refused", i)
		}
	}
	if l.Attempt("alice", "192.0.2.1") {
		t.Error("6th attempt allowed")
	}
	if !l.Attempt("alice", "192.0.2.2") || !l.Attempt("bob", "192.0.2.1") {
		t.Error("lockout leaked to another address or name")
	}
	clock.Advance(14 * time.Minute)
	if l.Attempt("alice", "192.0.2.1") {
		t.Error("lock ended early")
	}
	clock.Advance(2 * time.Minute)
	if !l.Attempt("alice", "192.0.2.1") {
		t.Error("lock did not end after 15 minutes")
	}
	// 30 attempts per address per minute.
	for i := 0; i < 30; i++ {
		if !l.Attempt("user"+string(rune('a'+i%26)), "192.0.2.9") {
			t.Fatalf("attempt %d refused", i)
		}
	}
	if l.Attempt("zed", "192.0.2.9") {
		t.Error("31st attempt in a minute allowed")
	}
	clock.Advance(time.Minute)
	if !l.Attempt("zed", "192.0.2.9") {
		t.Error("per-address window did not roll over")
	}
}

// TestWindowTake: parallel attempts are counted before any of them is checked; a good
// attempt gives its slot back.
func TestWindowTake(t *testing.T) {
	clock := &testutil.Clock{T: time.Unix(1_000_000, 0)}
	w := NewWindow(5, 15*time.Minute, clock.Now)
	var ok atomic.Int32
	var wg sync.WaitGroup
	for range 100 {
		wg.Add(1)
		go func() {
			defer wg.Done()
			if w.Take("k") {
				ok.Add(1)
			}
		}()
	}
	wg.Wait()
	if ok.Load() != 5 {
		t.Fatalf("%d of 100 parallel attempts passed, want 5", ok.Load())
	}
	clock.Advance(16 * time.Minute)
	for range 3 {
		w.Take("k")
	}
	w.Release("k")
	w.Release("k")
	for range 4 {
		if !w.Take("k") {
			t.Fatal("released slots not given back")
		}
	}
	if w.Take("k") {
		t.Error("sixth slot")
	}
	clock.Advance(14 * time.Minute)
	if w.Take("k") {
		t.Error("lock ended before a full period after the attempt that reached the limit")
	}
	clock.Advance(2 * time.Minute)
	if !w.Take("k") {
		t.Error("lock did not end")
	}
}

func TestSessions(t *testing.T) {
	// Restored afterwards: TestPasswordDefaultParams of a repeated run (-count) needs the defaults.
	defaults := HashParams
	t.Cleanup(func() { HashParams = defaults })
	testutil.FastHashes()
	ctx := context.Background()
	st := testutil.Store(t)
	clock := &testutil.Clock{T: time.Date(2026, 11, 16, 8, 0, 0, 0, time.UTC)}
	st.Now = clock.Now
	h, _ := HashPassword("long enough password")
	a := &store.Admin{Username: "root", PasswordHash: h, Role: store.RoleSuper}
	if err := st.CreateAdmin(ctx, a); err != nil {
		t.Fatal(err)
	}
	m := &Sessions{St: st, Now: clock.Now}
	tok, err := m.Create(ctx, a.ID, "192.0.2.1", "test")
	if err != nil {
		t.Fatal(err)
	}
	if got, _, err := m.Check(ctx, tok); err != nil || got.ID != a.ID {
		t.Fatalf("check: %v", err)
	}
	if _, _, err := m.Check(ctx, tok+"x"); err != ErrNoSession {
		t.Error("wrong token accepted")
	}
	// Idle: active every 90 minutes keeps it alive until the absolute 12 hours.
	for i := 0; i < 7; i++ {
		clock.Advance(90 * time.Minute)
		if _, _, err := m.Check(ctx, tok); err != nil {
			t.Fatalf("idle refresh %d: %v", i, err)
		}
	}
	clock.Advance(90 * time.Minute) // 12 h after creation
	if _, _, err := m.Check(ctx, tok); err != ErrNoSession {
		t.Error("absolute timeout not enforced")
	}
	tok2, _ := m.Create(ctx, a.ID, "", "")
	clock.Advance(2*time.Hour + time.Second)
	if _, _, err := m.Check(ctx, tok2); err != ErrNoSession {
		t.Error("idle timeout not enforced")
	}
	// Disabled administrators lose their sessions.
	tok3, _ := m.Create(ctx, a.ID, "", "")
	b := &store.Admin{Username: "other", PasswordHash: h, Role: store.RoleSuper}
	st.CreateAdmin(ctx, b)
	a.Disabled = true
	if err := st.UpdateAdmin(ctx, a); err != nil {
		t.Fatal(err)
	}
	if _, _, err := m.Check(ctx, tok3); err != ErrNoSession {
		t.Error("session of a disabled admin still valid")
	}
}
