package web

import (
	"net/http"
	"net/url"
	"regexp"
	"strings"
	"testing"

	"siplink/internal/store"
)

// templateSelect returns the options of the client form's template drop-down as value→selected.
func templateSelect(t *testing.T, body string) map[string]bool {
	t.Helper()
	m := regexp.MustCompile(`(?s)<select name="template"[^>]*>(.*?)</select>`).FindStringSubmatch(body)
	if m == nil {
		t.Fatal("no template drop-down")
	}
	out := map[string]bool{}
	for _, o := range regexp.MustCompile(`<option value="(\d+)"( selected)?>`).FindAllStringSubmatch(m[1], -1) {
		out[o[1]] = o[2] != ""
	}
	return out
}

// The template drop-down of the client form lists what fits the selected region from the
// first load on, and always shows the template the form below is for.
func TestClientFormRegionAndTemplateAgree(t *testing.T) {
	h := newHarness(t)
	north, east := h.region("东北"), h.region("华东")
	fEast := h.fixture("华东前台", &east)
	fGlobal := h.fixture("通用", nil)
	root, _ := h.loggedIn("root", store.RoleSuper)
	eastTpl, globalTpl := itoa(fEast.tpl.ID), itoa(fGlobal.tpl.ID)

	// first load: a concrete region is chosen and only its templates are listed
	r := root.get("/clients/new")
	opts := templateSelect(t, r.body)
	sel := regexp.MustCompile(`<option value="(\d+)" selected>`).FindStringSubmatch(r.body)
	if sel == nil {
		t.Fatal("first load: no region selected")
	}
	if _, listed := opts[eastTpl]; listed != (sel[1] == itoa(east)) || !strings.Contains(r.body, `value="`+globalTpl+`"`) {
		t.Errorf("first load: region %s lists %v", sel[1], opts)
	}

	// a template of another region is not used
	r = root.get("/clients/new?region=" + itoa(north) + "&template=" + eastTpl)
	opts = templateSelect(t, r.body)
	if _, listed := opts[eastTpl]; listed || strings.Contains(r.body, `name="user_`) || strings.Contains(r.body, `type="hidden" name="template"`) {
		t.Errorf("mismatching template used: %v", opts)
	}
	for v, selected := range opts {
		if selected {
			t.Errorf("option %s selected for a dropped template", v)
		}
	}

	// a fitting template is selected and the form is for it
	r = root.get("/clients/new?region=" + itoa(east) + "&template=" + eastTpl)
	if !templateSelect(t, r.body)[eastTpl] || !strings.Contains(r.body, `name="region" value="`+itoa(east)+`"`) ||
		!strings.Contains(r.body, `name="user_`) {
		t.Error("fitting template not shown with its form")
	}

	// the region filter of the top bar is the default region
	root.post("/filter", url.Values{"region": {itoa(east)}, "next": {"/clients/new"}})
	if r = root.get("/clients/new"); !strings.Contains(r.body, `<option value="`+itoa(east)+`" selected>`) {
		t.Error("region filter not used as the default region")
	}
	if _, listed := templateSelect(t, r.body)[eastTpl]; !listed {
		t.Error("templates of the filtered region not listed")
	}

	// editing: a region where the client's template does not fit drops the form
	id, _ := newClient(t, root, fEast, "前台", east, "1001")
	if r = root.get("/clients/" + itoa(id) + "/edit"); !templateSelect(t, r.body)[eastTpl] {
		t.Error("edit: own template not selected")
	}
	if r = root.get("/clients/" + itoa(id) + "/edit?region=" + itoa(north) + "&template=" + eastTpl); strings.Contains(r.body, `name="user_`) {
		t.Error("edit: form shown for a template that does not fit the region")
	}
}

// The switch "密码强度检测" (super administrators): on by default; off, a new own
// password only has to be non-empty.
func TestPasswordCheckSwitch(t *testing.T) {
	h := newHarness(t)
	east := h.region("华东")
	root, _ := h.loggedIn("root", store.RoleSuper)
	local, _ := h.loggedIn("east", store.RoleRegion, east)
	change := func(c *client, old, pw string) resp {
		return c.post("/me", url.Values{"current": {old}, "password": {pw}, "password2": {pw}})
	}

	if r := root.get("/system"); !strings.Contains(r.body, `name="check" value="1" form="f-password-check" data-autosubmit checked`) {
		t.Error("strength check not on by default")
	}
	if r := local.get("/me"); !strings.Contains(r.body, "至少 10 个字符") || !strings.Contains(r.body, `minlength="10"`) {
		t.Error("me page: length hint missing while the check is on")
	}
	if r := change(local, "password-east", "short"); r.code != http.StatusBadRequest || !strings.Contains(r.body, "密码至少为 10 个字符") {
		t.Errorf("short password with the check on: %d", r.code)
	}

	if r := local.post("/system/password-check", nil); r.code != http.StatusNotFound {
		t.Errorf("region admin switches the check: %d", r.code)
	}
	if r := root.post("/system/password-check", nil); r.code != http.StatusSeeOther {
		t.Fatalf("switch off: %d", r.code)
	}
	if r := root.get("/system"); strings.Contains(r.body, `name="check" value="1" form="f-password-check" data-autosubmit checked`) {
		t.Error("switch still on")
	}
	if r := local.get("/me"); strings.Contains(r.body, "至少 10 个字符") || strings.Contains(r.body, `minlength="10"`) {
		t.Error("me page: length hint shown while the check is off")
	}
	if r := change(local, "password-east", ""); r.code != http.StatusBadRequest || !strings.Contains(r.body, "请输入新密码") {
		t.Errorf("empty password with the check off: %d", r.code)
	}
	if r := change(local, "password-east", strings.Repeat("x", 257)); r.code != http.StatusBadRequest {
		t.Errorf("257 characters with the check off: %d", r.code)
	}
	if r := change(local, "password-east", "east"); r.code != http.StatusSeeOther {
		t.Errorf("short password equal to the user name with the check off: %d %s", r.code, r.body)
	}

	root.post("/system/password-check", url.Values{"check": {"1"}})
	root.post("/system/password-check", url.Values{"check": {"1"}}) // unchanged: no audit entry
	if acts := auditActions(t, h); acts["password.policy"] != 2 {
		t.Errorf("audit entries: %d", acts["password.policy"])
	}
	if r := change(local, "east", "short2"); r.code != http.StatusBadRequest {
		t.Errorf("short password after switching the check on again: %d", r.code)
	}
}

// The certificate card shows one source at a time, opening on the one in use.
func TestCertificateViews(t *testing.T) {
	h := newHarness(t)
	root, _ := h.loggedIn("root", store.RoleSuper)
	upload := `action="/system/certificate" enctype`
	ca := h.srv.Ext.Certs.CA().CA().Subject.String()

	page := root.get("/system").body
	if !strings.Contains(page, ca) || !strings.Contains(page, "/system/reissue") || !strings.Contains(page, "/system/ca.pem") ||
		strings.Contains(page, upload) || !strings.Contains(page, `value="builtin" aria-pressed="true"`) {
		t.Error("built-in view not shown first")
	}
	page = root.get("/system?cert=own").body
	if strings.Contains(page, ca) || strings.Contains(page, "/system/reissue") || !strings.Contains(page, upload) ||
		strings.Contains(page, "改用内置 CA") || strings.Contains(page, `class="card-body cert-view"`) || !strings.Contains(page, "HTTPS") {
		t.Error("own view without a certificate")
	}
	if h.srv.Ext.Certs.Uploaded() != nil || !strings.Contains(page, "内置 CA 自动签发") {
		t.Error("looking at the own view changed the source")
	}

	chain, key, _ := h.privateChain("platform.example.com")
	if r := root.uploadCert(chain, key); r.code != http.StatusSeeOther {
		t.Fatalf("upload: %d", r.code)
	}
	page = root.get("/system").body
	if !strings.Contains(page, `value="own" aria-pressed="true"`) || !strings.Contains(page, "改用内置 CA") ||
		!strings.Contains(page, upload) || strings.Contains(page, ca) {
		t.Error("own view not shown first while the own certificate is in use")
	}
	if page = root.get("/system?cert=builtin").body; !strings.Contains(page, ca) || strings.Contains(page, "改用内置 CA") ||
		h.srv.Ext.Certs.Uploaded() == nil {
		t.Error("built-in view while the own certificate is in use")
	}

	// 改用内置 CA: no bound phone, so no second question
	if r := root.post("/system/certificate/remove", nil); r.code != http.StatusSeeOther || h.srv.Ext.Certs.Uploaded() != nil {
		t.Fatalf("use built-in: %d", r.code)
	}
	if acts := auditActions(t, h); acts["pki.own_remove"] != 1 {
		t.Error("use built-in not audited")
	}
	if page = root.get("/system").body; !strings.Contains(page, `value="builtin" aria-pressed="true"`) {
		t.Error("built-in view not shown first after returning")
	}
}

// Hints of a field are a "?" tooltip beside its name, not a placeholder.
func TestFieldTips(t *testing.T) {
	h := newHarness(t)
	root, _ := h.loggedIn("root", store.RoleSuper)
	b := h.book("staff", nil)
	r := root.get("/books/" + itoa(b.ID) + "/entries/new")
	input := regexp.MustCompile(`<input[^>]*name="mobiles"[^>]*>`).FindString(r.body)
	if input == "" || strings.Contains(input, "placeholder") || !strings.Contains(input, `aria-describedby="f-mobiles-tip"`) {
		t.Errorf("mobiles input: %s", input)
	}
	if !strings.Contains(r.body, `<button type="button" class="help-tip" aria-label="说明" aria-describedby="f-mobiles-tip">`) ||
		!strings.Contains(r.body, `role="tooltip" id="f-mobiles-tip">以 ; 分隔，最多 4 个</span>`) ||
		!strings.Contains(r.body, `<label for="f-mobiles">手机</label>`) {
		t.Error("mobiles tip missing")
	}
}

// Mode choices inside form rows are drop-downs with the names the handlers read, and the
// 管理 group of the navigation reads 区域, 管理员, 邮件, 审计日志, 系统.
func TestSelectsAndNavigationOrder(t *testing.T) {
	h := newHarness(t)
	root, _ := h.loggedIn("root", store.RoleSuper)
	f := h.fixture("标准", nil)
	cfg := itoa(f.config.ID)
	for path, names := range map[string][]string{
		"/mail": {"security"}, "/directories/" + itoa(*f.dir.ExternalID): {"security", "passwordMode"},
		"/config-templates/" + cfg + "/directories/" + itoa(f.hosted.ID):   {"passwordMode"},
		"/config-templates/" + cfg + "/profiles/" + itoa(f.profiles[0].ID): {"transport"},
	} {
		body := root.get(path).body
		for _, n := range names {
			if !strings.Contains(body, `<select name="`+n+`">`) || strings.Contains(body, `type="radio" name="`+n+`"`) {
				t.Errorf("%s: %s is not a drop-down", path, n)
			}
		}
	}
	nav := root.get("/").body
	last := -1
	for _, href := range []string{`href="/regions"`, `href="/admins"`, `href="/mail"`, `href="/audit"`, `href="/system"`} {
		i := strings.Index(nav, href)
		if i < last {
			t.Errorf("navigation order at %s", href)
		}
		last = i
	}
}
