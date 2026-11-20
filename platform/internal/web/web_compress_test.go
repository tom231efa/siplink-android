package web

import (
	"compress/gzip"
	"encoding/base32"
	"io"
	"net/http"
	"net/url"
	"strings"
	"testing"

	"siplink/internal/store"
)

// gunzip returns the body of a compressed response, failing the test for any other.
func gunzip(t *testing.T, r resp) string {
	t.Helper()
	if r.header.Get("Content-Encoding") != "gzip" {
		t.Fatalf("not compressed: %d %q", r.code, r.header.Get("Content-Encoding"))
	}
	zr, err := gzip.NewReader(strings.NewReader(r.body))
	if err != nil {
		t.Fatal(err)
	}
	b, err := io.ReadAll(zr)
	if err != nil {
		t.Fatal(err)
	}
	return string(b)
}

// getWith sends a GET with the headers given as name, value pairs; an explicit
// Accept-Encoding keeps the transport from asking for and undoing gzip itself.
func (c *client) getWith(path string, headers ...string) resp {
	req, _ := http.NewRequest("GET", c.h.ts.URL+path, nil)
	for i := 0; i+1 < len(headers); i += 2 {
		req.Header.Set(headers[i], headers[i+1])
	}
	return c.do(req)
}

func TestAcceptsGzip(t *testing.T) {
	for v, want := range map[string]bool{"": false, "gzip": true, "GZIP": true, "br, gzip;q=0.5": true, "gzip;q=0": false,
		"gzip; q=0.0": false, "*": true, "*;q=0": false, "*, gzip;q=0": false, "deflate, br": false, "identity": false, "x-gzip": true} {
		r, _ := http.NewRequest("GET", "/", nil)
		if v != "" {
			r.Header.Set("Accept-Encoding", v)
		}
		if got := acceptsGzip(r); got != want {
			t.Errorf("Accept-Encoding %q: %v", v, got)
		}
	}
}

// TestCompression: pages and the text files under /static go gzip-compressed to a
// browser that accepts it, with Vary: Accept-Encoding, and as they are to one that does
// not; a static file's compressed form has its own ETag and answers If-None-Match with
// 304. No page that shows a secret is compressed (BREACH): one-time and LDAP account
// passwords, the TOTP secret, recovery codes, enrollment links and QR codes, revealed
// passwords, the document preview; nor is the device API.
func TestCompression(t *testing.T) {
	h := newHarness(t)
	east := h.region("华东")
	f := h.fixture("标准", nil)
	root, _ := h.loggedIn("root", store.RoleSuper)
	gz := []string{"Accept-Encoding", "gzip"}

	plain := root.getWith("/clients", "Accept-Encoding", "identity")
	r := root.getWith("/clients", gz...)
	if plain.header.Get("Content-Encoding") != "" || !strings.Contains(plain.header.Get("Vary"), "Accept-Encoding") ||
		!strings.Contains(r.header.Get("Vary"), "Accept-Encoding") || gunzip(t, r) != plain.body || len(r.body) >= len(plain.body) {
		t.Errorf("client list: plain %q, gzip %q", plain.header.Get("Content-Encoding"), r.header.Get("Content-Encoding"))
	}
	if r := root.getWith("/clients", "Accept-Encoding", "gzip;q=0"); r.header.Get("Content-Encoding") != "" {
		t.Error("gzip;q=0 got gzip")
	}

	// Static files: CSS compressed with its own ETag; the font as it is.
	css := assetURL("app.css")
	version := assetVersions["app.css"]
	r = root.getWith(css, gz...)
	if r.header.Get("ETag") != `"`+version+`-gzip"` || !strings.Contains(r.header.Get("Vary"), "Accept-Encoding") {
		t.Errorf("app.css gzip: ETag %s Vary %s", r.header.Get("ETag"), r.header.Get("Vary"))
	}
	orig, _ := staticFiles.ReadFile("static/app.css")
	if gunzip(t, r) != string(orig) {
		t.Error("app.css: compressed form differs")
	}
	if r := root.getWith(css, "Accept-Encoding", "gzip", "If-None-Match", `"`+version+`-gzip"`); r.code != http.StatusNotModified || r.body != "" {
		t.Errorf("app.css revalidation: %d", r.code)
	}
	if r := root.getWith(css, "Accept-Encoding", "identity", "If-None-Match", `"`+version+`-gzip"`); r.code != http.StatusOK ||
		r.header.Get("ETag") != `"`+version+`"` || r.body != string(orig) {
		t.Errorf("app.css without gzip after a gzip ETag: %d %s", r.code, r.header.Get("ETag"))
	}
	if r := root.getWith(assetURL("InterVariable.woff2"), gz...); r.code != http.StatusOK || r.header.Get("Content-Encoding") != "" ||
		r.header.Get("Vary") != "" {
		t.Errorf("font: %q %q", r.header.Get("Content-Encoding"), r.header.Get("Vary"))
	}

	// Pages with secrets.
	noGzip := func(what string, r resp, must string) {
		t.Helper()
		if r.code != http.StatusOK || r.header.Get("Content-Encoding") != "" || !strings.Contains(r.body, must) {
			t.Errorf("%s: %d %q", what, r.code, r.header.Get("Content-Encoding"))
		}
	}
	noGzip("one-time password", root.post("/admins", url.Values{"username": {"wang"}, "role": {"super"}}, gz...), `class="secret-once"`)
	book := h.book("global", nil)
	noGzip("LDAP account password", root.post("/ldap-accounts", url.Values{"name": {"desk1"}, "region": {"0"}, "books": {itoa(book.ID)}}, gz...),
		`class="secret-once`)
	if r := root.post("/me/totp/new", nil); r.code != http.StatusSeeOther {
		t.Fatalf("totp new: %d", r.code)
	}
	r = root.getWith("/me/totp", gz...)
	noGzip("TOTP secret", r, `id="totp-secret"`)
	key, _ := base32.StdEncoding.WithPadding(base32.NoPadding).DecodeString(totpSecretRe.FindStringSubmatch(r.body)[1])
	confirm := root.post("/me/totp/confirm", url.Values{"code": {h.totpCode(key)}})
	noGzip("recovery codes", root.getWith(confirm.location, gz...), `class="recovery-codes"`)

	pending, _ := newClient(t, root, f, "前台", east, "1001")
	bound, token := newClient(t, root, f, "大堂", east, "1002")
	code, enrolled, _ := h.deviceCall("/p/"+token, "", "")
	if code != http.StatusOK {
		t.Fatalf("enroll: %d", code)
	}
	noGzip("enrollment link and QR code", root.getWith("/clients/"+itoa(pending), gz...), "https://platform.example.com/p/")
	noGzip("print page", root.getWith("/clients/print?ids="+itoa(pending), gz...), `class="print-card"`)
	noGzip("document preview", root.getWith("/clients/"+itoa(bound)+"/preview", gz...), "configPasswordHash")
	if gunzip(t, root.getWith("/clients/"+itoa(bound), gz...)) == "" {
		t.Error("the page of a bound client without secrets: empty")
	}
	if r := root.post("/clients/"+itoa(bound)+"/reveal", url.Values{"password": {"password-root"}}); r.code != http.StatusSeeOther {
		t.Fatalf("reveal: %d", r.code)
	}
	noGzip("revealed passwords", root.getWith("/clients/"+itoa(bound)+"?show=1", gz...), "sip-secret-大堂")

	// The device API sends the document (SIP passwords) as it is.
	req, _ := http.NewRequest("POST", h.ts.URL+"/api/device/v1/sync", strings.NewReader(`{"have":""}`))
	req.Header.Set("Authorization", "Bearer "+decode(t, enrolled).DeviceKey)
	req.Header.Set("Accept-Encoding", "gzip")
	if r := root.do(req); r.code != http.StatusOK || r.header.Get("Content-Encoding") != "" || len(r.body) < minGzip ||
		!strings.Contains(r.body, "sip-secret-大堂") {
		t.Errorf("device API: %d %q, %d bytes", r.code, r.header.Get("Content-Encoding"), len(r.body))
	}
}
