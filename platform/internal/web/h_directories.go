package web

import (
	"errors"
	"html/template"
	"net/http"
	"net/url"
	"strconv"
	"strings"

	"siplink/internal/auth"
	"siplink/internal/books"
	"siplink/internal/catalog"
	"siplink/internal/i18n"
	"siplink/internal/rules"
	"siplink/internal/store"
)

// One page "通讯录" lists the address books hosted here (内部) and the external
// directories (外部) together (design §2.1, §5.2). Books keep their pages under /books;
// external directories have theirs under /directories.

func (s *Server) routesDirectories() {
	s.page("POST /directories", s.externalCreate)
	s.page("GET /directories/{id}", s.externalShow)
	s.page("POST /directories/{id}", s.externalUpdate)
	s.page("POST /directories/{id}/delete", s.externalDelete)
}

const directoriesPerPage = 50

type directoryListRow struct {
	store.DirectoryListRow
	Region string
	Link   string
}

type directoriesData struct {
	Rows  []directoryListRow
	Total int
	Page  int
	Pages int
	Q     string
	Kind  string
	Query template.URL // the filter as a query string, for paging links
	Max   int          // entries per book
}

// kindOf reads a kind word; "" when it is neither.
func kindOf(v string) string {
	if v == store.KindHosted || v == store.KindExternal {
		return v
	}
	return ""
}

func (s *Server) directoryList(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	f := store.DirectoryFilter{Scope: s.bookScope(r), Query: strings.TrimSpace(q.Get("q")), Kind: kindOf(q.Get("kind"))}
	page, _ := strconv.Atoi(q.Get("page"))
	page = max(page, 1)
	f.Limit, f.Offset = directoriesPerPage, (page-1)*directoriesPerPage
	list, total, err := s.St.ListDirectories(r.Context(), f)
	if err != nil {
		s.internal(w, r, err)
		return
	}
	names, err := s.allRegionNames(r)
	if err != nil {
		s.internal(w, r, err)
		return
	}
	d := directoriesData{Total: total, Page: page, Pages: (total + directoriesPerPage - 1) / directoriesPerPage, Q: f.Query,
		Kind: f.Kind, Max: s.Cfg.MaxBookEntries, Query: pageQuery(url.Values{"q": {f.Query}, "kind": {f.Kind}})}
	for _, row := range list {
		link := "/books/" + itoa(row.ID)
		if row.Kind == store.KindExternal {
			link = "/directories/" + itoa(row.ID)
		}
		d.Rows = append(d.Rows, directoryListRow{DirectoryListRow: row, Region: regionLabel(names, row.RegionID), Link: link})
	}
	s.ok(w, r, "books", &Page{Title: tr(r).T("nav.books"), Nav: "books", Data: d})
}

// kindRefused answers 400 when a form names another kind than the object has: the kind
// is chosen when a directory is made and never changes.
func (s *Server) kindRefused(w http.ResponseWriter, r *http.Request, kind string) bool {
	if v := r.FormValue("kind"); v != "" && v != kind {
		s.fail(w, r, http.StatusBadRequest, tr(r).T("dirs.kind_fixed"))
		return true
	}
	return false
}

// directoryMoveProblem says why a directory cannot move to another region, or "": a
// configuration template of another region, or a global one, uses it (§2.3).
func (s *Server) directoryMoveProblem(r *http.Request, kind string, id int64, to *int64) string {
	if to == nil {
		return "" // a global directory can be used from every region
	}
	regions, err := s.St.DirectoryTemplateRegions(r.Context(), kind, id)
	if err != nil {
		return tr(r).T("error.internal_retry")
	}
	for _, reg := range regions {
		if reg == nil || *reg != *to {
			return tr(r).T("dirs.move_used_by_templates")
		}
	}
	return ""
}

// ---- external directories ----

type externalForm struct {
	E       *store.ExternalDirectory
	New     bool
	Kind    string // for the type choice of a new directory
	CanEdit bool
	Regions []store.Region
	Global  bool // the administrator may choose "global"
	Region  string
	HasPass bool
	Users   i18n.Names // configuration templates that use it
	Clients int        // clients of those templates
	Errors  []string
}

func (s *Server) externalFormPage(w http.ResponseWriter, r *http.Request, status int, f externalForm) {
	a := current(r)
	ctx := r.Context()
	f.Regions, _ = s.St.ListRegions(ctx, a.RegionIDs, a.Role == store.RoleSuper)
	f.Global, f.Kind, f.HasPass = a.Role == store.RoleSuper, store.KindExternal, f.E.PasswordEnc != ""
	title := tr(r).T("dirs.new")
	if !f.New {
		names, _ := s.allRegionNames(r)
		f.Region = regionLabel(names, f.E.RegionID)
		f.Users, _ = s.St.DirectoryTemplates(ctx, store.KindExternal, f.E.ID)
		f.Clients, _ = s.St.DirectoryClients(ctx, store.KindExternal, f.E.ID)
		title = f.E.Name
	}
	s.render(w, r, status, "external", &Page{Title: title, Nav: "books", Data: f})
}

func (s *Server) externalNew(w http.ResponseWriter, r *http.Request) {
	e := &store.ExternalDirectory{RegionID: s.defaultRegion(r), Port: 389, Security: string(rules.LdapSecurityStartTLS),
		PasswordMode: catalog.PasswordNone, MaxResults: 50, NameAttributes: rules.LdapDefaultNameAttributes,
		NumberAttributes: rules.LdapDefaultNumberAttributes}
	s.externalFormPage(w, r, http.StatusOK, externalForm{E: e, New: true, CanEdit: true})
}

// loadExternal returns the external directory of the path when the administrator may
// see it (and edit it when edit is true); otherwise it answers 404 / 403 and returns nil.
func (s *Server) loadExternal(w http.ResponseWriter, r *http.Request, edit bool) *store.ExternalDirectory {
	id, ok := pathID(r, "id")
	e, err := s.St.ExternalDirectoryByID(r.Context(), id)
	if !ok || errors.Is(err, store.ErrNotFound) {
		s.notFound(w, r)
		return nil
	}
	if err != nil {
		s.internal(w, r, err)
		return nil
	}
	act := actor(r)
	if !act.Can(auth.BookView, e.RegionID) {
		s.notFound(w, r) // 404, not 403: do not reveal directories of other regions
		return nil
	}
	if edit && !act.Can(auth.BookEdit, e.RegionID) {
		s.fail(w, r, http.StatusForbidden, tr(r).T("dirs.global_super_only"))
		return nil
	}
	return e
}

// readExternal reads the connection fields of the form into e and normalizes them.
func readExternal(r *http.Request, e *store.ExternalDirectory) {
	e.Name, e.Note = r.FormValue("name"), truncate(trimmed(r, "note"), books.MaxNote)
	e.Host, e.Port, e.Security = r.FormValue("host"), intValue(r, "port", 0), r.FormValue("security")
	e.BindDN, e.BaseDN, e.PasswordMode = r.FormValue("bindDn"), r.FormValue("baseDn"), r.FormValue("passwordMode")
	e.MaxResults = intValue(r, "maxResults", 0)
	e.NameAttributes, e.NumberAttributes = r.FormValue("nameAttributes"), r.FormValue("numberAttributes")
	e.NameFilter, e.NumberFilter = r.FormValue("nameFilter"), r.FormValue("numberFilter")
	catalog.NormalizeExternal(e)
}

// saveExternal checks and stores a directory read from the form; problems go back to
// the form. The password is write-only: an empty field keeps the stored one.
func (s *Server) saveExternal(r *http.Request, e *store.ExternalDirectory) (sealed bool, problems []string, err error) {
	problems = tr(r).Msgs(catalog.ExternalProblems(e))
	password := r.FormValue("password")
	if e.PasswordMode == catalog.PasswordTemplate && password == "" && e.PasswordEnc == "" {
		problems = append(problems, tr(r).T("dirs.external.password_required"))
	}
	if e.RegionID != nil {
		if _, err := s.St.RegionByID(r.Context(), *e.RegionID); err != nil {
			problems = append(problems, tr(r).T("dirs.region_missing"))
		}
	}
	if len(problems) > 0 {
		return false, problems, nil
	}
	var seal func(id int64) (string, error)
	if e.PasswordMode == catalog.PasswordTemplate && password != "" {
		seal = func(id int64) (string, error) {
			return s.Ext.Box.Seal([]byte(password), store.ExternalDirectoryAAD(id))
		}
	}
	err = s.St.SaveExternalDirectory(r.Context(), e, seal)
	if errors.Is(err, store.ErrConflict) {
		return false, []string{tr(r).T("dirs.external.name_taken", "name", e.Name)}, nil
	}
	return seal != nil, nil, err
}

func (s *Server) externalCreate(w http.ResponseWriter, r *http.Request) {
	e := &store.ExternalDirectory{RegionID: formRegion(r)}
	readExternal(r, e)
	if !actor(r).Can(auth.BookEdit, e.RegionID) {
		s.externalFormPage(w, r, http.StatusBadRequest, externalForm{E: e, New: true, CanEdit: true,
			Errors: []string{tr(r).T("dirs.create_forbidden")}})
		return
	}
	sealed, problems, err := s.saveExternal(r, e)
	if err != nil {
		s.internal(w, r, err)
		return
	}
	if len(problems) > 0 {
		s.externalFormPage(w, r, http.StatusBadRequest, externalForm{E: e, New: true, CanEdit: true, Errors: problems})
		return
	}
	detail := map[string]any{"name": e.Name, "host": e.Host, "password_mode": e.PasswordMode}
	if sealed {
		detail["password"] = auditChanged
	}
	s.audit(r, "directory.create", "directory", e.ID, e.Name, e.RegionID, detail)
	s.redirect(w, r, "/directories/"+itoa(e.ID), "ok", tr(r).T("dirs.created", "name", e.Name))
}

func (s *Server) externalShow(w http.ResponseWriter, r *http.Request) {
	if e := s.loadExternal(w, r, false); e != nil {
		s.externalFormPage(w, r, http.StatusOK, externalForm{E: e, CanEdit: actor(r).Can(auth.BookEdit, e.RegionID)})
	}
}

func (s *Server) externalUpdate(w http.ResponseWriter, r *http.Request) {
	old := s.loadExternal(w, r, true)
	if old == nil || s.kindRefused(w, r, store.KindExternal) {
		return
	}
	e := *old
	readExternal(r, &e)
	var problems []string
	if current(r).Role == store.RoleSuper {
		// Moving a directory between regions is a super-administrator action (§2.3).
		if e.RegionID = formRegion(r); !sameRegion(old.RegionID, e.RegionID) {
			if p := s.directoryMoveProblem(r, store.KindExternal, e.ID, e.RegionID); p != "" {
				problems = append(problems, p)
			}
		}
	}
	sealed := false
	if len(problems) == 0 {
		var err error
		if sealed, problems, err = s.saveExternal(r, &e); err != nil {
			s.internal(w, r, err)
			return
		}
	}
	if len(problems) > 0 {
		s.externalFormPage(w, r, http.StatusBadRequest, externalForm{E: &e, CanEdit: true, Errors: problems})
		return
	}
	detail := changes(field{"name", old.Name, e.Name}, field{"note", old.Note, e.Note},
		field{"region_id", derefOr(old.RegionID), derefOr(e.RegionID)}, field{"host", old.Host, e.Host}, field{"port", old.Port, e.Port},
		field{"security", old.Security, e.Security}, field{"bind_dn", old.BindDN, e.BindDN},
		field{"password_mode", old.PasswordMode, e.PasswordMode}, field{"base_dn", old.BaseDN, e.BaseDN},
		field{"max_results", old.MaxResults, e.MaxResults}, field{"name_attributes", old.NameAttributes, e.NameAttributes},
		field{"number_attributes", old.NumberAttributes, e.NumberAttributes}, field{"name_filter", old.NameFilter, e.NameFilter},
		field{"number_filter", old.NumberFilter, e.NumberFilter})
	if sealed {
		detail["password"] = auditChanged
	}
	s.audit(r, "directory.update", "directory", e.ID, e.Name, e.RegionID, detail)
	s.redirect(w, r, "/directories/"+itoa(e.ID), "ok", tr(r).T("dirs.saved"))
}

func (s *Server) externalDelete(w http.ResponseWriter, r *http.Request) {
	e := s.loadExternal(w, r, true)
	if e == nil {
		return
	}
	err := s.St.DeleteExternalDirectory(r.Context(), e.ID)
	if errors.Is(err, store.ErrInUse) {
		users, _ := s.St.DirectoryTemplates(r.Context(), store.KindExternal, e.ID)
		s.redirect(w, r, "/directories/"+itoa(e.ID), "error", tr(r).T("dirs.external.in_use", "templates", i18n.Names(users)))
		return
	}
	if err != nil {
		s.internal(w, r, err)
		return
	}
	s.audit(r, "directory.delete", "directory", e.ID, e.Name, e.RegionID, map[string]any{"host": e.Host})
	s.redirect(w, r, "/books", "ok", tr(r).T("dirs.deleted", "name", e.Name))
}
