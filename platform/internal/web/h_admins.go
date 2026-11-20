package web

import (
	"errors"
	"net/http"
	"net/url"
	"slices"
	"strconv"
	"time"

	"siplink/internal/auth"
	"siplink/internal/i18n"
	"siplink/internal/mail"
	"siplink/internal/secret"
	"siplink/internal/store"
)

type adminsData struct {
	Admins  []*store.Admin
	Regions map[int64]string
	TwoStep map[int64]bool // administrators with a two-step method on
}

func (s *Server) adminList(w http.ResponseWriter, r *http.Request) {
	a := current(r)
	d := adminsData{}
	regions, err := s.St.ListRegions(r.Context(), nil, true)
	if err != nil {
		s.internal(w, r, err)
		return
	}
	d.Regions = regionNames(regions)
	if d.TwoStep, err = s.St.TwoStepAdmins(r.Context()); err != nil {
		s.internal(w, r, err)
		return
	}
	if a.Role == store.RoleSuper {
		if d.Admins, err = s.St.ListAdmins(r.Context()); err != nil {
			s.internal(w, r, err)
			return
		}
	} else {
		d.Admins = []*store.Admin{a} // region administrators see only their own profile
	}
	s.ok(w, r, "admins", &Page{Title: tr(r).T("nav.admins"), Nav: "admins", Data: d})
}

type adminForm struct {
	Admin    *store.Admin
	New      bool
	Self     bool
	Regions  []store.Region
	Error    string
	Password string // one-time password, shown once after create / reset
	TwoStep  store.TwoStep
	EmailWas string // the address the form was shown with
}

func (s *Server) adminFormPage(w http.ResponseWriter, r *http.Request, status int, f adminForm) {
	regions, err := s.St.ListRegions(r.Context(), nil, true)
	if err != nil {
		s.internal(w, r, err)
		return
	}
	f.Regions = regions
	if !f.New {
		if f.TwoStep, err = s.St.TwoStepState(r.Context(), f.Admin.ID); err != nil {
			s.internal(w, r, err)
			return
		}
	}
	title := tr(r).T("admins.edit_title")
	if f.New {
		title = tr(r).T("admins.new")
	}
	s.render(w, r, status, "admin_edit", &Page{Title: title, Nav: "admins", Data: f})
}

func (s *Server) adminNew(w http.ResponseWriter, r *http.Request) {
	s.adminFormPage(w, r, http.StatusOK, adminForm{New: true, Admin: &store.Admin{Role: store.RoleRegion}})
}

// readAdminForm reads role, display name, e-mail address and regions; it checks that
// every region exists. The problem it returns is in the request's language.
func (s *Server) readAdminForm(r *http.Request, a *store.Admin) string {
	a.DisplayName = truncate(trimmed(r, "display_name"), 64)
	if i18n.HasBidiControl(a.DisplayName) {
		return tr(r).T("names.bidi_control")
	}
	a.Email = trimmed(r, "email")
	if a.Email != "" && mail.ValidAddress(a.Email) != nil {
		return tr(r).T("admins.error.email_invalid")
	}
	a.Role = r.FormValue("role")
	a.RegionIDs = formIDs(r, "regions")
	if a.Role != store.RoleSuper && a.Role != store.RoleRegion {
		return tr(r).T("admins.error.role_missing")
	}
	if a.Role == store.RoleSuper {
		a.RegionIDs = nil
		return ""
	}
	if len(a.RegionIDs) == 0 {
		return tr(r).T("admins.error.regions_missing")
	}
	for _, id := range a.RegionIDs {
		if _, err := s.St.RegionByID(r.Context(), id); err != nil {
			return tr(r).T("admins.error.region_unknown")
		}
	}
	return ""
}

func (s *Server) adminCreate(w http.ResponseWriter, r *http.Request) {
	a := &store.Admin{Username: trimmed(r, "username"), MustChangePassword: true}
	msg := s.readAdminForm(r, a)
	if !auth.ValidUsername(a.Username) {
		msg = tr(r).T("admins.error.username_invalid")
	}
	if msg != "" {
		s.adminFormPage(w, r, http.StatusBadRequest, adminForm{New: true, Admin: a, Error: msg})
		return
	}
	password := secret.RandomText(16)
	hash, err := auth.HashPassword(password)
	if err != nil {
		s.internal(w, r, err)
		return
	}
	a.PasswordHash = hash
	if err := s.St.CreateAdmin(r.Context(), a); errors.Is(err, store.ErrConflict) {
		s.adminFormPage(w, r, http.StatusBadRequest, adminForm{New: true, Admin: a, Error: tr(r).T("admins.error.username_taken")})
		return
	} else if err != nil {
		s.internal(w, r, err)
		return
	}
	if err := s.St.SetAdminEmail(r.Context(), a.ID, a.Email, nil); err != nil {
		s.internal(w, r, err)
		return
	}
	s.audit(r, "admin.create", "admin", a.ID, a.Username, nil, map[string]any{"role": a.Role, "regions": a.RegionIDs})
	s.ok(w, r, "admin_password", &Page{Title: tr(r).T("admins.created_title"), Nav: "admins", Data: adminForm{Admin: a, Password: password}, secret: true})
}

func (s *Server) loadAdmin(w http.ResponseWriter, r *http.Request) *store.Admin {
	id, ok := pathID(r, "id")
	a, err := s.St.AdminByID(r.Context(), id)
	if !ok || errors.Is(err, store.ErrNotFound) {
		s.notFound(w, r)
		return nil
	}
	if err != nil {
		s.internal(w, r, err)
		return nil
	}
	return a
}

func (s *Server) adminEdit(w http.ResponseWriter, r *http.Request) {
	a := s.loadAdmin(w, r)
	if a == nil {
		return
	}
	s.adminFormPage(w, r, http.StatusOK, adminForm{Admin: a, Self: a.ID == current(r).ID, EmailWas: a.Email})
}

func (s *Server) adminUpdate(w http.ResponseWriter, r *http.Request) {
	old := s.loadAdmin(w, r)
	if old == nil {
		return
	}
	self := old.ID == current(r).ID
	a := *old
	msg := s.readAdminForm(r, &a)
	a.Disabled = r.FormValue("disabled") == "1"
	// The address is written only when the form changed the one it was shown with, and
	// never for a super administrator here (their own goes through 我的账号, §6.8).
	emailChanged := r.PostForm.Has("email") && a.Email != trimmed(r, "email_was")
	if !emailChanged {
		a.Email = old.Email
	}
	ts, err := s.St.TwoStepState(r.Context(), old.ID)
	if err != nil {
		s.internal(w, r, err)
		return
	}
	switch {
	case msg != "": // the form's own problem first
	case self && (a.Role != old.Role || a.Disabled):
		msg = tr(r).T("admins.error.self_change")
	case emailChanged && old.Role == store.RoleSuper:
		msg = tr(r).T("admins.error.super_email")
	case old.Role == store.RoleSuper && a.Role != old.Role && ts.Methods() > 0:
		msg = tr(r).T("admins.error.super_two_step_role")
	}
	if msg != "" {
		s.adminFormPage(w, r, http.StatusBadRequest, adminForm{Admin: &a, Self: self, Error: msg, EmailWas: trimmed(r, "email_was")})
		return
	}
	err = s.St.UpdateAdmin(r.Context(), &a)
	if errors.Is(err, store.ErrLastSuper) {
		s.adminFormPage(w, r, http.StatusBadRequest, adminForm{Admin: &a, Self: self, EmailWas: trimmed(r, "email_was"),
			Error: tr(r).T("admins.error.last_super")})
		return
	}
	if err == nil && emailChanged {
		err = s.St.SetAdminEmail(r.Context(), a.ID, a.Email, nil) // unconfirmed; a region administrator's
	}
	if err != nil {
		s.internal(w, r, err)
		return
	}
	action := "admin.update"
	if a.Disabled != old.Disabled {
		action = map[bool]string{true: "admin.disable", false: "admin.enable"}[a.Disabled]
	}
	oldRegions, newRegions := slices.Clone(old.RegionIDs), slices.Clone(a.RegionIDs)
	slices.Sort(oldRegions)
	slices.Sort(newRegions)
	s.audit(r, action, "admin", a.ID, a.Username, nil, changes(
		field{"display_name", old.DisplayName, a.DisplayName}, field{"role", old.Role, a.Role},
		field{"disabled", old.Disabled, a.Disabled}, field{"regions", oldRegions, newRegions}, field{"email", old.Email, a.Email}))
	s.redirect(w, r, "/admins", "ok", tr(r).T("admins.saved", "name", a.Username))
}

func (s *Server) adminResetPassword(w http.ResponseWriter, r *http.Request) {
	a := s.loadAdmin(w, r)
	if a == nil {
		return
	}
	password := secret.RandomText(16)
	hash, err := auth.HashPassword(password)
	if err != nil {
		s.internal(w, r, err)
		return
	}
	if err := s.St.SetAdminPassword(r.Context(), a.ID, hash, true); err != nil {
		s.internal(w, r, err)
		return
	}
	s.audit(r, "admin.password_reset", "admin", a.ID, a.Username, nil, map[string]any{"password": auditChanged})
	if a.ID == current(r).ID {
		s.clearSessionCookie(w, r) // one's own sessions ended with the reset
	}
	s.ok(w, r, "admin_password", &Page{Title: tr(r).T("admins.password_reset_title"), Nav: "admins", Data: adminForm{Admin: a, Password: password}, secret: true})
}

// ---- audit log page ----

type auditData struct {
	Recent  []store.AuditEntry
	Names   map[int64]string
	Admins  []*store.Admin
	Regions []store.Region
	Q       auditQuery
	Prev    string
	Next    string
}

type auditQuery struct {
	Admin, Region    int64
	Object, From, To string
	TZ               string // the viewer's IANA time zone, set by app.js; "" = the server's
	Page             int
}

func (s *Server) auditList(w http.ResponseWriter, r *http.Request) {
	a := current(r)
	q := r.URL.Query()
	d := auditData{Q: auditQuery{Object: q.Get("object"), From: q.Get("from"), To: q.Get("to"), TZ: q.Get("tz")}}
	loc := viewerZone(d.Q.TZ)
	if loc == time.Local {
		d.Q.TZ = ""
	}
	d.Q.Admin, _ = parseInt64(q.Get("admin"))
	d.Q.Region, _ = parseInt64(q.Get("region"))
	page, _ := parseInt64(q.Get("page"))
	d.Q.Page = int(max(page, 1))
	if !slices.Contains(objectTypes, d.Q.Object) {
		d.Q.Object = ""
	}
	f := visibleAudit(a)
	f.AdminID, f.RegionID, f.ObjectType = d.Q.Admin, d.Q.Region, d.Q.Object
	if t, ok := parseDay(d.Q.From, loc); ok {
		f.From = t.UTC().Format(store.TimeFormat)
	} else {
		d.Q.From = ""
	}
	if t, ok := parseDay(d.Q.To, loc); ok {
		f.To = t.AddDate(0, 0, 1).UTC().Format(store.TimeFormat)
	} else {
		d.Q.To = ""
	}
	const perPage = 50
	f.Limit, f.Offset = perPage+1, (d.Q.Page-1)*perPage
	entries, err := s.St.ListAudit(r.Context(), f)
	if err != nil {
		s.internal(w, r, err)
		return
	}
	if len(entries) > perPage {
		entries = entries[:perPage]
		d.Next = auditPageURL(d.Q, d.Q.Page+1)
	}
	if d.Q.Page > 1 {
		d.Prev = auditPageURL(d.Q, d.Q.Page-1)
	}
	d.Recent = entries
	regions, err := s.St.ListRegions(r.Context(), nil, true)
	if err != nil {
		s.internal(w, r, err)
		return
	}
	d.Names = regionNames(regions)
	if a.Role == store.RoleSuper {
		d.Regions = regions
		d.Admins, _ = s.St.ListAdmins(r.Context())
	} else {
		d.Regions, _ = s.St.ListRegions(r.Context(), a.RegionIDs, false)
		d.Admins = []*store.Admin{a}
	}
	s.ok(w, r, "audit", &Page{Title: tr(r).T("nav.audit"), Nav: "audit", Data: d})
}

func parseInt64(v string) (int64, bool) {
	n, err := strconv.ParseInt(v, 10, 64)
	return n, err == nil
}

// parseDay reads a date of a filter form as a day of loc.
func parseDay(v string, loc *time.Location) (time.Time, bool) {
	t, err := time.ParseInLocation("2006-01-02", v, loc)
	return t, err == nil
}

// viewerZone is the viewer's time zone named by the browser (app.js fills the hidden
// "tz" field), so the days typed into the audit filter are the viewer's days. Without
// JavaScript, or for a name the server does not know, the server's zone is used.
func viewerZone(name string) *time.Location {
	if name == "" || len(name) > 64 {
		return time.Local
	}
	loc, err := time.LoadLocation(name) // refuses absolute paths and ".."
	if err != nil {
		return time.Local
	}
	return loc
}

func auditPageURL(q auditQuery, page int) string {
	v := url.Values{}
	if q.Admin != 0 {
		v.Set("admin", itoa(q.Admin))
	}
	if q.Region != 0 {
		v.Set("region", itoa(q.Region))
	}
	for k, val := range map[string]string{"object": q.Object, "from": q.From, "to": q.To, "tz": q.TZ} {
		if val != "" {
			v.Set(k, val)
		}
	}
	v.Set("page", strconv.Itoa(page))
	return "/audit?" + v.Encode()
}
