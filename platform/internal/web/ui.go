package web

import (
	"context"
	"fmt"
	"html/template"
	"net/http"
	"strings"
	"time"
	"unicode"
	"unicode/utf8"

	"siplink/internal/store"
)

// ---- navigation: one list drives the sidebar, the breadcrumb and the quick-jump palette ----

type navItem struct {
	Key, Href, Label, Icon string
	Super                  bool // only super administrators see it
}

type navGroup struct {
	Label string // "" for the ungrouped first entry
	Items []navItem
}

var navGroups = []navGroup{
	{"", []navItem{{Key: "dashboard", Href: "/", Label: "概览", Icon: "layout-dashboard"}}},
	{"设备", []navItem{
		{Key: "clients", Href: "/clients", Label: "客户端", Icon: "smartphone"},
		{Key: "clienttpl", Href: "/client-templates", Label: "客户端模板", Icon: "layers"},
		{Key: "config", Href: "/config-templates", Label: "配置模板", Icon: "sliders-horizontal"},
		{Key: "keys", Href: "/key-templates", Label: "功能键模板", Icon: "keyboard"},
	}},
	{"通讯录", []navItem{
		{Key: "books", Href: "/books", Label: "通讯录", Icon: "book-user"},
		{Key: "ldap", Href: "/ldap-accounts", Label: "LDAP 账号", Icon: "key-round"},
	}},
	{"管理", []navItem{
		{Key: "regions", Href: "/regions", Label: "区域", Icon: "map-pin"},
		{Key: "admins", Href: "/admins", Label: "管理员", Icon: "shield-user", Super: true},
		{Key: "audit", Href: "/audit", Label: "审计日志", Icon: "scroll-text"},
		{Key: "system", Href: "/system", Label: "系统", Icon: "server", Super: true},
		{Key: "mail", Href: "/mail", Label: "邮件", Icon: "mail", Super: true},
	}},
}

// NavGroups is the sidebar of this page's administrator.
func (p *Page) NavGroups() []navGroup {
	var out []navGroup
	for _, g := range navGroups {
		ng := navGroup{Label: g.Label}
		for _, it := range g.Items {
			if !it.Super || p.Super {
				ng.Items = append(ng.Items, it)
			}
		}
		if len(ng.Items) > 0 {
			out = append(out, ng)
		}
	}
	return out
}

// Crumb is the breadcrumb: the section, the list the page belongs to (a link unless
// it is the page itself) and the page's own title when it is a single object.
type Crumb struct {
	Section string
	List    *navItem
	Here    string // "" when the list is the page itself
}

func (p *Page) Crumb() Crumb {
	for _, g := range navGroups {
		for i := range g.Items {
			if it := &g.Items[i]; it.Key == p.Nav {
				c := Crumb{Section: g.Label, List: it}
				if p.Title != it.Label {
					c.Here = p.Title
				}
				return c
			}
		}
	}
	return Crumb{Here: p.Title}
}

// ---- icons ----

// iconSVG is the template helper {{icon "name"}}: a Lucide icon as inline SVG that
// takes the text colour. An unknown name fails the page, so a typo shows in the tests.
func iconSVG(name string) (template.HTML, error) {
	inner, ok := icons[name]
	if !ok {
		return "", fmt.Errorf("unknown icon %q", name)
	}
	return template.HTML(`<svg class="icon" viewBox="0 0 24 24" aria-hidden="true" focusable="false">` + inner + `</svg>`), nil
}

// configTabIcons gives each tab of a configuration template its icon.
var configTabIcons = map[string]string{
	"accounts": "users", "calls": "settings-2", "features": "keyboard", "general": "activity",
	"media": "monitor", "security": "shield-check", "directories": "book-user", "advanced": "refresh-cw",
}

// ---- small text helpers for the shell ----

// initial is the first letter of a name, for the avatar.
func initial(name string) string {
	r, _ := utf8.DecodeRuneInString(strings.TrimSpace(name))
	if r == utf8.RuneError {
		return "?"
	}
	return string(unicode.ToUpper(r))
}

func roleLabel(role string) string {
	if role == store.RoleSuper {
		return "超级管理员"
	}
	return "区域管理员"
}

// sinceText is a stored time as a <time> tag that app.js shows as "3 分钟前", with the
// full local time in its title. Without JavaScript it reads like when.
func sinceText(s string) template.HTML {
	t := store.ParseTime(s)
	tag := string(timeTag(t, false))
	if t.IsZero() {
		return template.HTML(tag)
	}
	return template.HTML(strings.Replace(tag, `data-local="datetime">`, `data-local="datetime" data-rel>`, 1))
}

// unknownPath answers a GET that no route matches: the framed "not found" page for a
// signed-in administrator who may use the UI, Go's plain 404 for everyone else (no
// session is created), including one held on 我的账号 (password change, set-up).
func (s *Server) unknownPath(w http.ResponseWriter, r *http.Request) {
	a := s.fullSession(r)
	if a != nil && a.MustChangePassword {
		a = nil
	}
	if a != nil {
		if must, err := s.mustSetUp(r.Context(), a); err != nil || must {
			a = nil
		}
	}
	if a == nil { // like a session that waits for the second step: nothing of the UI
		http.NotFound(w, r)
		return
	}
	s.notFound(w, r.WithContext(context.WithValue(r.Context(), adminKey{}, a)))
}

// ---- theme: system, light or dark, kept in a cookie the layout reads ----

const themeCookie = "siplink_theme"

// themeOf is the stored theme choice: "light", "dark" or "" (follow the system).
func themeOf(r *http.Request) string {
	if c, err := r.Cookie(themeCookie); err == nil && (c.Value == "light" || c.Value == "dark") {
		return c.Value
	}
	return ""
}

func (s *Server) setTheme(w http.ResponseWriter, r *http.Request) {
	v := r.FormValue("theme")
	c := &http.Cookie{Name: themeCookie, Value: v, Path: "/", Secure: s.cookieSecure(r), SameSite: http.SameSiteLaxMode,
		MaxAge: int((365 * 24 * time.Hour).Seconds())} // not HttpOnly: app.js switches it without a reload
	if v != "light" && v != "dark" {
		c.Value, c.MaxAge = "", -1
	}
	http.SetCookie(w, c)
	http.Redirect(w, r, safeNext(r.FormValue("next")), http.StatusSeeOther)
}
