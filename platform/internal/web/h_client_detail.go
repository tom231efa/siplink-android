package web

import (
	"encoding/json"
	"errors"
	"html/template"
	"net/http"
	"strings"
	"sync"
	"time"

	"siplink/internal/auth"
	"siplink/internal/provision"
	"siplink/internal/qr"
	"siplink/internal/secret"
	"siplink/internal/store"
)

// revealGrants remember who re-entered their password to see stored secrets (§6.4).
type revealGrants struct {
	mu    sync.Mutex
	until map[int64]time.Time // admin id → end of the grant
}

const revealFor = 5 * time.Minute

func (g *revealGrants) grant(adminID int64, now time.Time) {
	g.mu.Lock()
	defer g.mu.Unlock()
	if g.until == nil {
		g.until = map[int64]time.Time{}
	}
	g.until[adminID] = now.Add(revealFor)
}

func (g *revealGrants) valid(adminID int64, now time.Time) bool {
	g.mu.Lock()
	defer g.mu.Unlock()
	return now.Before(g.until[adminID])
}

// enrollURL is the QR content of a pending client (§3.1), "" when it has no valid token.
func (s *Server) enrollURL(c *store.Client) string {
	if c.Status != store.StatusPending || c.EnrollTokenEnc == "" || !store.ParseTime(c.EnrollExpiresAt).After(s.Now()) {
		return ""
	}
	token, err := s.Ext.Box.Open(c.EnrollTokenEnc, store.EnrollTokenAAD(c.ID))
	if err != nil {
		s.Log.Error("enrollment token does not open", "client", c.ID)
		return ""
	}
	u := strings.TrimRight(s.Cfg.PublicURL, "/") + "/p/" + string(token)
	if pin := s.Ext.Certs.Pin(); pin != "" {
		u += "?pin=" + pin
	}
	return u
}

type secretRow struct {
	Label, Value string
}

type clientDetail struct {
	C          *store.Client
	Region     string
	Group      *store.Group
	Tags       []*store.Tag
	Template   *store.ClientTemplate
	Config     *store.ConfigTemplate
	Keys       *store.KeyTemplate
	Creds      []credRow
	Missing    []string // profiles without credentials
	Dirs       []dirPassRow
	Report     *store.Report
	EnrollURL  string
	QR         template.HTML
	Expired    bool
	LDAPBindDN string
	Show       bool
	Secrets    []secretRow
	CanSecrets bool
	Problems   []string // why the current document does not validate
	Offline    bool     // store.Offline
	Version    string   // of the current document
	Error      string
}

func (s *Server) clientShow(w http.ResponseWriter, r *http.Request) {
	if c := s.loadClient(w, r); c != nil {
		s.renderClient(w, r, http.StatusOK, c, r.URL.Query().Get("show") == "1", "")
	}
}

func (s *Server) renderClient(w http.ResponseWriter, r *http.Request, status int, c *store.Client, show bool, errMsg string) {
	ctx := r.Context()
	a := current(r)
	d := clientDetail{C: c, Error: errMsg, CanSecrets: actor(r).Can(auth.ClientViewSecrets, &c.RegionID)}
	if reg, err := s.St.RegionByID(ctx, c.RegionID); err == nil {
		d.Region = reg.Name
	}
	if c.GroupID != nil {
		d.Group, _ = s.St.GroupByID(ctx, *c.GroupID)
	}
	tags, _ := s.allTags(ctx)
	byTag := tagsByID(tags)
	chosen, _ := s.St.ClientTags(ctx, []int64{c.ID})
	for _, id := range chosen[c.ID] {
		d.Tags = append(d.Tags, byTag[id])
	}
	t, profiles, dirs, err := s.clientParts(r, c.ClientTemplateID)
	if err != nil {
		s.internal(w, r, err)
		return
	}
	d.Template = t
	d.Config, _ = s.St.ConfigTemplateByID(ctx, t.ConfigTemplateID)
	if t.KeyTemplateID != nil {
		d.Keys, _ = s.St.KeyTemplateByID(ctx, *t.KeyTemplateID)
	}
	stored, _ := s.St.Credentials(ctx, c.ID)
	dirPass, _ := s.St.DirectoryPasswords(ctx, c.ID)
	d.Show = show && d.CanSecrets && s.reveals.valid(a.ID, s.Now())
	for _, p := range profiles {
		k := stored[p.ID]
		if k == nil {
			d.Missing = append(d.Missing, p.Name)
			continue
		}
		d.Creds = append(d.Creds, credRow{P: p, In: store.CredentialInput{Username: k.Username, AuthUser: k.AuthUser,
			DisplayName: k.DisplayName, AccountName: k.AccountName}, HasPassword: k.PasswordEnc != ""})
		if d.Show && k.PasswordEnc != "" {
			pw, err := s.Ext.Box.Open(k.PasswordEnc, store.CredentialAAD(c.ID, p.ID))
			if err == nil {
				d.Secrets = append(d.Secrets, secretRow{tr(r).T("clients.detail.secret_sip", "name", p.Name), string(pw)})
			}
		}
	}
	for _, dir := range dirs {
		d.Dirs = append(d.Dirs, dirPassRow{D: dir, HasPassword: dirPass[dir.ID] != ""})
		if d.Show && dirPass[dir.ID] != "" {
			if pw, err := s.Ext.Box.Open(dirPass[dir.ID], store.DirectoryPasswordAAD(c.ID, dir.ID)); err == nil {
				d.Secrets = append(d.Secrets, secretRow{tr(r).T("clients.detail.secret_ldap", "name", dir.DisplayName()), string(pw)})
			}
		}
	}
	if s.hasHosted(r, t.ConfigTemplateID) {
		d.LDAPBindDN = "uid=" + c.PublicID + ",ou=clients," + s.Cfg.LDAPSuffix
		if d.Show {
			d.Secrets = append(d.Secrets, secretRow{tr(r).T("clients.detail.secret_hosted"), secret.LDAPPassword(s.Ext.LDAPKey, c.PublicID, c.LDAPGeneration)})
		}
	}
	if d.Show {
		s.audit(r, "secret.view", "client", c.ID, c.Name, &c.RegionID, nil)
	}
	d.Report, _ = s.St.ClientReport(ctx, c.ID)
	if c.Status == store.StatusActive && d.Config != nil {
		seen := ""
		if d.Report != nil {
			seen = d.Report.LastSeenAt
		}
		d.Offline = store.Offline(seen, d.Config.RefreshMinutes, s.Now())
	}
	if b, err := s.buildDocument(r, c); err != nil {
		s.Log.Error("document build failed", "client", c.ID, "err", err)
		d.Problems = []string{tr(r).T("clients.detail.build_failed")}
	} else {
		d.Problems, d.Version = tr(r).Msgs(b.problems), b.doc.Version
	}
	if d.EnrollURL = s.enrollURL(c); d.EnrollURL != "" {
		svg, err := qr.SVG(d.EnrollURL, tr(r).T("common.qr_code"))
		if err != nil {
			s.internal(w, r, err)
			return
		}
		d.QR = template.HTML(svg) // our own markup, no user data inside
	}
	d.Expired = c.Status == store.StatusPending && d.EnrollURL == ""
	s.render(w, r, status, "client", &Page{Title: c.Name, Nav: "clients", Data: d})
}

func (s *Server) hasHosted(r *http.Request, configID int64) bool {
	dirs, _ := s.St.Directories(r.Context(), configID)
	for _, d := range dirs {
		if d.Kind == "hosted" && d.LDAPAccountID == nil {
			return true
		}
	}
	return false
}

// clientPreview shows the document the client would get now, passwords masked.
func (s *Server) clientPreview(w http.ResponseWriter, r *http.Request) {
	c := s.loadClient(w, r)
	if c == nil {
		return
	}
	b, err := s.buildDocument(r, c)
	if err != nil {
		s.internal(w, r, err)
		return
	}
	doc := *b.doc
	doc.Accounts = append([]provision.Account(nil), doc.Accounts...)
	for i := range doc.Accounts {
		if doc.Accounts[i].Password != "" {
			doc.Accounts[i].Password = "••••••"
		}
	}
	doc.Directories = append([]provision.Directory(nil), doc.Directories...)
	for i := range doc.Directories {
		if doc.Directories[i].Password != "" {
			doc.Directories[i].Password = "••••••"
		}
	}
	raw, err := json.MarshalIndent(&doc, "", "  ")
	if err != nil {
		s.internal(w, r, err)
		return
	}
	s.ok(w, r, "client_preview", &Page{Title: tr(r).T("clients.preview.title", "name", c.Name), Nav: "clients",
		Data: map[string]any{"C": c, "JSON": string(raw), "Problems": b.problems}})
}

// clientReveal checks the administrator's own password, then shows the stored secrets.
func (s *Server) clientReveal(w http.ResponseWriter, r *http.Request) {
	c := s.loadClient(w, r)
	if c == nil {
		return
	}
	a := current(r)
	if !actor(r).Can(auth.ClientViewSecrets, &c.RegionID) {
		s.notFound(w, r)
		return
	}
	if msg := s.recheckPassword(r, a, r.FormValue("password")); msg != "" {
		s.audit(r, "secret.view_denied", "client", c.ID, c.Name, &c.RegionID, nil)
		s.renderClient(w, r, http.StatusBadRequest, c, false, msg)
		return
	}
	s.reveals.grant(a.ID, s.Now())
	http.Redirect(w, r, "/clients/"+itoa(c.ID)+"?show=1#secrets", http.StatusSeeOther)
}

func (s *Server) clientQRPNG(w http.ResponseWriter, r *http.Request) {
	c := s.loadClient(w, r)
	if c == nil {
		return
	}
	u := s.enrollURL(c)
	if u == "" {
		s.notFound(w, r)
		return
	}
	png, err := qr.PNG(u, 512)
	if err != nil {
		s.internal(w, r, err)
		return
	}
	w.Header().Set("Content-Type", "image/png")
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("Content-Disposition", `attachment; filename="siplink-`+c.PublicID+`.png"`)
	w.Write(png)
}

func (s *Server) newEnrollment(r *http.Request, c *store.Client) error {
	expires := s.Now().Add(time.Duration(s.Cfg.EnrollDays) * 24 * time.Hour)
	return s.St.Unbind(r.Context(), c, secret.Token(32), expires, s.Ext.Box)
}

// clientUnbind ("重新生成二维码") revokes the bound phone and makes a new QR code.
func (s *Server) clientUnbind(w http.ResponseWriter, r *http.Request) {
	c := s.loadClient(w, r)
	if c == nil {
		return
	}
	wasBound := c.DeviceKeyHash != ""
	if err := s.newEnrollment(r, c); err != nil {
		s.internal(w, r, err)
		return
	}
	s.audit(r, "client.unbind", "client", c.ID, c.Name, &c.RegionID, map[string]any{"bound": wasBound})
	s.redirect(w, r, "/clients/"+itoa(c.ID), "ok", tr(r).T("clients.detail.regenerated"))
}

func (s *Server) clientResetLDAP(w http.ResponseWriter, r *http.Request) {
	c := s.loadClient(w, r)
	if c == nil {
		return
	}
	if err := s.St.ResetLDAP(r.Context(), c.ID); err != nil {
		s.internal(w, r, err)
		return
	}
	s.audit(r, "client.ldap_reset", "client", c.ID, c.Name, &c.RegionID, map[string]any{"password": auditChanged})
	s.redirect(w, r, "/clients/"+itoa(c.ID), "ok", tr(r).T("clients.detail.ldap_reset_done"))
}

func (s *Server) clientDelete(w http.ResponseWriter, r *http.Request) {
	c := s.loadClient(w, r)
	if c == nil {
		return
	}
	if err := s.St.DeleteClient(r.Context(), c); err != nil && !errors.Is(err, store.ErrNotFound) {
		s.internal(w, r, err)
		return
	}
	s.audit(r, "client.delete", "client", c.ID, c.Name, &c.RegionID, nil)
	s.redirect(w, r, "/clients", "ok", tr(r).T("clients.deleted", "name", c.Name))
}

type printCard struct {
	Name, Region string
	QR           template.HTML
}

// clientPrint is the print page: one card per pending client with a valid QR code, in the
// order of the ids. The clients come in one query, the QR codes are made in parallel.
func (s *Server) clientPrint(w http.ResponseWriter, r *http.Request) {
	act := actor(r)
	names, _ := s.allRegionNames(r)
	var ids []int64
	for _, part := range strings.Split(r.URL.Query().Get("ids"), ",") {
		if id, ok := parseInt64(part); ok {
			ids = append(ids, id)
		}
	}
	byID, err := s.St.ClientsByID(r.Context(), ids)
	if err != nil {
		s.Log.Error("clients to print not read", "err", err) // as before: none of them is printed
	}
	var cards []printCard
	var urls []string
	skipped := 0
	for _, id := range ids {
		c := byID[id]
		if c == nil || !act.Can(auth.ClientManage, &c.RegionID) {
			continue
		}
		u := s.enrollURL(c)
		if u == "" {
			skipped++
			continue
		}
		cards, urls = append(cards, printCard{Name: c.Name, Region: names[c.RegionID]}), append(urls, u)
	}
	svgs, err := qr.SVGs(urls, tr(r).T("common.qr_code"))
	if err != nil {
		s.internal(w, r, err)
		return
	}
	for i, svg := range svgs {
		cards[i].QR = template.HTML(svg) // our own markup, no user data inside
	}
	s.render(w, r, http.StatusOK, "client_print", &Page{Title: tr(r).T("clients.print.title"), Nav: "clients", Bare: true,
		Data: map[string]any{"Cards": cards, "Skipped": skipped}})
}
