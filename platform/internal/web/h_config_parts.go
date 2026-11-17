package web

import (
	"errors"
	"net/http"
	"strings"

	"siplink/internal/auth"
	"siplink/internal/catalog"
	"siplink/internal/rules"
	"siplink/internal/secret"
	"siplink/internal/store"
)

// ---- account profiles ----

type profileForm struct {
	T       *store.ConfigTemplate
	P       *store.Profile
	New     bool
	Errors  []string
	Options settingOptions
	Clients int
}

func (s *Server) profileFormPage(w http.ResponseWriter, r *http.Request, status int, f profileForm) {
	f.Options = options()
	f.Clients, _ = s.St.ClientCountOfConfigTemplate(r.Context(), f.T.ID)
	title := "编辑账号"
	if f.New {
		title = "新建账号"
	}
	s.render(w, r, status, "profile_edit", &Page{Title: title, Nav: "config", Data: f})
}

func (s *Server) profileForm(w http.ResponseWriter, r *http.Request) {
	t := s.loadConfig(w, r, true)
	if t == nil {
		return
	}
	if r.PathValue("pid") == "" {
		p := &store.Profile{Transport: "UDP", Expires: 3600, Enabled: true}
		s.profileFormPage(w, r, http.StatusOK, profileForm{T: t, P: p, New: true})
		return
	}
	if p := s.loadProfile(w, r, t); p != nil {
		s.profileFormPage(w, r, http.StatusOK, profileForm{T: t, P: p})
	}
}

func (s *Server) loadProfile(w http.ResponseWriter, r *http.Request, t *store.ConfigTemplate) *store.Profile {
	pid, ok := pathID(r, "pid")
	p, err := s.St.ProfileByID(r.Context(), t.ID, pid)
	if !ok || errors.Is(err, store.ErrNotFound) {
		s.notFound(w, r)
		return nil
	}
	if err != nil {
		s.internal(w, r, err)
		return nil
	}
	return p
}

func (s *Server) profileSave(w http.ResponseWriter, r *http.Request) {
	t := s.loadConfig(w, r, true)
	if t == nil {
		return
	}
	p := &store.Profile{TemplateID: t.ID}
	isNew := r.PathValue("pid") == ""
	if !isNew {
		if p = s.loadProfile(w, r, t); p == nil {
			return
		}
	}
	old := *p
	p.Name, p.Domain, p.Port = r.FormValue("name"), r.FormValue("domain"), r.FormValue("port")
	p.Server, p.BackupServer, p.DNSServer = r.FormValue("server"), r.FormValue("backupServer"), r.FormValue("dnsServer")
	p.Transport, p.Expires = r.FormValue("transport"), intValue(r, "expires", 0)
	p.SubscribeMWI, p.Enabled, p.IsDefault = checkbox(r, "subscribeMwi"), checkbox(r, "enabled"), checkbox(r, "isDefault")
	catalog.NormalizeProfile(p)
	others, err := s.St.Profiles(r.Context(), t.ID)
	if err != nil {
		s.internal(w, r, err)
		return
	}
	problems := catalog.ProfileProblems(p, others)
	if len(problems) == 0 {
		err := s.St.SaveProfile(r.Context(), p, rules.MaxAccounts)
		switch {
		case errors.Is(err, store.ErrLimit):
			problems = append(problems, rules.AccountLimitReached.Full())
		case errors.Is(err, store.ErrConflict):
			problems = append(problems, rules.AccountNameTaken.Full())
		case err != nil:
			s.internal(w, r, err)
			return
		}
	}
	if len(problems) > 0 {
		s.profileFormPage(w, r, http.StatusBadRequest, profileForm{T: t, P: p, New: isNew, Errors: problems})
		return
	}
	if !isNew && !strings.EqualFold(old.Name, p.Name) {
		s.renameLines(r, t.ID, old.Name, p.Name)
	}
	s.audit(r, "config_template.update", "config_template", t.ID, t.Name, t.RegionID, map[string]any{"profile": p.Name})
	s.redirect(w, r, "/config-templates/"+itoa(t.ID)+"?tab=accounts", "ok", "账号「"+p.Name+"」已保存。")
}

// renameLines follows a profile rename in the keys of the key templates used together
// with this configuration template, so their line keeps naming a profile (§3.5.7).
func (s *Server) renameLines(r *http.Request, configID int64, from, to string) {
	cts, _ := s.St.ListClientTemplates(r.Context(), store.BookScope{All: true})
	done := map[int64]bool{}
	for _, ct := range cts {
		if ct.ConfigTemplateID != configID || ct.KeyTemplateID == nil || done[*ct.KeyTemplateID] {
			continue
		}
		done[*ct.KeyTemplateID] = true
		keys, _ := s.St.Keys(r.Context(), *ct.KeyTemplateID)
		for _, k := range keys {
			if strings.EqualFold(k.Line, from) {
				k.Line = to
				_ = s.St.SaveKey(r.Context(), *ct.KeyTemplateID, k, rules.MaxShortcuts)
			}
		}
	}
}

func (s *Server) profileOp(w http.ResponseWriter, r *http.Request) {
	t := s.loadConfig(w, r, true)
	if t == nil {
		return
	}
	p := s.loadProfile(w, r, t)
	if p == nil {
		return
	}
	var err error
	switch r.PathValue("op") {
	case "delete":
		err = s.St.DeleteProfile(r.Context(), t.ID, p.ID)
	case "default":
		err = s.St.SetDefaultProfile(r.Context(), t.ID, p.ID)
	default:
		s.notFound(w, r)
		return
	}
	if err != nil {
		s.internal(w, r, err)
		return
	}
	s.audit(r, "config_template.update", "config_template", t.ID, t.Name, t.RegionID, map[string]any{"profile": p.Name, "op": r.PathValue("op")})
	s.redirect(w, r, "/config-templates/"+itoa(t.ID)+"?tab=accounts", "ok", "已保存。")
}

// ---- directories ----

// accountPasswordProblem says why a typed password is not the service account's
// (only its SHA-256 is stored, §4.3); "" when it matches or nothing was typed.
func (s *Server) accountPasswordProblem(a *store.LDAPAccount, password string) string {
	if a == nil || password == "" || secret.HashHex(password) == a.PasswordHash {
		return ""
	}
	return "密码与 LDAP 账号「" + a.Name + "」不符"
}

type directoryForm struct {
	T          *store.ConfigTemplate
	D          *store.Directory
	New        bool
	Errors     []string
	Books      []*store.Book
	Profiles   []*store.Profile
	HasPass    bool
	HostedNote string
	Plain      bool
	Clients    int
	Accounts   []*store.LDAPAccount // service accounts a hosted book may bind with
	Manual     bool                 // hosted book with manual credentials
}

func (s *Server) directoryFormPage(w http.ResponseWriter, r *http.Request, status int, f directoryForm) {
	ctx := r.Context()
	f.Profiles, _ = s.St.Profiles(ctx, f.T.ID)
	f.Books = s.usableBooks(r, f.T.RegionID)
	f.HasPass = f.D.PasswordEnc != ""
	f.Clients, _ = s.St.ClientCountOfConfigTemplate(ctx, f.T.ID)
	f.Accounts = s.usableAccounts(r, f.T.RegionID)
	f.Manual = f.D.LDAPAccountID != nil
	if sec, ok := s.hosted().Security(); !ok {
		f.HostedNote = rules.HostedNeedsName
	} else {
		f.Plain = sec == rules.LdapSecurityNone
	}
	title := "编辑通讯录"
	if f.New {
		title = "添加通讯录"
	}
	s.render(w, r, status, "directory_edit", &Page{Title: title, Nav: "config", Data: f})
}

// usableAccounts are the enabled service accounts a template of region may bind a
// hosted book with: global ones and those of its own region.
func (s *Server) usableAccounts(r *http.Request, region *int64) []*store.LDAPAccount {
	all, _ := s.St.ListLDAPAccounts(r.Context(), store.BookScope{All: true})
	var out []*store.LDAPAccount
	for _, a := range all {
		if !a.Disabled && compatible(region, a.RegionID) {
			out = append(out, a)
		}
	}
	return out
}

// usableBooks are the books a template of region may refer to: global ones and those
// of its own region (§2.3), limited to what the administrator may use.
func (s *Server) usableBooks(r *http.Request, region *int64) []*store.Book {
	all, _ := s.St.ListBooks(r.Context(), store.BookScope{All: true})
	act := actor(r)
	var out []*store.Book
	for _, b := range all {
		if act.Can(auth.BookUse, b.RegionID) && (b.RegionID == nil || region != nil && *region == *b.RegionID) {
			out = append(out, b)
		}
	}
	return out
}

func (s *Server) directoryForm(w http.ResponseWriter, r *http.Request) {
	t := s.loadConfig(w, r, true)
	if t == nil {
		return
	}
	if r.PathValue("did") == "" {
		d := &store.Directory{Kind: r.URL.Query().Get("kind"), Enabled: true, Port: 389, Security: "START_TLS",
			PasswordMode: catalog.PasswordNone, LookupIncoming: true, LookupOutgoing: true, MaxResults: 50,
			NameAttributes: rules.LdapDefaultNameAttributes, NumberAttributes: rules.LdapDefaultNumberAttributes}
		if d.Kind != "hosted" {
			d.Kind = "external"
		}
		s.directoryFormPage(w, r, http.StatusOK, directoryForm{T: t, D: d, New: true})
		return
	}
	if d := s.loadDirectory(w, r, t); d != nil {
		s.directoryFormPage(w, r, http.StatusOK, directoryForm{T: t, D: d})
	}
}

func (s *Server) loadDirectory(w http.ResponseWriter, r *http.Request, t *store.ConfigTemplate) *store.Directory {
	did, ok := pathID(r, "did")
	d, err := s.St.DirectoryByID(r.Context(), t.ID, did)
	if !ok || errors.Is(err, store.ErrNotFound) {
		s.notFound(w, r)
		return nil
	}
	if err != nil {
		s.internal(w, r, err)
		return nil
	}
	return d
}

func (s *Server) directorySave(w http.ResponseWriter, r *http.Request) {
	t := s.loadConfig(w, r, true)
	if t == nil {
		return
	}
	ctx := r.Context()
	d := &store.Directory{TemplateID: t.ID}
	isNew := r.PathValue("did") == ""
	if !isNew {
		if d = s.loadDirectory(w, r, t); d == nil {
			return
		}
	} else {
		d.Kind = r.FormValue("kind")
	}
	d.Name, d.Enabled = r.FormValue("name"), checkbox(r, "enabled")
	d.LookupIncoming, d.LookupOutgoing = checkbox(r, "lookupIncoming"), checkbox(r, "lookupOutgoing")
	d.MaxResults = intValue(r, "maxResults", 0)
	d.LineProfileID = nil
	if pid := formID(r, "line"); pid > 0 {
		if _, err := s.St.ProfileByID(ctx, t.ID, pid); err == nil {
			d.LineProfileID = &pid
		}
	}
	var book *store.Book
	var hostedProblems []string
	password := r.FormValue("password")
	if d.Kind == "hosted" {
		d.BookID, d.PasswordMode, d.Host, d.BindDN, d.BaseDN = nil, catalog.PasswordNone, "", "", ""
		if id := formID(r, "book"); id > 0 {
			for _, b := range s.usableBooks(r, t.RegionID) {
				if b.ID == id {
					book, d.BookID = b, &b.ID
				}
			}
		}
		sec, _ := s.hosted().Security()
		d.Security = string(sec)
		oldAccount := d.LDAPAccountID
		d.LDAPAccountID = nil
		if r.FormValue("credentials") == "manual" {
			id := formID(r, "account")
			d.LDAPAccountID, d.PasswordMode = &id, r.FormValue("passwordMode")
			account, err := s.St.LDAPAccountByID(ctx, id)
			if err != nil {
				account = nil
			}
			if p := s.accountPasswordProblem(account, password); p != "" && d.PasswordMode == catalog.PasswordTemplate {
				hostedProblems = append(hostedProblems, p)
			}
			if p := catalog.HostedAccountProblem(d, book, account, t.RegionID); p != "" {
				hostedProblems = append(hostedProblems, p)
			} else if d.PasswordMode == catalog.PasswordTemplate && password == "" && (d.PasswordEnc == "" || derefOr(oldAccount) != id) {
				hostedProblems = append(hostedProblems, "请填写 LDAP 账号「"+account.Name+"」的密码")
			}
		}
	} else {
		d.Host, d.Port = r.FormValue("host"), intValue(r, "port", 0)
		d.Security, d.BindDN, d.BaseDN = r.FormValue("security"), r.FormValue("bindDn"), r.FormValue("baseDn")
		d.PasswordMode = r.FormValue("passwordMode")
		d.NameAttributes, d.NumberAttributes = r.FormValue("nameAttributes"), r.FormValue("numberAttributes")
		d.NameFilter, d.NumberFilter = r.FormValue("nameFilter"), r.FormValue("numberFilter")
	}
	catalog.NormalizeDirectory(d)
	others, err := s.St.Directories(ctx, t.ID)
	if err != nil {
		s.internal(w, r, err)
		return
	}
	problems := append(catalog.DirectoryProblems(d, others, book, s.hosted(), isNew), hostedProblems...)
	if d.Kind == "external" && d.PasswordMode == catalog.PasswordTemplate && password == "" && d.PasswordEnc == "" {
		problems = append(problems, "请填写模板中的 LDAP 密码")
	}
	var seal func(id int64) (string, error)
	if d.PasswordMode == catalog.PasswordTemplate && password != "" {
		seal = func(id int64) (string, error) {
			return s.Ext.Box.Seal([]byte(password), secret.AAD("template_directories", id, "password_enc"))
		}
	}
	if len(problems) == 0 {
		err := s.St.SaveDirectory(ctx, d, rules.MaxDirectories, seal)
		switch {
		case errors.Is(err, store.ErrLimit):
			problems = append(problems, rules.LdapLimitReached.Full())
		case err != nil:
			s.internal(w, r, err)
			return
		}
	}
	if len(problems) > 0 {
		s.directoryFormPage(w, r, http.StatusBadRequest, directoryForm{T: t, D: d, New: isNew, Errors: problems})
		return
	}
	detail := map[string]any{"directory": d.Name, "kind": d.Kind}
	if d.Kind == "hosted" {
		detail["credentials"] = map[bool]string{true: "manual", false: "auto"}[d.LDAPAccountID != nil]
	}
	if seal != nil {
		detail["password"] = "已修改"
	}
	s.audit(r, "config_template.update", "config_template", t.ID, t.Name, t.RegionID, detail)
	s.redirect(w, r, "/config-templates/"+itoa(t.ID)+"?tab=directories", "ok", "通讯录已保存。")
}

func (s *Server) directoryDelete(w http.ResponseWriter, r *http.Request) {
	t := s.loadConfig(w, r, true)
	if t == nil {
		return
	}
	d := s.loadDirectory(w, r, t)
	if d == nil {
		return
	}
	if err := s.St.DeleteDirectory(r.Context(), t.ID, d.ID); err != nil {
		s.internal(w, r, err)
		return
	}
	s.audit(r, "config_template.update", "config_template", t.ID, t.Name, t.RegionID, map[string]any{"directory": d.Name, "op": "delete"})
	s.redirect(w, r, "/config-templates/"+itoa(t.ID)+"?tab=directories", "ok", "已删除。")
}

// ---- order of profiles, directories and keys ----

// reorder saves the order of a template's list set by dragging (sortable.js): the
// form's "id" values name every row once, in the new order. A list that changed in
// the meantime is refused.
func (s *Server) reorder(w http.ResponseWriter, r *http.Request, table string, parentID int64) bool {
	err := s.St.Reorder(r.Context(), table, parentID, formIDs(r, "id"))
	if errors.Is(err, store.ErrOrder) {
		s.fail(w, r, http.StatusConflict, "列表已发生变化，请刷新页面后重试。")
		return false
	}
	if err != nil {
		s.internal(w, r, err)
		return false
	}
	return true
}

func (s *Server) profileOrder(w http.ResponseWriter, r *http.Request) {
	t := s.loadConfig(w, r, true)
	if t != nil && s.reorder(w, r, "account_profiles", t.ID) {
		s.audit(r, "config_template.update", "config_template", t.ID, t.Name, t.RegionID, map[string]any{"op": "profile_order"})
		w.WriteHeader(http.StatusNoContent)
	}
}

func (s *Server) directoryOrder(w http.ResponseWriter, r *http.Request) {
	t := s.loadConfig(w, r, true)
	if t != nil && s.reorder(w, r, "template_directories", t.ID) {
		s.audit(r, "config_template.update", "config_template", t.ID, t.Name, t.RegionID, map[string]any{"op": "directory_order"})
		w.WriteHeader(http.StatusNoContent)
	}
}
