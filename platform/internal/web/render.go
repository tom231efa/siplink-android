package web

import (
	"bytes"
	"crypto/sha256"
	"embed"
	"encoding/base64"
	"encoding/hex"
	"fmt"
	"html/template"
	"io/fs"
	"net/http"
	"net/url"
	"path"
	"strconv"
	"strings"
	"sync"
	"time"

	"siplink/internal/i18n"
	"siplink/internal/store"
)

//go:embed templates/*.html
var templateFiles embed.FS

//go:embed static/*
var staticFiles embed.FS

// Page is what every template receives.
type Page struct {
	Title     string
	Nav       string // active sidebar item
	Admin     *store.Admin
	Super     bool
	Flash     string
	FlashKind string // "ok" or "error"
	Filter    *RegionFilter
	Version   string
	Path      string // current path, for forms that come back here
	Bare      bool   // print pages: no navigation
	Theme     string // "light", "dark" or "" (follow the system)
	Lang      *i18n.Lang
	Data      any

	tr *i18n.Tr
}

// Langs are the languages of the pickers.
func (p *Page) Langs() []*i18n.Lang { return i18n.Langs }

// JSText are the texts the scripts read from the page.
func (p *Page) JSText() map[string]string { return jsTexts()[p.Lang] }

// RegionFilter is the region choice in the top bar.
type RegionFilter struct {
	Options  []store.Region
	Selected int64 // 0 = all
}

// timeTag shows a time as <time datetime="RFC 3339">. The text is the language's
// pattern in the server's zone and names the zone, so it is right without JavaScript;
// app.js rewrites it to the viewer's local time with the same pattern. dateOnly shows
// the day alone.
func timeTag(t *i18n.Tr, tm time.Time, dateOnly bool) template.HTML {
	if tm.IsZero() {
		return "—"
	}
	local := tm.In(time.Local)
	kind := "datetime"
	if dateOnly {
		kind = "date"
	}
	zone := "UTC"
	if _, off := local.Zone(); off != 0 {
		zone += local.Format("-07:00")
	}
	return template.HTML(`<time dir="ltr" datetime="` + tm.UTC().Format(time.RFC3339) + `" data-local="` + kind + `">` +
		template.HTMLEscapeString(t.Date(local, dateOnly)) + " " + zone + `</time>`)
}

type pages struct {
	set map[*i18n.Lang]map[string]*template.Template // language → page name → template
}

// baseFuncs are the template helpers that do not depend on the language.
var baseFuncs = template.FuncMap{
	"icon":    iconSVG,
	"tabIcon": func(id string) string { return configTabIcons[id] },
	"initial": initial,
	"asset":   assetURL,
	"join":    strings.Join,
	"pct": func(n, total int) int {
		if total <= 0 {
			return 0
		}
		return n * 100 / total
	},
	"add":   func(a, b int) int { return a + b },
	"pct90": func(n int) int { return n * 9 / 10 },
	"lines": func(s string) []string {
		if s == "" {
			return nil
		}
		return strings.Split(s, "\n")
	},
	"hasID": func(ids []int64, id int64) bool {
		for _, v := range ids {
			if v == id {
				return true
			}
		}
		return false
	},
	"mb": func(n int64) float64 { return float64(n) / (1 << 20) },
	"deref": func(p *int64) int64 {
		if p == nil {
			return 0
		}
		return *p
	},
}

// langFuncs are the template helpers of one language: t translates a key with its
// arguments, msg and msgs translate messages of other packages, and the rest put
// labels and times into words.
func langFuncs(t *i18n.Tr) template.FuncMap {
	return template.FuncMap{
		"t":            t.T,
		"msg":          t.Msg,
		"msgs":         t.Msgs,
		"when":         func(s string) template.HTML { return timeTag(t, store.ParseTime(s), false) },
		"since":        func(s string) template.HTML { return sinceText(t, s) },
		"at":           func(tm time.Time) template.HTML { return timeTag(t, tm, false) },
		"day":          func(tm time.Time) template.HTML { return timeTag(t, tm, true) },
		"tip":          func(id, text string) template.HTML { return fieldTip(t, id, text) },
		"roleLabel":    func(role string) string { return roleLabel(t, role) },
		"actionLabel":  func(action string) string { return actionLabel(t, action) },
		"objectLabels": func() map[string]string { return objectLabels(t) },
		"fieldLabel":   func(key string) string { return fieldLabel(t, key) },
		"objectName":   func(objectType, name string) string { return objectName(t, objectType, name) },
		"regSummary":   func(raw string) string { return regSummary(t, raw) },
	}
}

// parsedPages parses the templates once per process: each page is parsed with the
// layout, then cloned for every language with that language's helpers.
var parsedPages = sync.OnceValues(func() (*pages, error) {
	names, err := fs.Glob(templateFiles, "templates/*.html")
	if err != nil {
		return nil, err
	}
	p := &pages{set: map[*i18n.Lang]map[string]*template.Template{}}
	for _, l := range i18n.Langs {
		p.set[l] = map[string]*template.Template{}
	}
	probe := langFuncs(i18n.For(i18n.English))
	for _, name := range names {
		base := path.Base(name)
		if base == "layout.html" {
			continue
		}
		t, err := template.New("").Funcs(baseFuncs).Funcs(probe).ParseFS(templateFiles, "templates/layout.html", name)
		if err != nil {
			return nil, fmt.Errorf("template %s: %w", base, err)
		}
		for _, l := range i18n.Langs {
			c, err := t.Clone()
			if err != nil {
				return nil, fmt.Errorf("template %s: %w", base, err)
			}
			p.set[l][strings.TrimSuffix(base, ".html")] = c.Funcs(langFuncs(i18n.For(l)))
		}
	}
	return p, nil
})

func loadPages() (*pages, error) { return parsedPages() }

// render executes a page inside the layout with the given status.
func (s *Server) render(w http.ResponseWriter, r *http.Request, status int, name string, pg *Page) {
	pg.tr = tr(r)
	pg.Lang = pg.tr.Lang
	t := s.pages.set[pg.Lang][name]
	if t == nil {
		s.Log.Error("unknown template", "name", name)
		http.Error(w, pg.tr.T("error.internal"), http.StatusInternalServerError)
		return
	}
	if pg.Admin == nil {
		pg.Admin = current(r)
	}
	if pg.Admin != nil {
		pg.Super = pg.Admin.Role == store.RoleSuper
		if pg.Filter == nil {
			pg.Filter = s.regionFilter(r)
		}
	}
	pg.Version = Version
	pg.Theme = themeOf(r)
	pg.Path = r.URL.RequestURI()
	if pg.Flash == "" {
		pg.Flash, pg.FlashKind = takeFlash(w, r)
	}
	var buf bytes.Buffer
	if err := t.ExecuteTemplate(&buf, "layout", pg); err != nil {
		s.Log.Error("template failed", "name", name, "err", err)
		http.Error(w, pg.tr.T("error.internal"), http.StatusInternalServerError)
		return
	}
	h := w.Header()
	h.Set("Content-Type", "text/html; charset=utf-8")
	h.Set("Cache-Control", "no-store")
	h.Set("Content-Language", pg.Lang.Code)
	w.WriteHeader(status)
	w.Write(buf.Bytes())
}

func (s *Server) ok(w http.ResponseWriter, r *http.Request, name string, pg *Page) {
	s.render(w, r, http.StatusOK, name, pg)
}

// fail shows an error page with a message already in the request's language.
func (s *Server) fail(w http.ResponseWriter, r *http.Request, status int, msg string) {
	s.render(w, r, status, "error", &Page{Title: statusTitle(tr(r), status), Data: msg, Admin: current(r)})
}

// statusTitle names an error page.
func statusTitle(t *i18n.Tr, status int) string {
	switch status {
	case http.StatusNotFound:
		return t.T("error.not_found_title")
	case http.StatusForbidden:
		return t.T("error.forbidden_title")
	case http.StatusTooManyRequests:
		return t.T("error.too_many_title")
	case http.StatusInternalServerError:
		return t.T("error.internal")
	}
	return http.StatusText(status)
}

func (s *Server) notFound(w http.ResponseWriter, r *http.Request) {
	s.fail(w, r, http.StatusNotFound, tr(r).T("error.not_found"))
}

func (s *Server) internal(w http.ResponseWriter, r *http.Request, err error) {
	s.Log.Error("request failed", "route", logPattern(r), "err", err)
	s.fail(w, r, http.StatusInternalServerError, tr(r).T("error.internal_retry"))
}

// ---- flash messages: one short message carried by a cookie across a redirect ----

const flashCookie = "siplink_flash"

// maxFlash is the longest flash message in bytes. A longer one (a list of long names)
// gives way to a short message of the same outcome, so that the cookie stays small.
const maxFlash = 500

func (s *Server) flash(w http.ResponseWriter, r *http.Request, kind, msg string) {
	if len(msg) > maxFlash {
		msg = tr(r).T("flash.failed")
		if kind == "ok" {
			msg = tr(r).T("flash.done")
		}
	}
	http.SetCookie(w, &http.Cookie{Name: flashCookie, Value: kind + "." + base64.RawURLEncoding.EncodeToString([]byte(msg)),
		Path: "/", HttpOnly: true, Secure: s.cookieSecure(r), SameSite: http.SameSiteLaxMode, MaxAge: 60})
}

func takeFlash(w http.ResponseWriter, r *http.Request) (string, string) {
	c, err := r.Cookie(flashCookie)
	if err != nil {
		return "", ""
	}
	http.SetCookie(w, &http.Cookie{Name: flashCookie, Value: "", Path: "/", MaxAge: -1})
	kind, enc, ok := strings.Cut(c.Value, ".")
	msg, err := base64.RawURLEncoding.DecodeString(enc)
	if !ok || err != nil || len(msg) > maxFlash {
		return "", ""
	}
	if kind != "ok" {
		kind = "error"
	}
	return string(msg), kind
}

// redirect sends the browser on with a flash message.
func (s *Server) redirect(w http.ResponseWriter, r *http.Request, to, kind, msg string) {
	if msg != "" {
		s.flash(w, r, kind, msg)
	}
	http.Redirect(w, r, to, http.StatusSeeOther)
}

// ---- static files ----

var startTime = time.Now()

// assetVersions maps a static file name ("app.css") to a short hash of its content,
// computed once at start. Pages reference the files as /static/<name>?v=<hash>, so a
// new build gets new URLs and no browser keeps running the old script.
var assetVersions = hashAssets(staticFiles)

func hashAssets(files fs.FS) map[string]string {
	out := map[string]string{}
	names, _ := fs.Glob(files, "static/*")
	for _, name := range names {
		if data, err := fs.ReadFile(files, name); err == nil {
			sum := sha256.Sum256(data)
			out[path.Base(name)] = hex.EncodeToString(sum[:6])
		}
	}
	return out
}

// assetURL is the versioned URL of a static file (template helper "asset").
func assetURL(name string) string {
	if v, ok := assetVersions[name]; ok {
		return "/static/" + name + "?v=" + v
	}
	return "/static/" + name
}

func (s *Server) static(w http.ResponseWriter, r *http.Request) {
	name := strings.TrimPrefix(r.URL.Path, "/")
	data, err := staticFiles.ReadFile(name)
	if err != nil || strings.Contains(name, "..") {
		http.NotFound(w, r)
		return
	}
	version := assetVersions[path.Base(name)]
	switch path.Ext(name) {
	case ".css":
		w.Header().Set("Content-Type", "text/css; charset=utf-8")
	case ".js":
		w.Header().Set("Content-Type", "text/javascript; charset=utf-8")
	case ".svg":
		w.Header().Set("Content-Type", "image/svg+xml")
	case ".woff2":
		w.Header().Set("Content-Type", "font/woff2")
	}
	// The versioned URL never changes its content; anything else (an old or missing
	// version) is revalidated on every use.
	if v := r.URL.Query().Get("v"); v != "" && v == version {
		w.Header().Set("Cache-Control", "public, max-age=31536000, immutable")
	} else {
		w.Header().Set("Cache-Control", "no-cache")
	}
	w.Header().Set("ETag", `"`+version+`"`)
	http.ServeContent(w, r, name, startTime, bytes.NewReader(data))
}

// ---- small helpers ----

func pathID(r *http.Request, name string) (int64, bool) {
	id, err := strconv.ParseInt(r.PathValue(name), 10, 64)
	return id, err == nil && id > 0
}

func formID(r *http.Request, name string) int64 {
	id, _ := strconv.ParseInt(strings.TrimSpace(r.FormValue(name)), 10, 64)
	return id
}

func formIDs(r *http.Request, name string) []int64 {
	_ = r.ParseForm() // r.Form is empty until something parsed the body
	var out []int64
	for _, v := range r.Form[name] {
		if id, err := strconv.ParseInt(v, 10, 64); err == nil && id > 0 {
			out = append(out, id)
		}
	}
	return out
}

func trimmed(r *http.Request, name string) string { return strings.TrimSpace(r.FormValue(name)) }

// pageQuery is a filter as the query string of paging links. Encode escapes every value,
// so the text is safe in a URL; as template.URL the template keeps its "=" and "&".
func pageQuery(v url.Values) template.URL { return template.URL(v.Encode()) }
