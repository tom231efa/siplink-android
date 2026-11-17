package web

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/pem"
	"math/big"
	"net/http"
	"net/url"
	"strings"
	"testing"
	"time"

	"siplink/internal/catalog"
	"siplink/internal/config"
	"siplink/internal/pki"
	"siplink/internal/secret"
	"siplink/internal/store"
)

// testCAPEM makes a throw-away CA certificate at test time.
func testCAPEM(t *testing.T, name string) (string, string) {
	t.Helper()
	key, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	tpl := &x509.Certificate{SerialNumber: big.NewInt(7), Subject: pkix.Name{CommonName: name},
		NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().Add(24 * time.Hour),
		IsCA: true, BasicConstraintsValid: true, KeyUsage: x509.KeyUsageCertSign}
	der, err := x509.CreateCertificate(rand.Reader, tpl, tpl, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	c, _ := x509.ParseCertificate(der)
	return string(pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der})), pki.Fingerprint(c)
}

// created returns the id at the end of a redirect location like /config-templates/7.
func created(t *testing.T, r resp, prefix string) int64 {
	t.Helper()
	if r.code != http.StatusSeeOther || !strings.HasPrefix(r.location, prefix) {
		t.Fatalf("create: %d %q %s", r.code, r.location, r.body)
	}
	rest := strings.TrimPrefix(r.location, prefix)
	if rest == "" || rest[0] == '?' {
		return 0 // saved a part of the object
	}
	if i := strings.IndexAny(rest, "?/"); i >= 0 {
		rest = rest[:i]
	}
	id, ok := parseInt64(rest)
	if !ok {
		t.Fatalf("no id in %q", r.location)
	}
	return id
}

func profileValues(name string, def bool) url.Values {
	v := url.Values{"name": {name}, "domain": {"pbx.example.com"}, "port": {"5061"}, "transport": {"TLS"},
		"expires": {"3600"}, "enabled": {"1"}}
	if def {
		v.Set("isDefault", "1")
	}
	return v
}

func TestConfigTemplates(t *testing.T) {
	h := newHarness(t)
	east, west := h.region("华东"), h.region("华西")
	root, _ := h.loggedIn("root", store.RoleSuper)
	local, _ := h.loggedIn("east", store.RoleRegion, east)

	gid := created(t, root.post("/config-templates", url.Values{"name": {"标准"}, "region": {"0"}}), "/config-templates/")
	// A region administrator sees the global template but may not change it.
	if r := local.get("/config-templates/" + itoa(gid)); r.code != 200 || !strings.Contains(r.body, "只读") {
		t.Errorf("region admin views global template: %d", r.code)
	}
	if r := local.post("/config-templates/"+itoa(gid)+"/settings", url.Values{"tab": {"general"}}); r.code != http.StatusForbidden {
		t.Errorf("region admin edits global template: %d", r.code)
	}
	if r := local.post("/config-templates", url.Values{"name": {"x"}, "region": {itoa(west)}}); r.code != http.StatusBadRequest {
		t.Errorf("template in another region: %d", r.code)
	}
	eid := created(t, local.post("/config-templates", url.Values{"name": {"华东"}, "region": {itoa(east)}}), "/config-templates/")
	other, _ := h.loggedIn("west", store.RoleRegion, west)
	if r := other.get("/config-templates/" + itoa(eid)); r.code != http.StatusNotFound {
		t.Errorf("other region sees template: %d", r.code)
	}

	// Profiles: app rules apply, the first one becomes the default.
	base := "/config-templates/" + itoa(gid)
	bad := profileValues("总部", true)
	bad.Set("domain", "pbx example")
	if r := root.post(base+"/profiles", bad); r.code != http.StatusBadRequest {
		t.Errorf("bad domain accepted: %d", r.code)
	}
	created(t, root.post(base+"/profiles", profileValues("总部", true)), base)
	created(t, root.post(base+"/profiles", profileValues("分部", true)), base)
	if r := root.post(base+"/profiles", profileValues("总部", false)); r.code != http.StatusBadRequest {
		t.Errorf("duplicate profile name: %d", r.code)
	}
	ps, _ := h.st.Profiles(t.Context(), gid)
	if len(ps) != 2 || ps[0].IsDefault || !ps[1].IsDefault {
		t.Fatalf("profiles/default: %+v %+v", ps[0], ps[1])
	}

	// Settings tabs.
	if r := root.post(base+"/settings", url.Values{"tab": {"calls"}, "dndMode": {"SOFTWARE"}, "dtmfMode": {"RFC2833"},
		"forwardNoAnswerSeconds": {"999"}}); r.code != http.StatusBadRequest {
		t.Errorf("forward seconds out of range: %d", r.code)
	}
	r := root.post(base+"/settings", url.Values{"tab": {"media"}, "videoCalls": {"1"}, "videoSize": {"FHD"}, "videoFrameRate": {"30"},
		"codecMode": {"custom"}, "codecKey": {"PCMA/8000/1", "opus/48000/2"}, "codecKind": {"audio", "audio"},
		"codecOrder": {"2", "1"}, "codecOn": {"1", "0"}})
	if r.code != http.StatusSeeOther {
		t.Fatalf("media: %d %s", r.code, r.body)
	}
	tpl, _ := h.st.ConfigTemplateByID(t.Context(), gid)
	if tpl.CodecsJSON != `{"audio":[{"key":"opus/48000/2","enabled":false},{"key":"PCMA/8000/1","enabled":true}],"video":[]}` {
		t.Errorf("codecs: %s", tpl.CodecsJSON)
	}
	if s := catalog.ParseSettings(tpl.SettingsJSON); !s.VideoCalls || s.VideoSize != "FHD" || s.VideoFrameRate != 30 {
		t.Errorf("settings: %+v", s)
	}
	if r := root.post(base+"/settings", url.Values{"tab": {"advanced"}, "refreshMinutes": {"30"}, "configPassword": {"short"}}); r.code != http.StatusBadRequest {
		t.Errorf("short config password: %d", r.code)
	}
	root.post(base+"/settings", url.Values{"tab": {"advanced"}, "refreshMinutes": {"30"}, "configPassword": {"long-enough-1"}})
	tpl, _ = h.st.ConfigTemplateByID(t.Context(), gid)
	if tpl.RefreshMinutes != 30 || len(tpl.ConfigPasswordHash) != 64 {
		t.Errorf("advanced: %d %q", tpl.RefreshMinutes, tpl.ConfigPasswordHash)
	}

	// CA certificates: PEM only, duplicates merged, removal by fingerprint.
	if r := root.post(base+"/cas", url.Values{"pem": {"not a certificate"}}); r.code != http.StatusBadRequest {
		t.Errorf("garbage CA: %d", r.code)
	}
	caPEM, fp := testCAPEM(t, "Test Root")
	root.post(base+"/cas", url.Values{"pem": {caPEM}})
	root.post(base+"/cas", url.Values{"pem": {caPEM}})
	r = root.get(base + "?tab=security")
	if strings.Count(r.body, fp) != 2 || // shown once, plus the remove form
		!strings.Contains(r.body, "平台 CA（自动下发）") {
		t.Errorf("CA list: %d", strings.Count(r.body, fp))
	}
	root.post(base+"/cas/remove", url.Values{"fingerprint": {fp}})
	if tpl, _ = h.st.ConfigTemplateByID(t.Context(), gid); tpl.CAPEMs != "" {
		t.Error("CA not removed")
	}

	// Directories: a hosted book goes out with StartTLS when the LDAP host is a name.
	book := h.book("global", nil)
	eastBook := h.book("east", &east)
	if r := root.post(base+"/directories", url.Values{"kind": {"hosted"}, "book": {itoa(eastBook.ID)}, "enabled": {"1"}, "maxResults": {"50"}}); r.code != http.StatusBadRequest {
		t.Errorf("global template with a region book: %d", r.code)
	}
	created(t, root.post(base+"/directories", url.Values{"kind": {"hosted"}, "book": {itoa(book.ID)}, "enabled": {"1"},
		"lookupIncoming": {"1"}, "maxResults": {"50"}}), base)
	dirs, _ := h.st.Directories(t.Context(), gid)
	if len(dirs) != 1 || dirs[0].Security != "START_TLS" {
		t.Fatalf("hosted directory: %+v", dirs)
	}
	// An external directory with a template password keeps it sealed.
	created(t, root.post(base+"/directories", url.Values{"kind": {"external"}, "name": {"公司"}, "host": {"ldap.example.com"},
		"port": {"389"}, "security": {"START_TLS"}, "bindDn": {"cn=reader,dc=example,dc=com"}, "baseDn": {"dc=example,dc=com"},
		"passwordMode": {"template"}, "password": {"reader-secret"}, "enabled": {"1"}, "maxResults": {"50"},
		"nameAttributes": {"cn"}, "numberAttributes": {"telephoneNumber"}}), base)
	dirs, _ = h.st.Directories(t.Context(), gid)
	if len(dirs) != 2 || dirs[1].PasswordEnc == "" || strings.Contains(dirs[1].PasswordEnc, "reader-secret") {
		t.Fatalf("external directory: %+v", dirs)
	}
	plain, err := h.srv.Ext.Box.Open(dirs[1].PasswordEnc, secret.AAD("template_directories", dirs[1].ID, "password_enc"))
	if err != nil || string(plain) != "reader-secret" {
		t.Error("sealed password does not open")
	}
	// The book is now in use by the template.
	root.post("/books/"+itoa(book.ID)+"/delete", nil)
	if _, err := h.st.BookByID(t.Context(), book.ID); err != nil {
		t.Error("book used by a template was deleted")
	}

	// The audit log names fields in Chinese and never shows the password.
	r = root.get("/audit")
	if !strings.Contains(r.body, "修改配置模板") || strings.Contains(r.body, ">refresh_minutes<") || !strings.Contains(r.body, "刷新间隔") {
		t.Error("audit labels")
	}
	if strings.Contains(r.body, "reader-secret") || strings.Contains(r.body, "long-enough-1") {
		t.Error("secret in audit log")
	}
}

func TestHostedRuleWithAddress(t *testing.T) {
	strict := newHarness(t, func(c *config.Config) { c.LDAPPublicHost = "192.0.2.10" })
	root, _ := strict.loggedIn("root", store.RoleSuper)
	book := strict.book("global", nil)
	id := created(t, root.post("/config-templates", url.Values{"name": {"t"}, "region": {"0"}}), "/config-templates/")
	base := "/config-templates/" + itoa(id)
	if r := root.get(base + "?tab=directories"); !strings.Contains(r.body, "SIPLINK_LDAP_REQUIRE_TLS=false") || strings.Contains(r.body, "kind=hosted") {
		t.Error("strict mode: no explanation or hosted link offered")
	}
	if r := root.post(base+"/directories", url.Values{"kind": {"hosted"}, "book": {itoa(book.ID)}, "enabled": {"1"}, "maxResults": {"50"}}); r.code != http.StatusBadRequest {
		t.Errorf("hosted directory accepted with an address: %d", r.code)
	}

	loose := newHarness(t, func(c *config.Config) { c.LDAPPublicHost = "192.0.2.10"; c.LDAPRequireTLS = false })
	root, _ = loose.loggedIn("root", store.RoleSuper)
	book = loose.book("global", nil)
	id = created(t, root.post("/config-templates", url.Values{"name": {"t"}, "region": {"0"}}), "/config-templates/")
	base = "/config-templates/" + itoa(id)
	created(t, root.post(base+"/directories", url.Values{"kind": {"hosted"}, "book": {itoa(book.ID)}, "enabled": {"1"}, "maxResults": {"50"}}), base)
	dirs, _ := loose.st.Directories(t.Context(), id)
	if len(dirs) != 1 || dirs[0].Security != "NONE" {
		t.Fatalf("loose mode: %+v", dirs)
	}
	if r := root.get(base + "?tab=directories"); !strings.Contains(r.body, "不加密") {
		t.Error("unencrypted hosted directory not marked")
	}
}

func TestKeyAndClientTemplates(t *testing.T) {
	h := newHarness(t)
	east := h.region("华东")
	root, _ := h.loggedIn("root", store.RoleSuper)
	cid := created(t, root.post("/config-templates", url.Values{"name": {"标准"}, "region": {"0"}}), "/config-templates/")
	created(t, root.post("/config-templates/"+itoa(cid)+"/profiles", profileValues("总部", true)), "/config-templates/")
	kid := created(t, root.post("/key-templates", url.Values{"name": {"前台"}, "region": {"0"}}), "/key-templates/")
	kb := "/key-templates/" + itoa(kid)

	// An import with one bad line writes nothing.
	r := root.post(kb+"/import", url.Values{"csv": {"保安,8001\n转财务,8002,bogus\n"}, "mode": {"append"}})
	if r.code != http.StatusBadRequest {
		t.Errorf("bad import: %d", r.code)
	}
	if keys, _ := h.st.Keys(t.Context(), kid); len(keys) != 0 {
		t.Fatal("bad import wrote keys")
	}
	created(t, root.post(kb+"/import", url.Values{"csv": {"保安,8001,speed,前台,总部\n转财务,8002,bxfer\n"}, "mode": {"append"}}), kb)
	// With a client template in place, a line that names no profile is reported by its CSV line.
	defer func() {
		r := root.post(kb+"/import", url.Values{"csv": {"甲,1\n乙,2,speed,,无此线路\n"}, "mode": {"append"}})
		if r.code != http.StatusBadRequest || !strings.Contains(r.body, `<td class="num">2</td>`) {
			t.Errorf("stray line in import: %d", r.code)
		}
	}()
	created(t, root.post(kb+"/keys", url.Values{"label": {"免打扰"}, "action": {"dnd"}}), kb)
	r = root.get(kb + "/export.csv")
	if r.code != 200 || !strings.Contains(r.body, "保安,8001,speed,前台,总部") || !strings.Contains(r.body, "免打扰") {
		t.Errorf("export: %d %q", r.code, r.body)
	}

	// A client template ties them; key lines must name profiles of the configuration.
	created(t, root.post(kb+"/keys", url.Values{"label": {"分部键"}, "number": {"9"}, "action": {"speed"}, "line": {"分部"}}), kb)
	if r := root.post("/client-templates", url.Values{"name": {"前台"}, "region": {"0"}, "config": {itoa(cid)}, "keys": {itoa(kid)}}); r.code != http.StatusBadRequest || !strings.Contains(r.body, "分部") {
		t.Errorf("client template with a stray line: %d", r.code)
	}
	keys, _ := h.st.Keys(t.Context(), kid)
	root.post(kb+"/keys/"+itoa(keys[len(keys)-1].ID)+"/delete", nil)
	ctid := created(t, root.post("/client-templates", url.Values{"name": {"前台"}, "region": {"0"}, "config": {itoa(cid)}, "keys": {itoa(kid)}}), "/client-templates/")
	// Now the line is checked when a key is added.
	if r := root.post(kb+"/keys", url.Values{"label": {"错线"}, "number": {"9"}, "action": {"speed"}, "line": {"分部"}}); r.code != http.StatusBadRequest {
		t.Errorf("key with a stray line: %d", r.code)
	}
	// Renaming the profile follows into the keys.
	ps, _ := h.st.Profiles(t.Context(), cid)
	root.post("/config-templates/"+itoa(cid)+"/profiles/"+itoa(ps[0].ID), profileValues("总部A", true))
	if keys, _ := h.st.Keys(t.Context(), kid); keys[0].Line != "总部A" {
		t.Errorf("line not renamed: %q", keys[0].Line)
	}
	// Templates in use cannot be deleted; a global client template cannot use a region one.
	root.post("/config-templates/"+itoa(cid)+"/delete", nil)
	if _, err := h.st.ConfigTemplateByID(t.Context(), cid); err != nil {
		t.Error("used configuration template deleted")
	}
	rid := created(t, root.post("/config-templates", url.Values{"name": {"华东"}, "region": {itoa(east)}}), "/config-templates/")
	if r := root.post("/client-templates/"+itoa(ctid), url.Values{"name": {"前台"}, "region": {"0"}, "config": {itoa(rid)}}); r.code != http.StatusBadRequest {
		t.Errorf("global client template uses a region configuration: %d", r.code)
	}
	if r := root.get("/client-templates"); !strings.Contains(r.body, "配置：标准") {
		t.Error("client template list")
	}
}
