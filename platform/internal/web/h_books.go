package web

import (
	"errors"
	"fmt"
	"net/http"
	"strings"

	"siplink/internal/auth"
	"siplink/internal/books"
	"siplink/internal/store"
)

func (s *Server) routesBooks() {
	s.page("GET /books", s.bookList)
	s.page("GET /books/new", s.bookNew)
	s.page("POST /books", s.bookCreate)
	s.page("GET /books/{id}", s.bookShow)
	s.page("GET /books/{id}/edit", s.bookEdit)
	s.page("POST /books/{id}", s.bookUpdate)
	s.page("POST /books/{id}/delete", s.bookDelete)
	s.page("GET /books/{id}/entries/new", s.entryNew)
	s.page("POST /books/{id}/entries", s.entryCreate)
	s.page("GET /books/{id}/entries/{eid}", s.entryEdit)
	s.page("POST /books/{id}/entries/{eid}", s.entryUpdate)
	s.page("POST /books/{id}/entries/{eid}/delete", s.entryDelete)
	s.page("GET /books/{id}/import", s.importForm)
	s.page("POST /books/{id}/import", s.importDryRun)
	s.page("POST /books/{id}/import/confirm", s.importConfirm)
	s.page("GET /books/{id}/export.csv", s.exportCSV)
	s.page("GET /books/{id}/export.ldif", s.exportLDIF)
}

// bookScope is what an administrator may see, narrowed by the top-bar filter.
func (s *Server) bookScope(r *http.Request) store.BookScope {
	a := current(r)
	sc := store.BookScope{All: a.Role == store.RoleSuper, Regions: a.RegionIDs}
	sc.Region = s.selectedRegion(r)
	return sc
}

type bookRow struct {
	*store.Book
	Region  string
	CanEdit bool
}

type booksData struct {
	Rows  []bookRow
	Max   int
	Total int
	Limit int
}

func (s *Server) bookList(w http.ResponseWriter, r *http.Request) {
	list, err := s.St.ListBooks(r.Context(), s.bookScope(r))
	if err != nil {
		s.internal(w, r, err)
		return
	}
	names, err := s.allRegionNames(r)
	if err != nil {
		s.internal(w, r, err)
		return
	}
	act := actor(r)
	d := booksData{Max: s.Cfg.MaxBookEntries, Limit: s.Cfg.MaxBooks}
	d.Total, _ = s.St.CountBooks(r.Context())
	for _, b := range list {
		d.Rows = append(d.Rows, bookRow{Book: b, Region: regionLabel(names, b.RegionID), CanEdit: act.Can(auth.BookEdit, b.RegionID)})
	}
	s.ok(w, r, "books", &Page{Title: "通讯录", Nav: "books", Data: d})
}

func (s *Server) allRegionNames(r *http.Request) (map[int64]string, error) {
	regions, err := s.St.ListRegions(r.Context(), nil, true)
	return regionNames(regions), err
}

func regionLabel(names map[int64]string, id *int64) string {
	if id == nil {
		return "全局"
	}
	return names[*id]
}

// loadBook returns the book of the path when the administrator may see it (and
// edit it when edit is true); otherwise it answers 404 / 403 and returns nil.
func (s *Server) loadBook(w http.ResponseWriter, r *http.Request, edit bool) *store.Book {
	id, ok := pathID(r, "id")
	b, err := s.St.BookByID(r.Context(), id)
	if !ok || errors.Is(err, store.ErrNotFound) {
		s.notFound(w, r)
		return nil
	}
	if err != nil {
		s.internal(w, r, err)
		return nil
	}
	act := actor(r)
	if !act.Can(auth.BookView, b.RegionID) {
		s.notFound(w, r) // 404, not 403: do not reveal books of other regions
		return nil
	}
	if edit && !act.Can(auth.BookEdit, b.RegionID) {
		s.fail(w, r, http.StatusForbidden, "全局通讯录仅可由超级管理员修改。")
		return nil
	}
	return b
}

// ---- book create / edit / delete ----

type bookForm struct {
	Book    *store.Book
	New     bool
	Regions []store.Region
	Global  bool // the administrator may choose "global"
	Error   string
	Users   []string
}

func (s *Server) bookFormPage(w http.ResponseWriter, r *http.Request, status int, f bookForm) {
	a := current(r)
	regions, err := s.St.ListRegions(r.Context(), a.RegionIDs, a.Role == store.RoleSuper)
	if err != nil {
		s.internal(w, r, err)
		return
	}
	f.Regions, f.Global = regions, a.Role == store.RoleSuper
	title := "编辑通讯录"
	if f.New {
		title = "新建通讯录"
	}
	s.render(w, r, status, "book_edit", &Page{Title: title, Nav: "books", Data: f})
}

func (s *Server) bookNew(w http.ResponseWriter, r *http.Request) {
	b := &store.Book{}
	if sel := s.selectedRegion(r); sel != 0 {
		b.RegionID = &sel
	} else if a := current(r); a.Role != store.RoleSuper && len(a.RegionIDs) > 0 {
		b.RegionID = &a.RegionIDs[0]
	}
	s.bookFormPage(w, r, http.StatusOK, bookForm{New: true, Book: b})
}

// formRegion reads the region select: "" or "0" = global.
func formRegion(r *http.Request) *int64 {
	if id := formID(r, "region"); id > 0 {
		return &id
	}
	return nil
}

func (s *Server) bookCreate(w http.ResponseWriter, r *http.Request) {
	b := &store.Book{Slug: strings.ToLower(trimmed(r, "slug")), Name: trimmed(r, "name"), Note: truncate(trimmed(r, "note"), books.MaxNote),
		AllowAnonymous: r.FormValue("allow_anonymous") == "1", RegionID: formRegion(r)}
	msg := books.NameProblem(b.Name)
	if m := books.SlugProblem(b.Slug); m != "" {
		msg = m
	}
	if !actor(r).Can(auth.BookEdit, b.RegionID) {
		msg = "您无权在所选区域创建通讯录。"
	} else if b.RegionID != nil {
		if _, err := s.St.RegionByID(r.Context(), *b.RegionID); err != nil {
			msg = "所选区域不存在。"
		}
	}
	if msg != "" {
		s.bookFormPage(w, r, http.StatusBadRequest, bookForm{New: true, Book: b, Error: msg})
		return
	}
	err := s.St.CreateBook(r.Context(), b, s.Cfg.MaxBooks)
	switch {
	case errors.Is(err, store.ErrConflict):
		msg = "标识「" + b.Slug + "」已被使用。"
	case errors.Is(err, store.ErrLimit):
		msg = fmt.Sprintf("通讯录数量已达上限 %d 个。", s.Cfg.MaxBooks)
	case err != nil:
		s.internal(w, r, err)
		return
	}
	if msg != "" {
		s.bookFormPage(w, r, http.StatusBadRequest, bookForm{New: true, Book: b, Error: msg})
		return
	}
	s.audit(r, "book.create", "book", b.ID, b.Name, b.RegionID, map[string]any{"slug": b.Slug, "name": b.Name})
	s.redirect(w, r, "/books/"+itoa(b.ID), "ok", "通讯录「"+b.Name+"」已创建。")
}

func (s *Server) bookEdit(w http.ResponseWriter, r *http.Request) {
	b := s.loadBook(w, r, true)
	if b == nil {
		return
	}
	users, err := s.St.BookUsers(r.Context(), b.ID)
	if err != nil {
		s.internal(w, r, err)
		return
	}
	s.bookFormPage(w, r, http.StatusOK, bookForm{Book: b, Users: users})
}

func (s *Server) bookUpdate(w http.ResponseWriter, r *http.Request) {
	old := s.loadBook(w, r, true)
	if old == nil {
		return
	}
	b := *old
	b.Name, b.Note = trimmed(r, "name"), truncate(trimmed(r, "note"), books.MaxNote)
	b.AllowAnonymous = r.FormValue("allow_anonymous") == "1"
	msg := books.NameProblem(b.Name)
	if current(r).Role == store.RoleSuper {
		// Moving a book between regions is a super-administrator action (§2.3).
		b.RegionID = formRegion(r)
		if b.RegionID != nil {
			if _, err := s.St.RegionByID(r.Context(), *b.RegionID); err != nil {
				msg = "所选区域不存在。"
			}
		}
		if msg == "" && !sameRegion(old.RegionID, b.RegionID) {
			msg = s.bookMoveProblem(r, old, b.RegionID)
		}
	}
	if msg != "" {
		s.bookFormPage(w, r, http.StatusBadRequest, bookForm{Book: &b, Error: msg})
		return
	}
	if err := s.St.UpdateBook(r.Context(), &b); err != nil {
		s.internal(w, r, err)
		return
	}
	s.audit(r, "book.update", "book", b.ID, b.Name, b.RegionID, changes(
		field{"name", old.Name, b.Name}, field{"note", old.Note, b.Note},
		field{"allow_anonymous", old.AllowAnonymous, b.AllowAnonymous},
		field{"region_id", derefOr(old.RegionID), derefOr(b.RegionID)}))
	s.redirect(w, r, "/books/"+itoa(b.ID), "ok", "通讯录已保存。")
}

func sameRegion(a, b *int64) bool { return a == nil && b == nil || a != nil && b != nil && *a == *b }

func derefOr(p *int64) int64 {
	if p == nil {
		return 0
	}
	return *p
}

func (s *Server) bookDelete(w http.ResponseWriter, r *http.Request) {
	b := s.loadBook(w, r, true)
	if b == nil {
		return
	}
	err := s.St.DeleteBook(r.Context(), b.ID)
	if errors.Is(err, store.ErrInUse) {
		users, _ := s.St.BookUsers(r.Context(), b.ID)
		s.redirect(w, r, "/books/"+itoa(b.ID)+"/edit", "error", "通讯录正在使用中，无法删除："+strings.Join(users, "、"))
		return
	}
	if err != nil {
		s.internal(w, r, err)
		return
	}
	s.audit(r, "book.delete", "book", b.ID, b.Name, b.RegionID, map[string]any{"slug": b.Slug, "entries": b.EntryCount})
	s.redirect(w, r, "/books", "ok", "通讯录「"+b.Name+"」已删除。")
}

// ---- book page with entries ----

const entriesPerPage = 50

type bookData struct {
	Book    *store.Book
	Region  string
	CanEdit bool
	Entries []*store.Entry
	Total   int
	Q       string
	Page    int
	Prev    string
	Next    string
	Max     int
	Conn    connInfo
}

type connInfo struct {
	Host       string
	Port       int
	BaseDN     string
	RequireTLS bool
	LDAPS      bool
	Anonymous  bool
}

func (s *Server) bookShow(w http.ResponseWriter, r *http.Request) {
	b := s.loadBook(w, r, false)
	if b == nil {
		return
	}
	q := strings.TrimSpace(r.URL.Query().Get("q"))
	page, _ := parseInt64(r.URL.Query().Get("page"))
	d := bookData{Book: b, Q: q, Page: int(max(page, 1)), Max: s.Cfg.MaxBookEntries,
		CanEdit: actor(r).Can(auth.BookEdit, b.RegionID),
		Conn: connInfo{Host: s.Cfg.LDAPPublicHost, Port: s.Cfg.LDAPPublicPort, BaseDN: books.BookDN(b.Slug, s.Cfg.LDAPSuffix),
			RequireTLS: s.Cfg.LDAPRequireTLS, LDAPS: s.Cfg.LDAPSListen != "", Anonymous: b.AllowAnonymous}}
	var err error
	d.Entries, d.Total, err = s.St.SearchEntries(r.Context(), b.ID, q, entriesPerPage, (d.Page-1)*entriesPerPage)
	if err != nil {
		s.internal(w, r, err)
		return
	}
	names, _ := s.allRegionNames(r)
	d.Region = regionLabel(names, b.RegionID)
	link := func(p int) string {
		u := fmt.Sprintf("/books/%d?page=%d", b.ID, p)
		if q != "" {
			u += "&q=" + urlQueryEscape(q)
		}
		return u
	}
	if d.Page > 1 {
		d.Prev = link(d.Page - 1)
	}
	if d.Page*entriesPerPage < d.Total {
		d.Next = link(d.Page + 1)
	}
	s.ok(w, r, "book", &Page{Title: b.Name, Nav: "books", Data: d})
}

// ---- entries ----

type entryForm struct {
	Book    *store.Book
	Entry   *store.Entry
	New     bool
	Errors  []string
	Phones  string
	Mobiles string
}

func (s *Server) entryFormPage(w http.ResponseWriter, r *http.Request, status int, f entryForm) {
	if f.Phones == "" {
		f.Phones = strings.Join(f.Entry.Phones, "; ")
	}
	if f.Mobiles == "" {
		f.Mobiles = strings.Join(f.Entry.Mobiles, "; ")
	}
	title := "编辑联系人"
	if f.New {
		title = "新建联系人"
	}
	s.render(w, r, status, "entry_edit", &Page{Title: title, Nav: "books", Data: f})
}

func readEntry(r *http.Request) *store.Entry {
	e := &store.Entry{Name: r.FormValue("name"), Surname: r.FormValue("surname"), GivenName: r.FormValue("given_name"),
		Phones: books.SplitNumbers(r.FormValue("phones")), Mobiles: books.SplitNumbers(r.FormValue("mobiles")),
		Organization: r.FormValue("organization"), Department: r.FormValue("department"), Title: r.FormValue("title"),
		Email: r.FormValue("email")}
	books.Normalize(e)
	return e
}

func (s *Server) entryNew(w http.ResponseWriter, r *http.Request) {
	if b := s.loadBook(w, r, true); b != nil {
		s.entryFormPage(w, r, http.StatusOK, entryForm{Book: b, Entry: &store.Entry{}, New: true})
	}
}

func (s *Server) entryCreate(w http.ResponseWriter, r *http.Request) {
	b := s.loadBook(w, r, true)
	if b == nil {
		return
	}
	e := readEntry(r)
	if problems := books.Validate(e); len(problems) > 0 {
		s.entryFormPage(w, r, http.StatusBadRequest, entryForm{Book: b, Entry: e, New: true, Errors: problems,
			Phones: r.FormValue("phones"), Mobiles: r.FormValue("mobiles")})
		return
	}
	err := s.St.AddEntries(r.Context(), b.ID, []*store.Entry{e}, false, s.Cfg.MaxBookEntries)
	if errors.Is(err, store.ErrLimit) {
		s.entryFormPage(w, r, http.StatusBadRequest, entryForm{Book: b, Entry: e, New: true,
			Errors: []string{fmt.Sprintf("通讯录已达 %d 人上限。", s.Cfg.MaxBookEntries)}})
		return
	}
	if err != nil {
		s.internal(w, r, err)
		return
	}
	s.audit(r, "entry.create", "entry", e.ID, e.Name, b.RegionID, map[string]any{"book": b.Slug})
	s.redirect(w, r, "/books/"+itoa(b.ID), "ok", "联系人「"+e.Name+"」已添加。")
}

func (s *Server) loadEntry(w http.ResponseWriter, r *http.Request, b *store.Book) *store.Entry {
	eid, ok := pathID(r, "eid")
	e, err := s.St.EntryByID(r.Context(), b.ID, eid)
	if !ok || errors.Is(err, store.ErrNotFound) {
		s.notFound(w, r)
		return nil
	}
	if err != nil {
		s.internal(w, r, err)
		return nil
	}
	return e
}

func (s *Server) entryEdit(w http.ResponseWriter, r *http.Request) {
	b := s.loadBook(w, r, true)
	if b == nil {
		return
	}
	if e := s.loadEntry(w, r, b); e != nil {
		s.entryFormPage(w, r, http.StatusOK, entryForm{Book: b, Entry: e})
	}
}

func (s *Server) entryUpdate(w http.ResponseWriter, r *http.Request) {
	b := s.loadBook(w, r, true)
	if b == nil {
		return
	}
	old := s.loadEntry(w, r, b)
	if old == nil {
		return
	}
	e := readEntry(r)
	e.ID, e.BookID = old.ID, b.ID
	if problems := books.Validate(e); len(problems) > 0 {
		s.entryFormPage(w, r, http.StatusBadRequest, entryForm{Book: b, Entry: e, Errors: problems,
			Phones: r.FormValue("phones"), Mobiles: r.FormValue("mobiles")})
		return
	}
	if err := s.St.UpdateEntry(r.Context(), e); err != nil {
		s.internal(w, r, err)
		return
	}
	s.audit(r, "entry.update", "entry", e.ID, e.Name, b.RegionID, changes(
		field{"name", old.Name, e.Name}, field{"surname", old.Surname, e.Surname}, field{"given_name", old.GivenName, e.GivenName},
		field{"phones", old.Phones, e.Phones}, field{"mobiles", old.Mobiles, e.Mobiles},
		field{"organization", old.Organization, e.Organization}, field{"department", old.Department, e.Department},
		field{"title", old.Title, e.Title}, field{"email", old.Email, e.Email}))
	s.redirect(w, r, "/books/"+itoa(b.ID), "ok", "联系人已保存。")
}

func (s *Server) entryDelete(w http.ResponseWriter, r *http.Request) {
	b := s.loadBook(w, r, true)
	if b == nil {
		return
	}
	e := s.loadEntry(w, r, b)
	if e == nil {
		return
	}
	if err := s.St.DeleteEntry(r.Context(), b.ID, e.ID); err != nil {
		s.internal(w, r, err)
		return
	}
	s.audit(r, "entry.delete", "entry", e.ID, e.Name, b.RegionID, map[string]any{"book": b.Slug})
	s.redirect(w, r, "/books/"+itoa(b.ID), "ok", "联系人「"+e.Name+"」已删除。")
}
