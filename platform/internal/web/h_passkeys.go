package web

import (
	"bytes"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strings"
	"unicode/utf8"

	"siplink/internal/config"
	"siplink/internal/i18n"
	"siplink/internal/store"
	"siplink/internal/twostep"

	"github.com/go-webauthn/webauthn/protocol"
	"github.com/go-webauthn/webauthn/webauthn"
)

// Passkeys (WebAuthn) as a second step (design §6.8). The browser side is static/passkey.js;
// ceremonies are JSON requests from the same origin, under the usual CSRF protection.

// newPasskeys makes the relying party, or nil when browsers refuse WebAuthn at the
// public URL (then passkeys are neither offered nor accepted).
func newPasskeys(cfg *config.Config) (*webauthn.WebAuthn, error) {
	if !twostep.PasskeysUsable(cfg.PublicURL) {
		return nil, nil
	}
	return webauthn.New(&webauthn.Config{
		RPID:                  strings.ToLower(cfg.PublicHost),
		RPDisplayName:         i18n.For(i18n.Source).T("app.name"), // one name for every language, as before
		RPOrigins:             []string{twostep.Origin(cfg.PublicURL)},
		AttestationPreference: protocol.PreferNoAttestation,
		AuthenticatorSelection: protocol.AuthenticatorSelection{
			ResidentKey:      protocol.ResidentKeyRequirementPreferred,
			UserVerification: protocol.VerificationPreferred,
		},
	})
}

// passkeyUser is an administrator as the WebAuthn library sees them.
type passkeyUser struct {
	admin *store.Admin
	rows  []store.Passkey
	creds []webauthn.Credential // same order as rows
}

func (u *passkeyUser) WebAuthnID() []byte {
	return binary.BigEndian.AppendUint64(nil, uint64(u.admin.ID))
}
func (u *passkeyUser) WebAuthnName() string { return u.admin.Username }
func (u *passkeyUser) WebAuthnDisplayName() string {
	if u.admin.DisplayName != "" {
		return u.admin.DisplayName
	}
	return u.admin.Username
}
func (u *passkeyUser) WebAuthnCredentials() []webauthn.Credential { return u.creds }

func (s *Server) passkeyUser(r *http.Request, a *store.Admin) (*passkeyUser, error) {
	rows, err := s.St.Passkeys(r.Context(), a.ID)
	if err != nil {
		return nil, err
	}
	u := &passkeyUser{admin: a, rows: rows}
	for _, p := range rows {
		var c webauthn.Credential
		if err := json.Unmarshal([]byte(p.CredentialJSON), &c); err != nil {
			return nil, err
		}
		u.creds = append(u.creds, c)
	}
	return u, nil
}

// passkeyRequest is the body the browser posts to finish a ceremony.
type passkeyRequest struct {
	Token      string          `json:"token"`
	Name       string          `json:"name"`
	Next       string          `json:"next"`
	Credential json.RawMessage `json:"credential"`
}

const maxPasskeyBody = 64 << 10

func readPasskeyRequest(w http.ResponseWriter, r *http.Request) (*passkeyRequest, error) {
	body, err := io.ReadAll(http.MaxBytesReader(w, r.Body, maxPasskeyBody))
	if err != nil {
		return nil, err
	}
	var req passkeyRequest
	if err := json.NewDecoder(bytes.NewReader(body)).Decode(&req); err != nil || len(req.Credential) == 0 {
		return nil, errors.New("bad request")
	}
	return &req, nil
}

// ---- registration (我的账号) ----

func (s *Server) passkeyOptions(w http.ResponseWriter, r *http.Request) {
	a := current(r)
	if s.passkeys == nil {
		jsonError(w, http.StatusNotFound, tr(r).T("passkey.unavailable"))
		return
	}
	u, err := s.passkeyUser(r, a)
	if err != nil {
		s.Log.Error("passkeys unreadable", "admin", a.ID, "err", err)
		jsonError(w, http.StatusInternalServerError, tr(r).T("passkey.internal"))
		return
	}
	creation, session, err := s.passkeys.BeginRegistration(u,
		webauthn.WithExclusions(webauthn.Credentials(u.creds).CredentialDescriptors()))
	if err != nil {
		s.Log.Error("passkey registration start failed", "err", err)
		jsonError(w, http.StatusInternalServerError, tr(r).T("passkey.internal"))
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"token": s.ceremonies.put(s.Now(), a.ID, session), "options": creation})
}

func (s *Server) passkeyAdd(w http.ResponseWriter, r *http.Request) {
	a := current(r)
	if s.passkeys == nil {
		jsonError(w, http.StatusNotFound, tr(r).T("passkey.unavailable"))
		return
	}
	req, err := readPasskeyRequest(w, r)
	if err != nil {
		jsonError(w, http.StatusBadRequest, tr(r).T("passkey.bad_request"))
		return
	}
	name := strings.TrimSpace(req.Name)
	if name == "" || utf8.RuneCountInString(name) > 40 {
		jsonError(w, http.StatusBadRequest, tr(r).T("passkey.name_length"))
		return
	}
	session, ok := s.ceremonies.take(s.Now(), req.Token, a.ID)
	if !ok {
		jsonError(w, http.StatusBadRequest, tr(r).T("passkey.expired"))
		return
	}
	u, err := s.passkeyUser(r, a)
	if err != nil {
		jsonError(w, http.StatusInternalServerError, tr(r).T("passkey.internal"))
		return
	}
	parsed, err := protocol.ParseCredentialCreationResponseBytes(req.Credential)
	var cred *webauthn.Credential
	if err == nil {
		cred, err = s.passkeys.CreateCredential(u, *session, parsed)
	}
	if err != nil {
		s.Log.Info("passkey registration refused", "admin", a.ID, "err", err)
		jsonError(w, http.StatusBadRequest, tr(r).T("passkey.failed"))
		return
	}
	before, err := s.St.TwoStepState(r.Context(), a.ID)
	if err != nil {
		jsonError(w, http.StatusInternalServerError, tr(r).T("passkey.internal"))
		return
	}
	raw, _ := json.Marshal(cred)
	p := &store.Passkey{AdminID: a.ID, Name: name, CredentialID: base64.RawURLEncoding.EncodeToString(cred.ID), CredentialJSON: string(raw)}
	if err := s.St.AddPasskey(r.Context(), p); errors.Is(err, store.ErrConflict) {
		jsonError(w, http.StatusBadRequest, tr(r).T("passkey.exists"))
		return
	} else if err != nil {
		jsonError(w, http.StatusInternalServerError, tr(r).T("passkey.internal"))
		return
	}
	s.audit(r, "passkey.create", "admin", a.ID, a.Username, nil, map[string]any{"name": name})
	to, err := s.methodAdded(w, r, a, before, tr(r).T("passkey.added"))
	if err != nil {
		jsonError(w, http.StatusInternalServerError, tr(r).T("passkey.internal"))
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"redirect": to})
}

// errPasswordNeeded refuses the deletion of the last passkey without the password.
var errPasswordNeeded = errors.New("password needed")

// passkeyDelete removes a passkey. The last one asks for the password typed again; the
// decision and the removal are one store transaction, with the policy.
func (s *Server) passkeyDelete(w http.ResponseWriter, r *http.Request) {
	a := current(r)
	id, ok := pathID(r, "id")
	rows, err := s.St.Passkeys(r.Context(), a.ID)
	if err != nil {
		s.internal(w, r, err)
		return
	}
	var name string
	for _, p := range rows {
		if p.ID == id {
			name = p.Name
		}
	}
	if !ok || name == "" {
		s.notFound(w, r)
		return
	}
	password := r.FormValue("password")
	if password != "" {
		if msg := s.recheckPassword(r, a, password); msg != "" {
			s.redirect(w, r, "/me#two-step", "error", msg)
			return
		}
	}
	keep, err := s.keepRule(r.Context(), a)
	if err == nil {
		err = s.St.RemoveMethod(r.Context(), a.ID, store.MethodPasskey, id, func(before store.TwoStep) error {
			if before.Passkeys == 1 && password == "" {
				return errPasswordNeeded
			}
			return keep(before)
		})
	}
	switch {
	case errors.Is(err, store.ErrNotFound):
		s.notFound(w, r)
		return
	case errors.Is(err, errPasswordNeeded):
		s.redirect(w, r, "/me#two-step", "error", tr(r).T("passkey.password_needed"))
		return
	case errors.Is(err, store.ErrLastMethod):
		s.redirect(w, r, "/me#two-step", "error", tr(r).T("twostep.last_method"))
		return
	case err != nil:
		s.internal(w, r, err)
		return
	}
	s.audit(r, "passkey.delete", "admin", a.ID, a.Username, nil, map[string]any{"name": name})
	s.redirect(w, r, "/me#two-step", "ok", tr(r).T("passkey.deleted"))
}

// ---- sign-in (second step) ----

func (s *Server) stepPasskeyOptions(w http.ResponseWriter, r *http.Request) {
	a := pendingAdmin(r)
	if s.passkeys == nil {
		jsonError(w, http.StatusNotFound, tr(r).T("passkey.unavailable"))
		return
	}
	if s.stepFails.Exceeded(itoa(a.ID)) {
		jsonError(w, http.StatusTooManyRequests, tr(r).T("login.too_many"))
		return
	}
	u, err := s.passkeyUser(r, a)
	if err != nil || len(u.creds) == 0 {
		jsonError(w, http.StatusBadRequest, tr(r).T("passkey.none"))
		return
	}
	assertion, session, err := s.passkeys.BeginLogin(u)
	if err != nil {
		s.Log.Error("passkey login start failed", "err", err)
		jsonError(w, http.StatusInternalServerError, tr(r).T("passkey.internal"))
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"token": s.ceremonies.put(s.Now(), a.ID, session), "options": assertion})
}

func (s *Server) stepPasskey(w http.ResponseWriter, r *http.Request) {
	a := pendingAdmin(r)
	if s.passkeys == nil {
		jsonError(w, http.StatusNotFound, tr(r).T("passkey.unavailable"))
		return
	}
	req, err := readPasskeyRequest(w, r)
	if err != nil {
		jsonError(w, http.StatusBadRequest, tr(r).T("passkey.bad_request"))
		return
	}
	session, ok := s.ceremonies.take(s.Now(), req.Token, a.ID)
	if !ok {
		jsonError(w, http.StatusBadRequest, tr(r).T("passkey.expired"))
		return
	}
	u, err := s.passkeyUser(r, a)
	if err != nil {
		jsonError(w, http.StatusInternalServerError, tr(r).T("passkey.internal"))
		return
	}
	var cred *webauthn.Credential
	ok, limited, _ := s.tryStep(a, func() (bool, error) {
		parsed, err := protocol.ParseCredentialRequestResponseBytes(req.Credential)
		if err == nil {
			cred, err = s.passkeys.ValidateLogin(u, *session, parsed)
		}
		if err == nil && cred.Authenticator.CloneWarning {
			err = errors.New("sign count went backwards")
		}
		if err != nil {
			s.Log.Info("passkey sign-in refused", "admin", a.ID, "err", err)
		}
		return err == nil, nil
	})
	if limited {
		jsonError(w, http.StatusTooManyRequests, tr(r).T("login.too_many"))
		return
	}
	if !ok {
		s.stepFailed(r, a, "passkey")
		jsonError(w, http.StatusUnauthorized, tr(r).T("passkey.failed"))
		return
	}
	for i, c := range u.creds {
		if bytes.Equal(c.ID, cred.ID) {
			raw, _ := json.Marshal(cred)
			if err := s.St.UsePasskey(r.Context(), u.rows[i].ID, string(raw)); err != nil {
				s.Log.Error("passkey use not stored", "err", err)
			}
		}
	}
	to, err := s.completeStep(w, r, a, "passkey", req.Next)
	if err != nil {
		jsonError(w, http.StatusInternalServerError, tr(r).T("passkey.internal"))
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"redirect": to})
}
