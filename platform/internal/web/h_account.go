package web

import (
	"errors"
	"net/http"
	"strings"

	"siplink/internal/auth"
	"siplink/internal/mail"
	"siplink/internal/store"
	"siplink/internal/twostep"
)

// 我的账号 (/me): the administrator's e-mail address, password and two-step sign-in
// (design §5.2, §6.8).

func (s *Server) routesAccount() {
	s.page("GET /me", s.meForm)
	s.page("POST /me", s.meSubmit)
	s.page("POST /me/email", s.emailSave)
	s.page("POST /me/email/send", s.emailSend)
	s.page("POST /me/email/confirm", s.emailConfirm)
}

type accountData struct {
	Admin      *store.Admin
	Forced     bool // the password must be changed first
	SetUp      bool // the policy asks for a method first
	Error      string
	TwoStep    store.TwoStep
	Passkeys   []store.Passkey
	PasskeysOK bool // WebAuthn works at this public URL
	MailOK     bool // SMTP is configured
	Strict     bool // new passwords are checked for strength
}

func (s *Server) meForm(w http.ResponseWriter, r *http.Request) {
	s.renderAccount(w, r, http.StatusOK, "")
}

func (s *Server) renderAccount(w http.ResponseWriter, r *http.Request, status int, msg string) {
	a := current(r)
	d := accountData{Admin: a, Forced: a.MustChangePassword, Error: msg, PasskeysOK: s.passkeys != nil}
	var err error
	if d.TwoStep, err = s.St.TwoStepState(r.Context(), a.ID); err != nil {
		s.internal(w, r, err)
		return
	}
	if d.Passkeys, err = s.St.Passkeys(r.Context(), a.ID); err != nil {
		s.internal(w, r, err)
		return
	}
	set, err := s.mailSettings(r.Context())
	if err != nil {
		s.internal(w, r, err)
		return
	}
	d.MailOK = set.Configured()
	if d.Strict, err = s.St.PasswordCheck(r.Context()); err != nil {
		s.internal(w, r, err)
		return
	}
	if d.SetUp, err = s.mustSetUp(r.Context(), a); err != nil {
		s.internal(w, r, err)
		return
	}
	s.render(w, r, status, "me", &Page{Title: tr(r).T("layout.my_account"), Nav: "me", Data: d})
}

func (s *Server) meSubmit(w http.ResponseWriter, r *http.Request) {
	a := current(r)
	old, pw, again := r.FormValue("current"), r.FormValue("password"), r.FormValue("password2")
	t := tr(r)
	msg := s.recheckPassword(r, a, old)
	switch {
	case msg == t.T("twostep.wrong_password"):
		msg = t.T("me.password.wrong_current")
	case msg == "" && pw != again:
		msg = t.T("me.password.mismatch")
	case msg == "" && pw == old:
		msg = t.T("me.password.same")
	case msg == "":
		strict, err := s.St.PasswordCheck(r.Context())
		if err != nil {
			s.internal(w, r, err)
			return
		}
		msg = errText(r, auth.PasswordProblem(pw, a.Username, strict))
	}
	if msg != "" {
		s.renderAccount(w, r, http.StatusBadRequest, msg)
		return
	}
	hash, err := auth.HashPassword(pw)
	if err != nil {
		s.internal(w, r, err)
		return
	}
	// Ends every session of this administrator, then starts a fresh one here.
	if err := s.St.SetAdminPassword(r.Context(), a.ID, hash, false); err != nil {
		s.internal(w, r, err)
		return
	}
	s.audit(r, "admin.password_change", "admin", a.ID, a.Username, nil, map[string]any{"password": auditChanged})
	token, err := s.Sessions.Create(r.Context(), a.ID, s.clientIP(r), r.UserAgent())
	if err != nil {
		s.internal(w, r, err)
		return
	}
	s.setSessionCookie(w, r, token)
	s.redirect(w, r, "/", "ok", t.T("me.password.changed"))
}

// emailSave changes the own address (password typed again). A new address is
// unconfirmed; a confirmation code goes to it when mail is configured.
func (s *Server) emailSave(w http.ResponseWriter, r *http.Request) {
	a := current(r)
	email := strings.TrimSpace(r.FormValue("email"))
	if email == a.Email {
		http.Redirect(w, r, "/me", http.StatusSeeOther)
		return
	}
	if email != "" && mail.ValidAddress(email) != nil {
		s.redirect(w, r, "/me", "error", tr(r).T("me.email.invalid"))
		return
	}
	if m := s.recheckPassword(r, a, r.FormValue("password")); m != "" {
		s.redirect(w, r, "/me", "error", m)
		return
	}
	// The e-mail method goes off with the old address: the policy applies then.
	keep, err := s.keepRule(r.Context(), a)
	if err == nil {
		err = s.St.SetAdminEmail(r.Context(), a.ID, email, keep)
	}
	if errors.Is(err, store.ErrLastMethod) {
		s.redirect(w, r, "/me", "error", tr(r).T("twostep.last_method"))
		return
	}
	if err != nil {
		s.internal(w, r, err)
		return
	}
	s.audit(r, "admin.email", "admin", a.ID, a.Username, nil, changes(field{"email", a.Email, email}))
	if email == "" {
		s.redirect(w, r, "/me", "ok", tr(r).T("me.email.removed"))
		return
	}
	if m := s.sendCode(r, a, twostep.PurposeConfirm, email); m != "" {
		s.redirect(w, r, "/me", "error", tr(r).T("me.email.saved_unsent", "reason", m))
		return
	}
	s.redirect(w, r, "/me", "ok", tr(r).T("me.email.saved_sent"))
}

func (s *Server) emailSend(w http.ResponseWriter, r *http.Request) {
	a := current(r)
	if a.Email == "" || a.EmailConfirmed {
		http.Redirect(w, r, "/me", http.StatusSeeOther)
		return
	}
	if m := s.sendCode(r, a, twostep.PurposeConfirm, a.Email); m != "" {
		s.redirect(w, r, "/me", "error", m)
		return
	}
	s.redirect(w, r, "/me", "ok", tr(r).T("twostep.code_sent"))
}

func (s *Server) emailConfirm(w http.ResponseWriter, r *http.Request) {
	a := current(r)
	if a.Email == "" || a.EmailConfirmed {
		http.Redirect(w, r, "/me", http.StatusSeeOther)
		return
	}
	addr, ok, err := s.St.CheckEmailCode(r.Context(), a.ID, twostep.PurposeConfirm,
		twostep.CodeHash(s.Ext.CodeKey, a.ID, twostep.PurposeConfirm, r.FormValue("code")), twostep.CodeMaxTries)
	if err == nil && ok && addr == a.Email {
		err = s.St.ConfirmAdminEmail(r.Context(), a.ID, a.Email)
	}
	if err != nil {
		s.internal(w, r, err)
		return
	}
	if !ok || addr != a.Email {
		s.redirect(w, r, "/me", "error", tr(r).T("me.email.wrong_code"))
		return
	}
	s.audit(r, "admin.email_confirm", "admin", a.ID, a.Username, nil, nil)
	s.redirect(w, r, "/me", "ok", tr(r).T("me.email.confirm_done"))
}
