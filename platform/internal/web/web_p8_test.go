package web

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"
	"time"

	"siplink/internal/provision"
	"siplink/internal/store"
)

const deviceBody = `{"installId":"0f8c2d1e-0000-4000-8000-000000000001","have":"%s","device":{"appVersion":"0.2.0","appVersionCode":20,
"androidRelease":"16","androidSdk":36,"model":"Example Phone","applied":{"version":"%s","at":"2026-11-16T08:00:00Z","error":""},
"registrations":[{"account":"p1","state":"OK","error":""}],"codecs":{"audio":["opus/48000/2","PCMA/8000/1"],"video":["H264/90000/1"]}}}`

// deviceCall posts to the device API; key is the device key ("" = none).
func (h *harness) deviceCall(path, key, have string) (int, string, http.Header) {
	h.t.Helper()
	body := strings.ReplaceAll(strings.Replace(deviceBody, "%s", have, 1), "%s", have)
	req, _ := http.NewRequest("POST", h.ts.URL+path, strings.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	if key != "" {
		req.Header.Set("Authorization", "Bearer "+key)
	}
	r, err := h.ts.Client().Do(req)
	if err != nil {
		h.t.Fatal(err)
	}
	defer r.Body.Close()
	b, _ := io.ReadAll(r.Body)
	return r.StatusCode, string(b), r.Header
}

func decode(t *testing.T, body string) *provision.Document {
	t.Helper()
	var d provision.Document
	if err := json.Unmarshal([]byte(body), &d); err != nil {
		t.Fatalf("document: %v %q", err, body)
	}
	return &d
}

// newClient creates a client through the UI and returns its id and enrollment token.
func newClient(t *testing.T, c *client, f *fixture, name string, region int64, user string) (int64, string) {
	t.Helper()
	id := created(t, c.post("/clients", f.form(name, region, user)), "/clients/")
	m := enrollLink.FindStringSubmatch(c.get("/clients/" + itoa(id)).body)
	if m == nil {
		t.Fatal("no enrollment link")
	}
	return id, m[1]
}

func TestProvisioning(t *testing.T) {
	h := newHarness(t)
	east := h.region("华东")
	f := h.fixture("标准", nil)
	root, _ := h.loggedIn("root", store.RoleSuper)
	id, token := newClient(t, root, f, "前台", east, "1001")

	// The info page is the same for valid and invalid tokens.
	a, b := root.get("/p/"+token), root.get("/p/"+strings.Repeat("x", 43))
	if a.code != 200 || a.body != b.body || strings.Contains(a.body, "前台") {
		t.Error("info page differs or shows client data")
	}

	code, body, hdr := h.deviceCall("/p/"+token, "", "")
	if code != 200 || hdr.Get("Cache-Control") != "no-store" {
		t.Fatalf("enroll: %d %s", code, body)
	}
	doc := decode(t, body)
	key := doc.DeviceKey
	if len(key) != 43 || doc.Schema != 1 || len(doc.Version) != 64 || doc.Client.Name != "前台" || doc.Client.Region != "华东" ||
		len(doc.Accounts) != 1 || doc.Accounts[0].Password != "sip-secret-前台" || doc.Platform.SyncURL != "https://platform.example.com/api/device/v1/sync" ||
		len(doc.Directories) != 2 || doc.Directories[1].Security != "START_TLS" || !strings.Contains(doc.CACertificates[0], "BEGIN CERTIFICATE") {
		t.Fatalf("document: %+v", doc)
	}
	if code, _, _ := h.deviceCall("/p/"+token, "", ""); code != http.StatusNotFound {
		t.Errorf("second enrollment: %d", code)
	}

	// Sync: 204 when unchanged, 200 when asked without a version or after an edit.
	if code, _, _ := h.deviceCall("/api/device/v1/sync", key, doc.Version); code != http.StatusNoContent {
		t.Errorf("unchanged sync: %d", code)
	}
	if code, body, _ := h.deviceCall("/api/device/v1/sync", key, ""); code != 200 || decode(t, body).Version != doc.Version || decode(t, body).DeviceKey != "" {
		t.Errorf("sync without have: %d", code)
	}
	root.post("/config-templates/"+itoa(f.config.ID)+"/settings", url.Values{"tab": {"general"}, "keepAlive": {"1"}})
	code, body, _ = h.deviceCall("/api/device/v1/sync", key, doc.Version)
	if code != 200 || decode(t, body).Version == doc.Version {
		t.Fatalf("sync after an edit: %d", code)
	}
	doc = decode(t, body)
	if r := root.get("/clients/" + itoa(id)); !strings.Contains(r.body, "Example Phone") || !strings.Contains(r.body, "正常 1") {
		t.Error("report not on the client page")
	}

	// A document that does not validate is never sent: the last valid one is.
	p2 := itoa(f.profiles[1].ID)
	form := f.form("前台", east, "1001")
	form.Set("user_"+p2, "1001") // same user@domain on both lines: AccountRules DUPLICATE
	created(t, root.post("/clients/"+itoa(id), form), "/clients/")
	if r := root.get("/clients/" + itoa(id)); !strings.Contains(r.body, "当前配置未通过校验") {
		t.Error("problem not shown on the client page")
	}
	if code, _, _ := h.deviceCall("/api/device/v1/sync", key, doc.Version); code != http.StatusNoContent {
		t.Errorf("broken document: %d (want the last valid one, unchanged)", code)
	}
	if code, body, _ := h.deviceCall("/api/device/v1/sync", key, ""); code != 200 || decode(t, body).Version != doc.Version {
		t.Errorf("broken document without have: %d", code)
	}

	// Unbind: the old key gets 410 unbound; delete: 410 deleted; unknown: 401.
	root.post("/clients/"+itoa(id)+"/unbind", nil)
	if code, body, _ := h.deviceCall("/api/device/v1/sync", key, ""); code != http.StatusGone || !strings.Contains(body, `"reason":"unbound"`) {
		t.Errorf("unbound: %d %s", code, body)
	}
	if code, _, _ := h.deviceCall("/api/device/v1/sync", strings.Repeat("u", 43), ""); code != http.StatusUnauthorized {
		t.Errorf("unknown key: %d", code)
	}
	id2, token2 := newClient(t, root, f, "后台", east, "1002")
	_, body, _ = h.deviceCall("/p/"+token2, "", "")
	key2 := decode(t, body).DeviceKey
	root.post("/clients/"+itoa(id2)+"/delete", nil)
	if code, body, _ := h.deviceCall("/api/device/v1/sync", key2, ""); code != http.StatusGone || !strings.Contains(body, `"reason":"deleted"`) {
		t.Errorf("deleted: %d %s", code, body)
	}

	// Leave from the phone: the client is pending again with a new QR code.
	id3, token3 := newClient(t, root, f, "仓库", east, "1003")
	_, body, _ = h.deviceCall("/p/"+token3, "", "")
	key3 := decode(t, body).DeviceKey
	if code, _, _ := h.deviceCall("/api/device/v1/leave", key3, ""); code != http.StatusNoContent {
		t.Errorf("leave: %d", code)
	}
	if c, _ := h.st.ClientByID(t.Context(), id3); c.Status != store.StatusPending || c.EnrollTokenEnc == "" {
		t.Errorf("after leave: %+v", c)
	}
	if code, _, _ := h.deviceCall("/api/device/v1/sync", key3, ""); code != http.StatusGone {
		t.Errorf("sync after leave: %d", code)
	}

	// An expired token is refused like an unknown one.
	_, token4 := newClient(t, root, f, "门卫", east, "1004")
	h.clock.Advance(31 * 24 * time.Hour)
	if code, _, _ := h.deviceCall("/p/"+token4, "", ""); code != http.StatusNotFound {
		t.Errorf("expired token: %d", code)
	}

	// Nothing secret reaches the log.
	logs := h.logs.String()
	for _, s := range []string{token, token2, token3, key, key2, key3, "sip-secret", "dir-secret", "/p/" + token[:8]} {
		if strings.Contains(logs, s) {
			t.Errorf("log contains %q", s)
		}
	}
	if !strings.Contains(logs, `"route":"POST /p/{token}"`) || !strings.Contains(logs, `"route":"POST /api/device/v1/sync"`) {
		t.Error("device routes not logged by pattern")
	}
}

func TestDeviceLimitsAndHTTP(t *testing.T) {
	h := newHarness(t)
	for i := 0; i < 10; i++ {
		h.deviceCall("/p/"+strings.Repeat("z", 43), "", "")
	}
	if code, _, hdr := h.deviceCall("/p/"+strings.Repeat("z", 43), "", ""); code != http.StatusTooManyRequests || hdr.Get("Retry-After") == "" {
		t.Errorf("enroll limit: %d", code)
	}
	plain := httptest.NewServer(h.srv.Handler())
	defer plain.Close()
	r, err := http.Post(plain.URL+"/api/device/v1/sync", "application/json", strings.NewReader("{}"))
	if err != nil {
		t.Fatal(err)
	}
	r.Body.Close()
	if r.StatusCode != http.StatusForbidden {
		t.Errorf("plain HTTP: %d", r.StatusCode)
	}
}
