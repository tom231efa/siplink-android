package web

import (
	"encoding/base32"
	"net/http"
	"net/url"
	"regexp"
	"strings"
	"testing"
	"time"

	"siplink/internal/store"
	"siplink/internal/twostep"
)

// Two-step sign-in (design §6.8): TOTP, recovery codes, the pending session, the
// policy, the reset and the limits.

var (
	totpSecretRe   = regexp.MustCompile(`id="totp-secret">([A-Z2-7]+)<`)
	recoveryCodeRe = regexp.MustCompile(`<li>([a-z2-9]{5}-[a-z2-9]{5})</li>`)
)

// totpCode is the current code of a secret at the harness clock.
func (h *harness) totpCode(key []byte) string {
	return twostep.TOTPCode(key, twostep.TOTPStep(h.clock.Now()), twostep.TOTPDigits)
}

// enableTOTP switches TOTP on for the signed-in client and returns the key and, for a
// first method, the recovery codes shown once.
func enableTOTP(t *testing.T, c *client) ([]byte, []string) {
	t.Helper()
	if r := c.post("/me/totp/new", nil); r.code != http.StatusSeeOther || r.location != "/me/totp" {
		t.Fatalf("totp new: %d %s", r.code, r.location)
	}
	page := c.get("/me/totp")
	m := totpSecretRe.FindStringSubmatch(page.body)
	if page.code != 200 || m == nil || !strings.Contains(page.body, "<svg") {
		t.Fatalf("totp page: %d", page.code)
	}
	key, err := base32.StdEncoding.WithPadding(base32.NoPadding).DecodeString(m[1])
	if err != nil {
		t.Fatal(err)
	}
	if r := c.post("/me/totp/confirm", url.Values{"code": {"abcdef"}}); r.code != http.StatusBadRequest || !strings.Contains(r.body, "验证码不正确") {
		t.Fatalf("wrong code: %d", r.code)
	}
	c.h.stepReset(t)
	r := c.post("/me/totp/confirm", url.Values{"code": {c.h.totpCode(key)}})
	if r.code != http.StatusSeeOther {
		t.Fatalf("confirm: %d %s", r.code, r.body)
	}
	if !strings.HasPrefix(r.location, "/me/recovery?t=") {
		return key, nil
	}
	return key, recoveryCodes(t, c, r.location)
}

// stepReset forgets counted failures (the tests that count them make their own).
func (h *harness) stepReset(t *testing.T) {
	all, err := h.st.ListAdmins(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	for _, a := range all {
		h.srv.stepFails.Reset(itoa(a.ID))
	}
}

func recoveryCodes(t *testing.T, c *client, location string) []string {
	t.Helper()
	r := c.get(location)
	var codes []string
	for _, m := range recoveryCodeRe.FindAllStringSubmatch(r.body, -1) {
		codes = append(codes, m[1])
	}
	if r.code != 200 || len(codes) != twostep.RecoveryCount {
		t.Fatalf("recovery page: %d, %d codes", r.code, len(codes))
	}
	// A reload shows them again; "完成" drops them.
	if again := c.get(location); again.code != 200 || !strings.Contains(again.body, codes[0]) {
		t.Fatal("recovery codes lost on a reload")
	}
	token := strings.TrimPrefix(location, "/me/recovery?t=")
	if tok, _ := url.QueryUnescape(token); c.post("/me/recovery/done", url.Values{"t": {tok}}).code != http.StatusSeeOther {
		t.Fatal("完成")
	}
	if again := c.get(location); again.code != http.StatusSeeOther || recoveryCodeRe.MatchString(again.body) {
		t.Fatal("recovery codes shown after 完成")
	}
	return codes
}

// signInPending posts the password and expects the second step.
func signInPending(t *testing.T, c *client, name string) {
	t.Helper()
	r := c.post("/login", url.Values{"username": {name}, "password": {"password-" + name}, "next": {"/clients"}})
	if r.code != http.StatusSeeOther || r.location != "/login/step?next=%2Fclients" {
		t.Fatalf("password step: %d %q", r.code, r.location)
	}
}

func auditActions(t *testing.T, h *harness) map[string]int {
	entries, _ := h.st.ListAudit(t.Context(), store.AuditFilter{AllRegions: true, Limit: 500})
	out := map[string]int{}
	for _, e := range entries {
		out[e.Action]++
	}
	return out
}

func TestTOTPSignIn(t *testing.T) {
	h := newHarness(t)
	root, _ := h.loggedIn("root", store.RoleSuper)
	key, codes := enableTOTP(t, root)
	if len(codes) != 10 {
		t.Fatal("no recovery codes after the first method")
	}
	if r := root.get("/me"); !strings.Contains(r.body, "剩余 10 个") {
		t.Error("recovery count on 我的账号")
	}

	c := h.client()
	signInPending(t, c, "root")
	// The waiting session reaches nothing but the second step and /logout.
	for _, p := range []string{"/", "/clients", "/me", "/admins", "/system"} {
		if r := c.get(p); r.code != http.StatusSeeOther || r.location != "/login/step" {
			t.Errorf("pending %s: %d %s", p, r.code, r.location)
		}
	}
	if r := c.post("/me/totp/off", url.Values{"password": {"password-root"}}); r.location != "/login/step" {
		t.Errorf("pending POST: %d %s", r.code, r.location)
	}
	if r := c.get("/no/such/page"); r.code != http.StatusNotFound || strings.Contains(r.body, "<html") {
		t.Errorf("pending unknown path: %d", r.code)
	}
	if r := c.get("/login"); r.location != "/login/step" {
		t.Errorf("login page with a pending session: %s", r.location)
	}
	page := c.get("/login/step?next=%2Fclients")
	if page.code != 200 || !strings.Contains(page.body, "身份验证器验证码") || strings.Contains(page.body, "邮箱验证码") ||
		strings.Contains(page.body, `class="sidebar"`) {
		t.Fatalf("step page: %d", page.code)
	}
	// The code used to switch TOTP on is spent: the same step is refused.
	r := c.post("/login/step", url.Values{"method": {"totp"}, "code": {h.totpCode(key)}, "next": {"/clients"}})
	if r.code != http.StatusUnauthorized {
		t.Fatalf("replayed code: %d", r.code)
	}
	h.clock.Advance(30 * time.Second)
	code := h.totpCode(key)
	r = c.post("/login/step", url.Values{"method": {"totp"}, "code": {code[:3] + " " + code[3:]}, "next": {"/clients"}})
	if r.code != http.StatusSeeOther || r.location != "/clients" {
		t.Fatalf("totp step: %d %s", r.code, r.location)
	}
	if r := c.get("/clients"); r.code != 200 {
		t.Fatalf("signed in: %d", r.code)
	}
	// The same code once more, in another browser: refused.
	d := h.client()
	signInPending(t, d, "root")
	if r := d.post("/login/step", url.Values{"method": {"totp"}, "code": {code}}); r.code != http.StatusUnauthorized {
		t.Errorf("code used twice: %d", r.code)
	}
	// A pending session ends after 10 minutes.
	h.clock.Advance(11 * time.Minute)
	if r := d.get("/login/step"); r.code != http.StatusSeeOther || r.location != "/login" {
		t.Errorf("old pending session: %d %s", r.code, r.location)
	}
	a := auditActions(t, h)
	if a["two_step.enable"] != 1 || a["login.second_failure"] != 2 || a["login.success"] < 2 {
		t.Errorf("audit %v", a)
	}
	for _, s := range []string{base32.StdEncoding.WithPadding(base32.NoPadding).EncodeToString(key), code, codes[0]} {
		if strings.Contains(h.logs.String(), s) {
			t.Errorf("log contains %q", s)
		}
	}
}

func TestRecoveryCodes(t *testing.T) {
	h := newHarness(t)
	root, a := h.loggedIn("root", store.RoleSuper)
	_, codes := enableTOTP(t, root)
	c := h.client()
	signInPending(t, c, "root")
	typed := strings.ToUpper(strings.ReplaceAll(codes[3], "-", " "))
	if r := c.post("/login/step", url.Values{"method": {"recovery"}, "code": {typed}}); r.code != http.StatusSeeOther || r.location != "/" {
		t.Fatalf("recovery code: %d", r.code)
	}
	d := h.client()
	signInPending(t, d, "root")
	if r := d.post("/login/step", url.Values{"method": {"recovery"}, "code": {codes[3]}}); r.code != http.StatusUnauthorized {
		t.Fatalf("recovery code used twice: %d", r.code)
	}
	if ts, _ := h.st.TwoStepState(t.Context(), a.ID); ts.RecoveryLeft != 9 {
		t.Errorf("left %d", ts.RecoveryLeft)
	}
	// New codes need the password and void the old ones.
	if r := root.post("/me/recovery/renew", url.Values{"password": {"wrong-password"}}); r.code != http.StatusSeeOther || r.location != "/me#two-step" {
		t.Fatalf("renew with a wrong password: %d %s", r.code, r.location)
	}
	r := root.post("/me/recovery/renew", url.Values{"password": {"password-root"}})
	if !strings.HasPrefix(r.location, "/me/recovery?t=") {
		t.Fatalf("renew: %d %s", r.code, r.location)
	}
	fresh := recoveryCodes(t, root, r.location)
	if r := d.post("/login/step", url.Values{"method": {"recovery"}, "code": {codes[0]}}); r.code != http.StatusUnauthorized {
		t.Error("old code accepted after renewal")
	}
	if r := d.post("/login/step", url.Values{"method": {"recovery"}, "code": {fresh[0]}}); r.code != http.StatusSeeOther {
		t.Errorf("new code: %d", r.code)
	}
	if acts := auditActions(t, h); acts["two_step.recovery_used"] != 2 || acts["two_step.recovery_renew"] != 1 {
		t.Errorf("audit %v", acts)
	}
	// Switching the last method off needs the password and drops the codes.
	if r := root.post("/me/totp/off", url.Values{"password": {"nope-nope-nope"}}); r.code != http.StatusSeeOther {
		t.Fatal(r.code)
	}
	if ts, _ := h.st.TwoStepState(t.Context(), a.ID); !ts.TOTP {
		t.Fatal("TOTP off with a wrong password")
	}
	root.post("/me/totp/off", url.Values{"password": {"password-root"}})
	if ts, _ := h.st.TwoStepState(t.Context(), a.ID); ts.TOTP || ts.RecoveryLeft != 0 {
		t.Errorf("after off: %+v", ts)
	}
	if r := root.post("/login", url.Values{"username": {"root"}, "password": {"password-root"}}); r.location != "/" {
		t.Errorf("no second step without a method: %s", r.location)
	}
}

func TestSecondStepRateLimit(t *testing.T) {
	h := newHarness(t)
	root, _ := h.loggedIn("root", store.RoleSuper)
	key, _ := enableTOTP(t, root)
	h.clock.Advance(time.Minute)
	c := h.client()
	signInPending(t, c, "root")
	for range 5 {
		c.post("/login/step", url.Values{"method": {"totp"}, "code": {"999999"}})
	}
	r := c.post("/login/step", url.Values{"method": {"totp"}, "code": {h.totpCode(key)}})
	if r.code != http.StatusTooManyRequests || !strings.Contains(r.body, "尝试次数过多") {
		t.Fatalf("locked: %d", r.code)
	}
	// The lock is per administrator, not per browser.
	d := h.client()
	signInPending(t, d, "root")
	if r := d.post("/login/step", url.Values{"method": {"totp"}, "code": {h.totpCode(key)}}); r.code != http.StatusTooManyRequests {
		t.Errorf("other browser: %d", r.code)
	}
	h.clock.Advance(16 * time.Minute)
	e := h.client()
	signInPending(t, e, "root")
	if r := e.post("/login/step", url.Values{"method": {"totp"}, "code": {h.totpCode(key)}}); r.code != http.StatusSeeOther {
		t.Errorf("after the lock: %d", r.code)
	}
	if n := auditActions(t, h)["login.second_failure"]; n != 5 {
		t.Errorf("%d failures audited", n)
	}
}

func TestTwoStepPolicy(t *testing.T) {
	h := newHarness(t)
	east := h.region("华东")
	root, _ := h.loggedIn("root", store.RoleSuper)
	zhang, za := h.loggedIn("zhang", store.RoleRegion, east)
	if r := zhang.post("/system/two-step", url.Values{"required": {"1"}}); r.code != http.StatusNotFound {
		t.Fatalf("region administrator switched the policy: %d", r.code)
	}
	if r := root.get("/system"); !strings.Contains(r.body, "区域管理员必须启用两步验证") {
		t.Fatal("policy switch missing on 系统")
	}
	root.post("/system/two-step", url.Values{"required": {"1"}})
	if on, _ := h.st.TwoStepRequired(t.Context()); !on {
		t.Fatal("policy not stored")
	}
	for _, p := range []string{"/", "/clients", "/audit"} {
		if r := zhang.get(p); r.code != http.StatusSeeOther || r.location != "/me" {
			t.Errorf("forced set-up %s: %d %s", p, r.code, r.location)
		}
	}
	if r := zhang.get("/me"); r.code != 200 || !strings.Contains(r.body, "须启用一种两步验证方式") {
		t.Errorf("我的账号 under the policy: %d", r.code)
	}
	if r := root.get("/"); r.code != 200 {
		t.Error("super administrator bound by the policy")
	}
	enableTOTP(t, zhang)
	if r := zhang.get("/clients"); r.code != 200 {
		t.Errorf("after set-up: %d", r.code)
	}
	// The last method stays.
	zhang.post("/me/totp/off", url.Values{"password": {"password-zhang"}})
	if ts, _ := h.st.TwoStepState(t.Context(), za.ID); !ts.TOTP {
		t.Fatal("last method switched off under the policy")
	}
	root.post("/system/two-step", nil)
	zhang.post("/me/totp/off", url.Values{"password": {"password-zhang"}})
	if ts, _ := h.st.TwoStepState(t.Context(), za.ID); ts.TOTP {
		t.Error("TOTP still on after the policy went off")
	}
	if acts := auditActions(t, h); acts["two_step.policy"] != 2 || acts["two_step.disable"] != 1 {
		t.Errorf("audit %v", acts)
	}

	// Order with a forced password change: password first, then the set-up.
	root.post("/system/two-step", url.Values{"required": {"1"}})
	li := h.admin("li", store.RoleRegion, east)
	h.st.SetAdminPassword(t.Context(), li.ID, li.PasswordHash, true)
	c := h.client()
	if r := c.post("/login", url.Values{"username": {"li"}, "password": {"password-li"}}); r.location != "/me" {
		t.Fatalf("li: %s", r.location)
	}
	if r := c.get("/me/totp"); r.code != http.StatusSeeOther || r.location != "/me" {
		t.Errorf("set-up before the password change: %d %s", r.code, r.location)
	}
	c.post("/me", url.Values{"current": {"password-li"}, "password": {"li-new-password"}, "password2": {"li-new-password"}})
	if r := c.get("/"); r.code != http.StatusSeeOther || r.location != "/me" {
		t.Errorf("set-up after the password change: %d %s", r.code, r.location)
	}
	if r := c.post("/me/totp/new", nil); r.location != "/me/totp" {
		t.Errorf("set-up reachable: %s", r.location)
	}
}

func TestResetTwoStepByAdmin(t *testing.T) {
	h := newHarness(t)
	east := h.region("华东")
	root, ra := h.loggedIn("root", store.RoleSuper)
	zhang, za := h.loggedIn("zhang", store.RoleRegion, east)
	enableTOTP(t, zhang)
	enableTOTP(t, root)
	if r := root.get("/admins"); !strings.Contains(r.body, "两步验证") || strings.Count(r.body, `<span class="status ok">已启用</span>`) < 2 {
		t.Error("admin list does not show two-step sign-in")
	}
	page := root.get("/admins/" + itoa(za.ID))
	if !strings.Contains(page.body, "/admins/"+itoa(za.ID)+"/reset-2fa") {
		t.Fatal("reset button missing")
	}
	if strings.Contains(root.get("/admins/"+itoa(ra.ID)).body, "reset-2fa") {
		t.Error("reset offered for a super administrator")
	}
	if r := zhang.post("/admins/"+itoa(za.ID)+"/reset-2fa", nil); r.code != http.StatusNotFound {
		t.Errorf("region administrator reset: %d", r.code)
	}
	if r := root.post("/admins/"+itoa(ra.ID)+"/reset-2fa", nil); r.code != http.StatusSeeOther {
		t.Fatal(r.code)
	}
	if ts, _ := h.st.TwoStepState(t.Context(), ra.ID); !ts.TOTP {
		t.Error("super administrator reset through the web")
	}
	if r := root.post("/admins/"+itoa(za.ID)+"/reset-2fa", nil); r.code != http.StatusSeeOther {
		t.Fatal(r.code)
	}
	if ts, _ := h.st.TwoStepState(t.Context(), za.ID); ts.Methods() != 0 || ts.RecoveryLeft != 0 {
		t.Errorf("after reset: %+v", ts)
	}
	if r := zhang.get("/"); r.code != http.StatusSeeOther || !strings.HasPrefix(r.location, "/login") {
		t.Error("zhang's session survived the reset")
	}
	zhang.login("zhang") // no second step any more
	if n := auditActions(t, h)["two_step.reset"]; n != 1 {
		t.Errorf("%d reset entries", n)
	}
}

// TestUpgradeKeepsSessions: a session made before the migration (stage ”) is complete.
func TestUpgradeKeepsSessions(t *testing.T) {
	h := newHarness(t)
	c, a := h.loggedIn("root", store.RoleSuper)
	if _, err := h.st.DB.Exec(`UPDATE sessions SET stage = '' WHERE admin_id = ?`, a.ID); err != nil {
		t.Fatal(err)
	}
	if r := c.get("/"); r.code != 200 {
		t.Errorf("existing session: %d", r.code)
	}
	if v, _ := h.st.SchemaVersion(t.Context()); v < 7 {
		t.Errorf("schema %d", v)
	}
}
