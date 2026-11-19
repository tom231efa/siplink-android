package web

import (
	"context"
	"encoding/base32"
	"net/http"
	"net/url"
	"regexp"
	"strings"
	"testing"
	"time"

	"siplink/internal/i18n"
	"siplink/internal/store"
)

// langWorld is a console with one object of every kind, all named in ASCII so that a
// page in a language without Chinese characters shows none (§5.5).
type langWorld struct {
	root, east, step, setup *client
	ids                     map[string]string // the ids and tokens the page paths name in braces
}

func newLangWorld(t *testing.T) *langWorld {
	h := newHarness(t)
	ctx := context.Background()
	must := func(err error) {
		t.Helper()
		if err != nil {
			t.Fatal(err)
		}
	}
	east := h.region("East")
	// The provisioning document names the platform (§3.5.2): an ASCII instance name keeps
	// the preview page free of the default Chinese name.
	must(h.st.SetMeta(ctx, "instance_name", "SipLink"))
	root, _ := h.loggedIn("root", store.RoleSuper)
	localAdmin := h.admin("east", store.RoleRegion, east)

	cfg := &store.ConfigTemplate{Name: "Standard", SettingsJSON: "{}", RefreshMinutes: 60}
	must(h.st.CreateConfigTemplate(ctx, cfg))
	prof := &store.Profile{TemplateID: cfg.ID, Name: "Main", Domain: "pbx.example.com", Transport: "TLS", Expires: 3600, Enabled: true}
	must(h.st.SaveProfile(ctx, prof, 100))
	ext := &store.ExternalDirectory{Name: "Company", Host: "ldap.example.com", Port: 389, Security: "START_TLS",
		BindDN: "uid=x,dc=example,dc=com", PasswordMode: "client", BaseDN: "dc=example,dc=com", MaxResults: 50,
		NameAttributes: "cn", NumberAttributes: "telephoneNumber"}
	must(h.st.SaveExternalDirectory(ctx, ext, nil))
	extUse := &store.Directory{TemplateID: cfg.ID, Kind: store.KindExternal, ExternalID: &ext.ID, Name: "Company", Enabled: true,
		PasswordMode: "none", MaxResults: 50}
	must(h.st.SaveDirectory(ctx, extUse, 50, nil))
	book := h.book("staff", nil)
	must(h.st.AddEntries(ctx, book.ID, []*store.Entry{{Name: "Alice Example", Phones: []string{"1001"}, Email: "alice@example.com"}}, false, 10000))
	hostedUse := &store.Directory{TemplateID: cfg.ID, Kind: store.KindHosted, BookID: &book.ID, Name: "Staff", Enabled: true,
		PasswordMode: "none", MaxResults: 50}
	must(h.st.SaveDirectory(ctx, hostedUse, 50, nil))
	keys := &store.KeyTemplate{Name: "Keys"}
	must(h.st.CreateKeyTemplate(ctx, keys))
	must(h.st.SaveKey(ctx, keys.ID, &store.TemplateKey{Label: "Reception", Number: "1001", Action: "speed"}, 2000))
	tpl := &store.ClientTemplate{Name: "Standard", ConfigTemplateID: cfg.ID, KeyTemplateID: &keys.ID}
	must(h.st.CreateClientTemplate(ctx, tpl))
	acct := &store.LDAPAccount{Name: "phones", PasswordHash: strings.Repeat("0", 64), BookIDs: []int64{book.ID}}
	must(h.st.CreateLDAPAccount(ctx, acct))

	fx := &fixture{profiles: []*store.Profile{prof}, dir: extUse, tpl: tpl}
	pending, pendingToken := newClient(t, root, fx, "Front desk", east, "1001")
	bound, token := newClient(t, root, fx, "Lobby", east, "1002")
	if code, _, _ := h.deviceCall("/p/"+token, "", ""); code != http.StatusOK {
		t.Fatalf("enroll: %d", code)
	}

	// An administrator in the middle of a TOTP set-up, and one waiting for the second step.
	h.admin("setup", store.RoleSuper)
	stepper := h.client()
	stepper.login("setup")
	if r := stepper.post("/me/totp/new", nil); r.code != http.StatusSeeOther {
		t.Fatalf("totp new: %d", r.code)
	}
	key, _ := base32.StdEncoding.WithPadding(base32.NoPadding).DecodeString(totpSecretRe.FindStringSubmatch(stepper.get("/me/totp").body)[1])
	confirm := stepper.post("/me/totp/confirm", url.Values{"code": {h.totpCode(key)}})
	if !strings.HasPrefix(confirm.location, "/me/recovery?t=") {
		t.Fatalf("totp confirm: %d %s", confirm.code, confirm.location)
	}
	setupAgain := h.client()
	setupAgain.login("east") // a second session of the region administrator for /me/totp
	if r := setupAgain.post("/me/totp/new", nil); r.code != http.StatusSeeOther {
		t.Fatalf("totp new (east): %d", r.code)
	}
	pendingStep := h.client()
	signInPending(t, pendingStep, "setup")

	group := &store.Group{Name: "Front", RegionID: east}
	must(h.st.SaveGroup(ctx, group, 100))
	tag := &store.Tag{Name: "VIP", Color: "sand"}
	must(h.st.CreateTag(ctx, tag, 500))
	must(h.st.SetClientsGroup(ctx, []int64{bound}, &group.ID))
	must(h.st.AddClientsTag(ctx, []int64{bound}, tag.ID, 10))

	entries, _, err := h.st.AllEntries(ctx, book.ID)
	must(err)
	return &langWorld{root: root, east: setupAgain, step: pendingStep, setup: stepper, ids: map[string]string{
		"cfg": itoa(cfg.ID), "prof": itoa(prof.ID), "ext": itoa(ext.ID), "extUse": itoa(extUse.ID), "hostedUse": itoa(hostedUse.ID),
		"book": itoa(book.ID), "entry": itoa(entries[0].ID), "keys": itoa(keys.ID), "tpl": itoa(tpl.ID), "acct": itoa(acct.ID),
		"pending": itoa(pending), "bound": itoa(bound), "token": pendingToken, "east": itoa(localAdmin.ID),
		"recovery": confirm.location, "group": itoa(group.ID), "tag": itoa(tag.ID),
	}}
}

// rootPages are the pages a super administrator can open, with the ids of langWorld.
var rootPages = []string{
	"/", "/clients", "/clients?status=pending", "/clients/new", "/clients/{pending}", "/clients/{pending}/edit", "/clients/{bound}",
	"/clients/import", "/clients/print?ids={pending}", "/clients/{bound}/preview", "/clients?group={group}&tag={tag}", "/clients?group=none",
	"/groups", "/groups?new=1", "/groups?edit={group}", "/tags", "/tags?new=1", "/tags?edit={tag}",
	"/client-templates", "/client-templates/new", "/client-templates/{tpl}",
	"/config-templates", "/config-templates/new", "/config-templates/{cfg}",
	"/config-templates/{cfg}?tab=accounts", "/config-templates/{cfg}?tab=calls", "/config-templates/{cfg}?tab=features",
	"/config-templates/{cfg}?tab=general", "/config-templates/{cfg}?tab=media", "/config-templates/{cfg}?tab=security",
	"/config-templates/{cfg}?tab=directories", "/config-templates/{cfg}?tab=advanced",
	"/config-templates/{cfg}/profiles/new", "/config-templates/{cfg}/profiles/{prof}",
	"/config-templates/{cfg}/directories/new", "/config-templates/{cfg}/directories/{extUse}", "/config-templates/{cfg}/directories/{hostedUse}",
	"/key-templates", "/key-templates/new", "/key-templates/{keys}",
	"/books", "/books/new?kind=hosted", "/books/new?kind=external", "/books/{book}", "/books/{book}/edit",
	"/books/{book}/entries/new", "/books/{book}/entries/{entry}", "/books/{book}/import", "/directories/{ext}",
	"/ldap-accounts", "/ldap-accounts/new", "/ldap-accounts/{acct}",
	"/regions", "/admins", "/admins/new", "/admins/{east}", "/audit", "/system", "/system?cert=own", "/system?cert=builtin",
	"/mail", "/me",
}

// eastPages are pages of the region administrator (whose session also has a TOTP
// set-up waiting).
var eastPages = []string{
	"/", "/clients", "/clients/{pending}", "/clients/{bound}/edit", "/groups", "/tags", "/client-templates/{tpl}", "/config-templates/{cfg}", "/key-templates/{keys}",
	"/books", "/books/{book}", "/directories/{ext}", "/ldap-accounts", "/regions", "/admins", "/audit", "/me", "/me/totp",
}

var (
	leftArgument = regexp.MustCompile(`\{[a-z][a-z0-9_]*\}`)
	jsTextBlock  = regexp.MustCompile(`(?s)<script type="application/json" id="i18n">.*?</script>`)
	chineseChars = regexp.MustCompile(`[\p{Han}\p{Hiragana}\p{Katakana}\x{3000}-\x{303f}\x{ff00}-\x{ffef}]+`)
)

// TestEveryPageInEveryLanguage renders every page in every language (§8.2): no
// template error, no ⟦…⟧ marker of a missing key or argument, no argument left in
// braces, the page's lang and dir; and in the languages that use no Chinese
// characters, none (the names of the languages in the picker aside).
func TestEveryPageInEveryLanguage(t *testing.T) {
	w := newLangWorld(t)
	fill := func(p string) string {
		for k, v := range w.ids {
			p = strings.ReplaceAll(p, "{"+k+"}", v)
		}
		return p
	}
	type visit struct {
		c     *client
		path  string
		code  int
		label string
	}
	var visits []visit
	for _, p := range rootPages {
		visits = append(visits, visit{w.root, fill(p), http.StatusOK, "root " + p})
	}
	for _, p := range eastPages {
		visits = append(visits, visit{w.east, fill(p), http.StatusOK, "east " + p})
	}
	visits = append(visits,
		visit{w.root, "/no-such-page", http.StatusNotFound, "404"},
		visit{w.setup, fill("{recovery}"), http.StatusOK, "recovery codes"},
		visit{w.step, "/login/step", http.StatusOK, "second step"},
		visit{w.root.h.client(), "/login", http.StatusOK, "login"},
		visit{w.root.h.client(), fill("/p/{token}"), http.StatusOK, "enrollment info"},
	)
	for _, l := range i18n.Langs {
		for _, v := range visits {
			req, _ := http.NewRequest("GET", v.c.h.ts.URL+v.path, nil)
			req.Header.Set("Accept-Language", l.Code)
			r := v.c.do(req)
			where := l.Code + " " + v.label
			if r.code != v.code {
				t.Errorf("%s: status %d", where, r.code)
				continue
			}
			if !strings.Contains(r.body, `<html lang="`+l.Code+`" dir="`+l.Dir()+`"`) {
				t.Errorf("%s: lang/dir missing", where)
			}
			body := jsTextBlock.ReplaceAllString(r.body, "")
			if i := strings.Index(body, "⟦"); i >= 0 {
				t.Errorf("%s: untranslated %s", where, body[i:min(len(body), i+60)])
			}
			if m := leftArgument.FindString(body); m != "" {
				t.Errorf("%s: argument left: %s", where, m)
			}
			if l.Code == "zh-Hans" || l.Code == "zh-Hant" || l.Code == "ja" {
				continue
			}
			for _, other := range i18n.Langs {
				body = strings.ReplaceAll(body, other.Name, "")
			}
			if m := chineseChars.FindAllString(body, 5); m != nil {
				t.Errorf("%s: Chinese text %q", where, m)
			}
		}
	}
}

// TestLanguageChoice: before sign-in the cookie beats Accept-Language; a choice made on the
// login page becomes the administrator's at sign-in and follows them to another browser; a
// choice in the user menu is stored; a code mail is written in the recipient's language (§5.5).
func TestLanguageChoice(t *testing.T) {
	h := newHarness(t)
	srv := smtpFor(t, h, false)
	h.admin("root", store.RoleSuper)
	page := func(c *client, path, accept string) resp {
		t.Helper()
		req, _ := http.NewRequest("GET", h.ts.URL+path, nil)
		req.Header.Set("Accept-Language", accept)
		return c.do(req)
	}
	langOf := func(r resp) string {
		m := regexp.MustCompile(`<html lang="([^"]+)" dir="([a-z]+)"`).FindStringSubmatch(r.body)
		if m == nil {
			return ""
		}
		return m[1] + " " + m[2]
	}
	cookieOf := func(r resp) string {
		for _, c := range (&http.Response{Header: r.header}).Cookies() {
			if c.Name == langCookie {
				return c.Value
			}
		}
		return ""
	}

	first := h.client()
	if got := langOf(page(first, "/login", "fr-CA,fr;q=0.9")); got != "fr ltr" {
		t.Errorf("Accept-Language fr-CA: %q", got)
	}
	if got := langOf(page(first, "/login", "de-DE")); got != "en ltr" {
		t.Errorf("no match: %q", got)
	}
	if r := first.post("/language", url.Values{"lang": {"xx"}, "next": {"/login"}}); r.code != http.StatusSeeOther || cookieOf(r) != "" {
		t.Errorf("unknown language: %d cookie %q", r.code, cookieOf(r))
	}
	if r := first.post("/language", url.Values{"lang": {"ar"}, "next": {"/login"}}); r.code != http.StatusSeeOther || r.location != "/login" || cookieOf(r) != "ar" {
		t.Fatalf("choice on the login page: %d %s cookie %q", r.code, r.location, cookieOf(r))
	}
	if got := langOf(page(first, "/login", "fr")); got != "ar rtl" {
		t.Errorf("cookie over Accept-Language: %q", got)
	}
	first.login("root")
	if a, _ := h.st.AdminByUsername(t.Context(), "root"); a == nil || a.Language != "ar" {
		t.Errorf("cookie not adopted at sign-in: %+v", a)
	}

	second := h.client() // another browser, no cookie
	r := second.post("/login", url.Values{"username": {"root"}, "password": {"password-root"}}, "Accept-Language", "es")
	if cookieOf(r) != "ar" {
		t.Errorf("sign-in does not refresh the cookie: %q", cookieOf(r))
	}
	if got := langOf(page(second, "/clients", "es")); got != "ar rtl" {
		t.Errorf("stored language in another browser: %q", got)
	}
	if r := second.post("/language", url.Values{"lang": {"ru"}, "next": {"/clients"}}); r.location != "/clients" {
		t.Fatalf("choice in the menu: %d %s", r.code, r.location)
	}
	if got := langOf(page(first, "/", "zh-CN")); got != "ru ltr" {
		t.Errorf("stored choice not used by the first browser: %q", got)
	}

	// The code mail of the second step goes out in the recipient's language, not in the one
	// of the browser that asks for it.
	first.post("/mail", mailForm(srv, "starttls"))
	first.post("/me/email", url.Values{"email": {"root@example.com"}, "password": {"password-root"}})
	first.post("/me/email/confirm", url.Values{"code": {lastCode(t, srv, "root@example.com")}})
	if r := first.post("/me/email-code/on", nil); !strings.HasPrefix(r.location, "/me/recovery?t=") {
		t.Fatalf("e-mail method on: %s", r.location)
	}
	h.clock.Advance(2 * time.Minute)
	third := h.client()
	signInPending(t, third, "root")
	third.post("/login/step/email", nil, "Accept-Language", "es")
	mails := srv.Mails()
	ru := i18n.For(i18n.ByCode("ru"))
	if m := mails[len(mails)-1]; m.Subject != ru.T("mailtext.code_subject") || !strings.Contains(m.Body, ru.T("mailtext.code_valid")) {
		t.Errorf("code mail not in Russian: %q / %q", m.Subject, m.Body)
	}
}
