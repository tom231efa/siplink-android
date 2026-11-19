package web

import (
	"crypto/x509"
	"errors"
	"html/template"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"

	"siplink/internal/backup"
	"siplink/internal/i18n"
	"siplink/internal/pki"
	"siplink/internal/rules"
	"siplink/internal/store"
)

func (s *Server) routesSystem() {
	s.page("GET /system", s.requireSuper(s.systemPage))
	s.page("POST /system/backup", s.requireSuper(s.systemBackup))
	s.page("POST /system/reissue", s.requireSuper(s.systemReissue))
	s.page("POST /system/password-check", s.requireSuper(s.passwordCheckSave))
	s.page("GET /system/ca.pem", s.caDownload)
	s.page("POST /system/backups/download", s.requireSuper(s.backupDownload))
	s.routesCertificate()
}

// certInfo is a certificate as the system page shows it.
type certInfo struct {
	Subject     string
	NotBefore   time.Time
	NotAfter    time.Time
	Fingerprint string
	Names       []string
}

func infoOf(c *x509.Certificate) *certInfo {
	if c == nil {
		return nil
	}
	i := &certInfo{Subject: c.Subject.String(), NotBefore: c.NotBefore, NotAfter: c.NotAfter, Fingerprint: pki.Fingerprint(c)}
	i.Names = append(i.Names, c.DNSNames...)
	for _, ip := range c.IPAddresses {
		i.Names = append(i.Names, ip.String())
	}
	return i
}

type systemData struct {
	Version     string
	PublicURL   string
	LDAPHost    string
	LDAPPort    int
	LDAPSuffix  string
	RequireTLS  bool
	HostedNote  string
	HostedPlain bool
	Cert        *certCard
	CA          *certInfo
	Server      *certInfo
	DBSize      int64
	Backups     []backup.File
	BackupTime  string
	BackupKeep  int
	KeyNote     template.HTML // the backup card's warning, naming the key file
	// TwoStepRequired: region administrators must use two-step sign-in (§6.8).
	TwoStepRequired bool
	// PasswordCheck: new administrator passwords are checked for strength (§6.1).
	PasswordCheck bool
	// Download is the backup whose download dialog the server opens (?backup=, or a
	// refused download), DownloadError the refusal, DownloadPlain the switch 加密 left off.
	Download      string
	DownloadError string
	DownloadPlain bool
}

func (s *Server) systemPage(w http.ResponseWriter, r *http.Request) {
	s.renderSystem(w, r, http.StatusOK, nil)
}

// renderSystem shows the system page; edit may add the outcome of a form (an upload's
// refusal, warnings waiting for a confirmation, a refused backup download).
func (s *Server) renderSystem(w http.ResponseWriter, r *http.Request, status int, edit func(*systemData)) {
	c := s.Cfg
	d := systemData{Version: Version, PublicURL: c.PublicURL, LDAPHost: c.LDAPPublicHost, LDAPPort: c.LDAPPublicPort,
		LDAPSuffix: c.LDAPSuffix, RequireTLS: c.LDAPRequireTLS, Cert: s.certCard(r),
		BackupTime: c.BackupTime, BackupKeep: c.BackupKeep, KeyNote: monoText(tr(r), "system.backup.key_note", "file", c.KeyFile)}
	switch sec, ok := rules.HostedSecurity(c.LDAPPublicHost, c.LDAPRequireTLS); {
	case !ok:
		d.HostedNote = tr(r).Msg(rules.HostedNeedsName)
	case sec == rules.LdapSecurityNone:
		d.HostedPlain = true
	}
	ca := s.Ext.Certs.CA()
	d.CA, d.Server = infoOf(ca.CA()), infoOf(ca.Server())
	for _, f := range []string{store.FileName, store.FileName + "-wal"} {
		if st, err := os.Stat(filepath.Join(c.DataDir, f)); err == nil {
			d.DBSize += st.Size()
		}
	}
	d.Backups, _ = backup.List(backup.Dir(c.DataDir))
	for _, b := range d.Backups {
		if b.Name == r.URL.Query().Get("backup") {
			d.Download = b.Name
		}
	}
	var err error
	if d.TwoStepRequired, err = s.St.TwoStepRequired(r.Context()); err != nil {
		s.internal(w, r, err)
		return
	}
	if d.PasswordCheck, err = s.St.PasswordCheck(r.Context()); err != nil {
		s.internal(w, r, err)
		return
	}
	if edit != nil {
		edit(&d)
	}
	s.render(w, r, status, "system", &Page{Title: tr(r).T("nav.system"), Nav: "system", Data: d})
}

// monoText is the text of key with its argument name set as <span class="mono">value</span>
// (a path, a server's answer), the rest escaped.
func monoText(t *i18n.Tr, key, name, value string) template.HTML {
	const mark = "\x01"
	before, after, _ := strings.Cut(t.T(key, name, mark), mark)
	return template.HTML(template.HTMLEscapeString(before) + `<span class="mono">` + template.HTMLEscapeString(value) +
		`</span>` + template.HTMLEscapeString(after))
}

func (s *Server) systemBackup(w http.ResponseWriter, r *http.Request) {
	name, err := backup.Now(r.Context(), s.St.DB, backup.Dir(s.Cfg.DataDir), s.Now().Local())
	if err != nil {
		s.Log.Error("backup failed", "err", err)
		s.redirect(w, r, "/system", "error", tr(r).T("system.backup.failed"))
		return
	}
	backup.Prune(backup.Dir(s.Cfg.DataDir), s.Cfg.BackupKeep)
	s.audit(r, "backup.create", "backup", 0, name, nil, nil)
	s.redirect(w, r, "/system", "ok", tr(r).T("system.backup.done", "name", name))
}

// systemReissue issues a new server certificate of the built-in CA (the CA stays);
// not while issuing is paused.
func (s *Server) systemReissue(w http.ResponseWriter, r *http.Request) {
	if err := s.Ext.Certs.Issue(r.Context()); errors.Is(err, pki.ErrPaused) {
		s.redirect(w, r, "/system", "error", errText(r, err))
		return
	} else if err != nil {
		s.internal(w, r, err)
		return
	}
	s.audit(r, "pki.reissue", "system", 0, strings.Join(s.Cfg.CertNames(), ", "), nil, nil)
	s.redirect(w, r, "/system", "ok", tr(r).T("system.cert.reissued"))
}

// passwordCheckSave switches the strength check of new administrator passwords.
func (s *Server) passwordCheckSave(w http.ResponseWriter, r *http.Request) {
	on := r.FormValue("check") == "1"
	old, err := s.St.PasswordCheck(r.Context())
	if err == nil && old != on {
		err = s.St.SetPasswordCheck(r.Context(), on)
	}
	if err != nil {
		s.internal(w, r, err)
		return
	}
	if old != on {
		s.audit(r, "password.policy", "system", 0, "password_check", nil, map[string]any{"check": on})
	}
	s.redirect(w, r, "/system#security", "ok", tr(r).T("common.saved"))
}

// caDownload gives the built-in CA certificate (public) to any administrator, for
// browsers and desk phones.
func (s *Server) caDownload(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/x-pem-file")
	w.Header().Set("Content-Disposition", `attachment; filename="siplink-ca.pem"`)
	w.Write(s.Ext.Certs.CA().CAPEM())
}
