package web

import (
	"net"
	"net/http"
	"net/url"
	"regexp"
	"strings"
	"testing"
	"time"

	"siplink/internal/store"
	"siplink/internal/testutil"
)

// The 邮件 page and the e-mail code method (design §6.8), against an in-process SMTP
// server with a throwaway certificate.

var mailCodeRe = regexp.MustCompile(`\b(\d{6})\b`)

// smtpFor starts a fake SMTP server and makes the platform trust it.
func smtpFor(t *testing.T, h *harness, implicitTLS bool) *testutil.SMTPServer {
	srv := testutil.NewSMTPServer(t, implicitTLS)
	h.srv.Mailer.RootCAs = srv.Roots
	h.srv.Mailer.Timeout = 5 * time.Second
	return srv
}

func mailForm(srv *testutil.SMTPServer, security string) url.Values {
	_, port, _ := net.SplitHostPort(srv.Addr)
	return url.Values{"host": {srv.Host()}, "port": {port}, "security": {security}, "username": {srv.User},
		"password": {srv.Password}, "from_address": {"noreply@example.com"}, "from_name": {"SipLink 平台"}}
}

// lastCode is the code in the newest mail to an address.
func lastCode(t *testing.T, srv *testutil.SMTPServer, to string) string {
	t.Helper()
	mails := srv.Mails()
	for i := len(mails) - 1; i >= 0; i-- {
		if mails[i].To == to {
			if m := mailCodeRe.FindStringSubmatch(mails[i].Body); m != nil {
				return m[1]
			}
		}
	}
	t.Fatalf("no code mailed to %s", to)
	return ""
}

func TestMailPage(t *testing.T) {
	h := newHarness(t)
	east := h.region("华东")
	srv := smtpFor(t, h, true)
	root, _ := h.loggedIn("root", store.RoleSuper)
	zhang, _ := h.loggedIn("zhang", store.RoleRegion, east)
	for _, r := range []resp{zhang.get("/mail"), zhang.post("/mail", mailForm(srv, "tls")), zhang.post("/mail/test", url.Values{"to": {"a@example.com"}})} {
		if r.code != http.StatusNotFound {
			t.Errorf("region administrator on /mail: %d", r.code)
		}
	}
	if strings.Contains(zhang.get("/clients").body, `href="/mail"`) {
		t.Error("邮件 in a region administrator's navigation")
	}
	if r := root.get("/mail"); r.code != 200 || !strings.Contains(r.body, "未设置") {
		t.Fatalf("mail page: %d", r.code)
	}
	bad := mailForm(srv, "tls")
	bad.Set("from_address", "not an address")
	if r := root.post("/mail", bad); r.code != http.StatusBadRequest || !strings.Contains(r.body, "发件地址无效") {
		t.Fatalf("bad sender: %d", r.code)
	}
	if r := root.post("/mail", mailForm(srv, "tls")); r.code != http.StatusSeeOther {
		t.Fatalf("save: %d %s", r.code, r.body)
	}
	page := root.get("/mail")
	if !strings.Contains(page.body, "已设置") || strings.Contains(page.body, srv.Password) {
		t.Error("password not write-only")
	}
	row, _ := h.st.MailSettings(t.Context())
	if row.PasswordEnc == "" || strings.Contains(row.PasswordEnc, srv.Password) {
		t.Fatal("password not sealed")
	}
	// An empty password field keeps the password; an empty user name drops it.
	keep := mailForm(srv, "tls")
	keep.Set("password", "")
	root.post("/mail", keep)
	if r2, _ := h.st.MailSettings(t.Context()); r2.PasswordEnc != row.PasswordEnc {
		t.Error("empty field changed the password")
	}
	if r := root.post("/mail/test", url.Values{"to": {"ops@example.com"}}); r.code != http.StatusSeeOther {
		t.Fatalf("test mail: %d %s", r.code, r.body)
	}
	if m := srv.Mails(); len(m) != 1 || m[0].To != "ops@example.com" || !m[0].TLS || !m[0].Authed {
		t.Fatalf("mails %+v", m)
	}
	srv.RejectRcpt = "nobody@example.com"
	if r := root.post("/mail/test", url.Values{"to": {"nobody@example.com"}}); r.code != 200 || !strings.Contains(r.body, "550") {
		t.Errorf("server's answer not shown: %d", r.code)
	}
	drop := mailForm(srv, "tls")
	drop.Set("username", "")
	root.post("/mail", drop)
	if r2, _ := h.st.MailSettings(t.Context()); r2.PasswordEnc != "" {
		t.Error("password kept without a user name")
	}
	entries, _ := h.st.ListAudit(t.Context(), store.AuditFilter{AllRegions: true, Action: "mail."})
	if len(entries) != 5 {
		t.Errorf("%d mail entries", len(entries))
	}
	for _, e := range entries {
		if strings.Contains(e.DetailJSON, srv.Password) {
			t.Error("password in the audit trail")
		}
	}
	if entries[len(entries)-1].Detail["password"] != "已修改" {
		t.Errorf("first save: %v", entries[len(entries)-1].Detail)
	}
	if strings.Contains(h.logs.String(), srv.Password) {
		t.Error("password in the log")
	}
}

func TestEmailCodeSignIn(t *testing.T) {
	h := newHarness(t)
	srv := smtpFor(t, h, false) // STARTTLS
	root, a := h.loggedIn("root", store.RoleSuper)
	if r := root.get("/me"); !strings.Contains(r.body, "邮件服务未配置") {
		t.Error("e-mail method offered without SMTP")
	}
	root.post("/mail", mailForm(srv, "starttls"))
	// The address needs the password and a confirmation by code.
	if r := root.post("/me/email", url.Values{"email": {"root@example.com"}, "password": {"wrong"}}); r.code != http.StatusSeeOther {
		t.Fatal(r.code)
	}
	if got, _ := h.st.AdminByID(t.Context(), a.ID); got.Email != "" {
		t.Fatal("address saved with a wrong password")
	}
	h.stepReset(t)
	root.post("/me/email", url.Values{"email": {"root@example.com"}, "password": {"password-root"}})
	if r := root.post("/me/email-code/on", nil); r.location != "/me#two-step" {
		t.Errorf("method on before confirmation: %s", r.location)
	}
	if ts, _ := h.st.TwoStepState(t.Context(), a.ID); ts.Email {
		t.Fatal("e-mail method on with an unconfirmed address")
	}
	code := lastCode(t, srv, "root@example.com")
	if m := srv.Mails(); !strings.Contains(m[0].Body, "10 分钟内有效") || m[0].Subject != "SipLink 管理平台验证码" || !m[0].TLS {
		t.Errorf("mail %+v", m[0])
	}
	root.post("/me/email/confirm", url.Values{"code": {code}})
	if got, _ := h.st.AdminByID(t.Context(), a.ID); !got.EmailConfirmed {
		t.Fatal("address not confirmed")
	}
	r := root.post("/me/email-code/on", nil)
	if !strings.HasPrefix(r.location, "/me/recovery?t=") {
		t.Fatalf("method on: %s", r.location)
	}

	h.clock.Advance(2 * time.Minute)
	c := h.client()
	signInPending(t, c, "root")
	if page := c.get("/login/step"); !strings.Contains(page.body, "r***@example.com") || strings.Contains(page.body, "root@example.com") {
		t.Error("masked address")
	}
	if r := c.post("/login/step/email", url.Values{"next": {"/clients"}}); !strings.Contains(r.location, "/login/step?next=%2Fclients") {
		t.Fatalf("send: %s", r.location)
	}
	sent := len(srv.Mails())
	c.post("/login/step/email", nil)
	if len(srv.Mails()) != sent {
		t.Error("second code within a minute")
	}
	code = lastCode(t, srv, "root@example.com")
	if r := c.post("/login/step", url.Values{"method": {"email"}, "code": {code}, "next": {"/clients"}}); r.code != http.StatusSeeOther || r.location != "/clients" {
		t.Fatalf("e-mail step: %d %s", r.code, r.location)
	}
	// A code works once.
	d := h.client()
	signInPending(t, d, "root")
	if r := d.post("/login/step", url.Values{"method": {"email"}, "code": {code}}); r.code != http.StatusUnauthorized {
		t.Errorf("code used twice: %d", r.code)
	}
	// Expiry after 10 minutes.
	h.clock.Advance(2 * time.Minute)
	d.post("/login/step/email", nil)
	code = lastCode(t, srv, "root@example.com")
	h.clock.Advance(10*time.Minute + time.Second)
	e := h.client()
	signInPending(t, e, "root")
	if r := e.post("/login/step", url.Values{"method": {"email"}, "code": {code}}); r.code != http.StatusUnauthorized {
		t.Errorf("expired code: %d", r.code)
	}
	if strings.Contains(h.logs.String(), code) {
		t.Error("code in the log")
	}
	// Changing the address switches the method off.
	h.stepReset(t)
	root.post("/me/email", url.Values{"email": {"other@example.com"}, "password": {"password-root"}})
	if ts, _ := h.st.TwoStepState(t.Context(), a.ID); ts.Email || ts.RecoveryLeft != 0 {
		t.Errorf("after the address changed: %+v", ts)
	}
}

func TestEmailCodeLimits(t *testing.T) {
	h := newHarness(t)
	srv := smtpFor(t, h, true)
	root, a := h.loggedIn("root", store.RoleSuper)
	root.post("/mail", mailForm(srv, "tls"))
	root.post("/me/email", url.Values{"email": {"root@example.com"}, "password": {"password-root"}})
	for i := range 6 {
		h.clock.Advance(61 * time.Second)
		r := root.post("/me/email/send", nil)
		if i < 4 && !strings.Contains(r.header.Get("Set-Cookie"), "siplink_flash=ok") {
			t.Errorf("send %d refused", i)
		}
	}
	if n := len(srv.Mails()); n != 5 {
		t.Errorf("%d codes in an hour, want 5", n)
	}
	// Five wrong tries void a code.
	code := lastCode(t, srv, "root@example.com")
	for range 5 {
		root.post("/me/email/confirm", url.Values{"code": {"000000"}})
	}
	root.post("/me/email/confirm", url.Values{"code": {code}})
	if got, _ := h.st.AdminByID(t.Context(), a.ID); got.EmailConfirmed {
		t.Error("code accepted after five wrong tries")
	}
}

func TestEmailCodeStore(t *testing.T) {
	h := newHarness(t)
	a := h.admin("root", store.RoleSuper)
	ctx := t.Context()
	put := func() {
		h.st.PutEmailCode(ctx, &store.EmailCode{AdminID: a.ID, Purpose: "login", CodeHash: "aa", Address: "x@example.com",
			ExpiresAt: h.clock.Now().Add(10 * time.Minute).UTC().Format(store.TimeFormat)})
	}
	put()
	for i := range 4 {
		if _, ok, _ := h.st.CheckEmailCode(ctx, a.ID, "login", "bb", 5); ok {
			t.Fatalf("wrong hash accepted at try %d", i)
		}
	}
	if addr, ok, _ := h.st.CheckEmailCode(ctx, a.ID, "login", "aa", 5); !ok || addr != "x@example.com" {
		t.Fatal("right code after four misses refused")
	}
	if _, ok, _ := h.st.CheckEmailCode(ctx, a.ID, "login", "aa", 5); ok {
		t.Fatal("code used twice")
	}
	put()
	for range 5 {
		h.st.CheckEmailCode(ctx, a.ID, "login", "bb", 5)
	}
	if _, ok, _ := h.st.CheckEmailCode(ctx, a.ID, "login", "aa", 5); ok {
		t.Fatal("code alive after five misses")
	}
	put()
	h.clock.Advance(10 * time.Minute)
	if _, ok, _ := h.st.CheckEmailCode(ctx, a.ID, "login", "aa", 5); ok {
		t.Fatal("expired code accepted")
	}
}
