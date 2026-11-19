package web

import (
	"io/fs"
	"net/http"
	"net/url"
	"regexp"
	"strings"
	"testing"

	"siplink/internal/store"
)

// TestThemeCookie: the theme switch keeps its choice in a cookie that the layout turns
// into data-theme on <html>, so the page renders in the chosen theme without a script.
func TestThemeCookie(t *testing.T) {
	h := newHarness(t)
	root, _ := h.loggedIn("root", store.RoleSuper)
	if r := root.get("/"); !strings.Contains(r.body, `<html lang="zh-Hans" dir="ltr">`) || !strings.Contains(r.body, `content="light dark"`) {
		t.Fatal("default page does not follow the system theme")
	}
	for _, theme := range []string{"dark", "light"} {
		r := root.post("/theme", url.Values{"theme": {theme}, "next": {"/clients"}})
		if r.code != http.StatusSeeOther || r.location != "/clients" {
			t.Fatalf("%s: %d %q", theme, r.code, r.location)
		}
		if c := cookieOf(r, themeCookie); c == nil || c.Value != theme || c.MaxAge <= 0 || c.HttpOnly {
			t.Fatalf("%s: cookie %+v", theme, c)
		}
		if body := root.get("/clients").body; !strings.Contains(body, `<html lang="zh-Hans" dir="ltr" data-theme="`+theme+`">`) ||
			!strings.Contains(body, `<meta name="color-scheme" content="`+theme+`">`) ||
			!strings.Contains(body, `value="`+theme+`" aria-pressed="true"`) {
			t.Errorf("%s: page does not carry the theme", theme)
		}
	}
	r := root.post("/theme", url.Values{"theme": {"system"}, "next": {"//evil.example"}})
	if c := cookieOf(r, themeCookie); r.location != "/" || c == nil || c.MaxAge >= 0 {
		t.Errorf("system: %q %+v", r.location, c)
	}
	if body := root.get("/").body; strings.Contains(body, "data-theme=") {
		t.Error("system theme still carries data-theme")
	}
	// A forged value never reaches the page.
	anon := h.client()
	u, _ := url.Parse(h.ts.URL)
	anon.c.Jar.SetCookies(u, []*http.Cookie{{Name: themeCookie, Value: `dark" onload="x`}})
	if body := anon.get("/login").body; strings.Contains(body, "data-theme") || strings.Contains(body, "onload") {
		t.Error("forged theme cookie reached the page")
	}
	if r := h.client().post("/theme", url.Values{"theme": {"dark"}}); r.code != http.StatusSeeOther || !strings.HasPrefix(r.location, "/login") {
		t.Errorf("theme without a session: %d %q", r.code, r.location)
	}
}

// qrMarkup is the enrollment QR code as the qr package draws it, inside its frame (the
// pages carry other SVG, the icons, so "<svg" alone proves nothing).
var qrMarkup = regexp.MustCompile(`<div class="qr"><svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 (\d+) (\d+)" shape-rendering="crispEdges" role="img" aria-label="二维码"><rect width="\d+" height="\d+" fill="#fff"/><path fill="#000" d="M[^"]{200,}"`)

func cookieOf(r resp, name string) *http.Cookie {
	for _, c := range (&http.Response{Header: r.header}).Cookies() {
		if c.Name == name {
			return c
		}
	}
	return nil
}

// TestNavigation: the sidebar marks the current page with aria-current, groups its
// items, hides the super administrators' pages from others, and the breadcrumb names
// the section.
func TestNavigation(t *testing.T) {
	h := newHarness(t)
	east := h.region("华东")
	root, _ := h.loggedIn("root", store.RoleSuper)
	body := root.get("/clients").body
	if !strings.Contains(body, `<a href="/clients" aria-current="page">`) || strings.Count(body, `aria-current="page">`+"<svg") != 1 {
		t.Error("clients is not the one current sidebar item")
	}
	for _, want := range []string{`<div class="nav-label">设备</div>`, `<div class="nav-label">通讯录</div>`, `<div class="nav-label">管理</div>`,
		`<span class="crumb-section">设备</span>`, `href="/admins"`, `href="/system"`, `<span class="user-role">超级管理员</span>`} {
		if !strings.Contains(body, want) {
			t.Errorf("clients page lacks %q", want)
		}
	}
	id := created(t, root.post("/config-templates", url.Values{"name": {"标准"}, "region": {"0"}}), "/config-templates/")
	r := root.get("/config-templates/" + itoa(id) + "?tab=media")
	if !strings.Contains(r.body, `<a href="/config-templates" aria-current="page">`) || !strings.Contains(r.body, `<a href="/config-templates">配置模板</a>`) ||
		!strings.Contains(r.body, `<span class="crumb-here fade" aria-current="page">标准</span>`) ||
		!strings.Contains(r.body, `?tab=media" aria-current="page">`) {
		t.Error("template page: sidebar, breadcrumb or section list")
	}
	local, _ := h.loggedIn("east", store.RoleRegion, east)
	body = local.get("/").body
	if strings.Contains(body, `href="/admins"`) || strings.Contains(body, `href="/system"`) || !strings.Contains(body, `<a href="/" aria-current="page">`) ||
		!strings.Contains(body, "区域管理员") {
		t.Error("region administrator's sidebar")
	}
}

// TestIcons: the icon helper draws known icons and refuses unknown names; every icon a
// template, the sidebar or the template tabs name exists.
func TestIcons(t *testing.T) {
	svg, err := iconSVG("users")
	if err != nil || !strings.HasPrefix(string(svg), `<svg class="icon" viewBox="0 0 24 24" aria-hidden="true" focusable="false"><`) ||
		!strings.HasSuffix(string(svg), "</svg>") {
		t.Fatalf("users: %q %v", svg, err)
	}
	if _, err := iconSVG("no-such-icon"); err == nil {
		t.Error("unknown icon accepted")
	}
	used := map[string]bool{}
	names, _ := fs.Glob(templateFiles, "templates/*.html")
	call := regexp.MustCompile(`\{\{icon "([a-z0-9-]+)"\}\}`)
	for _, name := range names {
		data, _ := fs.ReadFile(templateFiles, name)
		for _, m := range call.FindAllStringSubmatch(string(data), -1) {
			used[m[1]] = true
		}
	}
	for _, g := range navGroups {
		for _, it := range g.Items {
			used[it.Icon] = true
		}
	}
	for _, tab := range configTabs {
		if configTabIcons[tab.ID] == "" {
			t.Errorf("tab %s has no icon", tab.ID)
		}
		used[configTabIcons[tab.ID]] = true
	}
	for name := range used {
		if _, ok := icons[name]; !ok {
			t.Errorf("icon %q is used but not vendored", name)
		}
	}
	if len(used) < 20 {
		t.Errorf("only %d icons found in use: the scan is broken", len(used))
	}
}

// TestFont: the Inter font is served as a versioned static file, cached for good, and the
// stylesheet asks for the same URL that the layout preloads.
func TestFont(t *testing.T) {
	h := newHarness(t)
	anon := h.client()
	u := assetURL("InterVariable.woff2")
	if !strings.Contains(u, "?v=") {
		t.Fatalf("font has no version: %s", u)
	}
	if r := anon.get(u); r.code != 200 || r.header.Get("Content-Type") != "font/woff2" ||
		r.header.Get("Cache-Control") != "public, max-age=31536000, immutable" || len(r.body) < 100000 {
		t.Errorf("font: %d %q %q %d", r.code, r.header.Get("Content-Type"), r.header.Get("Cache-Control"), len(r.body))
	}
	if page := anon.get("/login").body; !strings.Contains(page, `<link rel="preload" href="`+u+`" as="font" type="font/woff2" crossorigin>`) {
		t.Error("layout does not preload the font")
	}
	css, _ := staticFiles.ReadFile("static/app.css")
	if !strings.Contains(string(css), `url("`+strings.TrimPrefix(u, "/static/")+`")`) || !strings.Contains(string(css), "font-display: swap") {
		t.Error("app.css does not ask for the versioned font URL")
	}
	// Every stylesheet and script the layout links is versioned and cached for good.
	page := anon.get("/login").body
	for _, name := range []string{"app.css", "components.css", "shell.css", "pages.css", "forms.css", "app.js"} {
		v := assetURL(name)
		if !strings.Contains(page, `"`+v+`"`) {
			t.Errorf("layout does not reference %s", v)
		}
		if r := anon.get(v); r.code != 200 || r.header.Get("Cache-Control") != "public, max-age=31536000, immutable" {
			t.Errorf("%s: %d %q", v, r.code, r.header.Get("Cache-Control"))
		}
	}
}

// TestPagesFramed: the pages the new stylesheet touches render, keep the CSP and carry
// no inline styles, scripts or handlers.
func TestPagesFramed(t *testing.T) {
	h := newHarness(t)
	east := h.region("华东")
	f := h.fixture("标准", &east)
	root, _ := h.loggedIn("root", store.RoleSuper)
	id, _ := newClient(t, root, f, "前台", east, "1001")
	cfg := "/config-templates/" + itoa(f.config.ID)
	inline := regexp.MustCompile(`\sstyle="|<style|<script>|\son[a-z]+="`)
	paths := []string{"/", "/clients", "/clients?q=nothing-matches", "/clients/" + itoa(id), "/clients/" + itoa(id) + "/edit",
		"/clients/" + itoa(id) + "/preview", "/clients/new", "/regions", "/admins", "/admins/new", "/books", "/books/new",
		"/ldap-accounts", "/client-templates", "/key-templates", "/config-templates", "/me", "/audit", "/system"}
	for _, tab := range configTabs {
		paths = append(paths, cfg+"?tab="+tab.ID)
	}
	for _, p := range paths {
		r := root.get(p)
		if r.code != 200 {
			t.Errorf("%s: %d", p, r.code)
			continue
		}
		if m := inline.FindString(r.body); m != "" {
			t.Errorf("%s: inline %q", p, m)
		}
		if !strings.Contains(r.body, `<main class="content">`) {
			t.Errorf("%s: no content frame", p)
		}
	}
	if r := root.get("/clients?q=nothing-matches"); !strings.Contains(r.body, "无匹配的客户端") {
		t.Error("filtered empty state")
	}
	// Settings that are switches stay checkboxes with the same names and values.
	if r := root.get(cfg + "?tab=calls"); !strings.Contains(r.body, `<input type="checkbox" role="switch" name="callWaiting" value="1"`) ||
		!strings.Contains(r.body, `<input type="radio" name="dndMode" value="SOFTWARE"`) {
		t.Error("calls tab: switch or segmented control")
	}
}

// TestUnknownPath: a signed-in administrator gets the framed page for an unknown path;
// anyone else gets the plain 404 and no hint of the admin UI.
func TestUnknownPath(t *testing.T) {
	h := newHarness(t)
	root, _ := h.loggedIn("root", store.RoleSuper)
	if r := root.get("/no/such/page"); r.code != http.StatusNotFound || !strings.Contains(r.body, `<main class="content">`) ||
		!strings.Contains(r.body, "页面不存在") {
		t.Errorf("signed in: %d", r.code)
	}
	if r := h.client().get("/no/such/page"); r.code != http.StatusNotFound || strings.Contains(r.body, "<html") {
		t.Errorf("anonymous: %d %q", r.code, r.body)
	}
	if r := root.get("/static/no-such-file.css"); r.code != http.StatusNotFound || strings.Contains(r.body, "<html") {
		t.Errorf("static: %d", r.code)
	}
}
