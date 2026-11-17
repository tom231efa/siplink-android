package web

import (
	"net/http"
	"net/url"
	"strings"
	"testing"
	"time"

	"siplink/internal/config"
	"siplink/internal/store"
)

func TestLoginLogout(t *testing.T) {
	h := newHarness(t)
	h.admin("root", store.RoleSuper)
	c := h.client()
	if r := c.get("/"); r.code != http.StatusSeeOther || !strings.HasPrefix(r.location, "/login") {
		t.Fatalf("anonymous dashboard: %d %s", r.code, r.location)
	}
	if r := c.get("/login"); r.code != 200 || !strings.Contains(r.body, "SipLink 管理平台") {
		t.Fatalf("login page: %d", r.code)
	}
	r := c.post("/login", url.Values{"username": {"root"}, "password": {"wrong-password"}})
	if r.code != http.StatusUnauthorized || !strings.Contains(r.body, "用户名或密码不正确") {
		t.Fatalf("wrong password: %d", r.code)
	}
	r = c.post("/login", url.Values{"username": {"ROOT"}, "password": {"password-root"}, "next": {"//evil.example.com"}})
	if r.code != http.StatusSeeOther || r.location != "/" {
		t.Fatalf("login: %d %q", r.code, r.location)
	}
	var cookie *http.Cookie
	for _, ck := range r.header.Values("Set-Cookie") {
		if strings.HasPrefix(ck, SessionCookie+"=") {
			cookie, _ = http.ParseSetCookie(ck)
		}
	}
	if cookie == nil || !cookie.HttpOnly || !cookie.Secure || cookie.SameSite != http.SameSiteLaxMode || cookie.Path != "/" {
		t.Fatalf("session cookie attributes: %+v", cookie)
	}
	if r := c.get("/"); r.code != 200 || !strings.Contains(r.body, "最近操作") || !strings.Contains(r.body, "登录") {
		t.Fatalf("dashboard: %d", r.code)
	}
	if r.header.Get("Strict-Transport-Security") == "" && c.get("/").header.Get("Strict-Transport-Security") == "" {
		t.Error("no HSTS over TLS")
	}
	if r := c.post("/logout", nil); r.code != http.StatusSeeOther {
		t.Fatalf("logout: %d", r.code)
	}
	if r := c.get("/"); r.code != http.StatusSeeOther {
		t.Error("session alive after logout")
	}
	entries, _ := h.st.ListAudit(t.Context(), store.AuditFilter{AllRegions: true})
	actions := map[string]int{}
	for _, e := range entries {
		actions[e.Action]++
	}
	if actions["login.failure"] != 1 || actions["login.success"] != 1 || actions["logout"] != 1 {
		t.Errorf("audit actions %v", actions)
	}
}

func TestLoginLockout(t *testing.T) {
	h := newHarness(t)
	h.admin("root", store.RoleSuper)
	c := h.client()
	for i := 0; i < 5; i++ {
		c.post("/login", url.Values{"username": {"root"}, "password": {"nope"}})
	}
	r := c.post("/login", url.Values{"username": {"root"}, "password": {"password-root"}})
	if r.code != http.StatusTooManyRequests {
		t.Fatalf("locked login: %d", r.code)
	}
	h.clock.Advance(16 * time.Minute)
	c.login("root")
}

func TestCSRFCrossSiteRejected(t *testing.T) {
	h := newHarness(t)
	c, _ := h.loggedIn("root", store.RoleSuper)
	r := c.post("/me", url.Values{"current": {"password-root"}, "password": {"new-password-1"}, "password2": {"new-password-1"}},
		"Sec-Fetch-Site", "cross-site")
	if r.code != http.StatusForbidden || !strings.Contains(r.body, "跨站请求") {
		t.Fatalf("cross-site POST: %d", r.code)
	}
	r = c.post("/logout", nil, "Origin", "https://evil.example.com")
	if r.code != http.StatusForbidden {
		t.Fatalf("foreign Origin: %d", r.code)
	}
	r = c.post("/login", url.Values{"username": {"root"}, "password": {"password-root"}}, "Sec-Fetch-Site", "cross-site")
	if r.code != http.StatusForbidden {
		t.Fatalf("cross-site login: %d", r.code)
	}
	if r := c.get("/"); r.code != 200 {
		t.Error("same-site GET refused")
	}
}

func TestSessionExpiry(t *testing.T) {
	h := newHarness(t)
	c, _ := h.loggedIn("root", store.RoleSuper)
	h.clock.Advance(2*time.Hour + time.Minute)
	if r := c.get("/"); r.code != http.StatusSeeOther {
		t.Fatal("idle session still valid")
	}
	c.login("root")
	for i := 0; i < 11; i++ {
		h.clock.Advance(time.Hour)
		if r := c.get("/"); r.code != 200 {
			t.Fatalf("active session ended after %d h", i+1)
		}
	}
	h.clock.Advance(time.Hour)
	if r := c.get("/"); r.code != http.StatusSeeOther {
		t.Fatal("session older than 12 h still valid")
	}
}

func TestForcedPasswordChange(t *testing.T) {
	h := newHarness(t)
	a := h.admin("root", store.RoleSuper)
	h.st.SetAdminPassword(t.Context(), a.ID, a.PasswordHash, true)
	c := h.client()
	r := c.post("/login", url.Values{"username": {"root"}, "password": {"password-root"}})
	if r.location != "/me" {
		t.Fatalf("must change: went to %q", r.location)
	}
	if r := c.get("/"); r.code != http.StatusSeeOther || r.location != "/me" {
		t.Fatalf("dashboard before change: %d %s", r.code, r.location)
	}
	r = c.post("/me", url.Values{"current": {"password-root"}, "password": {"short"}, "password2": {"short"}})
	if r.code != http.StatusBadRequest || !strings.Contains(r.body, "至少为 10 个字符") {
		t.Fatalf("short password: %d", r.code)
	}
	other := h.client()
	other.login("root")
	r = c.post("/me", url.Values{"current": {"password-root"}, "password": {"a-new-password"}, "password2": {"a-new-password"}})
	if r.code != http.StatusSeeOther || r.location != "/" {
		t.Fatalf("change: %d %s", r.code, r.body)
	}
	if r := c.get("/"); r.code != 200 {
		t.Fatal("this browser lost its session after the change")
	}
	if r := other.get("/"); r.code != http.StatusSeeOther {
		t.Fatal("other session survived the password change")
	}
}

func TestPlainHTTPRefused(t *testing.T) {
	h := newHarness(t)
	h.admin("root", store.RoleSuper)
	plain := newPlainServer(t, h)
	r, err := http.Get(plain + "/login")
	if err != nil {
		t.Fatal(err)
	}
	r.Body.Close()
	if r.StatusCode != http.StatusForbidden {
		t.Fatalf("plain HTTP login page: %d", r.StatusCode)
	}

	h2 := newHarness(t, func(c *config.Config) { c.AllowHTTPProvisioning = true })
	plain2 := newPlainServer(t, h2)
	r, err = http.Get(plain2 + "/login")
	if err != nil {
		t.Fatal(err)
	}
	r.Body.Close()
	if r.StatusCode != 200 {
		t.Fatalf("plain HTTP allowed: %d", r.StatusCode)
	}
}

func TestTrustedProxy(t *testing.T) {
	h := newHarness(t)
	_ = h
	r, _ := http.NewRequest("GET", "/", nil)
	r.RemoteAddr = "192.0.2.5:1234"
	r.Header.Set("X-Forwarded-Proto", "https")
	r.Header.Set("X-Forwarded-For", "203.0.113.9, 192.0.2.6")
	if h.srv.isSecure(r) || h.srv.clientIP(r) != "192.0.2.5" {
		t.Error("untrusted peer believed")
	}
	h.srv.Cfg.TrustedProxies = append(h.srv.Cfg.TrustedProxies, mustPrefix("192.0.2.0/24"))
	if !h.srv.isSecure(r) || h.srv.clientIP(r) != "203.0.113.9" {
		t.Errorf("trusted proxy: secure=%v ip=%s", h.srv.isSecure(r), h.srv.clientIP(r))
	}
}

func TestLogHasNoSecrets(t *testing.T) {
	h := newHarness(t)
	c, _ := h.loggedIn("root", store.RoleSuper)
	c.get("/")
	c.post("/me", url.Values{"current": {"password-root"}, "password": {"secret-new-pass"}, "password2": {"secret-new-pass"}})
	logs := h.logs.String()
	for _, s := range []string{"password-root", "secret-new-pass", SessionCookie} {
		if strings.Contains(logs, s) {
			t.Errorf("log contains %q", s)
		}
	}
	if !strings.Contains(logs, `"route":"POST /login"`) || !strings.Contains(logs, `"route":"GET /{$}"`) {
		t.Errorf("route patterns not logged: %s", logs)
	}
}
