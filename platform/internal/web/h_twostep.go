package web

import (
	"context"
	"errors"
	"html/template"
	"net/http"
	"net/url"

	"siplink/internal/auth"
	"siplink/internal/qr"
	"siplink/internal/secret"
	"siplink/internal/store"
	"siplink/internal/twostep"
)

// Two-step settings of 我的账号, the policy and the reset (design §6.8).

func (s *Server) routesTwoStep() {
	s.stepPage("GET /login/step", s.stepForm)
	s.stepPage("POST /login/step", s.stepSubmit)
	s.stepPage("POST /login/step/email", s.stepEmail)
	s.stepPage("POST /login/step/passkey/options", s.stepPasskeyOptions)
	s.stepPage("POST /login/step/passkey", s.stepPasskey)

	s.page("GET /me/totp", s.totpPage)
	s.page("POST /me/totp/new", s.totpNew)
	s.page("POST /me/totp/confirm", s.totpConfirm)
	s.page("POST /me/totp/cancel", s.totpCancel)
	s.page("POST /me/totp/off", s.totpOff)
	s.page("POST /me/email-code/on", s.emailMethodOn)
	s.page("POST /me/email-code/off", s.emailMethodOff)
	s.page("POST /me/passkeys/options", s.passkeyOptions)
	s.page("POST /me/passkeys", s.passkeyAdd)
	s.page("POST /me/passkeys/{id}/delete", s.passkeyDelete)
	s.page("POST /me/recovery/renew", s.recoveryRenew)
	s.page("GET /me/recovery", s.recoveryShow)
	s.page("POST /me/recovery/done", s.recoveryDone)

	s.page("POST /admins/{id}/reset-2fa", s.requireSuper(s.adminResetTwoStep))
	s.page("POST /system/two-step", s.requireSuper(s.policySave))
}

// mustSetUp reports whether the policy keeps this administrator on their account page
// until a method is on.
func (s *Server) mustSetUp(ctx context.Context, a *store.Admin) (bool, error) {
	if a.Role != store.RoleRegion {
		return false, nil
	}
	required, err := s.St.TwoStepRequired(ctx)
	if err != nil || !required {
		return false, err
	}
	ts, err := s.St.TwoStepState(ctx, a.ID)
	return twostep.MustSetUp(true, true, ts.Methods()), err
}

// Messages of the checks below.
const (
	wrongPassword  = "密码不正确。"
	lastMethodText = "区域管理员须至少保留一种两步验证方式。"
)

// tryStep runs one check of the administrator's (second step, password typed again,
// first TOTP code) under the limit of §6.8. The slot is taken before the check, so
// parallel requests cannot all pass, and given back when the check passes (or fails for
// an internal reason). limited reports a refusal by the limit.
func (s *Server) tryStep(a *store.Admin, check func() (bool, error)) (ok, limited bool, err error) {
	key := itoa(a.ID)
	if !s.stepFails.Take(key) {
		return false, true, nil
	}
	ok, err = check()
	if ok || err != nil {
		s.stepFails.Release(key)
	}
	return ok, false, err
}

// recheckPassword verifies a password typed again (sensitive changes, 修改密码, viewing
// stored passwords) under the same limit. It returns a message, "" when it is right.
func (s *Server) recheckPassword(a *store.Admin, password string) string {
	ok, limited, _ := s.tryStep(a, func() (bool, error) { return auth.VerifyPassword(a.PasswordHash, password) })
	switch {
	case limited:
		return tooManySteps
	case !ok:
		return wrongPassword
	}
	return ""
}

// keepRule is the policy check of a removal, run by the store inside the transaction
// that removes (§6.8): a region administrator under the policy keeps one method.
func (s *Server) keepRule(ctx context.Context, a *store.Admin) (func(store.TwoStep) error, error) {
	required, err := s.St.TwoStepRequired(ctx)
	return func(before store.TwoStep) error {
		if !twostep.MayRemove(a.Role == store.RoleRegion, required, before.Methods()-1) {
			return store.ErrLastMethod
		}
		return nil
	}, err
}

// methodAdded finishes switching a method on. The first method makes recovery codes,
// shown on /me/recovery, and ends the administrator's other sessions (as a password
// change does). It returns where the browser goes.
func (s *Server) methodAdded(w http.ResponseWriter, r *http.Request, a *store.Admin, before store.TwoStep, msg string) (string, error) {
	if before.Methods() > 0 {
		s.flash(w, r, "ok", msg)
		return "/me#two-step", nil
	}
	if err := s.St.DeleteOtherSessions(r.Context(), a.ID, secret.HashHex(sessionToken(r))); err != nil {
		return "", err
	}
	token, err := s.newRecoveryCodes(r, a)
	if err != nil {
		return "", err
	}
	return "/me/recovery?t=" + url.QueryEscape(token), nil
}

func (s *Server) newRecoveryCodes(r *http.Request, a *store.Admin) (string, error) {
	codes := twostep.NewRecoveryCodes()
	hashes := make([]string, len(codes))
	for i, c := range codes {
		hashes[i] = twostep.RecoveryHash(s.Ext.CodeKey, a.ID, c)
	}
	if err := s.St.ReplaceRecoveryCodes(r.Context(), a.ID, hashes); err != nil {
		return "", err
	}
	return s.shown.put(s.Now(), a.ID, codes), nil
}

// sendCode makes a new e-mail code and sends it; it returns a message on failure. The
// reason of a failed send goes to the log only (the 邮件 page's test shows it).
func (s *Server) sendCode(r *http.Request, a *store.Admin, purpose, address string) string {
	set, err := s.mailSettings(r.Context())
	if err != nil {
		s.Log.Error("mail settings unreadable", "err", err)
		return "内部错误，请稍后重试。"
	}
	if !set.Configured() {
		return "邮件服务未配置。"
	}
	key := itoa(a.ID)
	if !s.mailMinute.Take(key) {
		return "发送过于频繁，请 1 分钟后重试。"
	}
	if !s.mailHour.Take(key) {
		s.mailMinute.Release(key)
		return "发送次数过多，请稍后重试。"
	}
	code := twostep.NewCode()
	err = s.St.PutEmailCode(r.Context(), &store.EmailCode{AdminID: a.ID, Purpose: purpose, Address: address,
		CodeHash: twostep.CodeHash(s.Ext.CodeKey, a.ID, purpose, code), ExpiresAt: s.Now().Add(twostep.CodeTTL).UTC().Format(store.TimeFormat)})
	if err != nil {
		s.Log.Error("e-mail code not stored", "err", err)
		return "内部错误，请稍后重试。"
	}
	body := "SipLink 管理平台验证码：" + code + "\n10 分钟内有效。\n"
	if err := s.Mailer.Send(r.Context(), set, address, "SipLink 管理平台验证码", body); err != nil {
		s.Log.Warn("e-mail code not sent", "admin", a.ID, "err", err)
		return "邮件发送失败。"
	}
	return ""
}

// ---- TOTP ----

type totpData struct {
	QR     template.HTML
	Secret string
	Error  string
}

func (s *Server) totpNew(w http.ResponseWriter, r *http.Request) {
	a := current(r)
	enc, err := s.Ext.Box.Seal(twostep.NewTOTPSecret(), store.TOTPAAD(a.ID))
	if err != nil {
		s.internal(w, r, err)
		return
	}
	if err := s.St.PutPendingTOTP(r.Context(), a.ID, enc); errors.Is(err, store.ErrTOTPOn) {
		s.redirect(w, r, "/me#two-step", "error", "身份验证器已启用。")
		return
	} else if err != nil {
		s.internal(w, r, err)
		return
	}
	http.Redirect(w, r, "/me/totp", http.StatusSeeOther)
}

func (s *Server) totpPage(w http.ResponseWriter, r *http.Request) {
	s.renderTOTP(w, r, http.StatusOK, "")
}

// loadTOTP loads the administrator's TOTP row with its secret opened.
func (s *Server) loadTOTP(ctx context.Context, adminID int64) (*store.TOTPRow, []byte, error) {
	row, err := s.St.TOTP(ctx, adminID)
	if err != nil {
		return nil, nil, err
	}
	key, err := s.Ext.Box.Open(row.SecretEnc, store.TOTPAAD(adminID))
	return row, key, err
}

// pendingTOTP loads a set-up that waits for its first code; done is true when the
// browser was sent on (none pending, or an error page).
func (s *Server) pendingTOTP(w http.ResponseWriter, r *http.Request) (row *store.TOTPRow, key []byte, done bool) {
	row, key, err := s.loadTOTP(r.Context(), current(r).ID)
	switch {
	case errors.Is(err, store.ErrNotFound) || err == nil && row.Confirmed:
		http.Redirect(w, r, "/me#two-step", http.StatusSeeOther)
		return nil, nil, true
	case err != nil:
		s.internal(w, r, err)
		return nil, nil, true
	}
	return row, key, false
}

func (s *Server) renderTOTP(w http.ResponseWriter, r *http.Request, status int, msg string) {
	_, key, done := s.pendingTOTP(w, r)
	if done {
		return
	}
	svg, err := qr.SVG(twostep.TOTPURI(current(r).Username, key))
	if err != nil {
		s.internal(w, r, err)
		return
	}
	d := totpData{QR: template.HTML(svg), Secret: twostep.SecretText(key), Error: msg} // our own SVG
	s.render(w, r, status, "totp_setup", &Page{Title: "身份验证器", Nav: "me", Data: d})
}

func (s *Server) totpConfirm(w http.ResponseWriter, r *http.Request) {
	a := current(r)
	row, key, done := s.pendingTOTP(w, r)
	if done {
		return
	}
	var step int64
	ok, limited, _ := s.tryStep(a, func() (bool, error) {
		var ok bool
		step, ok = twostep.MatchTOTP(key, r.FormValue("code"), s.Now(), 0)
		return ok, nil
	})
	switch {
	case limited:
		s.renderTOTP(w, r, http.StatusTooManyRequests, tooManySteps)
		return
	case !ok:
		s.renderTOTP(w, r, http.StatusBadRequest, "验证码不正确。")
		return
	}
	before, err := s.St.TwoStepState(r.Context(), a.ID)
	if err == nil {
		err = s.St.ConfirmTOTP(r.Context(), a.ID, step, row.SecretEnc)
	}
	if errors.Is(err, store.ErrNotFound) { // the set-up was started again meanwhile
		s.redirect(w, r, "/me/totp", "error", "验证码不正确。")
		return
	}
	if err != nil {
		s.internal(w, r, err)
		return
	}
	s.audit(r, "two_step.enable", "admin", a.ID, a.Username, nil, map[string]any{"method": "totp"})
	to, err := s.methodAdded(w, r, a, before, "身份验证器已启用。")
	if err != nil {
		s.internal(w, r, err)
		return
	}
	http.Redirect(w, r, to, http.StatusSeeOther)
}

// totpCancel drops a set-up that was not confirmed.
func (s *Server) totpCancel(w http.ResponseWriter, r *http.Request) {
	if err := s.St.DropPendingTOTP(r.Context(), current(r).ID); err != nil {
		s.internal(w, r, err)
		return
	}
	http.Redirect(w, r, "/me#two-step", http.StatusSeeOther)
}

func (s *Server) totpOff(w http.ResponseWriter, r *http.Request) {
	s.switchOff(w, r, store.MethodTOTP)
}

// ---- e-mail code method ----

func (s *Server) emailMethodOn(w http.ResponseWriter, r *http.Request) {
	a := current(r)
	set, err := s.mailSettings(r.Context())
	if err != nil {
		s.internal(w, r, err)
		return
	}
	if !a.EmailConfirmed || !set.Configured() {
		s.redirect(w, r, "/me#two-step", "error", "须先验证邮箱，且邮件服务已配置。")
		return
	}
	before, err := s.St.TwoStepState(r.Context(), a.ID)
	if err == nil && !before.Email {
		err = s.St.EnableTwoStepEmail(r.Context(), a.ID)
	}
	if err != nil {
		s.internal(w, r, err)
		return
	}
	if before.Email {
		http.Redirect(w, r, "/me#two-step", http.StatusSeeOther)
		return
	}
	s.audit(r, "two_step.enable", "admin", a.ID, a.Username, nil, map[string]any{"method": "email"})
	to, err := s.methodAdded(w, r, a, before, "邮箱验证码已启用。")
	if err != nil {
		s.internal(w, r, err)
		return
	}
	http.Redirect(w, r, to, http.StatusSeeOther)
}

func (s *Server) emailMethodOff(w http.ResponseWriter, r *http.Request) {
	s.switchOff(w, r, store.MethodEmail)
}

// switchOff switches a method off: the password typed again, then the policy and the
// removal in one store transaction.
func (s *Server) switchOff(w http.ResponseWriter, r *http.Request, method string) {
	a := current(r)
	if msg := s.recheckPassword(a, r.FormValue("password")); msg != "" {
		s.redirect(w, r, "/me#two-step", "error", msg)
		return
	}
	keep, err := s.keepRule(r.Context(), a)
	if err == nil {
		err = s.St.RemoveMethod(r.Context(), a.ID, method, 0, keep)
	}
	switch {
	case errors.Is(err, store.ErrNotFound):
		http.Redirect(w, r, "/me#two-step", http.StatusSeeOther)
		return
	case errors.Is(err, store.ErrLastMethod):
		s.redirect(w, r, "/me#two-step", "error", lastMethodText)
		return
	case err != nil:
		s.internal(w, r, err)
		return
	}
	s.audit(r, "two_step.disable", "admin", a.ID, a.Username, nil, map[string]any{"method": method})
	s.redirect(w, r, "/me#two-step", "ok", "已关闭。")
}

// ---- recovery codes ----

func (s *Server) recoveryRenew(w http.ResponseWriter, r *http.Request) {
	a := current(r)
	ts, err := s.St.TwoStepState(r.Context(), a.ID)
	if err != nil {
		s.internal(w, r, err)
		return
	}
	if ts.Methods() == 0 {
		http.Redirect(w, r, "/me#two-step", http.StatusSeeOther)
		return
	}
	if msg := s.recheckPassword(a, r.FormValue("password")); msg != "" {
		s.redirect(w, r, "/me#two-step", "error", msg)
		return
	}
	token, err := s.newRecoveryCodes(r, a)
	if err != nil {
		s.internal(w, r, err)
		return
	}
	s.audit(r, "two_step.recovery_renew", "admin", a.ID, a.Username, nil, nil)
	http.Redirect(w, r, "/me/recovery?t="+url.QueryEscape(token), http.StatusSeeOther)
}

type recoveryData struct {
	Codes []string
	Token string
}

// recoveryShow shows a new set of recovery codes. They stay readable with the token, by
// the same administrator, for its 5 minutes or until "完成" (a reload or a prefetch does
// not lose them).
func (s *Server) recoveryShow(w http.ResponseWriter, r *http.Request) {
	token := r.URL.Query().Get("t")
	codes, ok := s.shown.peek(s.Now(), token, current(r).ID)
	if !ok {
		http.Redirect(w, r, "/me#two-step", http.StatusSeeOther)
		return
	}
	s.ok(w, r, "recovery_codes", &Page{Title: "恢复码", Nav: "me", Data: recoveryData{Codes: codes, Token: token}})
}

// recoveryDone ("完成") forgets the codes shown.
func (s *Server) recoveryDone(w http.ResponseWriter, r *http.Request) {
	s.shown.take(s.Now(), r.FormValue("t"), current(r).ID)
	http.Redirect(w, r, "/me#two-step", http.StatusSeeOther)
}

// ---- reset and policy (super administrators) ----

func (s *Server) adminResetTwoStep(w http.ResponseWriter, r *http.Request) {
	a := s.loadAdmin(w, r)
	if a == nil {
		return
	}
	if a.Role != store.RoleRegion {
		s.redirect(w, r, "/admins/"+itoa(a.ID), "error", "超级管理员的两步验证只能在服务器上用命令行重置。")
		return
	}
	if err := s.St.ResetTwoStep(r.Context(), a.ID); err != nil {
		s.internal(w, r, err)
		return
	}
	s.audit(r, "two_step.reset", "admin", a.ID, a.Username, nil, nil)
	s.redirect(w, r, "/admins/"+itoa(a.ID), "ok", "「"+a.Username+"」的两步验证已重置。")
}

func (s *Server) policySave(w http.ResponseWriter, r *http.Request) {
	on := r.FormValue("required") == "1"
	old, err := s.St.TwoStepRequired(r.Context())
	if err == nil && old != on {
		err = s.St.SetTwoStepRequired(r.Context(), on)
	}
	if err != nil {
		s.internal(w, r, err)
		return
	}
	if old != on {
		s.audit(r, "two_step.policy", "system", 0, "两步验证", nil, map[string]any{"required": on})
	}
	s.redirect(w, r, "/system#security", "ok", "已保存。")
}
