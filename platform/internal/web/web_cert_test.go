package web

import (
	"bytes"
	"encoding/pem"
	"mime/multipart"
	"net/http"
	"net/url"
	"regexp"
	"strings"
	"testing"
	"time"

	"siplink/internal/config"
	"siplink/internal/pki"
	"siplink/internal/store"
	"siplink/internal/testutil"
)

// upload posts the certificate form; empty parts are left out.
func (c *client) uploadCert(chain, key []byte, headers ...string) resp {
	c.h.t.Helper()
	var body bytes.Buffer
	mw := multipart.NewWriter(&body)
	for name, data := range map[string][]byte{"chain": chain, "key": key} {
		if data != nil {
			fw, _ := mw.CreateFormFile(name, name+".pem")
			fw.Write(data)
		}
	}
	mw.Close()
	req, _ := http.NewRequest("POST", c.h.ts.URL+"/system/certificate", &body)
	req.Header.Set("Content-Type", mw.FormDataContentType())
	for i := 0; i+1 < len(headers); i += 2 {
		req.Header.Set(headers[i], headers[i+1])
	}
	return c.do(req)
}

// privateChain makes a leaf named cn under a private root, valid 90 days. In the
// harness the LDAP host is the public host.
func (h *harness) privateChain(cn string) (chain, key []byte, root *testutil.Cert) {
	from := h.clock.T.Add(-time.Hour)
	root = testutil.MakeCert(h.t, testutil.CertSpec{CN: cn + " Root", CA: true, From: from, Days: 3650}, nil)
	leaf := testutil.MakeCert(h.t, testutil.CertSpec{CN: cn, Names: []string{cn}, From: from, Days: 90}, root)
	return testutil.ChainPEM(leaf, root), testutil.KeyPEM(h.t, leaf), root
}

var confirmToken = regexp.MustCompile(`name="token" value="([A-Za-z0-9_-]+)"`)

func TestCertificateUpload(t *testing.T) {
	h := newHarness(t)
	root, _ := h.loggedIn("root", store.RoleSuper)
	east := h.region("华东")
	local, _ := h.loggedIn("east", store.RoleRegion, east)
	chain, key, priv := h.privateChain("platform.example.com")

	// Super administrators only, and only from the platform's own pages.
	if r := local.uploadCert(chain, key); r.code != http.StatusNotFound {
		t.Errorf("region admin: %d", r.code)
	}
	if r := root.uploadCert(chain, key, "Sec-Fetch-Site", "cross-site"); r.code != http.StatusForbidden {
		t.Errorf("cross-site upload: %d", r.code)
	}
	if h.srv.Ext.Certs.Uploaded() != nil {
		t.Fatal("stored by a refused request")
	}

	// Refusals are shown with their reason; nothing is stored.
	block, _ := pem.Decode(key)
	other, otherKey, _ := h.privateChain("other.example.com")
	wrongHost := testutil.MakeCert(t, testutil.CertSpec{CN: "x", Names: []string{"elsewhere.example.com"}, From: h.clock.T.Add(-time.Hour)}, nil)
	for _, c := range []struct {
		chain, key []byte
		want       string
	}{
		{chain, nil, "请选择私钥文件"},
		{chain, pem.EncodeToMemory(&pem.Block{Type: "ENCRYPTED PRIVATE KEY", Bytes: block.Bytes}), "私钥已加密"},
		{chain, otherKey, "不匹配"},
		{other, otherKey, "不包含公开地址的主机名 platform.example.com"},
		{testutil.ChainPEM(wrongHost), testutil.KeyPEM(t, wrongHost), "不包含公开地址的主机名"},
		{bytes.Repeat([]byte("A"), 70<<10), key, "64 KB"},
	} {
		r := root.uploadCert(c.chain, c.key)
		if r.code != http.StatusBadRequest || !strings.Contains(r.body, "证书未启用") || !strings.Contains(r.body, c.want) {
			t.Errorf("%q: %d", c.want, r.code)
		}
	}
	if h.srv.Ext.Certs.Uploaded() != nil {
		t.Fatal("stored after a refusal")
	}

	// A good chain, no bound phone: used at once; issuing pauses; the key is never shown.
	serial := h.srv.Ext.Certs.CA().Server().SerialNumber
	if r := root.uploadCert(chain, key); r.code != http.StatusSeeOther {
		t.Fatalf("upload: %d %s", r.code, r.body)
	}
	page := root.get("/system").body
	if !strings.Contains(page, "已启用自有证书") || !strings.Contains(page, "使用自有证书（自动签发已暂停）") ||
		strings.Contains(page, "/system/reissue") || !strings.Contains(page, "改用内置 CA") || strings.Contains(page, "PRIVATE KEY") ||
		!strings.Contains(page, "私有根证书「CN=platform.example.com Root」") {
		t.Fatalf("system page after upload:\n%s", page)
	}
	if r := root.post("/system/reissue", nil); r.code != http.StatusSeeOther || h.srv.Ext.Certs.CA().Server().SerialNumber.Cmp(serial) != 0 {
		t.Errorf("reissue while paused: %d", r.code)
	}
	if !strings.Contains(root.get("/system").body, "无服务使用内置 CA 签发的证书") {
		t.Error("reissue refusal not shown")
	}
	// QR codes pin the private root; documents start with it.
	f := h.fixture("标准", &east)
	id, _ := newClient(t, root, f, "前台", east, "1001")
	m := enrollLink.FindStringSubmatch(root.get("/clients/" + itoa(id)).body)
	if m == nil || m[2] != "?pin="+pki.Pin(priv.Cert) {
		t.Errorf("pin: %v", m)
	}
	if !strings.Contains(root.get("/config-templates/"+itoa(f.config.ID)+"?tab=security").body, "platform.example.com Root") {
		t.Error("platform CA row of the template")
	}
	if a := root.get("/audit").body; !strings.Contains(a, "启用自有证书") || strings.Contains(a, "PRIVATE") {
		t.Error("audit of the upload")
	}

	// A bound phone pinned this root: a certificate from another root waits for a confirmation.
	if _, err := h.st.DB.Exec(`UPDATE clients SET status = 'active' WHERE id = ?`, id); err != nil {
		t.Fatal(err)
	}
	next, nextKey, nextRoot := h.privateChain("platform.example.com")
	r := root.uploadCert(next, nextKey)
	tok := confirmToken.FindStringSubmatch(r.body)
	if r.code != 200 || tok == nil || !strings.Contains(r.body, "已绑定的 1 台设备") || !strings.Contains(r.body, "确认启用此证书") {
		t.Fatalf("warning: %d %v", r.code, tok)
	}
	if m := regexp.MustCompile(`\sstyle="|<style|<script>|\son[a-z]+="`).FindString(r.body); m != "" {
		t.Errorf("inline %q on the confirmation page", m)
	}
	if pki.Pin(h.srv.Ext.Certs.Uploaded().Anchor) != pki.Pin(priv.Cert) {
		t.Fatal("used before the confirmation")
	}
	// Another administrator cannot confirm it.
	other2, _ := h.loggedIn("root2", store.RoleSuper)
	if r := other2.post("/system/certificate/confirm", url.Values{"token": {tok[1]}}); r.code != http.StatusSeeOther ||
		!strings.Contains(other2.get("/system").body, "确认已失效") {
		t.Errorf("foreign confirmation: %d", r.code)
	}
	if r := root.post("/system/certificate/confirm", url.Values{"token": {tok[1]}}); r.code != http.StatusSeeOther {
		t.Fatalf("confirm: %d", r.code)
	}
	if h.srv.Ext.Certs.Pin() != pki.Pin(nextRoot.Cert) {
		t.Error("confirmed certificate not in use")
	}

	// Returning to the built-in CA locks the bound phone out: asked first.
	r = root.post("/system/certificate/remove", nil)
	if r.code != 200 || !strings.Contains(r.body, "确认改用内置 CA，1 台设备需重新扫码") || h.srv.Ext.Certs.Uploaded() == nil {
		t.Fatalf("remove warning: %d", r.code)
	}
	if r := root.post("/system/certificate/remove", url.Values{"confirm": {"1"}}); r.code != http.StatusSeeOther {
		t.Fatalf("remove: %d", r.code)
	}
	if h.srv.Ext.Certs.Uploaded() != nil || h.srv.Ext.Certs.Paused() || h.srv.Ext.Certs.Pin() != h.srv.Ext.Certs.CA().Pin() {
		t.Error("not back to the built-in CA")
	}
	if a := root.get("/audit").body; !strings.Contains(a, "停用自有证书") {
		t.Error("audit of the removal")
	}
	if page := root.get("/system").body; !strings.Contains(page, "内置 CA 自动签发") || !strings.Contains(page, "/system/reissue") {
		t.Error("system page after the removal")
	}
}

func TestCertificateReminder(t *testing.T) {
	h := newHarness(t)
	root, _ := h.loggedIn("root", store.RoleSuper)
	east := h.region("华东")
	local, _ := h.loggedIn("east", store.RoleRegion, east)
	anon := h.client()

	if r := local.post("/system/certificate/reminder", url.Values{"days": {"10"}}); r.code != http.StatusNotFound {
		t.Errorf("region admin: %d", r.code)
	}
	for _, bad := range []string{"0", "366", "x"} {
		if r := root.post("/system/certificate/reminder", url.Values{"days": {bad}}); r.code != http.StatusBadRequest || !strings.Contains(r.body, "1 到 365") {
			t.Errorf("days %q: %d", bad, r.code)
		}
	}
	if r := root.post("/system/certificate/reminder", url.Values{"days": {"45"}}); r.code != http.StatusSeeOther {
		t.Fatalf("days: %d", r.code)
	}
	if !strings.Contains(root.get("/system?cert=own").body, `value="45"`) || !strings.Contains(root.get("/audit").body, "修改证书到期提醒") {
		t.Error("setting not shown or not audited")
	}

	// The built-in certificate never has a notice.
	if r := anon.get("/healthz"); r.body != "ok\n" {
		t.Errorf("healthz: %q", r.body)
	}
	chain, key, _ := h.privateChain("platform.example.com")
	if r := root.uploadCert(chain, key); r.code != http.StatusSeeOther {
		t.Fatalf("upload: %d %s", r.code, r.body)
	}
	if strings.Contains(root.get("/").body, `<a href="/system">证书</a>`) {
		t.Error("notice outside the window")
	}
	// Inside the window: the dashboard of super administrators, the system page, health.
	h.clock.Advance(50 * 24 * time.Hour)
	root.login("root") // the sessions ended meanwhile
	local.login("east")
	if d := root.get("/").body; !strings.Contains(d, `<span class="dot warn"></span><div><a href="/system">证书</a><span class="sub">自有证书将在 39 天后`) {
		t.Errorf("dashboard notice missing:\n%s", d)
	}
	if strings.Contains(local.get("/").body, "自有证书将在") {
		t.Error("region administrators see the notice")
	}
	if !strings.Contains(root.get("/system").body, `class="flash warn" role="alert">自有证书将在 39 天后`) {
		t.Error("system page notice")
	}
	if r := anon.get("/healthz"); r.code != 200 || !strings.HasPrefix(r.body, "ok\nwarn 自有证书将在 39 天后") {
		t.Errorf("healthz in the window: %q", r.body)
	}
	// After expiry: an error.
	h.clock.Advance(45 * 24 * time.Hour)
	root.login("root")
	if !strings.Contains(root.get("/").body, `<span class="dot bad"></span><div><a href="/system">证书</a><span class="sub">证书已过期，设备将无法同步`) ||
		!strings.Contains(root.get("/system").body, `class="flash error" role="alert">证书已过期，设备将无法同步`) {
		t.Error("expired notice")
	}
	if r := anon.get("/healthz"); r.code != 200 || !strings.Contains(r.body, "\nerror 证书已过期，设备将无法同步") {
		t.Errorf("healthz after expiry: %q", r.body)
	}
}

// TestCertificatePublicChain: a chain of a CA in the system store needs no pin; a
// certificate without the LDAP host leaves LDAP on the built-in CA, after a confirmation.
func TestCertificatePublicChain(t *testing.T) {
	h := newHarness(t, func(c *config.Config) { c.LDAPPublicHost = "ldap.example.com" })
	root, _ := h.loggedIn("root", store.RoleSuper)
	from := h.clock.T.Add(-time.Hour)
	ca := testutil.MakeCert(t, testutil.CertSpec{CN: "Public CA", CA: true, From: from}, nil)
	h.roots.AddCert(ca.Cert)
	leaf := testutil.MakeCert(t, testutil.CertSpec{CN: "p", Names: []string{"platform.example.com"}, From: from}, ca)
	r := root.uploadCert(testutil.ChainPEM(leaf), testutil.KeyPEM(t, leaf))
	// It does not name the LDAP host: a warning, then the confirmation.
	tok := confirmToken.FindStringSubmatch(r.body)
	if r.code != 200 || tok == nil || !strings.Contains(r.body, "LDAP 继续使用内置 CA") {
		t.Fatalf("public chain: %d", r.code)
	}
	root.post("/system/certificate/confirm", url.Values{"token": {tok[1]}})
	c := h.srv.Ext.Certs
	if https, ldap := c.Sources(); https != pki.SourceOwn || ldap != pki.SourceBuiltin || c.Pin() != "" || c.Paused() {
		t.Errorf("public: %s %s pin=%q", https, ldap, c.Pin())
	}
	if docs := c.DocumentCAs(); len(docs) != 1 || docs[0] != string(c.CA().CAPEM()) {
		t.Error("documents keep the built-in CA for LDAP")
	}
	page := root.get("/system").body
	if !strings.Contains(page, "公共 CA（二维码不带 pin）") || !strings.Contains(page, "混合：部分服务使用内置 CA 签发的证书") ||
		!strings.Contains(page, "证书不包含 LDAP 地址 ldap.example.com") {
		t.Errorf("public chain page:\n%s", page)
	}
}
