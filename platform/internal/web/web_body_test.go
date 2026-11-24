package web

import (
	"bytes"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"strings"
	"testing"

	"siplink/internal/books"
	"siplink/internal/i18n"
	"siplink/internal/store"
)

// privateTemp points the temporary directory of multipart parsing at an empty folder of
// the test and returns it.
func privateTemp(t *testing.T) string {
	dir := t.TempDir()
	t.Setenv("TMPDIR", dir)
	return dir
}

func noTempFiles(t *testing.T, dir, after string) {
	t.Helper()
	if list, _ := os.ReadDir(dir); len(list) > 0 {
		t.Errorf("%s: %d files left in the temporary directory, first %s", after, len(list), list[0].Name())
	}
}

// multipartForm is a multipart body with the fields and one file part per entry of files.
func multipartForm(fields map[string]string, files map[string][]byte) (string, []byte) {
	var body bytes.Buffer
	mw := multipart.NewWriter(&body)
	for k, v := range fields {
		mw.WriteField(k, v)
	}
	for name, data := range files {
		fw, _ := mw.CreateFormFile(name, name+".txt")
		fw.Write(data)
	}
	mw.Close()
	return mw.FormDataContentType(), body.Bytes()
}

// padded is text followed by lines of filler up to exactly n bytes; each filler line
// starts with lead ("#" for a comment line, "" for a blank one).
func padded(text string, n int, lead string) []byte {
	b := []byte(text)
	line := lead + strings.Repeat(" ", 1000) + "\n"
	if lead == "" {
		line = "\n"
	}
	for len(b)+len(line) <= n {
		b = append(b, line...)
	}
	for len(b) < n {
		b = append(b, '\n')
	}
	return b
}

// TestOpenRoutesRefuseLargeBodies: the login and the language choice take at most 64 KiB;
// a larger body, urlencoded or multipart, is refused before anything is counted, written
// or set, and no part of it is left in a temporary file.
func TestOpenRoutesRefuseLargeBodies(t *testing.T) {
	tmp := privateTemp(t)
	h := newHarness(t)
	h.admin("alice", store.RoleSuper)
	c := h.client()
	big := strings.Repeat("a", maxOpenBody)
	for _, path := range []string{"/login", "/language"} {
		form := url.Values{"username": {big}, "password": {"x"}, "lang": {"en"}, "next": {"/"}}
		if r := c.post(path, form); r.code != http.StatusRequestEntityTooLarge || r.header.Get("Set-Cookie") != "" {
			t.Errorf("%s urlencoded: %d %q", path, r.code, r.header.Get("Set-Cookie"))
		}
		ct, body := multipartForm(map[string]string{"username": "alice", "lang": "en"},
			map[string][]byte{"file": bytes.Repeat([]byte("x"), 1<<20)})
		if r := c.postBody(path, ct, body); r.code != http.StatusRequestEntityTooLarge || r.header.Get("Set-Cookie") != "" {
			t.Errorf("%s multipart: %d %q", path, r.code, r.header.Get("Set-Cookie"))
		}
	}
	if entries, _ := h.st.ListAudit(t.Context(), store.AuditFilter{AllRegions: true, Action: "login."}); len(entries) != 0 {
		t.Errorf("refused logins wrote %d audit entries", len(entries))
	}
	noTempFiles(t, tmp, "refused bodies")
	// Just under the limit is a normal attempt.
	ct, body := multipartForm(map[string]string{"username": "alice", "password": "password-alice"},
		map[string][]byte{"file": bytes.Repeat([]byte("x"), maxOpenBody-1<<10)})
	if r := c.postBody("/login", ct, body); r.code != http.StatusSeeOther {
		t.Errorf("login under the limit: %d", r.code)
	}
	noTempFiles(t, tmp, "a multipart login")
}

// TestPageBodyLimit: a signed-in page takes at most 1 MiB.
func TestPageBodyLimit(t *testing.T) {
	tmp := privateTemp(t)
	h := newHarness(t)
	root, _ := h.loggedIn("root", store.RoleSuper)
	ct, body := multipartForm(map[string]string{"name": "华东"}, map[string][]byte{"file": bytes.Repeat([]byte("x"), maxPageBody)})
	if r := root.postBody("/regions", ct, body); r.code != http.StatusRequestEntityTooLarge {
		t.Errorf("region over the limit: %d", r.code)
	}
	if list, _ := h.st.ListRegions(t.Context(), nil, true); len(list) != 0 {
		t.Errorf("a refused request made %d regions", len(list))
	}
	noTempFiles(t, tmp, "a refused page")
}

// TestInvalidNameLogin: a user name no administrator can have fails like a wrong
// password (401, the same text) and keeps nothing per name, so it cannot lock anyone.
func TestInvalidNameLogin(t *testing.T) {
	h := newHarness(t)
	h.admin("alice", store.RoleSuper)
	c := h.client()
	failed := i18n.For(i18n.ByCode("zh-Hans")).T("login.failed")
	wrong := c.post("/login", url.Values{"username": {"alice"}, "password": {"nope"}})
	for _, name := range []string{strings.Repeat("a", 33), "名字", "a b", ""} {
		r := c.post("/login", url.Values{"username": {name}, "password": {"nope"}})
		if r.code != http.StatusUnauthorized || r.code != wrong.code || !strings.Contains(r.body, failed) {
			t.Errorf("%q: %d", name, r.code)
		}
	}
	for range 6 { // more than the lockout of a name, which an invalid name never gets
		c.post("/login", url.Values{"username": {"x y"}, "password": {"nope"}})
	}
	c.login("alice")
}

// TestMaxUploadsWork: each upload takes a file of its documented size, above the limit of
// other pages, and leaves no temporary file.
func TestMaxUploadsWork(t *testing.T) {
	tmp := privateTemp(t)
	h := newHarness(t)
	root, _ := h.loggedIn("root", store.RoleSuper)

	b := h.book("people", nil)
	r := root.upload("/books/"+itoa(b.ID)+"/import", "append", padded("姓名\n张三\n", books.MaxCSVBytes, ""))
	if r.code != http.StatusOK || !importKey.MatchString(r.body) {
		t.Errorf("directory CSV of %d bytes: %d", books.MaxCSVBytes, r.code)
	}
	noTempFiles(t, tmp, "directory CSV")

	f := h.fixture("标准", nil)
	h.region("华东")
	csv := "名称,区域,客户端模板,总部用户名,总部密码\n甲,华东,标准,2001,pw\n"
	r = root.uploadCSV("/clients/import", padded(csv, maxClientCSV, "#"))
	if r.code != http.StatusOK || !importKey.MatchString(r.body) {
		t.Errorf("client CSV of %d bytes: %d", maxClientCSV, r.code)
	}
	noTempFiles(t, tmp, "client CSV")

	kid := created(t, root.post("/key-templates", url.Values{"name": {"前台"}, "region": {"0"}}), "/key-templates/")
	kb := "/key-templates/" + itoa(kid)
	ct, body := multipartForm(map[string]string{"mode": "append"}, map[string][]byte{"file": padded("保安,8001\n", maxKeyUpload-4<<10, "#")})
	created(t, root.postBody(kb+"/import", ct, body), kb)
	noTempFiles(t, tmp, "key CSV")

	caPEM, _ := testCAPEM(t, "Big Root")
	base := "/config-templates/" + itoa(f.config.ID)
	ct, body = multipartForm(nil, map[string][]byte{"file": padded(caPEM, maxCAUpload-4<<10, "")})
	if r := root.postBody(base+"/cas", ct, body); r.code != http.StatusSeeOther {
		t.Errorf("CA file of %d bytes: %d %s", len(body), r.code, r.body)
	}
	noTempFiles(t, tmp, "CA certificates")

	chain, key, _ := h.privateChain("platform.example.com")
	if r := root.uploadCert(padded(string(chain), maxCertFile, ""), padded(string(key), maxCertFile, "")); r.code != http.StatusSeeOther {
		t.Errorf("certificate files of %d bytes: %d", maxCertFile, r.code)
	}
	noTempFiles(t, tmp, "own certificate")
}

// TestDropFiles: the multipart files of the request the handler parsed are removed when
// it returns, also when the handler got a copy of the request net/http made.
func TestDropFiles(t *testing.T) {
	tmp := privateTemp(t)
	ct, body := multipartForm(nil, map[string][]byte{"file": []byte("on disk")})
	req := httptest.NewRequest("POST", "/", bytes.NewReader(body))
	req.Header.Set("Content-Type", ct)
	h := dropFiles(func(w http.ResponseWriter, r *http.Request) {
		if err := r.ParseMultipartForm(0); err != nil { // 0: every file part goes to disk
			t.Fatal(err)
		}
		if list, _ := os.ReadDir(tmp); len(list) != 1 {
			t.Fatalf("files while the handler runs: %d", len(list))
		}
	})
	h(httptest.NewRecorder(), req.WithContext(t.Context()))
	noTempFiles(t, tmp, "the handler returned")
}
