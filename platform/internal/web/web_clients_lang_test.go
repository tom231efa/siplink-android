package web

import (
	"bytes"
	"html"
	"mime/multipart"
	"net/http"
	"net/url"
	"strings"
	"testing"

	"siplink/internal/i18n"
	"siplink/internal/provision"
	"siplink/internal/store"
)

// uploadCSV sends a client CSV to the dry run in a page language.
func uploadCSV(c *client, lang, data string) resp {
	var body bytes.Buffer
	mw := multipart.NewWriter(&body)
	fw, _ := mw.CreateFormFile("file", "clients.csv")
	fw.Write([]byte(data))
	mw.Close()
	req, _ := http.NewRequest("POST", c.h.ts.URL+"/clients/import", &body)
	req.Header.Set("Content-Type", mw.FormDataContentType())
	req.Header.Set("Accept-Language", lang)
	return c.do(req)
}

// TestClientCSVLanguages: the header download writes the Chinese column names on a
// Chinese page and the English ones on any other, and the import reads the English
// names, their English part in any case (§5.5).
func TestClientCSVLanguages(t *testing.T) {
	h := newHarness(t)
	east := h.region("East")
	f := h.fixture("Standard", nil)
	root, _ := h.loggedIn("root", store.RoleSuper)
	inLang := func(lang string, req *http.Request) resp {
		req.Header.Set("Accept-Language", lang)
		return root.do(req)
	}

	path := root.h.ts.URL + "/clients/import/template.csv?template=" + itoa(f.tpl.ID)
	for lang, want := range map[string]string{
		"zh-CN": "名称,区域,客户端模板,备注,总部用户名,总部密码,总部认证用户,总部显示名,分部用户名,分部密码,分部认证用户,分部显示名,公司LDAP密码",
		"zh-TW": "名称,区域,客户端模板,备注,总部用户名,总部密码,总部认证用户,总部显示名,分部用户名,分部密码,分部认证用户,分部显示名,公司LDAP密码",
		"en": "name,region,client_template,note,总部 username,总部 password,总部 auth user,总部 display name," +
			"分部 username,分部 password,分部 auth user,分部 display name,公司 LDAP password",
		"fr": "name,region,client_template,note,总部 username,总部 password,总部 auth user,总部 display name," +
			"分部 username,分部 password,分部 auth user,分部 display name,公司 LDAP password",
	} {
		req, _ := http.NewRequest("GET", path, nil)
		if r := inLang(lang, req); r.code != http.StatusOK || strings.TrimSpace(r.body) != "\xef\xbb\xbf"+want {
			t.Errorf("%s header: %d %q", lang, r.code, r.body)
		}
	}

	upload := func(lang, data string) resp { return uploadCSV(root, lang, data) }
	// A required column missing is named as the page's language names it.
	if r := upload("en", "name,client_template\nAlpha,Standard\n"); r.code != http.StatusBadRequest ||
		!strings.Contains(r.body, "The header lacks the column “region”.") {
		t.Errorf("missing column: %d", r.code)
	}

	csv := "Name,REGION,Client_Template,note,总部 Username,总部 PASSWORD,总部 auth user,总部 Display Name,公司 ldap password\n" +
		"Alpha,east,Standard,hello,2001,pw1,auth1,Alice,dir-secret\n"
	r := upload("en", csv)
	key := importKey.FindStringSubmatch(r.body)
	if key == nil {
		t.Fatalf("no key: %d %s", r.code, r.body)
	}
	if !strings.Contains(r.body, "Lines ready: 1/1") {
		t.Error("dry run page not in English")
	}
	if r := root.post("/clients/import/confirm", url.Values{"key": {key[1]}}); r.code != http.StatusSeeOther {
		t.Fatalf("confirm: %d", r.code)
	}
	list, _, _ := h.st.ListClients(t.Context(), store.ClientFilter{All: true, Region: east})
	if len(list) != 1 || list[0].Name != "Alpha" || list[0].Note != "hello" || list[0].ClientTemplateID != f.tpl.ID {
		t.Fatalf("imported: %+v", list)
	}
	creds, _ := h.st.Credentials(t.Context(), list[0].ID)
	if k := creds[f.profiles[0].ID]; len(creds) != 1 || k == nil || k.Username != "2001" || k.AuthUser != "auth1" ||
		k.DisplayName != "Alice" || k.PasswordEnc == "" {
		t.Errorf("credentials: %+v", creds)
	}
	if dirs, _ := h.st.DirectoryPasswords(t.Context(), list[0].ID); dirs[f.dir.ID] == "" {
		t.Error("directory password not imported")
	}
}

// importOne imports a one-client CSV and returns what the client's phone receives.
func importOne(t *testing.T, h *harness, c *client, lang, csv string, region int64) *provision.Document {
	t.Helper()
	r := uploadCSV(c, lang, csv)
	key := importKey.FindStringSubmatch(r.body)
	if key == nil {
		t.Fatalf("no key: %d %s", r.code, r.body)
	}
	if r := c.post("/clients/import/confirm", url.Values{"key": {key[1]}}); r.code != http.StatusSeeOther {
		t.Fatalf("confirm: %d", r.code)
	}
	list, _, _ := h.st.ListClients(t.Context(), store.ClientFilter{All: true, Region: region})
	if len(list) != 1 {
		t.Fatalf("imported: %+v", list)
	}
	m := enrollLink.FindStringSubmatch(c.get("/clients/" + itoa(list[0].ID)).body)
	if m == nil {
		t.Fatal("no enrollment link")
	}
	code, body, _ := h.deviceCall("/p/"+m[1], "", "")
	if code != http.StatusOK {
		t.Fatalf("enroll: %d %s", code, body)
	}
	return decode(t, body)
}

func passwords(doc *provision.Document) (accounts, dirs map[string]string) {
	accounts, dirs = map[string]string{}, map[string]string{}
	for _, a := range doc.Accounts {
		accounts[a.Name] = a.Password
	}
	for _, d := range doc.Directories {
		dirs[d.Name] = d.Password
	}
	return accounts, dirs
}

// TestClientCSVColumnClash: a profile "公司 LDAP" and a directory "公司" both give the
// English column "公司 LDAP password" (and "公司LDAP" and "公司" the Chinese "公司LDAP密码").
// The header download refuses such a template and names the column; an import whose
// header has a column that fits two fields, or a field with two columns, is refused as a
// whole, so that no password goes to the wrong place (§5.5).
func TestClientCSVColumnClash(t *testing.T) {
	h := newHarness(t)
	east := h.region("East")
	f := h.fixture("Standard", nil)
	clash := &store.Profile{TemplateID: f.config.ID, Name: "公司 LDAP", Domain: "pbx.example.com", Transport: "TLS", Expires: 3600, Enabled: true}
	if err := h.st.SaveProfile(t.Context(), clash, 100); err != nil {
		t.Fatal(err)
	}
	root, _ := h.loggedIn("root", store.RoleSuper)
	download := func(lang string) resp {
		req, _ := http.NewRequest("GET", h.ts.URL+"/clients/import/template.csv?template="+itoa(f.tpl.ID), nil)
		req.Header.Set("Accept-Language", lang)
		return root.do(req)
	}
	page := func(lang string) string {
		req, _ := http.NewRequest("GET", h.ts.URL+"/clients/import", nil)
		req.Header.Set("Accept-Language", lang)
		return root.do(req).body
	}
	en := i18n.For(i18n.English)
	if r := download("en"); r.code != http.StatusSeeOther || r.location != "/clients/import" ||
		!strings.Contains(page("en"), html.EscapeString(en.T("clients.import.header_clash", "column", "公司 LDAP password"))) {
		t.Errorf("English download with a clash: %d %s", r.code, r.location)
	}
	if r := download("zh-CN"); r.code != http.StatusOK || !strings.Contains(r.body, "公司 LDAP密码,") || !strings.Contains(r.body, "公司LDAP密码") {
		t.Errorf("Chinese download without a clash: %d %q", r.code, r.body)
	}
	// The reviewer's round trip: as the English header would have it, refused, nothing imported.
	head := "name,region,client_template,公司 LDAP username,公司 LDAP password\nAlpha,East,Standard,3001,SECRET\n"
	if r := uploadCSV(root, "en", head); r.code != http.StatusBadRequest ||
		!strings.Contains(r.body, html.EscapeString(en.T("clients.import.ambiguous_column", "column", "公司 LDAP password"))) {
		t.Errorf("ambiguous English column: %d", r.code)
	}
	if r := uploadCSV(root, "en", strings.Replace(head, "公司 LDAP password", "公司 ldap PASSWORD", 1)); r.code != http.StatusBadRequest {
		t.Errorf("ambiguous English column in another case: %d", r.code)
	}

	// The Chinese names clash for "公司LDAP" and "公司".
	clash.Name = "公司LDAP"
	if err := h.st.SaveProfile(t.Context(), clash, 100); err != nil {
		t.Fatal(err)
	}
	zh := i18n.For(i18n.Source)
	if r := download("zh-CN"); r.code != http.StatusSeeOther ||
		!strings.Contains(page("zh-CN"), zh.T("clients.import.header_clash", "column", "公司LDAP密码")) {
		t.Errorf("Chinese download with a clash: %d", r.code)
	}
	if r := download("en"); r.code != http.StatusOK {
		t.Errorf("English download without a clash: %d", r.code)
	}
	if r := uploadCSV(root, "zh-CN", "名称,区域,客户端模板,公司LDAP用户名,公司LDAP密码\nAlpha,East,Standard,3001,SECRET\n"); r.code != http.StatusBadRequest ||
		!strings.Contains(r.body, zh.T("clients.import.ambiguous_column", "column", "公司LDAP密码")) {
		t.Errorf("ambiguous Chinese column: %d", r.code)
	}

	// A field with two columns, in either language.
	for lang, csv := range map[string]string{
		"名称":          "名称,区域,客户端模板,名称\nA,East,Standard,B\n",
		"Name":        "name,region,client_template,Name\nA,East,Standard,B\n",
		"总部密码":        "名称,区域,客户端模板,总部用户名,总部密码,总部密码\nA,East,Standard,3001,x,y\n",
		"总部 PASSWORD": "name,region,client_template,总部 username,总部 password,总部 PASSWORD\nA,East,Standard,3001,x,y\n",
	} {
		if r := uploadCSV(root, "zh-CN", csv); r.code != http.StatusBadRequest ||
			!strings.Contains(r.body, zh.T("clients.import.duplicate_column", "column", lang)) {
			t.Errorf("duplicate column %s: %d", lang, r.code)
		}
	}
	if list, _, _ := h.st.ListClients(t.Context(), store.ClientFilter{All: true, Region: east}); len(list) != 0 {
		t.Errorf("imported despite the refusals: %d", len(list))
	}
}

// TestClientCSVOneLanguage: a file with a 名称 column is a Chinese one, whose English
// names are extra columns, ignored as before (an old file with its own "Name" or "NOTE"
// column imports as it did); a file without 名称 counts the English names only.
func TestClientCSVOneLanguage(t *testing.T) {
	h := newHarness(t)
	east := h.region("East")
	h.fixture("Standard", nil)
	root, _ := h.loggedIn("root", store.RoleSuper)
	doc := importOne(t, h, root, "en", "名称,区域,客户端模板,备注,Name,NOTE,总部用户名,总部密码,总部 password,公司 LDAP password\n"+
		"Alpha,East,Standard,hello,Bob Smith,other note,3001,zh-secret,en-secret,dir-en\n", east)
	list, _, _ := h.st.ListClients(t.Context(), store.ClientFilter{All: true, Region: east})
	if list[0].Name != "Alpha" || list[0].Note != "hello" {
		t.Errorf("Chinese file read English columns: %+v", list[0])
	}
	accounts, dirs := passwords(doc)
	if accounts["总部"] != "zh-secret" || dirs["公司"] != "" {
		t.Errorf("Chinese file: accounts %v directories %v", accounts, dirs)
	}

	h2 := newHarness(t)
	east2 := h2.region("East")
	h2.fixture("Standard", nil)
	root2, _ := h2.loggedIn("root", store.RoleSuper)
	doc = importOne(t, h2, root2, "zh-CN", "NAME,Region,client_template,总部 username,总部 password,总部密码,公司 LDAP password\n"+
		"Beta,East,Standard,3002,en-secret,zh-secret,dir-en\n", east2)
	if accounts, dirs := passwords(doc); accounts["总部"] != "en-secret" || dirs["公司"] != "dir-en" {
		t.Errorf("English file: accounts %v directories %v", accounts, dirs)
	}
}
