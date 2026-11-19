package web

import (
	"errors"
	"io"
	"net/http"
	"strconv"
	"strings"
	"time"

	"siplink/internal/i18n"
	"siplink/internal/pki"
)

// The operator's own certificate on the system page (design §6.7).

func (s *Server) routesCertificate() {
	s.page("POST /system/certificate", s.requireSuper(s.certUpload))
	s.page("POST /system/certificate/confirm", s.requireSuper(s.certConfirm))
	s.page("POST /system/certificate/remove", s.requireSuper(s.certRemove))
	s.page("POST /system/certificate/reminder", s.requireSuper(s.certReminder))
}

// maxCertFile is the size limit of each uploaded file (chain, key).
const maxCertFile = 64 << 10

// certCard is the certificate card of the system page.
type certCard struct {
	Mode         string
	HTTPS, LDAP  string
	Paused       bool
	Own          []ownInfo // files from the environment, then the uploaded certificate
	HasUploaded  bool
	Notices      []pki.Notice
	ReminderDays int
	MaxDays      int
	// The outcome of a form: a refusal, or warnings that wait for a confirmation.
	Error        string
	Warnings     []string
	Token        string // confirms the pending upload
	RemoveAsk    bool   // the removal waits for a confirmation
	RemoveRescan int    // phones that would have to scan again after the removal
	// The card shows one source at a time (View: certBuiltin or certOwn), opening on the
	// one in use; looking at the other changes nothing. Both can be in use (mixed).
	View                 string
	BuiltinUsed, OwnUsed bool
}

// The two views of the certificate card.
const (
	certBuiltin = "builtin"
	certOwn     = "own"
)

// ownInfo is a certificate of the operator's as the card shows it.
type ownInfo struct {
	Title       string
	Subject     string
	Issuer      string
	Names       []string
	NotAfter    time.Time
	DaysLeft    int
	Fingerprint string
	Trust       string
	Uses        string
	EmptyAKI    string
}

func (s *Server) certCard(r *http.Request) *certCard {
	c := s.Ext.Certs
	https, ldap := c.Sources()
	t := tr(r)
	card := &certCard{Mode: t.Msg(c.Mode()), HTTPS: t.Msg(pki.SourceLabel(https, i18n.M("pki.source.proxy"))),
		LDAP: t.Msg(pki.SourceLabel(ldap, i18n.M("pki.source.off"))), Paused: c.Paused(), ReminderDays: c.ReminderDays(r.Context()),
		MaxDays: pki.MaxReminderDays}
	card.Notices = c.Notices(card.ReminderDays)
	card.BuiltinUsed = https == pki.SourceBuiltin || ldap == pki.SourceBuiltin
	card.OwnUsed = https == pki.SourceOwn || https == pki.SourceFiles || ldap == pki.SourceOwn || ldap == pki.SourceFiles
	switch v := r.URL.Query().Get("cert"); {
	case v == certBuiltin || v == certOwn:
		card.View = v
	case card.OwnUsed:
		card.View = certOwn
	default:
		card.View = certBuiltin
	}
	httpsFiles, ldapFiles := c.Files()
	if httpsFiles != nil {
		card.Own = append(card.Own, s.ownInfo(t, t.T("system.cert.https_files"), httpsFiles, ""))
	}
	if ldapFiles != nil && ldapFiles != httpsFiles {
		card.Own = append(card.Own, s.ownInfo(t, t.T("system.cert.ldap_files"), ldapFiles, ""))
	}
	if o := c.Uploaded(); o != nil {
		card.HasUploaded = true
		card.Own = append(card.Own, s.ownInfo(t, t.T("system.cert.own"), o, usesText(t, https, ldap, c.Options())))
	}
	return card
}

// usesText says which listeners use the uploaded certificate, and why not the others.
func usesText(t *i18n.Tr, https, ldap pki.Source, opt pki.Options) string {
	text := t.T("system.cert.unused")
	switch {
	case https == pki.SourceOwn && ldap == pki.SourceOwn:
		text = t.T("system.cert.uses_both")
	case https == pki.SourceOwn:
		text = "HTTPS"
	case ldap == pki.SourceOwn:
		text = "LDAP"
	}
	var notes []string
	if ldap == pki.SourceBuiltin {
		notes = append(notes, t.T("system.cert.ldap_builtin", "host", opt.LDAPHost))
	}
	if https == pki.SourceBuiltin {
		notes = append(notes, t.T("system.cert.https_builtin", "host", opt.HTTPSHost))
	}
	if len(notes) > 0 {
		text = t.T("system.cert.uses_notes", "uses", text, "notes", strings.Join(notes, t.T("format.problem_sep")))
	}
	return text
}

func (s *Server) ownInfo(t *i18n.Tr, title string, o *pki.Own, uses string) ownInfo {
	leaf := o.Leaf()
	i := ownInfo{Title: title, Subject: leaf.Subject.String(), Issuer: leaf.Issuer.String(), Names: o.Names(),
		NotAfter: leaf.NotAfter, DaysLeft: s.Ext.Certs.DaysLeft(leaf.NotAfter), Fingerprint: pki.Fingerprint(leaf), Uses: uses}
	switch p := o.Private(); {
	case o.Public:
		i.Trust = t.T("system.cert.trust_public")
	case p != nil:
		i.Trust = t.T("system.cert.trust_private", "name", p.Subject.String())
	default:
		i.Trust = t.T("system.cert.trust_none")
	}
	if len(o.EmptyAKI) > 0 {
		i.EmptyAKI = t.T("system.cert.empty_aki", "n", o.EmptyAKI)
	}
	return i
}

// certUpload checks an uploaded chain and key. Without warnings it is used at once;
// with warnings it waits in memory for a confirmation (certConfirm).
func (s *Server) certUpload(w http.ResponseWriter, r *http.Request) {
	r.Body = http.MaxBytesReader(w, r.Body, 2*maxCertFile+16<<10)
	// Everything stays in memory: the private key never reaches a temporary file.
	if err := r.ParseMultipartForm(4 * maxCertFile); err != nil {
		s.certRefused(w, r, tr(r).T("system.cert.upload_too_big", "max", maxCertFile>>10))
		return
	}
	defer r.MultipartForm.RemoveAll()
	a, err := s.assessUpload(r)
	var refusal *pki.Refusal
	switch {
	case errors.As(err, &refusal):
		s.certRefused(w, r, tr(r).Msg(refusal.Reason))
	case err != nil:
		s.internal(w, r, err)
	case len(a.Warnings) == 0:
		s.certImport(w, r, a)
	default:
		token := s.pending.put(s.Now(), current(r).ID, a.Own)
		s.renderSystem(w, r, http.StatusOK, func(d *systemData) {
			d.Cert.Warnings, d.Cert.Token, d.Cert.View = tr(r).Msgs(a.Warnings), token, certOwn
		})
	}
}

func (s *Server) assessUpload(r *http.Request) (*pki.Assessment, error) {
	chain, err := formFile(r, "chain")
	if err != nil {
		return nil, err
	}
	key, err := formFile(r, "key")
	if err != nil {
		return nil, err
	}
	o, err := pki.ParseOwn(chain, key)
	if err != nil {
		return nil, err
	}
	return s.Ext.Certs.Assess(r.Context(), o)
}

func formFile(r *http.Request, name string) ([]byte, error) {
	label := i18n.M("pki.file." + name)
	f, h, err := r.FormFile(name)
	if err != nil {
		return nil, &pki.Refusal{Reason: i18n.M("pki.file.missing", "file", label)}
	}
	defer f.Close()
	if h.Size > maxCertFile {
		return nil, &pki.Refusal{Reason: i18n.M("pki.file.too_big", "file", label, "max", maxCertFile>>10)}
	}
	return io.ReadAll(io.LimitReader(f, maxCertFile))
}

func (s *Server) certRefused(w http.ResponseWriter, r *http.Request, reason string) {
	s.renderSystem(w, r, http.StatusBadRequest, func(d *systemData) {
		d.Cert.Error, d.Cert.View = tr(r).T("system.cert.refused", "reason", reason), certOwn
	})
}

// certConfirm uses an upload whose warnings the administrator has read. It is checked
// again: time may have passed.
func (s *Server) certConfirm(w http.ResponseWriter, r *http.Request) {
	o, ok := s.pending.take(s.Now(), r.FormValue("token"), current(r).ID)
	if !ok {
		s.redirect(w, r, "/system", "error", tr(r).T("system.cert.confirm_expired"))
		return
	}
	a, err := s.Ext.Certs.Assess(r.Context(), o)
	var refusal *pki.Refusal
	if errors.As(err, &refusal) {
		s.certRefused(w, r, tr(r).Msg(refusal.Reason))
		return
	} else if err != nil {
		s.internal(w, r, err)
		return
	}
	s.certImport(w, r, a)
}

func (s *Server) certImport(w http.ResponseWriter, r *http.Request, a *pki.Assessment) {
	if err := s.Ext.Certs.Import(r.Context(), a.Own); err != nil {
		s.internal(w, r, err)
		return
	}
	s.audit(r, "pki.own_import", "system", 0, a.Own.Leaf().Subject.String(), nil, pki.AuditDetail(a))
	s.redirect(w, r, "/system", "ok", tr(r).T("system.cert.imported"))
}

// certRemove returns to the built-in CA. When phones would have to scan again it asks first.
func (s *Server) certRemove(w http.ResponseWriter, r *http.Request) {
	c := s.Ext.Certs
	o := c.Uploaded()
	if o == nil {
		s.redirect(w, r, "/system?cert=own#certificate", "error", tr(r).T("system.cert.not_uploaded"))
		return
	}
	warnings, rescan, err := c.RemovalWarnings(r.Context())
	if err != nil {
		s.internal(w, r, err)
		return
	}
	if len(warnings) > 0 && r.FormValue("confirm") != "1" {
		s.renderSystem(w, r, http.StatusOK, func(d *systemData) {
			card := d.Cert
			card.Warnings, card.RemoveAsk, card.RemoveRescan, card.View = tr(r).Msgs(warnings), true, rescan, certOwn
		})
		return
	}
	reason, err := c.Remove(r.Context())
	if err != nil {
		s.internal(w, r, err)
		return
	}
	detail := map[string]any{"fingerprint": pki.Fingerprint(o.Leaf())}
	if rescan > 0 {
		detail["rescan"] = rescan
	}
	if reason != "" {
		detail["issued"] = reason
	}
	s.audit(r, "pki.own_remove", "system", 0, o.Leaf().Subject.String(), nil, detail)
	s.redirect(w, r, "/system", "ok", tr(r).T("system.cert.removed"))
}

func (s *Server) certReminder(w http.ResponseWriter, r *http.Request) {
	c := s.Ext.Certs
	old := c.ReminderDays(r.Context())
	days, err := strconv.Atoi(strings.TrimSpace(r.FormValue("days")))
	if err != nil || days < 1 || days > pki.MaxReminderDays {
		s.renderSystem(w, r, http.StatusBadRequest, func(d *systemData) {
			d.Cert.Error, d.Cert.View = tr(r).T("system.cert.reminder_invalid", "max", pki.MaxReminderDays), certOwn
		})
		return
	}
	if err := c.SetReminderDays(r.Context(), days); err != nil {
		s.internal(w, r, err)
		return
	}
	if days != old {
		s.audit(r, "pki.reminder", "system", 0, "cert_reminder", nil, changes(field{"reminder_days", old, days}))
	}
	s.redirect(w, r, "/system?cert=own#certificate", "ok", tr(r).T("system.cert.reminder_saved", "n", days))
}

// certNotices are the reminders the dashboard shows super administrators.
func (s *Server) certNotices(r *http.Request) []pki.Notice {
	return s.Ext.Certs.Notices(s.Ext.Certs.ReminderDays(r.Context()))
}

// healthz answers "ok" and one line per certificate notice ("warn …", "error …"); the
// health command reads them (design §6.7). The status stays 200: a reverse proxy must
// not take the platform out of service for a reminder.
func (s *Server) healthz(w http.ResponseWriter, r *http.Request) {
	var b strings.Builder
	b.WriteString("ok\n")
	if s.Ext.Certs != nil {
		for _, n := range s.certNotices(r) {
			b.WriteString(n.Level + " " + tr(r).Msg(n.Text) + "\n")
		}
	}
	w.Write([]byte(b.String()))
}
