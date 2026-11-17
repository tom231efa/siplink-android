package web

import (
	"encoding/base64"
	"io"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"testing"
	"time"

	"siplink/internal/store"
)

// Parallel requests against the limits and the removal rules of §6.8: every request
// leaves at the same moment over its own warmed-up connection.

type rawResp struct {
	code  int
	flash string // decoded flash message, "" if none
}

// warm gives the client n open keep-alive connections.
func (c *client) warm(n int) {
	tr := c.h.ts.Client().Transport.(*http.Transport).Clone()
	tr.MaxIdleConnsPerHost, tr.MaxIdleConns = n, n
	c.c.Transport = tr
	c.parallel(n, func(int) {
		if r, err := c.c.Get(c.h.ts.URL + "/healthz"); err == nil {
			io.Copy(io.Discard, r.Body)
			r.Body.Close()
		}
	})
}

// parallel runs f n times, all released at once.
func (c *client) parallel(n int, f func(i int)) {
	var wg sync.WaitGroup
	gate := make(chan struct{})
	for i := range n {
		wg.Add(1)
		go func() {
			defer wg.Done()
			<-gate
			f(i)
		}()
	}
	close(gate)
	wg.Wait()
}

// burst posts n forms in parallel; it does not touch *testing.T inside the goroutines.
func (c *client) burst(n int, path string, form func(i int) url.Values) []rawResp {
	out := make([]rawResp, n)
	c.parallel(n, func(i int) {
		req, _ := http.NewRequest("POST", c.h.ts.URL+path, strings.NewReader(form(i).Encode()))
		req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
		r, err := c.c.Do(req)
		if err != nil {
			return
		}
		io.Copy(io.Discard, r.Body)
		r.Body.Close()
		out[i].code = r.StatusCode
		for _, ck := range r.Cookies() {
			if ck.Name == flashCookie {
				_, enc, _ := strings.Cut(ck.Value, ".")
				msg, _ := base64.RawURLEncoding.DecodeString(enc)
				out[i].flash = string(msg)
			}
		}
	})
	return out
}

func countCodes(rs []rawResp, code int) (n int) {
	for _, r := range rs {
		if r.code == code {
			n++
		}
	}
	return n
}

func countFlash(rs []rawResp, msg string) (n int) {
	for _, r := range rs {
		if r.flash == msg {
			n++
		}
	}
	return n
}

func fixed(v url.Values) func(int) url.Values { return func(int) url.Values { return v } }

func TestParallelSecondStep(t *testing.T) {
	h := newHarness(t)
	root, _ := h.loggedIn("root", store.RoleSuper)
	enableTOTP(t, root)
	h.stepReset(t)
	h.clock.Advance(time.Minute)
	c := h.client()
	signInPending(t, c, "root")
	c.warm(40)
	rs := c.burst(40, "/login/step", fixed(url.Values{"method": {"totp"}, "code": {"000000"}}))
	if n := countCodes(rs, http.StatusUnauthorized); n != 5 || countCodes(rs, http.StatusTooManyRequests) != 35 {
		t.Errorf("%d of 40 parallel wrong codes were checked, want 5", n)
	}
	if n := auditActions(t, h)["login.second_failure"]; n != 5 {
		t.Errorf("%d failures audited", n)
	}
}

func TestParallelPasswordChecks(t *testing.T) {
	h := newHarness(t)
	root, _ := h.loggedIn("root", store.RoleSuper)
	enableTOTP(t, root)
	h.stepReset(t)
	root.warm(30)
	rs := root.burst(30, "/me/totp/off", fixed(url.Values{"password": {"guess-guess-guess"}}))
	if n := countFlash(rs, wrongPassword); n != 5 || countFlash(rs, tooManySteps) != 25 {
		t.Errorf("%d of 30 parallel password re-entries were checked, want 5", n)
	}
	// The login limiter takes its slot the same way.
	d := h.client()
	d.warm(30)
	rs = d.burst(30, "/login", fixed(url.Values{"username": {"root"}, "password": {"wrong-wrong-wrong"}}))
	if n := countCodes(rs, http.StatusUnauthorized); n != 5 {
		t.Errorf("%d of 30 parallel wrong logins were checked, want 5", n)
	}
	// The first TOTP code of a set-up.
	h.stepReset(t)
	h.clock.Advance(time.Minute) // the per-address login limit
	zhang, _ := h.loggedIn("zhang", store.RoleSuper)
	zhang.post("/me/totp/new", nil)
	zhang.warm(20)
	rs = zhang.burst(20, "/me/totp/confirm", fixed(url.Values{"code": {"abcdef"}}))
	if n := countCodes(rs, http.StatusBadRequest); n != 5 {
		t.Errorf("%d of 20 parallel set-up codes were checked, want 5", n)
	}
}

func TestParallelSendCode(t *testing.T) {
	h := newHarness(t)
	srv := smtpFor(t, h, false)
	root, _ := h.loggedIn("root", store.RoleSuper)
	root.post("/mail", mailForm(srv, "starttls"))
	root.post("/me/email", url.Values{"email": {"root@example.com"}, "password": {"password-root"}})
	h.clock.Advance(61 * time.Minute)
	before := len(srv.Mails())
	root.warm(20)
	root.burst(20, "/me/email/send", fixed(nil))
	if sent := len(srv.Mails()) - before; sent != 1 {
		t.Errorf("20 parallel sends: %d mails, want 1", sent)
	}
}

// TestParallelRemoval: the decision "may it go" and the removal are one transaction.
func TestParallelRemoval(t *testing.T) {
	for round := range 8 {
		h := newHarness(t)
		east := h.region("华东")
		root, _ := h.loggedIn("root", store.RoleSuper)
		zhang, za := h.loggedIn("zhang", store.RoleRegion, east)
		for _, name := range []string{"one", "two"} {
			if r := newSoftKey(t).register(zhang, name); r.code != 200 {
				t.Fatalf("register: %d %s", r.code, r.body)
			}
		}
		root.post("/system/two-step", url.Values{"required": {"1"}})
		keys, _ := h.st.Passkeys(t.Context(), za.ID)
		zhang.warm(2)
		zhang.parallel(2, func(i int) {
			req, _ := http.NewRequest("POST", h.ts.URL+"/me/passkeys/"+itoa(keys[i].ID)+"/delete", strings.NewReader("password=password-zhang"))
			req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
			if r, err := zhang.c.Do(req); err == nil {
				r.Body.Close()
			}
		})
		if ts, _ := h.st.TwoStepState(t.Context(), za.ID); ts.Methods() != 1 {
			t.Fatalf("round %d: %d methods left under the policy, want 1", round, ts.Methods())
		}
		// Without the policy the last passkey still needs the password.
		boss, ba := h.loggedIn("boss", store.RoleSuper)
		for _, name := range []string{"one", "two"} {
			newSoftKey(t).register(boss, name)
		}
		bkeys, _ := h.st.Passkeys(t.Context(), ba.ID)
		boss.warm(2)
		boss.parallel(2, func(i int) {
			req, _ := http.NewRequest("POST", h.ts.URL+"/me/passkeys/"+itoa(bkeys[i].ID)+"/delete", nil)
			if r, err := boss.c.Do(req); err == nil {
				r.Body.Close()
			}
		})
		if ts, _ := h.st.TwoStepState(t.Context(), ba.ID); ts.Passkeys != 1 {
			t.Fatalf("round %d: %d passkeys left after two deletes without a password, want 1", round, ts.Passkeys)
		}
	}
}

// TestParallelSwitchOff: two different methods switched off at once under the policy.
func TestParallelSwitchOff(t *testing.T) {
	for round := range 8 {
		h := newHarness(t)
		east := h.region("华东")
		root, _ := h.loggedIn("root", store.RoleSuper)
		zhang, za := h.loggedIn("zhang", store.RoleRegion, east)
		enableTOTP(t, zhang)
		newSoftKey(t).register(zhang, "one")
		keys, _ := h.st.Passkeys(t.Context(), za.ID)
		root.post("/system/two-step", url.Values{"required": {"1"}})
		paths := []string{"/me/totp/off", "/me/passkeys/" + itoa(keys[0].ID) + "/delete"}
		zhang.warm(2)
		zhang.parallel(2, func(i int) {
			req, _ := http.NewRequest("POST", h.ts.URL+paths[i], strings.NewReader("password=password-zhang"))
			req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
			if r, err := zhang.c.Do(req); err == nil {
				r.Body.Close()
			}
		})
		if ts, _ := h.st.TwoStepState(t.Context(), za.ID); ts.Methods() != 1 {
			t.Fatalf("round %d: %d methods left, want 1", round, ts.Methods())
		}
	}
}
