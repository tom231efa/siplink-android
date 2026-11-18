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
	"time"

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
	Data      any
}

// RegionFilter is the region choice in the top bar.
type RegionFilter struct {
	Options  []store.Region
	Selected int64 // 0 = all
}

// timeTag shows a time as <time datetime="RFC 3339">. The text is the server's
// zone and names it, so it is right without JavaScript; app.js rewrites it to the
// viewer's local time in the same form. dateOnly shows the day alone.
func timeTag(t time.Time, dateOnly bool) template.HTML {
	if t.IsZero() {
		return "—"
	}
	local := t.In(time.Local)
	layout, kind := "2006-01-02 15:04", "datetime"
	if dateOnly {
		layout, kind = "2006-01-02", "date"
	}
	zone := "UTC"
	if _, off := local.Zone(); off != 0 {
		zone += local.Format("-07:00")
	}
	return template.HTML(`<time datetime="` + t.UTC().Format(time.RFC3339) + `" data-local="` + kind + `">` +
		local.Format(layout) + " " + zone + `</time>`) // only digits, '-', ':', ' ' and "UTC": nothing to escape
}

// whenText shows a stored time (store.TimeFormat), "—" when there is none.
func whenText(s string) template.HTML { return timeTag(store.ParseTime(s), false) }

type pages struct {
	set map[string]*template.Template
}

var funcs = template.FuncMap{
	"when":         whenText,
	"since":        sinceText,
	"icon":         iconSVG,
	"tip":          fieldTip,
	"tabIcon":      func(id string) string { return configTabIcons[id] },
	"initial":      initial,
	"roleLabel":    roleLabel,
	"asset":        assetURL,
	"at":           func(t time.Time) template.HTML { return timeTag(t, false) },
	"day":          func(t time.Time) template.HTML { return timeTag(t, true) },
	"join":         strings.Join,
	"actionLabel":  actionLabel,
	"objectLabels": func() map[string]string { return objectLabels },
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
	"fieldLabel": fieldLabel,
	"regSummary": regSummary,
	"mb":         func(n int64) float64 { return float64(n) / (1 << 20) },
	"deref": func(p *int64) int64 {
		if p == nil {
			return 0
		}
		return *p
	},
}

func loadPages() (*pages, error) {
	names, err := fs.Glob(templateFiles, "templates/*.html")
	if err != nil {
		return nil, err
	}
	p := &pages{set: map[string]*template.Template{}}
	for _, name := range names {
		base := path.Base(name)
		if base == "layout.html" {
			continue
		}
		t, err := template.New("").Funcs(funcs).ParseFS(templateFiles, "templates/layout.html", name)
		if err != nil {
			return nil, fmt.Errorf("template %s: %w", base, err)
		}
		p.set[strings.TrimSuffix(base, ".html")] = t
	}
	return p, nil
}

// render executes a page inside the layout with the given status.
func (s *Server) render(w http.ResponseWriter, r *http.Request, status int, name string, pg *Page) {
	t := s.pages.set[name]
	if t == nil {
		s.Log.Error("unknown template", "name", name)
		http.Error(w, "内部错误", http.StatusInternalServerError)
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
		http.Error(w, "内部错误", http.StatusInternalServerError)
		return
	}
	h := w.Header()
	h.Set("Content-Type", "text/html; charset=utf-8")
	h.Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	w.Write(buf.Bytes())
}

func (s *Server) ok(w http.ResponseWriter, r *http.Request, name string, pg *Page) {
	s.render(w, r, http.StatusOK, name, pg)
}

// fail shows an error page.
func (s *Server) fail(w http.ResponseWriter, r *http.Request, status int, msg string) {
	s.render(w, r, status, "error", &Page{Title: statusTitle(status), Data: msg, Admin: current(r)})
}

// statusTitle names an error page in the UI's language.
func statusTitle(status int) string {
	switch status {
	case http.StatusNotFound:
		return "页面不存在"
	case http.StatusForbidden:
		return "无法访问"
	case http.StatusTooManyRequests:
		return "请求过多"
	case http.StatusInternalServerError:
		return "内部错误"
	}
	return http.StatusText(status)
}

func (s *Server) notFound(w http.ResponseWriter, r *http.Request) {
	s.fail(w, r, http.StatusNotFound, "页面不存在或无权访问。")
}

func (s *Server) internal(w http.ResponseWriter, r *http.Request, err error) {
	s.Log.Error("request failed", "route", logPattern(r), "err", err)
	s.fail(w, r, http.StatusInternalServerError, "内部错误，请稍后重试。")
}

// ---- flash messages: one short message carried by a cookie across a redirect ----

const flashCookie = "siplink_flash"

func (s *Server) flash(w http.ResponseWriter, r *http.Request, kind, msg string) {
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
	if !ok || err != nil || len(msg) > 500 {
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
