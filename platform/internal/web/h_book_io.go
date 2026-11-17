package web

import (
	"bytes"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
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
}

func (s *Server) importForm(w http.ResponseWriter, r *http.Request) {
	if b := s.loadBook(w, r, true); b != nil {
		s.ok(w, r, "book_import", &Page{Title: "导入联系人", Nav: "books",
			Data: importData{Book: b, Max: s.Cfg.MaxBookEntries, MaxBytes: books.MaxCSVBytes >> 20}})
	}
}

// importDryRun reads the uploaded file and shows what an import would do. Nothing is
// written; the parsed rows wait in memory for the confirmation.
func (s *Server) importDryRun(w http.ResponseWriter, r *http.Request) {
	b := s.loadBook(w, r, true)
	if b == nil {
		return
	}
	d := importData{Book: b, Max: s.Cfg.MaxBookEntries, MaxBytes: books.MaxCSVBytes >> 20}
	fail := func(msg string) {
		d.Error = msg
		s.render(w, r, http.StatusBadRequest, "book_import", &Page{Title: "导入联系人", Nav: "books", Data: d})
	}
	r.Body = http.MaxBytesReader(w, r.Body, books.MaxCSVBytes+64<<10)
	if err := r.ParseMultipartForm(1 << 20); err != nil {
		fail(fmt.Sprintf("文件无法读取（最大 %d MB）。", books.MaxCSVBytes>>20))
		return
	}
	defer r.MultipartForm.RemoveAll()
	d.Replace = r.FormValue("mode") == "replace"
	f, _, err := r.FormFile("file")
	if err != nil {
		fail("请选择一个 CSV 文件。")
		return
	}
	defer f.Close()
	var buf bytes.Buffer
	if _, err := io.Copy(&buf, io.LimitReader(f, books.MaxCSVBytes+1)); err != nil {
		fail("文件无法读取。")
		return
	}
	p, err := books.ParseCSV(buf.Bytes())
	if err != nil {
		fail(err.Error())
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
	s.ok(w, r, "book_import", &Page{Title: "导入联系人：校验结果", Nav: "books", Data: d})
}

func (s *Server) importConfirm(w http.ResponseWriter, r *http.Request) {
	b := s.loadBook(w, r, true)
	if b == nil {
		return
	}
	p := s.imports.take(s.Now(), r.FormValue("key"), current(r).ID, b.ID)
	if p == nil {
		s.redirect(w, r, "/books/"+itoa(b.ID)+"/import", "error", "校验结果已失效，请重新上传文件。")
		return
	}
	err := s.St.AddEntries(r.Context(), b.ID, p.entries, p.replace, s.Cfg.MaxBookEntries)
	if errors.Is(err, store.ErrLimit) {
		s.redirect(w, r, "/books/"+itoa(b.ID)+"/import", "error",
			fmt.Sprintf("导入后将超出 %d 人上限，未导入任何联系人。", s.Cfg.MaxBookEntries))
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
	s.redirect(w, r, "/books/"+itoa(b.ID), "ok", fmt.Sprintf("已导入 %d 位联系人。", len(p.entries)))
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
		err = books.WriteCSV(&buf, entries)
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
