package web

import (
	"bytes"
	"mime/multipart"
	"net/http"
	"net/url"
	"strings"
	"testing"

	"siplink/internal/store"
)

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

	upload := func(lang, data string) resp {
		var body bytes.Buffer
		mw := multipart.NewWriter(&body)
		fw, _ := mw.CreateFormFile("file", "clients.csv")
		fw.Write([]byte(data))
		mw.Close()
		req, _ := http.NewRequest("POST", root.h.ts.URL+"/clients/import", &body)
		req.Header.Set("Content-Type", mw.FormDataContentType())
		return inLang(lang, req)
	}
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
