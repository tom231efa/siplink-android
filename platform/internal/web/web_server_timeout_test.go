package web

import (
	"html"
	"net/http"
	"net/url"
	"regexp"
	"strings"
	"testing"

	"siplink/internal/i18n"
	"siplink/internal/store"
)

// serverTimeoutValue reads the fast server timeout field of a line's form.
var serverTimeoutValue = regexp.MustCompile(`name="serverTimeoutMs" value="([^"]*)"`)

// TestServerTimeoutForm: a line's form shows the fast server timeout empty while it is off,
// refuses what is neither empty nor 200–10000 ms, keeps what it saved through another edit,
// and a phone's document carries serverTimeoutMs only while it is on.
func TestServerTimeoutForm(t *testing.T) {
	h := newHarness(t)
	east := h.region("华东")
	f := h.fixture("标准", nil)
	root, _ := h.loggedIn("root", store.RoleSuper)
	_, token := newClient(t, root, f, "前台", east, "1001")
	code, body, _ := h.deviceCall("/p/"+token, "", "")
	if code != http.StatusOK {
		t.Fatalf("enroll: %d", code)
	}
	first := decode(t, body)
	if strings.Contains(body, "serverTimeoutMs") {
		t.Error("a line without the timeout sends serverTimeoutMs")
	}
	p := f.profiles[0]
	path := "/config-templates/" + itoa(f.config.ID) + "/profiles/" + itoa(p.ID)
	page := root.get(path).body
	for _, want := range []string{"快速超时（毫秒）", `name="serverTimeoutMs" value="" min="200" max="10000" placeholder="关闭"`} {
		if !strings.Contains(page, want) {
			t.Errorf("form lacks %q", want)
		}
	}
	if strings.Index(page, `name="backupServer"`) > strings.Index(page, `name="serverTimeoutMs"`) {
		t.Error("the timeout is not after the backup server")
	}
	form := func(timeout string) url.Values {
		v := profileValues(p.Name, true)
		v.Set("backupServer", "sbc.example.com:5061")
		v.Set("serverTimeoutMs", timeout)
		return v
	}
	refusal := html.EscapeString(i18n.For(i18n.Source).T("provision.server_timeout_range", "min", 200, "max", 10000))
	for _, v := range []string{"199", "10001", "-1", "1", "abc", "1.5"} {
		if r := root.post(path, form(v)); r.code != http.StatusBadRequest || !strings.Contains(r.body, refusal) {
			t.Errorf("%q: %d", v, r.code)
		}
	}
	saved := func() int {
		got, err := h.st.ProfileByID(t.Context(), f.config.ID, p.ID)
		if err != nil {
			t.Fatal(err)
		}
		return got.ServerTimeoutMs
	}
	if saved() != 0 {
		t.Fatal("a refused form was saved")
	}
	for v, want := range map[string]int{"200": 200, "10000": 10000, " 1500 ": 1500, "": 0, "0": 0} {
		created(t, root.post(path, form(v)), "/config-templates/")
		if got := saved(); got != want {
			t.Errorf("%q saved as %d", v, got)
		}
	}

	// A stored value, here one the form would not offer as a round number, survives an edit
	// that sends the form back as the page showed it.
	created(t, root.post(path, form("4321")), "/config-templates/")
	m := serverTimeoutValue.FindStringSubmatch(root.get(path).body)
	if m == nil || m[1] != "4321" {
		t.Fatalf("form shows %v", m)
	}
	created(t, root.post(path, form(m[1])), "/config-templates/")
	if got := saved(); got != 4321 {
		t.Errorf("after a second edit: %d", got)
	}
	code, body, _ = h.deviceCall("/api/device/v1/sync", first.DeviceKey, first.Version)
	if doc := decode(t, body); code != http.StatusOK || doc.Accounts[0].ServerTimeoutMs != 4321 ||
		!strings.Contains(body, `"backupServer":"sbc.example.com:5061","serverTimeoutMs":4321,`) || strings.Count(body, "serverTimeoutMs") != 1 {
		t.Errorf("document with the timeout: %d %s", code, body)
	}

	// Without a backup server the timeout is kept and sent; the app uses it only with one.
	noBackup := form("800")
	noBackup.Del("backupServer")
	created(t, root.post(path, noBackup), "/config-templates/")
	if got := saved(); got != 800 {
		t.Errorf("without a backup server: %d", got)
	}
	// Off again (the field left empty), the key leaves the document.
	created(t, root.post(path, form("")), "/config-templates/")
	code, body, _ = h.deviceCall("/api/device/v1/sync", first.DeviceKey, first.Version)
	if code != http.StatusOK || strings.Contains(body, "serverTimeoutMs") {
		t.Errorf("document with the timeout off: %d %s", code, body)
	}
}

// TestServerTimeoutLanguages: every language of the console labels the field with its own
// words and the unit, and refuses a value with its own text.
func TestServerTimeoutLanguages(t *testing.T) {
	h := newHarness(t)
	f := h.fixture("标准", nil)
	root, _ := h.loggedIn("root", store.RoleSuper)
	path := "/config-templates/" + itoa(f.config.ID) + "/profiles/" + itoa(f.profiles[0].ID)
	seen := map[string]string{}
	for _, l := range i18n.Langs {
		tr := i18n.For(l)
		label, off := tr.T("config.profile.server_timeout_ms"), tr.T("config.profile.server_timeout_off")
		if label == "config.profile.server_timeout_ms" || off == "config.profile.server_timeout_off" {
			t.Fatalf("%s: catalog lacks the field", l.Code)
		}
		if other, ok := seen[label]; ok {
			t.Errorf("%s labels the field as %s does: %q", l.Code, other, label)
		}
		seen[label] = l.Code
		req, _ := http.NewRequest("GET", h.ts.URL+path, nil)
		req.Header.Set("Accept-Language", l.Code)
		page := root.do(req).body
		if !strings.Contains(page, html.EscapeString(label)) || !strings.Contains(page, `placeholder="`+html.EscapeString(off)+`"`) {
			t.Errorf("%s: form lacks %q or %q", l.Code, label, off)
		}
		v := profileValues(f.profiles[0].Name, true)
		v.Set("serverTimeoutMs", "10001")
		r := root.post(path, v, "Accept-Language", l.Code)
		refusal := tr.T("provision.server_timeout_range", "min", 200, "max", 10000)
		if r.code != http.StatusBadRequest || !strings.Contains(r.body, html.EscapeString(refusal)) {
			t.Errorf("%s: refusal %d %q", l.Code, r.code, refusal)
		}
	}
}
