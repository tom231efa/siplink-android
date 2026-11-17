package web

import (
	"errors"
	"fmt"
	"io"
	"net/http"
	"strconv"
	"strings"
	"time"

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
}

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
	card := &certCard{Mode: c.Mode(), HTTPS: pki.SourceLabel(https, "反向代理（SIPLINK_TLS=off）"),
		LDAP: pki.SourceLabel(ldap, "未开启"), Paused: c.Paused(), ReminderDays: c.ReminderDays(r.Context()),
		MaxDays: pki.MaxReminderDays}
	card.Notices = c.Notices(card.ReminderDays)
	httpsFiles, ldapFiles := c.Files()
	if httpsFiles != nil {
		card.Own = append(card.Own, s.ownInfo("HTTPS 证书文件", httpsFiles, ""))
	}
	if ldapFiles != nil && ldapFiles != httpsFiles {
		card.Own = append(card.Own, s.ownInfo("LDAP 证书文件", ldapFiles, ""))
	}
	if o := c.Uploaded(); o != nil {
		card.HasUploaded = true
		card.Own = append(card.Own, s.ownInfo("自有证书", o, usesText(https, ldap, c.Options())))
	}
	return card
}

// usesText says which listeners use the uploaded certificate, and why not the others.
func usesText(https, ldap pki.Source, opt pki.Options) string {
	var used, notes []string
	if https == pki.SourceOwn {
		used = append(used, "HTTPS")
	}
	if ldap == pki.SourceOwn {
		used = append(used, "LDAP")
	} else if ldap == pki.SourceBuiltin {
		notes = append(notes, "证书不包含 LDAP 地址 "+opt.LDAPHost+"，LDAP 使用内置 CA 签发的证书")
	}
	if https == pki.SourceBuiltin {
		notes = append(notes, "证书不包含公开地址的主机名 "+opt.HTTPSHost+"，HTTPS 使用内置 CA 签发的证书")
	}
	text := "未使用"
	if len(used) > 0 {
		text = strings.Join(used, " 与 ")
	}
	if len(notes) > 0 {
		text += "（" + strings.Join(notes, "；") + "）"
	}
	return text
}

func (s *Server) ownInfo(title string, o *pki.Own, uses string) ownInfo {
	leaf := o.Leaf()
	i := ownInfo{Title: title, Subject: leaf.Subject.String(), Issuer: leaf.Issuer.String(), Names: o.Names(),
		NotAfter: leaf.NotAfter, DaysLeft: s.Ext.Certs.DaysLeft(leaf.NotAfter), Fingerprint: pki.Fingerprint(leaf), Uses: uses}
	switch p := o.Private(); {
	case o.Public:
		i.Trust = "公共 CA（二维码不带 pin）"
	case p != nil:
		i.Trust = "私有根证书「" + p.Subject.String() + "」（二维码带 pin）"
	default:
		i.Trust = "设备无法信任：不受公共 CA 信任，且证书链不含根证书"
	}
	if len(o.EmptyAKI) > 0 {
		parts := make([]string, len(o.EmptyAKI))
		for k, n := range o.EmptyAKI {
			parts[k] = strconv.Itoa(n)
		}
		i.EmptyAKI = "证书链中第 " + strings.Join(parts, "、") + " 个证书的 AuthorityKeyIdentifier 扩展为空，设备的 SIP/LDAP TLS 库（mbedTLS 3.6.0）将中止握手"
	}
	return i
}

// certUpload checks an uploaded chain and key. Without warnings it is used at once;
// with warnings it waits in memory for a confirmation (certConfirm).
func (s *Server) certUpload(w http.ResponseWriter, r *http.Request) {
	r.Body = http.MaxBytesReader(w, r.Body, 2*maxCertFile+16<<10)
	// Everything stays in memory: the private key never reaches a temporary file.
	if err := r.ParseMultipartForm(4 * maxCertFile); err != nil {
		s.certRefused(w, r, "上传失败：单个文件不得超过 64 KB，请通过本页表单上传。")
		return
	}
	defer r.MultipartForm.RemoveAll()
	a, err := s.assessUpload(r)
	var refusal *pki.Refusal
	switch {
	case errors.As(err, &refusal):
		s.certRefused(w, r, refusal.Reason)
	case err != nil:
		s.internal(w, r, err)
	case len(a.Warnings) == 0:
		s.certImport(w, r, a)
	default:
		token := s.pending.put(s.Now(), current(r).ID, a.Own)
		s.renderSystem(w, r, http.StatusOK, func(c *certCard) { c.Warnings, c.Token = a.Warnings, token })
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
	label := map[string]string{"chain": "证书链文件", "key": "私钥文件"}[name]
	f, h, err := r.FormFile(name)
	if err != nil {
		return nil, &pki.Refusal{Reason: "请选择" + label + "。"}
	}
	defer f.Close()
	if h.Size > maxCertFile {
		return nil, &pki.Refusal{Reason: label + "过大（最多 64 KB）。"}
	}
	return io.ReadAll(io.LimitReader(f, maxCertFile))
}

func (s *Server) certRefused(w http.ResponseWriter, r *http.Request, reason string) {
	s.renderSystem(w, r, http.StatusBadRequest, func(c *certCard) { c.Error = "证书未启用：" + reason })
}

// certConfirm uses an upload whose warnings the administrator has read. It is checked
// again: time may have passed.
func (s *Server) certConfirm(w http.ResponseWriter, r *http.Request) {
	o, ok := s.pending.take(s.Now(), r.FormValue("token"), current(r).ID)
	if !ok {
		s.redirect(w, r, "/system", "error", "确认已失效，请重新上传证书。")
		return
	}
	a, err := s.Ext.Certs.Assess(r.Context(), o)
	var refusal *pki.Refusal
	if errors.As(err, &refusal) {
		s.certRefused(w, r, refusal.Reason)
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
	s.redirect(w, r, "/system", "ok", "已启用自有证书。")
}

// certRemove returns to the built-in CA. When phones would have to scan again it asks first.
func (s *Server) certRemove(w http.ResponseWriter, r *http.Request) {
	c := s.Ext.Certs
	o := c.Uploaded()
	if o == nil {
		s.redirect(w, r, "/system", "error", "尚未上传自有证书。")
		return
	}
	warnings, rescan, err := c.RemovalWarnings(r.Context())
	if err != nil {
		s.internal(w, r, err)
		return
	}
	if len(warnings) > 0 && r.FormValue("confirm") != "1" {
		s.renderSystem(w, r, http.StatusOK, func(card *certCard) {
			card.Warnings, card.RemoveAsk, card.RemoveRescan = warnings, true, rescan
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
	s.redirect(w, r, "/system", "ok", "已停用自有证书，已恢复使用内置 CA。")
}

func (s *Server) certReminder(w http.ResponseWriter, r *http.Request) {
	c := s.Ext.Certs
	old := c.ReminderDays(r.Context())
	days, err := strconv.Atoi(strings.TrimSpace(r.FormValue("days")))
	if err != nil || days < 1 || days > pki.MaxReminderDays {
		s.renderSystem(w, r, http.StatusBadRequest, func(c *certCard) {
			c.Error = fmt.Sprintf("到期前提醒天数须为 1 到 %d 的整数。", pki.MaxReminderDays)
		})
		return
	}
	if err := c.SetReminderDays(r.Context(), days); err != nil {
		s.internal(w, r, err)
		return
	}
	if days != old {
		s.audit(r, "pki.reminder", "system", 0, "到期前提醒天数", nil, changes(field{"reminder_days", old, days}))
	}
	s.redirect(w, r, "/system", "ok", fmt.Sprintf("已设置为到期前 %d 天提醒。", days))
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
			b.WriteString(n.Level + " " + n.Text + "\n")
		}
	}
	w.Write([]byte(b.String()))
}
