package web

import (
	"context"
	"fmt"
	"html"
	"net/http"
	"net/url"
	"strings"
	"testing"

	"siplink/internal/books"
	"siplink/internal/catalog"
	"siplink/internal/i18n"
	"siplink/internal/store"
)

// TestAuditObjectNames: only an entry of the type "system" has its object name read as a
// fixed name of the platform; a client that happens to be named "password_check" shows
// as it is (§5.5).
func TestAuditObjectNames(t *testing.T) {
	h := newHarness(t)
	east := h.region("East")
	f := h.fixture("Standard", nil)
	root, _ := h.loggedIn("root", store.RoleSuper)
	newClient(t, root, f, "password_check", east, "1001")
	if err := h.st.AddAudit(t.Context(), &store.AuditEntry{AdminName: "root", Action: "password.policy", ObjectType: "system",
		ObjectName: "password_check"}); err != nil {
		t.Fatal(err)
	}
	en := i18n.For(i18n.English)
	for _, path := range []string{"/audit", "/"} {
		req, _ := http.NewRequest("GET", h.ts.URL+path, nil)
		req.Header.Set("Accept-Language", "en")
		body := root.do(req).body
		setting := ">" + en.T("audit.name.password_check") + "<"
		if strings.Count(body, setting) != 1 || strings.Count(body, ">password_check<") != 1 {
			t.Errorf("%s: the setting %d times, the client's own name %d times", path,
				strings.Count(body, setting), strings.Count(body, ">password_check<"))
		}
	}
}

// TestNamesWithoutBidiControls: new names may not hold embedding, override or isolate
// characters; names stored before are left as they are, and messages drop the characters.
func TestNamesWithoutBidiControls(t *testing.T) {
	const evil = "East‮⁦"
	for _, err := range []error{catalog.NameProblem(evil), catalog.ClientNameProblem(evil), books.NameProblem(evil)} {
		if m, ok := i18n.AsMsg(err); !ok || m.Key != "names.bidi_control" {
			t.Errorf("name rule: %v", err)
		}
	}
	has := func(list []i18n.Msg) bool {
		for _, m := range list {
			if m.Key == "names.bidi_control" {
				return true
			}
		}
		return false
	}
	if !has(catalog.ProfileProblems(&store.Profile{Name: evil, Domain: "pbx.example.com", Transport: "TLS", Expires: 3600}, nil)) {
		t.Error("profile name")
	}
	if !has(catalog.ExternalProblems(&store.ExternalDirectory{Name: evil})) {
		t.Error("external directory name")
	}
	if !has(catalog.DirectoryProblems(&store.Directory{Name: evil, Kind: store.KindExternal}, nil, nil, catalog.Hosted{}, true)) {
		t.Error("directory name in a template")
	}

	h := newHarness(t)
	root, _ := h.loggedIn("root", store.RoleSuper)
	zh := i18n.For(i18n.Source)
	if r := root.post("/regions", url.Values{"name": {evil}}); r.code != http.StatusBadRequest || !strings.Contains(r.body, zh.T("names.bidi_control")) {
		t.Errorf("region: %d", r.code)
	}
	form := url.Values{"username": {"wang"}, "display_name": {"王‫敏"}, "role": {"super"}}
	if r := root.post("/admins", form); r.code != http.StatusBadRequest || !strings.Contains(r.body, zh.T("names.bidi_control")) {
		t.Errorf("display name: %d", r.code)
	}
	// A region stored with such a name before still works; the flash of its deletion
	// carries the name without the characters.
	reg := &store.Region{Name: evil}
	if err := h.st.CreateRegion(context.Background(), reg); err != nil {
		t.Fatal(err)
	}
	if r := root.get("/regions"); r.code != http.StatusOK {
		t.Fatalf("regions: %d", r.code)
	}
	r := root.post("/regions/"+itoa(reg.ID)+"/delete", nil)
	if r.code != http.StatusSeeOther {
		t.Fatalf("delete: %d", r.code)
	}
	if page := root.get(r.location).body; strings.ContainsAny(page, "‮⁦") || !strings.Contains(page, "East") {
		t.Error("flash with the stored name")
	}
}

// TestLongNameListsInFlash: a directory used by 20 templates names five of them and how
// many more, in Arabic too; a message still too long gives way to the outcome alone
// instead of being dropped (§5.5).
func TestLongNameListsInFlash(t *testing.T) {
	h := newHarness(t)
	ctx := context.Background()
	root, _ := h.loggedIn("root", store.RoleSuper)
	useBook := func(name string, b *store.Book) {
		t.Helper()
		cfg := &store.ConfigTemplate{Name: name, SettingsJSON: "{}", RefreshMinutes: 60}
		if err := h.st.CreateConfigTemplate(ctx, cfg); err != nil {
			t.Fatal(err)
		}
		d := &store.Directory{TemplateID: cfg.ID, Kind: store.KindHosted, BookID: &b.ID, Name: "Staff", Enabled: true,
			PasswordMode: "none", MaxResults: 50}
		if err := h.st.SaveDirectory(ctx, d, 50, nil); err != nil {
			t.Fatal(err)
		}
	}
	deleteIn := func(b *store.Book, lang string) string {
		t.Helper()
		req, _ := http.NewRequest("POST", h.ts.URL+"/books/"+itoa(b.ID)+"/delete", nil)
		req.Header.Set("Accept-Language", lang)
		r := root.do(req)
		if r.code != http.StatusSeeOther {
			t.Fatalf("delete: %d", r.code)
		}
		req, _ = http.NewRequest("GET", h.ts.URL+r.location, nil)
		req.Header.Set("Accept-Language", lang)
		return root.do(req).body
	}

	staff := h.book("staff", nil)
	for i := 1; i <= 20; i++ {
		useBook(fmt.Sprintf("Template %02d", i), staff)
	}
	ar := i18n.For(i18n.ByCode("ar"))
	users, _ := h.st.BookUsers(ctx, staff.ID)
	want := ar.T("dirs.in_use", "users", useNames(ar, users))
	if len(users) != 20 || len(want) > maxFlash || !strings.Contains(want, ar.T("format.list_more", "list", "", "n", 15)) {
		t.Fatalf("message: %d users, %d bytes: %q", len(users), len(want), want)
	}
	if body := deleteIn(staff, "ar"); !strings.Contains(body, html.EscapeString(want)) {
		t.Error("the in-use message is not shown in Arabic")
	}

	// Five names of 60 Chinese characters are too long for a flash: the outcome remains.
	long := h.book("long", nil)
	for i := 1; i <= 6; i++ {
		useBook(fmt.Sprintf("%02d", i)+strings.Repeat("模", 58), long)
	}
	if body := deleteIn(long, "ar"); !strings.Contains(body, ar.T("flash.failed")) {
		t.Error("a too long flash was dropped")
	}
}
