package web

import (
	"errors"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"

	"siplink/internal/auth"
	"siplink/internal/catalog"
	"siplink/internal/provision"
	"siplink/internal/secret"
	"siplink/internal/store"
)

func (s *Server) routesClients() {
	s.page("GET /clients", s.clientList)
	s.page("GET /clients/new", s.clientForm)
	s.page("POST /clients", s.clientSave)
	s.page("GET /clients/{id}", s.clientShow)
	s.page("GET /clients/{id}/edit", s.clientForm)
	s.page("POST /clients/{id}", s.clientSave)
	s.page("POST /clients/{id}/unbind", s.clientUnbind)
	s.page("POST /clients/{id}/reset-ldap", s.clientResetLDAP)
	s.page("POST /clients/{id}/delete", s.clientDelete)
	s.page("POST /clients/{id}/reveal", s.clientReveal)
	s.page("GET /clients/{id}/qr.png", s.clientQRPNG)
	s.page("GET /clients/{id}/preview", s.clientPreview)
	s.page("POST /clients/bulk", s.clientBulk)
	s.page("GET /clients/print", s.clientPrint)
	s.routesClientImport()
}

const clientsPerPage = 50

// clientScope is what an administrator may see of clients, narrowed by the region filter.
func (s *Server) clientScope(r *http.Request) store.ClientFilter {
	a := current(r)
	return store.ClientFilter{All: a.Role == store.RoleSuper, Regions: a.RegionIDs, Region: s.selectedRegion(r)}
}

type clientRow struct {
	*store.Client
	Region   string
	Template string
	Report   *store.Report
	Offline  bool // store.Offline
}

type clientsData struct {
	Rows      []clientRow
	Total     int
	Page      int
	Pages     int
	Q         string
	Status    string
	Template  int64
	Templates []*store.ClientTemplate
	Query     string // the filter as a query string, for paging links
}

func (s *Server) clientList(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	q := r.URL.Query()
	f := s.clientScope(r)
	f.Query, f.Status = q.Get("q"), q.Get("status")
	if f.Status != store.StatusActive && f.Status != store.StatusPending && f.Status != store.StatusOffline {
		f.Status = ""
	}
	f.Now = s.Now()
	f.Template, _ = parseInt64(q.Get("template"))
	page, _ := strconv.Atoi(q.Get("page"))
	page = max(page, 1)
	f.Limit, f.Offset = clientsPerPage, (page-1)*clientsPerPage
	list, total, err := s.St.ListClients(ctx, f)
	if err != nil {
		s.internal(w, r, err)
		return
	}
	d := clientsData{Total: total, Page: page, Pages: (total + clientsPerPage - 1) / clientsPerPage, Q: f.Query,
		Status: f.Status, Template: f.Template}
	d.Templates, _ = s.St.ListClientTemplates(ctx, s.templateScope(r))
	d.Query = url.Values{"q": {f.Query}, "status": {f.Status}, "template": {itoa(f.Template)}}.Encode()
	names, _ := s.allRegionNames(r)
	tplNames := map[int64]string{}
	all, _ := s.St.ListClientTemplates(ctx, store.BookScope{All: true})
	for _, t := range all {
		tplNames[t.ID] = t.Name
	}
	ids := make([]int64, len(list))
	for i, c := range list {
		ids[i] = c.ID
	}
	reports, _ := s.St.ClientReports(ctx, ids)
	refresh, _ := s.St.RefreshMinutes(ctx, ids)
	for _, c := range list {
		rep := reports[c.ID]
		if rep == nil {
			rep = &store.Report{}
		}
		d.Rows = append(d.Rows, clientRow{Client: c, Region: names[c.RegionID], Template: tplNames[c.ClientTemplateID], Report: rep,
			Offline: c.Status == store.StatusActive && store.Offline(rep.LastSeenAt, refresh[c.ID], f.Now)})
	}
	s.ok(w, r, "clients", &Page{Title: "客户端", Nav: "clients", Data: d})
}

// loadClient returns the client of the path when the administrator may manage it.
func (s *Server) loadClient(w http.ResponseWriter, r *http.Request) *store.Client {
	id, ok := pathID(r, "id")
	c, err := s.St.ClientByID(r.Context(), id)
	if !ok || errors.Is(err, store.ErrNotFound) || err == nil && !actor(r).Can(auth.ClientManage, &c.RegionID) {
		s.notFound(w, r)
		return nil
	}
	if err != nil {
		s.internal(w, r, err)
		return nil
	}
	return c
}

// ---- form ----

type credRow struct {
	P           *store.Profile
	In          store.CredentialInput
	HasPassword bool
}

type dirPassRow struct {
	D           *store.Directory
	HasPassword bool
}

type clientFormData struct {
	C         *store.Client
	New       bool
	Regions   []store.Region
	Templates []*store.ClientTemplate
	Template  *store.ClientTemplate
	Creds     []credRow
	Dirs      []dirPassRow
	Fwd       struct{ Always, Busy, NoAnswer string }
	FwdSet    struct{ Always, Busy, NoAnswer bool }
	Errors    []string
}

// usableClientTemplates are the client templates a client of region may use (§2.3).
func (s *Server) usableClientTemplates(r *http.Request, region int64) []*store.ClientTemplate {
	all, _ := s.St.ListClientTemplates(r.Context(), store.BookScope{All: true})
	act := actor(r)
	var out []*store.ClientTemplate
	for _, t := range all {
		if act.Can(auth.TemplateUse, t.RegionID) && (region == 0 || compatible(&region, t.RegionID)) {
			out = append(out, t)
		}
	}
	return out
}

// clientParts loads the profiles and client-password directories of a client template.
func (s *Server) clientParts(r *http.Request, templateID int64) (*store.ClientTemplate, []*store.Profile, []*store.Directory, error) {
	ctx := r.Context()
	t, err := s.St.ClientTemplateByID(ctx, templateID)
	if err != nil {
		return nil, nil, nil, err
	}
	profiles, err := s.St.Profiles(ctx, t.ConfigTemplateID)
	if err != nil {
		return nil, nil, nil, err
	}
	all, err := s.St.Directories(ctx, t.ConfigTemplateID)
	var dirs []*store.Directory
	for _, d := range all {
		if d.PasswordMode != catalog.PasswordClient {
			continue
		}
		if d.Name == "" && d.BookID != nil { // a hosted book bound by hand shows its book's name
			if b, err := s.St.BookByID(ctx, *d.BookID); err == nil {
				d.Name = b.Name
			}
		}
		dirs = append(dirs, d)
	}
	return t, profiles, dirs, err
}

func (s *Server) clientFormPage(w http.ResponseWriter, r *http.Request, status int, d clientFormData, inputs map[int64]store.CredentialInput) {
	ctx := r.Context()
	a := current(r)
	d.Regions, _ = s.St.ListRegions(ctx, a.RegionIDs, a.Role == store.RoleSuper)
	d.Templates = s.usableClientTemplates(r, d.C.RegionID)
	var stored map[int64]*store.Credential
	var dirPass map[int64]string
	if !d.New {
		stored, _ = s.St.Credentials(ctx, d.C.ID)
		dirPass, _ = s.St.DirectoryPasswords(ctx, d.C.ID)
	}
	if d.C.ClientTemplateID != 0 {
		t, profiles, dirs, err := s.clientParts(r, d.C.ClientTemplateID)
		if err == nil {
			d.Template = t
			for _, p := range profiles {
				row := credRow{P: p}
				if k := stored[p.ID]; k != nil {
					row.In = store.CredentialInput{ProfileID: p.ID, Username: k.Username, AuthUser: k.AuthUser,
						DisplayName: k.DisplayName, AccountName: k.AccountName}
					row.HasPassword = k.PasswordEnc != ""
				}
				if in, ok := inputs[p.ID]; ok {
					in.Password = ""
					row.In = in
				}
				d.Creds = append(d.Creds, row)
			}
			for _, dir := range dirs {
				d.Dirs = append(d.Dirs, dirPassRow{D: dir, HasPassword: dirPass[dir.ID] != ""})
			}
		}
	}
	for _, f := range []struct {
		p   *string
		v   *string
		set *bool
	}{{d.C.ForwardAlways, &d.Fwd.Always, &d.FwdSet.Always}, {d.C.ForwardBusy, &d.Fwd.Busy, &d.FwdSet.Busy},
		{d.C.ForwardNoAnswer, &d.Fwd.NoAnswer, &d.FwdSet.NoAnswer}} {
		if f.p != nil {
			*f.v, *f.set = *f.p, true
		}
	}
	title := "新建客户端"
	if !d.New {
		title = "编辑客户端：" + d.C.Name
	}
	s.render(w, r, status, "client_edit", &Page{Title: title, Nav: "clients", Data: d})
}

func (s *Server) clientForm(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	d := clientFormData{New: r.PathValue("id") == ""}
	if d.New {
		d.C = &store.Client{}
		if def := s.defaultRegion(r); def != nil {
			d.C.RegionID = *def
		}
	} else if d.C = s.loadClient(w, r); d.C == nil {
		return
	}
	if id, ok := parseInt64(q.Get("region")); ok && id > 0 {
		d.C.RegionID = id
	}
	if id, ok := parseInt64(q.Get("template")); ok && id > 0 {
		d.C.ClientTemplateID = id
	}
	s.clientFormPage(w, r, http.StatusOK, d, nil)
}

// readForward reads one per-client forwarding override (nil = the template's value).
func readForward(r *http.Request, name string) *string {
	if !checkbox(r, name+"Set") {
		return nil
	}
	v := trimmed(r, name)
	return &v
}

func (s *Server) clientSave(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	act := actor(r)
	isNew := r.PathValue("id") == ""
	c := &store.Client{}
	if !isNew {
		if c = s.loadClient(w, r); c == nil {
			return
		}
	}
	old := *c
	c.Name, c.Note = trimmed(r, "name"), truncate(trimmed(r, "note"), 200)
	c.RegionID, c.ClientTemplateID = formID(r, "region"), formID(r, "template")
	c.ForwardAlways, c.ForwardBusy, c.ForwardNoAnswer = readForward(r, "fwdAlways"), readForward(r, "fwdBusy"), readForward(r, "fwdNoAnswer")
	var problems []string
	if p := catalog.ClientNameProblem(c.Name); p != "" {
		problems = append(problems, p)
	}
	if !act.Can(auth.ClientManage, &c.RegionID) || !isNew && !act.CanMoveClient(old.RegionID, c.RegionID) {
		problems = append(problems, "您无权在所选区域管理客户端。")
	} else if _, err := s.St.RegionByID(ctx, c.RegionID); err != nil {
		problems = append(problems, "请选择区域。")
	}
	usable := false
	for _, t := range s.usableClientTemplates(r, c.RegionID) {
		usable = usable || t.ID == c.ClientTemplateID
	}
	if !usable {
		problems = append(problems, "请选择可用的客户端模板（全局的或属于同一区域）。")
	}
	for _, v := range []*string{c.ForwardAlways, c.ForwardBusy, c.ForwardNoAnswer} {
		if v != nil {
			if p := provision.ForwardProblem(*v); p != "" {
				problems = append(problems, p)
				break
			}
		}
	}
	inputs := map[int64]store.CredentialInput{}
	var creds []store.CredentialInput
	dirs := map[int64]string{}
	if usable {
		_, profiles, dirList, err := s.clientParts(r, c.ClientTemplateID)
		if err != nil {
			s.internal(w, r, err)
			return
		}
		creds, problems = readCredentials(r, profiles, problems)
		for _, in := range creds {
			inputs[in.ProfileID] = in
		}
		for _, d := range dirList {
			dirs[d.ID] = r.FormValue("dir_" + itoa(d.ID))
			if d.LDAPAccountID != nil && dirs[d.ID] != "" {
				a, err := s.St.LDAPAccountByID(ctx, *d.LDAPAccountID)
				if p := s.accountPasswordProblem(a, dirs[d.ID]); err == nil && p != "" {
					problems = append(problems, "通讯录「"+d.Name+"」："+p)
				}
			}
		}
	}
	if len(problems) == 0 {
		var token string
		if isNew {
			token = secret.Token(32)
			c.EnrollExpiresAt = s.Now().Add(time.Duration(s.Cfg.EnrollDays) * 24 * time.Hour).UTC().Format(store.TimeFormat)
		}
		err := s.St.SaveClient(ctx, c, creds, dirs, s.Ext.Box, token)
		if errors.Is(err, store.ErrConflict) {
			problems = append(problems, "该区域已有同名客户端。")
		} else if err != nil {
			s.internal(w, r, err)
			return
		}
	}
	if len(problems) > 0 {
		s.clientFormPage(w, r, http.StatusBadRequest, clientFormData{C: c, New: isNew, Errors: problems}, inputs)
		return
	}
	s.auditClientSave(r, isNew, &old, c, creds, dirs)
	s.redirect(w, r, "/clients/"+itoa(c.ID), "ok", "客户端「"+c.Name+"」已保存。")
}

// readCredentials reads and checks the credentials of every profile of the template.
func readCredentials(r *http.Request, profiles []*store.Profile, problems []string) ([]store.CredentialInput, []string) {
	var creds []store.CredentialInput
	var taken []string
	for _, p := range profiles {
		id := itoa(p.ID)
		in := store.CredentialInput{ProfileID: p.ID, Username: r.FormValue("user_" + id), AuthUser: r.FormValue("auth_" + id),
			Password: r.FormValue("pass_" + id), DisplayName: r.FormValue("disp_" + id)}
		if v := r.FormValue("acct_" + id); strings.TrimSpace(v) != "" {
			in.AccountName = &v
		}
		catalog.NormalizeCredential(p, &in)
		creds = append(creds, in)
		if in.Username == "" {
			continue
		}
		problems = append(problems, catalog.CredentialProblems(p, in, taken)...)
		taken = append(taken, catalog.CredentialDraft(p, in).Name)
	}
	return creds, problems
}

func (s *Server) auditClientSave(r *http.Request, isNew bool, old, c *store.Client, creds []store.CredentialInput, dirs map[int64]string) {
	detail := changes(field{"name", old.Name, c.Name}, field{"note", old.Note, c.Note}, field{"region_id", old.RegionID, c.RegionID},
		field{"client_template_id", old.ClientTemplateID, c.ClientTemplateID},
		field{"forward_always", derefText(old.ForwardAlways), derefText(c.ForwardAlways)},
		field{"forward_busy", derefText(old.ForwardBusy), derefText(c.ForwardBusy)},
		field{"forward_no_answer", derefText(old.ForwardNoAnswer), derefText(c.ForwardNoAnswer)})
	for _, in := range creds {
		if in.Password != "" {
			detail["password"] = "已修改"
		}
	}
	for _, pw := range dirs {
		if pw != "" {
			detail["directory_password"] = "已修改"
		}
	}
	action := "client.update"
	if isNew {
		action = "client.create"
	}
	s.audit(r, action, "client", c.ID, c.Name, &c.RegionID, detail)
}

func derefText(p *string) any {
	if p == nil {
		return nil
	}
	return *p
}
