package web

import (
	"bytes"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"

	"siplink/internal/books"
	"siplink/internal/secret"
	"siplink/internal/store"
)

func urlQueryEscape(s string) string { return url.QueryEscape(s) }

// pendingImport is a checked file waiting for the administrator's confirmation.
type pendingImport struct {
	adminID int64
	bookID  int64
	replace bool
	entries []*store.Entry
	clients []*importedClient // client import (bookID 0)
	expires time.Time
}

// importCache keeps dry-run results in memory for a short time.
type importCache struct {
	mu    sync.Mutex
	items map[string]*pendingImport
}

const importTTL = 30 * time.Minute

func (c *importCache) put(now time.Time, p *pendingImport) string {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.items == nil {
		c.items = map[string]*pendingImport{}
	}
	for k, v := range c.items {
		if now.After(v.expires) {
			delete(c.items, k)
		}
	}
	key := secret.Token(18)
	p.expires = now.Add(importTTL)
	c.items[key] = p
	return key
}

// take removes and returns a pending import of this administrator and book.
func (c *importCache) take(now time.Time, key string, adminID, bookID int64) *pendingImport {
	c.mu.Lock()
	defer c.mu.Unlock()
	p := c.items[key]
	if p == nil || p.adminID != adminID || p.bookID != bookID || now.After(p.expires) {
		return nil
	}
	delete(c.items, key)
	return p
}

type importData struct {
	Book     *store.Book
	Error    string
	Parsed   *books.Parsed
	Replace  bool
	Key      string
	After    int // entries after the import
	Max      int
	TooMany  bool
	MaxBytes int
	Columns  []string // header lines the format help shows
}

// newImportData is the import page of a book. Its format help shows the English
// column names, on a page in Chinese the Chinese ones first; the import reads both.
func (s *Server) newImportData(r *http.Request, b *store.Book) importData {
	columns := []string{strings.Join(books.ExportHeader(false), ",")}
	if chineseColumns(r) {
		columns = append([]string{strings.Join(books.ExportHeader(true), ",")}, columns...)
	}
	return importData{Book: b, Max: s.Cfg.MaxBookEntries, MaxBytes: books.MaxCSVBytes >> 20, Columns: columns}
}

func (s *Server) importForm(w http.ResponseWriter, r *http.Request) {
	if b := s.loadBook(w, r, true); b != nil {
		s.ok(w, r, "book_import", &Page{Title: tr(r).T("dirs.import.title"), Nav: "books", Data: s.newImportData(r, b)})
	}
}

// maxBookUpload is the body limit of a directory import: the file and the form around it.
const maxBookUpload = books.MaxCSVBytes + 64<<10

// importDryRun reads the uploaded file and shows what an import would do. Nothing is
// written; the parsed rows wait in memory for the confirmation.
func (s *Server) importDryRun(w http.ResponseWriter, r *http.Request) {
	b := s.loadBook(w, r, true)
	if b == nil {
		return
	}
	d := s.newImportData(r, b)
	fail := func(msg string) {
		d.Error = msg
		s.render(w, r, http.StatusBadRequest, "book_import", &Page{Title: tr(r).T("dirs.import.title"), Nav: "books", Data: d})
	}
	if err := r.ParseMultipartForm(maxBookUpload); err != nil {
		fail(tr(r).T("dirs.import.unreadable_max", "max", books.MaxCSVBytes>>20))
		return
	}
	d.Replace = r.FormValue("mode") == "replace"
	f, _, err := r.FormFile("file")
	if err != nil {
		fail(tr(r).T("dirs.import.no_file"))
		return
	}
	defer f.Close()
	var buf bytes.Buffer
	if _, err := io.Copy(&buf, io.LimitReader(f, books.MaxCSVBytes+1)); err != nil {
		fail(tr(r).T("dirs.import.unreadable"))
		return
	}
	p, err := books.ParseCSV(buf.Bytes(), s.Cfg.MaxBookEntries)
	if err != nil {
		fail(errText(r, err))
		return
	}
	d.Parsed = p
	d.After = len(p.Entries)
	if !d.Replace {
		d.After += b.EntryCount
	}
	d.TooMany = d.After > s.Cfg.MaxBookEntries
	if len(p.Entries) > 0 && !d.TooMany {
		d.Key = s.imports.put(s.Now(), &pendingImport{adminID: current(r).ID, bookID: b.ID, replace: d.Replace, entries: p.Entries})
	}
	s.ok(w, r, "book_import", &Page{Title: tr(r).T("dirs.import.result_title"), Nav: "books", Data: d})
}

func (s *Server) importConfirm(w http.ResponseWriter, r *http.Request) {
	b := s.loadBook(w, r, true)
	if b == nil {
		return
	}
	p := s.imports.take(s.Now(), r.FormValue("key"), current(r).ID, b.ID)
	if p == nil {
		s.redirect(w, r, "/books/"+itoa(b.ID)+"/import", "error", tr(r).T("dirs.import.expired"))
		return
	}
	err := s.St.AddEntries(r.Context(), b.ID, p.entries, p.replace, s.Cfg.MaxBookEntries)
	if errors.Is(err, store.ErrLimit) {
		s.redirect(w, r, "/books/"+itoa(b.ID)+"/import", "error",
			tr(r).T("dirs.import.over_limit", "max", s.Cfg.MaxBookEntries))
		return
	}
	if err != nil {
		s.internal(w, r, err)
		return
	}
	mode := "append"
	if p.replace {
		mode = "replace"
	}
	s.audit(r, "book.import", "book", b.ID, b.Name, b.RegionID, map[string]any{"mode": mode, "count": len(p.entries)})
	s.redirect(w, r, "/books/"+itoa(b.ID), "ok", tr(r).T("dirs.import.done", "n", len(p.entries)))
}

func (s *Server) exportBook(w http.ResponseWriter, r *http.Request, format string) {
	b := s.loadBook(w, r, false)
	if b == nil {
		return
	}
	entries, _, err := s.St.AllEntries(r.Context(), b.ID)
	if err != nil {
		s.internal(w, r, err)
		return
	}
	var buf bytes.Buffer
	ctype := "text/csv; charset=utf-8"
	if format == "ldif" {
		ctype = "text/plain; charset=utf-8"
		err = books.WriteLDIF(&buf, b, entries, s.Cfg.LDAPSuffix)
	} else {
		err = books.WriteCSV(&buf, entries, chineseColumns(r))
	}
	if err != nil {
		s.internal(w, r, err)
		return
	}
	s.audit(r, "book.export", "book", b.ID, b.Name, b.RegionID, map[string]any{"format": format, "count": len(entries)})
	w.Header().Set("Content-Type", ctype)
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("Content-Disposition", fmt.Sprintf(`attachment; filename="%s.%s"`, b.Slug, format))
	w.Write(buf.Bytes())
}

func (s *Server) exportCSV(w http.ResponseWriter, r *http.Request)  { s.exportBook(w, r, "csv") }
func (s *Server) exportLDIF(w http.ResponseWriter, r *http.Request) { s.exportBook(w, r, "ldif") }
