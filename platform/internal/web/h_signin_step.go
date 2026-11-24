package web

import (
	"context"
	"errors"
	"net/http"
	"net/url"
	"strings"

	"siplink/internal/store"
	"siplink/internal/twostep"
)

// The second step of the sign-in (design §6.8). Its routes need a session waiting for
// it (requirePending); the administrator is pendingAdmin(r), not current(r).

// stepPage registers a route of the second step.
func (s *Server) stepPage(pattern string, h http.HandlerFunc) {
	s.handle(pattern, maxOpenBody, s.requireSecure(s.csrfCheck(s.requirePending(s.readForm(maxOpenBody, h)))))
}

type stepData struct {
	Next    string
	Passkey bool
	TOTP    bool
	Email   string // masked address when the e-mail method is usable
	Error   string
}

func (s *Server) stepForm(w http.ResponseWriter, r *http.Request) {
	s.renderStep(w, r, http.StatusOK, safeNext(r.URL.Query().Get("next")), "")
}

func (s *Server) renderStep(w http.ResponseWriter, r *http.Request, status int, next, msg string) {
	a := pendingAdmin(r)
	ts, err := s.St.TwoStepState(r.Context(), a.ID)
	if err != nil {
		s.internal(w, r, err)
		return
	}
	d := stepData{Next: next, Passkey: ts.Passkeys > 0 && s.passkeys != nil, TOTP: ts.TOTP, Error: msg}
	if ts.Email && a.EmailConfirmed {
		d.Email = maskAddress(a.Email)
	}
	s.render(w, r, status, "login_step", &Page{Title: tr(r).T("twostep.title"), Data: d})
}

// maskAddress shows an address as "z***@example.com".
func maskAddress(addr string) string {
	local, domain, ok := strings.Cut(addr, "@")
	if !ok || local == "" {
		return "***"
	}
	return string([]rune(local)[:1]) + "***@" + domain
}

func (s *Server) stepSubmit(w http.ResponseWriter, r *http.Request) {
	a := pendingAdmin(r)
	next := safeNext(r.FormValue("next"))
	method, code := r.FormValue("method"), r.FormValue("code")
	ok, limited, err := s.tryStep(a, func() (bool, error) {
		switch method {
		case "totp":
			return s.checkTOTP(r.Context(), a.ID, code)
		case "email":
			if !a.TwoStepEmail || !a.EmailConfirmed {
				return false, nil
			}
			addr, ok, err := s.St.CheckEmailCode(r.Context(), a.ID, twostep.PurposeLogin,
				twostep.CodeHash(s.Ext.CodeKey, a.ID, twostep.PurposeLogin, code), twostep.CodeMaxTries)
			return ok && addr == a.Email, err
		case "recovery":
			return s.St.UseRecoveryCode(r.Context(), a.ID, twostep.RecoveryHash(s.Ext.CodeKey, a.ID, code))
		}
		method = ""
		return false, nil
	})
	if limited {
		s.renderStep(w, r, http.StatusTooManyRequests, next, tr(r).T("login.too_many"))
		return
	}
	if err != nil {
		s.internal(w, r, err)
		return
	}
	if !ok {
		s.stepFailed(r, a, method)
		s.renderStep(w, r, http.StatusUnauthorized, next, tr(r).T("twostep.wrong_code"))
		return
	}
	if method == "recovery" {
		s.addAudit(r, &store.AuditEntry{AdminID: &a.ID, AdminName: a.Username, Action: "two_step.recovery_used",
			ObjectType: "admin", ObjectID: &a.ID, ObjectName: a.Username})
	}
	to, err := s.completeStep(w, r, a, method, next)
	if err != nil {
		s.internal(w, r, err)
		return
	}
	http.Redirect(w, r, to, http.StatusSeeOther)
}

// stepEmail sends a sign-in code to the administrator's confirmed address.
func (s *Server) stepEmail(w http.ResponseWriter, r *http.Request) {
	a := pendingAdmin(r)
	back := "/login/step?next=" + url.QueryEscape(safeNext(r.FormValue("next")))
	if !a.TwoStepEmail || !a.EmailConfirmed {
		s.redirect(w, r, back, "error", tr(r).T("step.email_off"))
		return
	}
	if msg := s.sendCode(r, a, twostep.PurposeLogin, a.Email); msg != "" {
		s.redirect(w, r, back, "error", msg)
		return
	}
	s.redirect(w, r, back, "ok", tr(r).T("twostep.code_sent"))
}

// checkTOTP verifies a code against the administrator's TOTP secret and spends its step.
func (s *Server) checkTOTP(ctx context.Context, adminID int64, code string) (bool, error) {
	row, key, err := s.loadTOTP(ctx, adminID)
	if errors.Is(err, store.ErrNotFound) || err == nil && !row.Confirmed {
		return false, nil
	}
	if err != nil {
		return false, err
	}
	step, ok := twostep.MatchTOTP(key, code, s.Now(), row.LastStep)
	if !ok {
		return false, nil
	}
	return s.St.UseTOTPStep(ctx, adminID, step)
}

// stepFailed audits a failed second step (tryStep has counted it).
func (s *Server) stepFailed(r *http.Request, a *store.Admin, method string) {
	s.addAudit(r, &store.AuditEntry{Action: "login.second_failure", ObjectType: "admin", ObjectID: &a.ID,
		ObjectName: a.Username, Detail: map[string]any{"method": method}})
}

// completeStep ends the waiting session and starts a complete one; it returns where
// the browser goes next.
func (s *Server) completeStep(w http.ResponseWriter, r *http.Request, a *store.Admin, method, next string) (string, error) {
	_ = s.Sessions.Destroy(r.Context(), sessionToken(r))
	s.stepFails.Reset(itoa(a.ID))
	return s.signIn(w, r, a, safeNext(next), map[string]any{"method": method})
}
