package web

import (
	"encoding/json"
	"io"
	"net/http"
	"net/url"
	"strings"
	"testing"

	"siplink/internal/store"
)

// externalValues is a valid external directory form.
func externalValues(name, region string) url.Values {
	return url.Values{"name": {name}, "region": {region}, "host": {"ldap.example.com"}, "port": {"389"},
		"security": {"START_TLS"}, "bindDn": {"cn=reader,dc=example,dc=com"}, "baseDn": {"dc=example,dc=com"},
		"passwordMode": {"none"}, "maxResults": {"50"}, "nameAttributes": {"cn sn"}, "numberAttributes": {"telephoneNumber mobile"}}
}

func with(v url.Values, kv ...string) url.Values {
	out := url.Values{}
	for k, x := range v {
		out[k] = append([]string(nil), x...)
	}
	for i := 0; i+1 < len(kv); i += 2 {
		out.Set(kv[i], kv[i+1])
	}
	return out
}

// TestDirectoryList: one list of internal and external directories with search, type
// filter and paging, limited to what the administrator may see.
func TestDirectoryList(t *testing.T) {
	h := newHarness(t)
	east, west := h.region("华东"), h.region("华西")
	root, _ := h.loggedIn("root", store.RoleSuper)
	local, _ := h.loggedIn("east", store.RoleRegion, east)
	h.book("staff", &east)
	h.book("west-book", &west)
	created(t, root.post("/directories", externalValues("总部目录", "0")), "/directories/")
	created(t, root.post("/directories", with(externalValues("华西目录", itoa(west)), "host", "ldap.west.example.com")), "/directories/")

	r := root.get("/books")
	for _, want := range []string{"staff", "west-book", "总部目录", "华西目录", "ldap.west.example.com:389", "内部", "外部"} {
		if !strings.Contains(r.body, want) {
			t.Errorf("list misses %q", want)
		}
	}
	if r := root.get("/books?kind=external"); strings.Contains(r.body, "staff") || !strings.Contains(r.body, "总部目录") {
		t.Error("type filter external")
	}
	if r := root.get("/books?kind=hosted"); strings.Contains(r.body, "总部目录") || !strings.Contains(r.body, "staff") {
		t.Error("type filter hosted")
	}
	if r := root.get("/books?q=west.example"); !strings.Contains(r.body, "华西目录") || strings.Contains(r.body, "总部目录") {
		t.Error("search by server")
	}
	if r := root.get("/books?q=sta"); !strings.Contains(r.body, ">staff<") || strings.Contains(r.body, "华西目录") {
		t.Error("search by name")
	}
	if r := root.get("/books?q=nothing-like-this"); !strings.Contains(r.body, "无匹配的通讯录") {
		t.Error("empty search result")
	}
	r = local.get("/books")
	if !strings.Contains(r.body, "staff") || !strings.Contains(r.body, "总部目录") || strings.Contains(r.body, "west-book") ||
		strings.Contains(r.body, "华西目录") {
		t.Error("region administrator sees other regions or misses global ones")
	}

	// Paging: 50 per page, the filter kept in the links.
	for i := range 51 {
		h.book("p-"+itoa(int64(100+i)), &east)
	}
	r = root.get("/books?kind=hosted")
	if !strings.Contains(r.body, "第 1 / 2 页") || !strings.Contains(r.body, "/books?kind=hosted&amp;q=&page=2") {
		t.Errorf("paging: %s", between(r.body, "card-foot", "</div>"))
	}
	if r := root.get("/books?kind=hosted&page=2"); !strings.Contains(r.body, "第 2 / 2 页") {
		t.Error("second page")
	}
}

func between(s, from, to string) string {
	if i := strings.Index(s, from); i >= 0 {
		s = s[i:]
		if j := strings.Index(s, to); j >= 0 {
			return s[:j]
		}
	}
	return ""
}

// TestDirectoryCreateAndFixedType: "新建" asks for the type; each type is created; the
// type cannot change afterwards whatever the form sends.
func TestDirectoryCreateAndFixedType(t *testing.T) {
	h := newHarness(t)
	root, _ := h.loggedIn("root", store.RoleSuper)
	if r := root.get("/books/new"); !strings.Contains(r.body, `name="kind" value="hosted" data-autosubmit checked`) ||
		!strings.Contains(r.body, `name="slug"`) || strings.Contains(r.body, `name="host"`) {
		t.Error("new page: internal by default")
	}
	if r := root.get("/books/new?kind=external"); !strings.Contains(r.body, `value="external" data-autosubmit checked`) ||
		!strings.Contains(r.body, `name="host"`) || !strings.Contains(r.body, `action="/directories"`) {
		t.Error("new page: external")
	}
	bookID := created(t, root.post("/books", url.Values{"name": {"员工"}, "slug": {"staff"}, "region": {"0"}}), "/books/")
	extID := created(t, root.post("/directories", externalValues("公司", "0")), "/directories/")

	if r := root.post("/directories/"+itoa(extID), with(externalValues("公司", "0"), "kind", "hosted")); r.code != http.StatusBadRequest {
		t.Errorf("external turned internal: %d", r.code)
	}
	if r := root.post("/books/"+itoa(bookID), url.Values{"name": {"员工"}, "kind": {"external"}}); r.code != http.StatusBadRequest {
		t.Errorf("internal turned external: %d", r.code)
	}
	if _, err := h.st.ExternalDirectoryByID(t.Context(), extID); err != nil {
		t.Error("external directory gone")
	}
	if _, err := h.st.BookByID(t.Context(), bookID); err != nil {
		t.Error("book gone")
	}
	if r := root.get("/directories/" + itoa(extID)); !strings.Contains(r.body, `<input value="外部" disabled>`) {
		t.Error("external page: type not shown as fixed")
	}
	if r := root.get("/books/" + itoa(bookID) + "/edit"); !strings.Contains(r.body, `<input value="内部" disabled>`) {
		t.Error("book settings: type not shown as fixed")
	}
}

// TestExternalDirectoryPage: the connection is checked with the app's rules; the stored
// password is write-only and never reaches the page, the audit log or the log.
func TestExternalDirectoryPage(t *testing.T) {
	h := newHarness(t)
	root, _ := h.loggedIn("root", store.RoleSuper)
	for name, v := range map[string]url.Values{
		"ldaps":          with(externalValues("x", "0"), "host", "ldaps://ldap.example.com"),
		"starttls ip":    with(externalValues("x", "0"), "host", "192.0.2.1"),
		"filter":         with(externalValues("x", "0"), "nameFilter", "(cn=*)"),
		"no base":        with(externalValues("x", "0"), "baseDn", ""),
		"no name":        with(externalValues("", "0")),
		"no password":    with(externalValues("x", "0"), "passwordMode", "template"),
		"pass, no bind":  with(externalValues("x", "0"), "passwordMode", "template", "password", "p", "bindDn", ""),
		"max results":    with(externalValues("x", "0"), "maxResults", "1001"),
		"unknown region": with(externalValues("x", "999")),
	} {
		if r := root.post("/directories", v); r.code != http.StatusBadRequest || !strings.Contains(r.body, `class="flash error"`) {
			t.Errorf("%s: %d", name, r.code)
		}
	}
	if r := root.post("/directories", with(externalValues("x", "0"), "host", "ldaps://ldap.example.com")); !strings.Contains(r.body, "ldaps") {
		t.Error("reason not shown")
	}
	secretValue := "reader-" + itoa(h.clock.T.Unix()) // made at test time
	id := created(t, root.post("/directories", with(externalValues("公司", "0"), "passwordMode", "template", "password", secretValue)), "/directories/")
	if r := root.post("/directories", externalValues("公司", "0")); r.code != http.StatusBadRequest || !strings.Contains(r.body, "已有名为") {
		t.Errorf("duplicate name: %d", r.code)
	}
	r := root.get("/directories/" + itoa(id))
	if strings.Contains(r.body, secretValue) || !strings.Contains(r.body, "已设置，留空保持不变") {
		t.Error("password shown or not marked as set")
	}
	// Saving without a password keeps it; another mode drops it.
	created(t, root.post("/directories/"+itoa(id), with(externalValues("公司", "0"), "passwordMode", "template", "port", "1389")), "/directories/")
	e, _ := h.st.ExternalDirectoryByID(t.Context(), id)
	if e.Port != 1389 || e.PasswordEnc == "" {
		t.Errorf("kept password: %+v", e.Port)
	}
	if plain, err := h.srv.Ext.Box.Open(e.PasswordEnc, store.ExternalDirectoryAAD(id)); err != nil || string(plain) != secretValue {
		t.Error("stored password does not open")
	}
	created(t, root.post("/directories/"+itoa(id), with(externalValues("公司", "0"), "passwordMode", "client")), "/directories/")
	if e, _ = h.st.ExternalDirectoryByID(t.Context(), id); e.PasswordEnc != "" {
		t.Error("password kept after switching to per client")
	}
	a := root.get("/audit")
	if strings.Contains(a.body, secretValue) || !strings.Contains(a.body, "新建外部通讯录") || !strings.Contains(a.body, "密码方式") {
		t.Error("audit: secret shown or entries missing")
	}
	entries, _ := h.st.ListAudit(t.Context(), store.AuditFilter{AllRegions: true, Limit: 100})
	for _, en := range entries {
		if raw, _ := json.Marshal(en.Detail); strings.Contains(string(raw), secretValue) {
			t.Error("secret in an audit entry")
		}
	}
	if strings.Contains(h.logs.String(), secretValue) {
		t.Error("secret in the log")
	}
}

// TestDirectoryRegionsAndUse: region rules for seeing, editing, using and moving
// directories; a directory in use cannot be deleted; the template's part refers to a
// directory and the document follows the directory.
func TestDirectoryRegionsAndUse(t *testing.T) {
	h := newHarness(t)
	east, west := h.region("华东"), h.region("华西")
	root, _ := h.loggedIn("root", store.RoleSuper)
	local, _ := h.loggedIn("east", store.RoleRegion, east)
	global := created(t, root.post("/directories", externalValues("全局目录", "0")), "/directories/")
	westDir := created(t, root.post("/directories", externalValues("华西目录", itoa(west))), "/directories/")

	if r := local.post("/directories", externalValues("x", "0")); r.code != http.StatusBadRequest || !strings.Contains(r.body, "无权") {
		t.Errorf("region administrator made a global directory: %d", r.code)
	}
	if r := local.get("/directories/" + itoa(westDir)); r.code != http.StatusNotFound {
		t.Errorf("other region visible: %d", r.code)
	}
	if r := local.get("/directories/" + itoa(global)); r.code != 200 || !strings.Contains(r.body, `<fieldset class="plain bare" disabled>`) {
		t.Errorf("global directory not read-only for a region administrator: %d", r.code)
	}
	if r := local.post("/directories/"+itoa(global), externalValues("改名", "0")); r.code != http.StatusForbidden {
		t.Errorf("region administrator changed a global directory: %d", r.code)
	}
	own := created(t, local.post("/directories", externalValues("华东目录", itoa(east))), "/directories/")

	// A template of 华东 may use global and own directories, not those of 华西.
	tplID := created(t, local.post("/config-templates", url.Values{"name": {"华东"}, "region": {itoa(east)}}), "/config-templates/")
	base := "/config-templates/" + itoa(tplID)
	r := local.get(base + "/directories/new")
	if !strings.Contains(r.body, "全局目录（全局）") || !strings.Contains(r.body, "华东目录") || strings.Contains(r.body, "华西目录") {
		t.Error("choice of directories")
	}
	if r := local.post(base+"/directories", url.Values{"source": {"external:" + itoa(westDir)}, "enabled": {"1"}}); r.code != http.StatusBadRequest {
		t.Errorf("used another region's directory: %d", r.code)
	}
	created(t, local.post(base+"/directories", url.Values{"source": {"external:" + itoa(own)}, "enabled": {"1"},
		"lookupIncoming": {"1"}}), base)
	created(t, local.post(base+"/directories", url.Values{"source": {"external:" + itoa(global)}, "name": {"总部"}, "enabled": {"1"}}), base)
	if r := local.post(base+"/directories", url.Values{"source": {"external:" + itoa(global)}, "name": {"华东目录"}}); r.code != http.StatusBadRequest {
		t.Errorf("two uses with one name on the phone: %d", r.code)
	}
	dirs, _ := h.st.Directories(t.Context(), tplID)
	if len(dirs) != 2 || dirs[0].DisplayName() != "华东目录" || dirs[1].DisplayName() != "总部" || dirs[0].External.Host != "ldap.example.com" {
		t.Fatalf("uses: %+v", dirs)
	}
	if r := local.get(base + "?tab=directories"); !strings.Contains(r.body, "/directories/"+itoa(own)) || !strings.Contains(r.body, "外部") {
		t.Error("template tab does not link the directory")
	}
	// Editing a use keeps its directory whatever the form says.
	created(t, local.post(base+"/directories/"+itoa(dirs[0].ID), url.Values{"source": {"external:" + itoa(global)}, "enabled": {"1"}}), base)
	if d, _ := h.st.DirectoryByID(t.Context(), tplID, dirs[0].ID); *d.ExternalID != own {
		t.Error("a use changed its directory")
	}

	// A change of the directory reaches the template's revision.
	before, _ := h.st.ConfigTemplateByID(t.Context(), tplID)
	created(t, local.post("/directories/"+itoa(own), with(externalValues("华东目录", itoa(east)), "host", "ldap2.example.com")), "/directories/")
	after, _ := h.st.ConfigTemplateByID(t.Context(), tplID)
	if after.Revision <= before.Revision {
		t.Error("template revision not bumped by a directory change")
	}

	// In use: no delete; the page names the template.
	r = local.get("/directories/" + itoa(own))
	if !strings.Contains(r.body, "使用中：配置模板 华东") {
		t.Error("users not shown")
	}
	local.post("/directories/"+itoa(own)+"/delete", nil)
	if _, err := h.st.ExternalDirectoryByID(t.Context(), own); err != nil {
		t.Error("directory in use deleted")
	}
	// Moving the global directory to 华西 would break the 华东 template.
	if r := root.post("/directories/"+itoa(global), externalValues("全局目录", itoa(west))); r.code != http.StatusBadRequest ||
		!strings.Contains(r.body, "无法移至该区域") {
		t.Errorf("move: %d", r.code)
	}
	// Removed from the template it can go.
	for _, d := range dirs {
		local.post(base+"/directories/"+itoa(d.ID)+"/delete", nil)
	}
	if r := local.post("/directories/"+itoa(own)+"/delete", nil); r.code != http.StatusSeeOther || r.location != "/books" {
		t.Errorf("delete: %d %s", r.code, r.location)
	}
	if _, err := h.st.ExternalDirectoryByID(t.Context(), own); err == nil {
		t.Error("unused directory not deleted")
	}
}

// TestDirectoryInDocument: a per-client password of an external directory is asked on
// the client form and goes into the document with the directory's connection.
func TestDirectoryInDocument(t *testing.T) {
	h := newHarness(t)
	east := h.region("华东")
	f := h.fixture("标准", &east)
	root, _ := h.loggedIn("root", store.RoleSuper)
	if r := root.get("/clients/new?region=" + itoa(east) + "&template=" + itoa(f.tpl.ID)); !strings.Contains(r.body, `name="dir_`+itoa(f.dir.ID)+`"`) {
		t.Error("client form does not ask for the directory password")
	}
	id, _ := newClient(t, root, f, "前台", east, "1001")
	c, _ := h.st.ClientByID(t.Context(), id)
	b, err := h.srv.buildDocument(&http.Request{}, c)
	if err != nil || len(b.problems) > 0 {
		t.Fatal(err, b.problems)
	}
	var found bool
	for _, d := range b.doc.Directories {
		if d.Key == "d"+itoa(f.dir.ID) {
			found = d.Name == "公司" && d.Host == "ldap.example.com" && d.Password == "dir-secret-前台" && d.BindDn == "uid=x,dc=example,dc=com"
		}
	}
	if !found {
		t.Errorf("external directory in the document: %+v", b.doc.Directories)
	}
}

// TestReportedVersion: the platform keeps the version code and the User-Agent the phone
// reports and shows them on the client page.
func TestReportedVersion(t *testing.T) {
	h := newHarness(t)
	east := h.region("华东")
	f := h.fixture("标准", &east)
	root, _ := h.loggedIn("root", store.RoleSuper)
	id, token := newClient(t, root, f, "前台", east, "1001")
	body := strings.ReplaceAll(deviceBody, "%s", "")
	req, _ := http.NewRequest("POST", h.ts.URL+"/p/"+token, strings.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("User-Agent", "SipLink/0.2.0 (Android 16)")
	res, err := h.ts.Client().Do(req)
	if err != nil {
		t.Fatal(err)
	}
	io.Copy(io.Discard, res.Body)
	res.Body.Close()
	if res.StatusCode != 200 {
		t.Fatalf("enroll: %d", res.StatusCode)
	}
	rep, _ := h.st.ClientReport(t.Context(), id)
	if rep.AppVersion != "0.2.0" || rep.AppVersionCode != 20 || rep.UserAgent != "SipLink/0.2.0 (Android 16)" {
		t.Errorf("report: %q %d %q", rep.AppVersion, rep.AppVersionCode, rep.UserAgent)
	}
	r := root.get("/clients/" + itoa(id))
	if !strings.Contains(r.body, "版本号</span> <span class=\"mono\">20</span>") || !strings.Contains(r.body, "SipLink/0.2.0 (Android 16)") {
		t.Error("client page: version code or User-Agent")
	}
	if r := root.get("/clients"); !strings.Contains(r.body, "0.2.0") {
		t.Error("client list: version")
	}
}
