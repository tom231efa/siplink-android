package web

import (
	"crypto/x509"
	"errors"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"

	"siplink/internal/backup"
	"siplink/internal/pki"
	"siplink/internal/rules"
	"siplink/internal/store"
)

func (s *Server) routesSystem() {
	s.page("GET /system", s.requireSuper(s.systemPage))
	s.page("POST /system/backup", s.requireSuper(s.systemBackup))
	s.page("POST /system/reissue", s.requireSuper(s.systemReissue))
	s.page("GET /system/ca.pem", s.caDownload)
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
	KeyFile     string
	// TwoStepRequired: region administrators must use two-step sign-in (§6.8).
	TwoStepRequired bool
}

func (s *Server) systemPage(w http.ResponseWriter, r *http.Request) {
	s.renderSystem(w, r, http.StatusOK, nil)
}

// renderSystem shows the system page; card may add to the certificate card (an
// upload's refusal, warnings waiting for a confirmation).
func (s *Server) renderSystem(w http.ResponseWriter, r *http.Request, status int, card func(*certCard)) {
	c := s.Cfg
	d := systemData{Version: Version, PublicURL: c.PublicURL, LDAPHost: c.LDAPPublicHost, LDAPPort: c.LDAPPublicPort,
		LDAPSuffix: c.LDAPSuffix, RequireTLS: c.LDAPRequireTLS, Cert: s.certCard(r),
		BackupTime: c.BackupTime, BackupKeep: c.BackupKeep, KeyFile: c.KeyFile}
	if card != nil {
		card(d.Cert)
	}
	switch sec, ok := rules.HostedSecurity(c.LDAPPublicHost, c.LDAPRequireTLS); {
	case !ok:
		d.HostedNote = rules.HostedNeedsName
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
	var err error
	if d.TwoStepRequired, err = s.St.TwoStepRequired(r.Context()); err != nil {
		s.internal(w, r, err)
		return
	}
	s.render(w, r, status, "system", &Page{Title: "系统", Nav: "system", Data: d})
}

func (s *Server) systemBackup(w http.ResponseWriter, r *http.Request) {
	name, err := backup.Now(r.Context(), s.St.DB, backup.Dir(s.Cfg.DataDir), s.Now().Local())
	if err != nil {
		s.Log.Error("backup failed", "err", err)
		s.redirect(w, r, "/system", "error", "备份失败，请查看日志。")
		return
	}
	backup.Prune(backup.Dir(s.Cfg.DataDir), s.Cfg.BackupKeep)
	s.audit(r, "backup.create", "backup", 0, name, nil, nil)
	s.redirect(w, r, "/system", "ok", "已备份至 "+name+"。")
}

// systemReissue issues a new server certificate of the built-in CA (the CA stays);
// not while issuing is paused.
func (s *Server) systemReissue(w http.ResponseWriter, r *http.Request) {
	if err := s.Ext.Certs.Issue(r.Context()); errors.Is(err, pki.ErrPaused) {
		s.redirect(w, r, "/system", "error", err.Error())
		return
	} else if err != nil {
		s.internal(w, r, err)
		return
	}
	s.audit(r, "pki.reissue", "system", 0, strings.Join(s.Cfg.CertNames(), ", "), nil, nil)
	s.redirect(w, r, "/system", "ok", "已重新签发服务器证书，新连接将立即使用。")
}

// caDownload gives the built-in CA certificate (public) to any administrator, for
// browsers and desk phones.
func (s *Server) caDownload(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/x-pem-file")
	w.Header().Set("Content-Disposition", `attachment; filename="siplink-ca.pem"`)
	w.Write(s.Ext.Certs.CA().CAPEM())
}
