package web

import (
	"bytes"
	"context"
	"mime/multipart"
	"net/http"
	"net/url"
	"regexp"
	"strings"
	"testing"

	"siplink/internal/secret"
	"siplink/internal/store"
)

type fixture struct {
	config   *store.ConfigTemplate
	profiles []*store.Profile
	dir      *store.Directory // external, password per client
	hosted   *store.Directory
	tpl      *store.ClientTemplate
}

// fixture makes a configuration template with two profiles, an external directory with
// per-client passwords, a hosted book, and a client template (global when region is nil).
func (h *harness) fixture(name string, region *int64) *fixture {
	h.t.Helper()
	ctx := context.Background()
	f := &fixture{config: &store.ConfigTemplate{Name: name, SettingsJSON: "{}", RefreshMinutes: 60, RegionID: region}}
	must := func(err error) {
		h.t.Helper()
		if err != nil {
			h.t.Fatal(err)
		}
	}
	must(h.st.CreateConfigTemplate(ctx, f.config))
	for _, pn := range []string{"总部", "分部"} {
		p := &store.Profile{TemplateID: f.config.ID, Name: pn, Domain: "pbx.example.com", Transport: "TLS", Expires: 3600, Enabled: true}
		must(h.st.SaveProfile(ctx, p, 100))
		f.profiles = append(f.profiles, p)
	}
	ext := &store.ExternalDirectory{RegionID: region, Name: "公司-" + name, Host: "ldap.example.com", Port: 389, Security: "START_TLS",
		BindDN: "uid=x,dc=example,dc=com", PasswordMode: "client", BaseDN: "dc=example,dc=com", MaxResults: 50,
		NameAttributes: "cn", NumberAttributes: "telephoneNumber"}
	must(h.st.SaveExternalDirectory(ctx, ext, nil))
	f.dir = &store.Directory{TemplateID: f.config.ID, Kind: store.KindExternal, ExternalID: &ext.ID, Name: "公司", Enabled: true,
		PasswordMode: "none", MaxResults: 50}
	must(h.st.SaveDirectory(ctx, f.dir, 50, nil))
	book := h.book(strings.ToLower("b-"+secret.RandomText(6)), region)
	f.hosted = &store.Directory{TemplateID: f.config.ID, Kind: store.KindHosted, BookID: &book.ID, Name: "内部", Enabled: true,
		PasswordMode: "none", MaxResults: 50}
	must(h.st.SaveDirectory(ctx, f.hosted, 50, nil))
	f.tpl = &store.ClientTemplate{Name: name, ConfigTemplateID: f.config.ID, RegionID: region}
	must(h.st.CreateClientTemplate(ctx, f.tpl))
	return f
}

func (f *fixture) form(name string, region int64, user string) url.Values {
	p := itoa(f.profiles[0].ID)
	return url.Values{"name": {name}, "region": {itoa(region)}, "template": {itoa(f.tpl.ID)},
		"user_" + p: {user}, "pass_" + p: {"sip-secret-" + name}, "dir_" + itoa(f.dir.ID): {"dir-secret-" + name}}
}

var enrollLink = regexp.MustCompile(`https://platform\.example\.com/p/([A-Za-z0-9_-]{43})(\?pin=[A-Za-z0-9_-]{43})?`)

func TestClients(t *testing.T) {
	h := newHarness(t)
	east, west := h.region("华东"), h.region("华西")
	f := h.fixture("标准", nil)
	local, _ := h.loggedIn("east", store.RoleRegion, east)

	// Bad credentials are refused with the app's message; nothing is written.
	bad := f.form("前台", east, "10 01")
	if r := local.post("/clients", bad); r.code != http.StatusBadRequest || !strings.Contains(r.body, "账号「总部」") {
		t.Fatalf("bad user name: %d", r.code)
	}
	if r := local.post("/clients", f.form("前台", west, "1001")); r.code != http.StatusBadRequest {
		t.Errorf("client in another region: %d", r.code)
	}
	id := created(t, local.post("/clients", f.form("前台", east, "sip:1001@pbx.example.com")), "/clients/")
	c, _ := h.st.ClientByID(t.Context(), id)
	creds, _ := h.st.Credentials(t.Context(), id)
	k := creds[f.profiles[0].ID]
	if c.Status != store.StatusPending || len(c.PublicID) != 10 || k == nil || k.Username != "1001" || len(creds) != 1 ||
		strings.Contains(k.PasswordEnc, "sip-secret") {
		t.Fatalf("stored client: %+v %+v", c, k)
	}
	if r := local.post("/clients", f.form("前台", east, "1002")); r.code != http.StatusBadRequest {
		t.Errorf("duplicate name: %d", r.code)
	}

	// The detail page shows the QR code and the enrollment URL, never the password.
	r := local.get("/clients/" + itoa(id))
	// The built-in CA serves HTTPS: the URL carries its pin (§3.1).
	m := enrollLink.FindStringSubmatch(r.body)
	if r.code != 200 || m == nil || m[2] != "?pin="+h.srv.Ext.Certs.CA().Pin() || !qrMarkup.MatchString(r.body) || strings.Contains(r.body, "sip-secret") {
		t.Fatalf("detail: %d %v", r.code, m)
	}
	if secret.HashHex(m[1]) == "" {
		t.Fatal("no token")
	}
	if r := local.get("/clients/" + itoa(id) + "/qr.png"); r.code != 200 || !strings.HasPrefix(r.body, "\x89PNG") {
		t.Errorf("png: %d", r.code)
	}
	other, _ := h.loggedIn("west", store.RoleRegion, west)
	for _, path := range []string{"/clients/" + itoa(id), "/clients/" + itoa(id) + "/qr.png"} {
		if r := other.get(path); r.code != http.StatusNotFound {
			t.Errorf("other region %s: %d", path, r.code)
		}
	}
	if r := other.get("/clients"); strings.Contains(r.body, "前台") {
		t.Error("client listed for another region")
	}

	// Secrets need the administrator's password again; both outcomes are audited.
	if r := local.post("/clients/"+itoa(id)+"/reveal", url.Values{"password": {"wrong"}}); r.code != http.StatusBadRequest {
		t.Errorf("wrong password: %d", r.code)
	}
	r = local.post("/clients/"+itoa(id)+"/reveal", url.Values{"password": {"password-east"}})
	if r.code != http.StatusSeeOther {
		t.Fatalf("reveal: %d", r.code)
	}
	r = local.get(r.location)
	if !strings.Contains(r.body, "sip-secret-前台") || !strings.Contains(r.body, "dir-secret-前台") ||
		!strings.Contains(r.body, secret.LDAPPassword(h.srv.Ext.LDAPKey, c.PublicID, 1)) {
		t.Error("secrets not shown after reveal")
	}
	audit, _ := h.st.ListAudit(t.Context(), store.AuditFilter{AllRegions: true, Limit: 50})
	var actions []string
	for _, a := range audit {
		actions = append(actions, a.Action)
	}
	if !strings.Contains(strings.Join(actions, ","), "secret.view_denied") || !strings.Contains(strings.Join(actions, ","), "secret.view,") {
		t.Errorf("audit: %v", actions)
	}

	// Editing keeps the stored password when the field is empty.
	form := f.form("前台", east, "1001")
	form.Del("pass_" + itoa(f.profiles[0].ID))
	form.Set("fwdBusySet", "1")
	form.Set("fwdBusy", "8000")
	created(t, local.post("/clients/"+itoa(id), form), "/clients/")
	creds2, _ := h.st.Credentials(t.Context(), id)
	c, _ = h.st.ClientByID(t.Context(), id)
	if creds2[f.profiles[0].ID].PasswordEnc != k.PasswordEnc || c.ForwardBusy == nil || *c.ForwardBusy != "8000" || c.ForwardAlways != nil {
		t.Errorf("edit: %+v", c)
	}

	// Regenerating gives a new token; deleting a bound client revokes its key.
	local.post("/clients/"+itoa(id)+"/unbind", nil)
	if m3 := enrollLink.FindStringSubmatch(local.get("/clients/" + itoa(id)).body); m3 == nil || m3[1] == m[1] {
		t.Error("token not renewed")
	}
	c, _ = h.st.ClientByID(t.Context(), id)
	tok, _ := h.srv.Ext.Box.Open(c.EnrollTokenEnc, store.EnrollTokenAAD(c.ID))
	if _, err := h.st.Enroll(t.Context(), secret.HashHex(string(tok)), secret.HashHex("dev"), "i"); err != nil {
		t.Fatal(err)
	}
	local.post("/clients/"+itoa(id)+"/delete", nil)
	if reason, _ := h.st.Revoked(t.Context(), secret.HashHex("dev")); reason != store.RevokedDeleted {
		t.Errorf("revoked: %q", reason)
	}
	if _, err := h.st.ClientByID(t.Context(), id); err == nil {
		t.Error("client not deleted")
	}
}

func (c *client) uploadCSV(path string, data []byte) resp {
	var body bytes.Buffer
	mw := multipart.NewWriter(&body)
	fw, _ := mw.CreateFormFile("file", "clients.csv")
	fw.Write(data)
	mw.Close()
	return c.postBody(path, mw.FormDataContentType(), body.Bytes())
}

func TestClientImport(t *testing.T) {
	h := newHarness(t)
	east := h.region("华东")
	f := h.fixture("标准", nil)
	root, _ := h.loggedIn("root", store.RoleSuper)

	r := root.get("/clients/import/template.csv?template=" + itoa(f.tpl.ID))
	head := "\xef\xbb\xbf名称,区域,客户端模板,备注,总部用户名,总部密码,总部认证用户,总部显示名,分部用户名,分部密码,分部认证用户,分部显示名,公司LDAP密码"
	if r.code != 200 || strings.TrimSpace(r.body) != head {
		t.Fatalf("template: %q", r.body)
	}
	bad := strings.TrimPrefix(head, "\xef\xbb\xbf") + "\n甲,华东,标准,,2001,pw,,,,,,,x\n乙,无此区域,标准,,2002,pw,,,,,,,\n"
	r = root.uploadCSV("/clients/import", []byte(bad))
	if r.code != 200 || strings.Contains(r.body, `name="key"`) || !strings.Contains(r.body, "无此区域") {
		t.Fatalf("dry run with an error: %d", r.code)
	}
	good := strings.TrimPrefix(head, "\xef\xbb\xbf") + "\n甲,华东,标准,,2001,pw1,,,3001,pw2,,,x\n乙,华东,标准,备注,2002,pw,,,,,,,\n"
	r = root.uploadCSV("/clients/import", []byte(good))
	key := importKey.FindStringSubmatch(r.body)
	if key == nil {
		t.Fatalf("no key: %s", r.body)
	}
	if n, _, _ := h.st.ListClients(t.Context(), store.ClientFilter{All: true}); len(n) != 0 {
		t.Fatal("dry run wrote clients")
	}
	r = root.post("/clients/import/confirm", url.Values{"key": {key[1]}})
	if r.code != http.StatusSeeOther || !strings.HasPrefix(r.location, "/clients/print?ids=") {
		t.Fatalf("confirm: %d %s", r.code, r.location)
	}
	list, _, _ := h.st.ListClients(t.Context(), store.ClientFilter{All: true, Region: east})
	if len(list) != 2 {
		all, _, _ := h.st.ListClients(t.Context(), store.ClientFilter{All: true})
		t.Fatalf("imported %d of %d, east=%d: %+v", len(list), len(all), east, all)
	}
	creds, _ := h.st.Credentials(t.Context(), list[1].ID) // 甲 sorts after 乙 by code point
	if len(creds) != 2 {
		t.Errorf("credentials of 甲: %d", len(creds))
	}
	if r := root.post("/clients/import/confirm", url.Values{"key": {key[1]}}); !strings.Contains(r.location, "/clients/import") {
		t.Error("confirm key reused")
	}
	r = root.get(r.location)
	r = root.get("/clients/print?ids=" + itoa(list[0].ID) + "," + itoa(list[1].ID))
	if strings.Count(r.body, `class="print-card"`) != 2 || strings.Contains(r.body, "sidebar") {
		t.Errorf("print page: %d cards", strings.Count(r.body, `class="print-card"`))
	}
	// Bulk: change template, then delete.
	g := h.fixture("其他", &east)
	form := url.Values{"ids": {itoa(list[0].ID), itoa(list[1].ID)}, "action": {"template"}, "template": {itoa(g.tpl.ID)}}
	root.post("/clients/bulk", form)
	creds, _ = h.st.Credentials(t.Context(), list[0].ID)
	if c, _ := h.st.ClientByID(t.Context(), list[0].ID); c.ClientTemplateID != g.tpl.ID || len(creds) != 0 {
		t.Errorf("bulk template: %d creds", len(creds))
	}
	form.Set("action", "delete")
	root.post("/clients/bulk", form)
	if n, _, _ := h.st.ListClients(t.Context(), store.ClientFilter{All: true}); len(n) != 0 {
		t.Error("bulk delete")
	}
}
