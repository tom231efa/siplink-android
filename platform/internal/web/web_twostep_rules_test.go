package web

import (
	"crypto/sha256"
	"encoding/base32"
	"encoding/hex"
	"net/http"
	"net/url"
	"strings"
	"testing"
	"time"

	"siplink/internal/config"
	"siplink/internal/store"
	"siplink/internal/twostep"
)

// The rules of §6.8 that protect accounts from each other and from stale or crafted input.

func TestSafeNext(t *testing.T) {
	for in, want := range map[string]string{
		"/clients": "/clients", "/clients?q=a%20b#x": "/clients?q=a%20b#x", "/": "/", "": "/",
		"/\t/evil.example/x": "/", "/\t\\evil.example": "/", "/\r\n/evil.example": "/", "/a\nb": "/",
		"/\\evil.example": "/", "//evil.example": "/", "/%2F/evil.example": "/", "/%2f%2fevil.example": "/",
		"https://evil.example": "/", "evil": "/", "/a\\b": "/", "/a\x7fb": "/", "/a\u0085b": "/",
	} {
		if got := safeNext(in); got != want {
			t.Errorf("safeNext(%q) = %q, want %q", in, got, want)
		}
	}
	// Through the login, the second step and the passkey step's JSON answer.
	h := newHarness(t)
	root, _ := h.loggedIn("root", store.RoleSuper)
	key, _ := enableTOTP(t, root)
	k := newSoftKey(t)
	k.register(root, "key")
	h.clock.Advance(time.Minute)
	bad := "/\t/evil.example/x"
	c := h.client()
	if r := c.post("/login", url.Values{"username": {"root"}, "password": {"password-root"}, "next": {bad}}); r.location != "/login/step?next=%2F" {
		t.Errorf("pending redirect %q", r.location)
	}
	if r := c.post("/login/step", url.Values{"method": {"totp"}, "code": {h.totpCode(key)}, "next": {bad}}); r.location != "/" {
		t.Errorf("after the step %q", r.location)
	}
	d := h.client()
	signInPending(t, d, "root")
	if r := k.signIn(d, bad); redirectOf(t, r) != "/" {
		t.Errorf("passkey step redirect %q", redirectOf(t, r))
	}
}

func TestPasswordRecheckLimited(t *testing.T) {
	h := newHarness(t)
	east := h.region("华东")
	f := h.fixture("标准", &east)
	root, _ := h.loggedIn("root", store.RoleSuper)
	id, _ := newClient(t, root, f, "前台", east, "1001")
	for range 5 {
		root.post("/me", url.Values{"current": {"wrong-wrong-wrong"}, "password": {"new-password-1"}, "password2": {"new-password-1"}})
	}
	r := root.post("/me", url.Values{"current": {"password-root"}, "password": {"new-password-1"}, "password2": {"new-password-1"}})
	if r.code != http.StatusBadRequest || !strings.Contains(r.body, "尝试次数过多") {
		t.Errorf("password change after five wrong passwords: %d", r.code)
	}
	if r := root.post("/clients/"+itoa(id)+"/reveal", url.Values{"password": {"password-root"}}); !strings.Contains(r.body, "尝试次数过多") {
		t.Errorf("reveal not limited: %d", r.code)
	}
	h.clock.Advance(16 * time.Minute)
	if r := root.post("/clients/"+itoa(id)+"/reveal", url.Values{"password": {"password-root"}}); r.code != http.StatusSeeOther {
		t.Errorf("reveal after the lock: %d", r.code)
	}
}

func TestSuperAdministratorsProtected(t *testing.T) {
	h := newHarness(t)
	east := h.region("华东")
	srv := smtpFor(t, h, false)
	root, ra := h.loggedIn("root", store.RoleSuper)
	boss, ba := h.loggedIn("boss", store.RoleSuper)
	zhang, za := h.loggedIn("zhang", store.RoleRegion, east)
	root.post("/mail", mailForm(srv, "starttls"))
	boss.post("/me/email", url.Values{"email": {"boss@example.com"}, "password": {"password-boss"}})
	boss.post("/me/email/confirm", url.Values{"code": {lastCode(t, srv, "boss@example.com")}})
	boss.post("/me/email-code/on", nil)

	form := func(email, was, role string) url.Values {
		v := url.Values{"email": {email}, "email_was": {was}, "role": {role}}
		if role == store.RoleRegion {
			v.Set("regions", itoa(east))
		}
		return v
	}
	if page := root.get("/admins/" + itoa(ba.ID)); !strings.Contains(page.body, `name="email" type="email" value="boss@example.com" maxlength="254" autocomplete="off" disabled`) {
		t.Error("a super administrator's address is editable on the admin page")
	}
	for _, target := range []int64{ba.ID, ra.ID} {
		r := root.post("/admins/"+itoa(target), form("other@example.com", "", store.RoleSuper))
		if r.code != http.StatusBadRequest || !strings.Contains(r.body, "超级管理员的邮箱只能由本人在「我的账号」中修改") {
			t.Errorf("address of super administrator %d changed: %d", target, r.code)
		}
	}
	if ts, _ := h.st.TwoStepState(t.Context(), ba.ID); !ts.Email {
		t.Fatal("boss lost the e-mail method")
	}
	// Demote → reset → promote is closed: no role change while a method is on.
	r := root.post("/admins/"+itoa(ba.ID), form("boss@example.com", "boss@example.com", store.RoleRegion))
	if r.code != http.StatusBadRequest || !strings.Contains(r.body, "已启用两步验证的超级管理员不可修改角色") {
		t.Errorf("demotion: %d", r.code)
	}
	if got, _ := h.st.AdminByID(t.Context(), ba.ID); got.Role != store.RoleSuper {
		t.Fatal("boss demoted")
	}
	// An unchanged form leaves the stored address alone; a changed one is written for a
	// region administrator.
	r = root.post("/admins/"+itoa(ba.ID), form("", "", store.RoleSuper)) // a stale form shown before the address
	if got, _ := h.st.AdminByID(t.Context(), ba.ID); r.code != http.StatusSeeOther || got.Email != "boss@example.com" || !got.EmailConfirmed {
		t.Errorf("stale form: %d %+v", r.code, got)
	}
	h.clock.Advance(2 * time.Minute)
	zhang.post("/me/email", url.Values{"email": {"zhang@example.com"}, "password": {"password-zhang"}})
	zhang.post("/me/email/confirm", url.Values{"code": {lastCode(t, srv, "zhang@example.com")}})
	root.post("/admins/"+itoa(za.ID), form("", "", store.RoleRegion))
	if got, _ := h.st.AdminByID(t.Context(), za.ID); got.Email != "zhang@example.com" || !got.EmailConfirmed {
		t.Errorf("stale form overwrote zhang's address: %+v", got)
	}
	root.post("/admins/"+itoa(za.ID), form("zhang2@example.com", "zhang@example.com", store.RoleRegion))
	if got, _ := h.st.AdminByID(t.Context(), za.ID); got.Email != "zhang2@example.com" || got.EmailConfirmed {
		t.Errorf("region administrator's address not changed: %+v", got)
	}
}

func TestMailErrorsStayOnTheMailPage(t *testing.T) {
	h := newHarness(t)
	srv := smtpFor(t, h, false)
	root, _ := h.loggedIn("root", store.RoleSuper)
	root.post("/mail", mailForm(srv, "starttls"))
	root.post("/me/email", url.Values{"email": {"root@example.com"}, "password": {"password-root"}})
	root.post("/me/email/confirm", url.Values{"code": {lastCode(t, srv, "root@example.com")}})
	root.post("/me/email-code/on", nil)
	dead := mailForm(srv, "starttls")
	dead.Set("port", "1")
	root.post("/mail", dead)
	h.clock.Advance(2 * time.Minute)
	c := h.client()
	signInPending(t, c, "root")
	c.post("/login/step/email", nil)
	page := c.get("/login/step")
	if !strings.Contains(page.body, "邮件发送失败。") || strings.Contains(page.body, "127.0.0.1") || strings.Contains(page.body, "连接失败") {
		t.Error("SMTP detail shown outside the 邮件 page")
	}
	if !strings.Contains(h.logs.String(), "e-mail code not sent") {
		t.Error("cause not logged")
	}
	if r := root.post("/mail/test", url.Values{"to": {"ops@example.com"}}); !strings.Contains(r.body, "连接失败") {
		t.Error("detail missing on the 邮件 test")
	}
}

func TestMailPasswordFollowsServer(t *testing.T) {
	h := newHarness(t)
	srv := smtpFor(t, h, true)
	root, _ := h.loggedIn("root", store.RoleSuper)
	root.post("/mail", mailForm(srv, "tls"))
	row, _ := h.st.MailSettings(t.Context())
	for _, change := range []func(url.Values){
		func(v url.Values) { v.Set("host", "smtp.example.net") },
		func(v url.Values) { v.Set("port", "2525") },
		func(v url.Values) { v.Set("security", "starttls") },
	} {
		v := mailForm(srv, "tls")
		v.Set("password", "")
		change(v)
		if r := root.post("/mail", v); r.code != http.StatusBadRequest || !strings.Contains(r.body, "须重新输入密码") {
			t.Errorf("server changed without the password: %d", r.code)
		}
	}
	if r2, _ := h.st.MailSettings(t.Context()); r2.Host != row.Host || r2.PasswordEnc != row.PasswordEnc {
		t.Error("settings changed by a refused save")
	}
	v := mailForm(srv, "tls")
	v.Set("host", "localhost")
	if r := root.post("/mail", v); r.code != http.StatusSeeOther {
		t.Errorf("with the password typed again: %d", r.code)
	}
	v.Set("host", "smtp.example.net")
	v.Set("password", "")
	v.Set("username", "")
	if r := root.post("/mail", v); r.code != http.StatusSeeOther {
		t.Errorf("without a user name: %d", r.code)
	}
}

func TestFirstMethodEndsOtherSessions(t *testing.T) {
	h := newHarness(t)
	root, _ := h.loggedIn("root", store.RoleSuper)
	other := h.client()
	other.login("root")
	enableTOTP(t, root)
	if r := other.get("/"); r.code != http.StatusSeeOther || !strings.HasPrefix(r.location, "/login") {
		t.Errorf("other session alive after the first method: %d", r.code)
	}
	if r := root.get("/"); r.code != 200 {
		t.Errorf("current session ended: %d", r.code)
	}
}

func TestUnknownPathWhileHeld(t *testing.T) {
	h := newHarness(t)
	east := h.region("华东")
	root, _ := h.loggedIn("root", store.RoleSuper)
	zhang, _ := h.loggedIn("zhang", store.RoleRegion, east)
	root.post("/system/two-step", url.Values{"required": {"1"}})
	li := h.admin("li", store.RoleSuper)
	h.st.SetAdminPassword(t.Context(), li.ID, li.PasswordHash, true)
	c := h.client()
	c.post("/login", url.Values{"username": {"li"}, "password": {"password-li"}})
	for name, cl := range map[string]*client{"set-up": zhang, "password change": c} {
		if r := cl.get("/no/such/page"); r.code != http.StatusNotFound || strings.Contains(r.body, "<html") {
			t.Errorf("%s: %d framed=%v", name, r.code, strings.Contains(r.body, "<html"))
		}
	}
	if r := root.get("/no/such/page"); !strings.Contains(r.body, `<main class="content">`) {
		t.Error("free administrator lost the framed page")
	}
}

func TestTOTPSetUpRules(t *testing.T) {
	h := newHarness(t)
	root, a := h.loggedIn("root", store.RoleSuper)
	secretOf := func() []byte {
		root.post("/me/totp/new", nil)
		m := totpSecretRe.FindStringSubmatch(root.get("/me/totp").body)
		key, _ := base32.StdEncoding.WithPadding(base32.NoPadding).DecodeString(m[1])
		return key
	}
	first := secretOf()
	second := secretOf() // started again: the first secret is gone
	if r := root.post("/me/totp/confirm", url.Values{"code": {h.totpCode(first)}}); r.code == http.StatusSeeOther && !strings.HasPrefix(r.location, "/me/totp") {
		t.Fatalf("code of a replaced secret confirmed: %s", r.location)
	}
	// A confirmation names the secret it checked.
	row, _ := h.st.TOTP(t.Context(), a.ID)
	if err := h.st.ConfirmTOTP(t.Context(), a.ID, 1, "another-sealed-secret"); err == nil {
		t.Error("confirmed a secret that was not checked")
	}
	_ = second
	// 取消 drops the pending secret.
	if r := root.post("/me/totp/cancel", nil); r.location != "/me#two-step" {
		t.Errorf("cancel: %s", r.location)
	}
	if _, err := h.st.TOTP(t.Context(), a.ID); err == nil {
		t.Errorf("pending secret %q kept after 取消", row.SecretEnc[:8])
	}
}

// TestCodeHashesKeyed: stored codes are HMACs under the platform's code key, bound to the
// administrator; without that key (a restore without the key file) they do not verify.
func TestCodeHashesKeyed(t *testing.T) {
	h := newHarness(t)
	root, a := h.loggedIn("root", store.RoleSuper)
	_, codes := enableTOTP(t, root)
	var stored []string
	rows, _ := h.st.DB.Query(`SELECT code_hash FROM admin_recovery_codes WHERE admin_id = ?`, a.ID)
	for rows.Next() {
		var s string
		rows.Scan(&s)
		stored = append(stored, s)
	}
	rows.Close()
	plain := sha256.Sum256([]byte(strings.ReplaceAll(codes[0], "-", "")))
	for _, s := range stored {
		if s == hex.EncodeToString(plain[:]) {
			t.Fatal("recovery code stored as a plain SHA-256")
		}
	}
	if !contains(stored, twostep.RecoveryHash(h.srv.Ext.CodeKey, a.ID, codes[0])) {
		t.Fatal("stored hash is not the keyed one")
	}
	good := h.srv.Ext.CodeKey
	h.srv.Ext.CodeKey[0] ^= 1
	c := h.client()
	signInPending(t, c, "root")
	if r := c.post("/login/step", url.Values{"method": {"recovery"}, "code": {codes[0]}}); r.code != http.StatusUnauthorized {
		t.Errorf("recovery code verified under another key: %d", r.code)
	}
	h.srv.Ext.CodeKey = good
	if r := c.post("/login/step", url.Values{"method": {"recovery"}, "code": {codes[0]}}); r.code != http.StatusSeeOther {
		t.Errorf("recovery code under the right key: %d", r.code)
	}
}

func contains(list []string, v string) bool {
	for _, s := range list {
		if s == v {
			return true
		}
	}
	return false
}

// TestPasskeysUpperCaseHost: the RP ID and origin are the lower-case host.
func TestPasskeysUpperCaseHost(t *testing.T) {
	h := newHarness(t, func(c *config.Config) {
		c.PublicURL, c.PublicHost = "https://Platform.Example.com", "Platform.Example.com"
	})
	root, _ := h.loggedIn("root", store.RoleSuper)
	if r := newSoftKey(t).register(root, "key"); r.code != 200 {
		t.Errorf("register at an upper-case public URL: %d %s", r.code, r.body)
	}
}
