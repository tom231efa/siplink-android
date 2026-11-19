package web

import (
	"net/http"
	"net/url"
	"regexp"
	"strings"
	"testing"
	"testing/fstest"
	"time"

	"siplink/internal/catalog"
	"siplink/internal/secret"
	"siplink/internal/store"
)

const wantCSP = "default-src 'self'; img-src 'self' data:; style-src 'self'; script-src 'self'; frame-ancestors 'none'; form-action 'self'; base-uri 'none'"

// TestStrictPages: the CSP is unchanged and no page carries inline styles or scripts
// the CSP would block.
func TestStrictPages(t *testing.T) {
	h := newHarness(t)
	east := h.region("华东")
	f := h.fixture("标准", &east)
	root, _ := h.loggedIn("root", store.RoleSuper)
	id, _ := newClient(t, root, f, "前台", east, "1001")
	cfg := itoa(f.config.ID)
	inline := regexp.MustCompile(`\sstyle="|<style|<script>|\son[a-z]+="`)
	for _, path := range []string{"/", "/login", "/clients", "/clients/" + itoa(id), "/clients/print?ids=" + itoa(id), "/system", "/audit",
		"/config-templates/" + cfg + "?tab=calls", "/config-templates/" + cfg + "?tab=media", "/clients/import"} {
		r := root.get(path)
		if r.header.Get("Content-Security-Policy") != wantCSP {
			t.Errorf("%s: CSP %q", path, r.header.Get("Content-Security-Policy"))
		}
		if m := inline.FindString(r.body); m != "" {
			t.Errorf("%s: inline %q", path, m)
		}
	}
}

// TestStaticVersions: pages load CSS and JS through URLs that change with their
// content; those are cached for good, anything else is revalidated.
func TestStaticVersions(t *testing.T) {
	h := newHarness(t)
	anon := h.client()
	page := anon.get("/login").body
	for _, name := range []string{"app.css", "app.js"} {
		v := assetVersions[name]
		if len(v) != 12 {
			t.Fatalf("%s: version %q", name, v)
		}
		u := "/static/" + name + "?v=" + v
		if !strings.Contains(page, `"`+u+`"`) {
			t.Errorf("layout does not reference %s", u)
		}
		if r := anon.get(u); r.code != 200 || r.header.Get("Cache-Control") != "public, max-age=31536000, immutable" ||
			r.header.Get("ETag") != `"`+v+`"` {
			t.Errorf("%s: %d %q %q", u, r.code, r.header.Get("Cache-Control"), r.header.Get("ETag"))
		}
		for _, other := range []string{"/static/" + name, "/static/" + name + "?v=000000000000"} {
			if r := anon.get(other); r.code != 200 || r.header.Get("Cache-Control") != "no-cache" {
				t.Errorf("%s: %d %q", other, r.code, r.header.Get("Cache-Control"))
			}
		}
		req, _ := http.NewRequest("GET", h.ts.URL+"/static/"+name, nil)
		req.Header.Set("If-None-Match", `"`+v+`"`)
		if r := anon.do(req); r.code != http.StatusNotModified {
			t.Errorf("%s: revalidation answered %d", name, r.code)
		}
	}
	if strings.Contains(page, `"/static/app.css"`) || strings.Contains(page, `"/static/app.js"`) {
		t.Error("unversioned asset URL in the layout")
	}
	a := hashAssets(fstest.MapFS{"static/app.js": {Data: []byte("one")}})
	b := hashAssets(fstest.MapFS{"static/app.js": {Data: []byte("two")}})
	if a["app.js"] == "" || a["app.js"] == b["app.js"] {
		t.Errorf("version does not follow the content: %q %q", a["app.js"], b["app.js"])
	}
}

// TestTimesAndVideoLabels: times are <time> tags naming their zone; video sizes read
// like the app's (240p … 1080p) while the values stay.
func TestTimesAndVideoLabels(t *testing.T) {
	h := newHarness(t)
	root, _ := h.loggedIn("root", store.RoleSuper)
	r := root.get("/audit")
	tag := regexp.MustCompile(`<time dir="ltr" datetime="\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ" data-local="datetime">\d{4}-\d\d-\d\d \d\d:\d\d UTC(?:[+-]\d\d:\d\d)?</time>`)
	if !tag.MatchString(r.body) {
		t.Errorf("audit times are not <time> tags")
	}
	if r := root.get("/system"); !strings.Contains(r.body, `data-local="date"`) {
		t.Error("certificate validity is not a <time> tag")
	}
	id := created(t, root.post("/config-templates", url.Values{"name": {"v"}, "region": {"0"}}), "/config-templates/")
	r = root.get("/config-templates/" + itoa(id) + "?tab=media")
	for _, want := range []string{`value="QVGA">240p<`, `value="VGA" selected>480p<`, `value="HD">720p<`, `value="FHD">1080p<`} {
		if !strings.Contains(r.body, want) {
			t.Errorf("video label %s missing", want)
		}
	}
	if strings.Contains(r.body, "640×480") {
		t.Error("old video label still shown")
	}
}

// TestAuditDaysInViewerZone: the days typed into the audit filter are the viewer's.
func TestAuditDaysInViewerZone(t *testing.T) {
	h := newHarness(t)
	root, _ := h.loggedIn("root", store.RoleSuper)
	// An entry at 2026-11-16 20:00 in UTC+8 is 12:00 UTC.
	start := h.clock.T
	defer func() { h.clock.T = start }()
	h.clock.T = time.Date(2026, 11, 16, 12, 0, 0, 0, time.UTC) // the store stamps entries with its clock
	if err := h.st.AddAudit(t.Context(), &store.AuditEntry{AdminName: "x", Action: "region.create",
		ObjectType: "region", ObjectName: "时区测试"}); err != nil {
		t.Fatal(err)
	}
	h.clock.T = start // the session lives on the real clock
	has := func(q string) bool { return strings.Contains(root.get("/audit?"+q).body, "时区测试") }
	if !has("from=2026-11-16&to=2026-11-16&tz=Asia/Shanghai") || has("from=2026-11-17&to=2026-11-17&tz=Asia/Shanghai") {
		t.Error("days in Asia/Shanghai")
	}
	// A late entry: 2026-11-16 23:30 UTC is already 11-17 in Shanghai.
	h.clock.T = time.Date(2026, 11, 16, 23, 30, 0, 0, time.UTC)
	h.st.AddAudit(t.Context(), &store.AuditEntry{AdminName: "x", Action: "region.create",
		ObjectType: "region", ObjectName: "深夜条目"})
	h.clock.T = start
	sh := root.get("/audit?from=2026-11-17&to=2026-11-17&tz=Asia/Shanghai").body
	utc := root.get("/audit?from=2026-11-17&to=2026-11-17&tz=UTC").body
	if !strings.Contains(sh, "深夜条目") || strings.Contains(utc, "深夜条目") {
		t.Error("day boundary does not follow the viewer's zone")
	}
	if r := root.get("/audit?from=2026-11-17&tz=../../etc/passwd"); r.code != 200 {
		t.Errorf("bad zone: %d", r.code)
	}
	if !strings.Contains(root.get("/audit").body, `name="tz" value="" data-tz`) {
		t.Error("hidden tz field missing")
	}
}

func TestAdvancedSettings(t *testing.T) {
	h := newHarness(t)
	root, _ := h.loggedIn("root", store.RoleSuper)
	id := created(t, root.post("/config-templates", url.Values{"name": {"s"}, "region": {"0"}}), "/config-templates/")
	base := "/config-templates/" + itoa(id)
	r := root.get(base + "?tab=calls")
	for _, want := range []string{">高级设置<", "<h2>客户端与媒体</h2>", "客户端名称（User-Agent）", `placeholder="应用默认名称"`, "自适应速率控制",
		"回声消除", "允许 IPv6"} {
		if !strings.Contains(r.body, want) {
			t.Errorf("calls tab lacks %q", want)
		}
	}
	form := url.Values{"tab": {"calls"}, "dndMode": {"SOFTWARE"}, "dtmfMode": {"RFC2833"}, "forwardNoAnswerSeconds": {"20"},
		"userAgent": {" Acme/1.0"}, "echoCancellation": {"1"}, "ipv6": {"1"}}
	if r := root.post(base+"/settings", form); r.code != http.StatusBadRequest || !strings.Contains(r.body, "首尾不得包含空格") {
		t.Errorf("leading space accepted: %d", r.code)
	}
	form.Set("userAgent", "Acme/1.0")
	form.Del("ipv6")
	form.Set("adaptiveRateControl", "1")
	created(t, root.post(base+"/settings", form), "/config-templates/")
	tpl, _ := h.st.ConfigTemplateByID(t.Context(), id)
	s := catalog.ParseSettings(tpl.SettingsJSON)
	if s.UserAgent != "Acme/1.0" || !s.AdaptiveRateControl || !s.EchoCancellation || s.IPv6 {
		t.Errorf("settings: %+v", s)
	}
	if r := root.get("/audit"); !strings.Contains(r.body, "允许 IPv6") {
		t.Error("audit label of ipv6")
	}
}

func TestHostedManualCredentials(t *testing.T) {
	h := newHarness(t)
	east, west := h.region("华东"), h.region("华西")
	root, _ := h.loggedIn("root", store.RoleSuper)
	book := h.book("staff", &east)
	pw := secret.RandomText(32) // made at test time
	grant := &store.LDAPAccount{Name: "desk", RegionID: &east, PasswordHash: secret.HashHex(pw), BookIDs: []int64{book.ID}}
	nogrant := &store.LDAPAccount{Name: "other", RegionID: &east, PasswordHash: secret.HashHex(pw)}
	foreign := &store.LDAPAccount{Name: "west", RegionID: &west, PasswordHash: secret.HashHex(pw), BookIDs: []int64{book.ID}}
	for _, a := range []*store.LDAPAccount{grant, nogrant, foreign} {
		if err := h.st.CreateLDAPAccount(t.Context(), a); err != nil {
			t.Fatal(err)
		}
	}
	id := created(t, root.post("/config-templates", url.Values{"name": {"m"}, "region": {itoa(east)}}), "/config-templates/")
	base := "/config-templates/" + itoa(id)
	if r := root.get(base + "/directories/new?source=hosted:" + itoa(book.ID)); !strings.Contains(r.body, "<strong>自动</strong>") ||
		!strings.Contains(r.body, "手动指定") || strings.Contains(r.body, ">west") ||
		!strings.Contains(r.body, `<fieldset class="plain manual" data-manual>`) {
		t.Error("hosted form: credentials choice or account list")
	}
	post := func(account *store.LDAPAccount, mode, password string) resp {
		return root.post(base+"/directories", url.Values{"source": {"hosted:" + itoa(book.ID)}, "enabled": {"1"},
			"maxResults": {"50"}, "credentials": {"manual"}, "account": {itoa(account.ID)}, "passwordMode": {mode}, "password": {password}})
	}
	for name, r := range map[string]resp{
		"no grant":       post(nogrant, "template", pw),
		"other region":   post(foreign, "template", pw),
		"wrong password": post(grant, "template", "not-the-password"),
		"no password":    post(grant, "template", ""),
	} {
		if r.code != http.StatusBadRequest {
			t.Errorf("%s: %d", name, r.code)
		}
	}
	if r := post(nogrant, "template", pw); !strings.Contains(r.body, "无权读取通讯录") {
		t.Error("reason for a missing grant not shown")
	}
	created(t, post(grant, "client", ""), base)
	dirs, _ := h.st.Directories(t.Context(), id)
	if len(dirs) != 1 || dirs[0].LDAPAccountID == nil || *dirs[0].LDAPAccountID != grant.ID || dirs[0].PasswordMode != "client" {
		t.Fatalf("stored: %+v", dirs)
	}
	// The client form asks for the password, checks it against the account, and the
	// document carries the account's DN.
	p := &store.Profile{TemplateID: id, Name: "总部", Domain: "pbx.example.com", Transport: "UDP", Expires: 3600, Enabled: true}
	h.st.SaveProfile(t.Context(), p, 100)
	tpl := &store.ClientTemplate{Name: "m", ConfigTemplateID: id, RegionID: &east}
	h.st.CreateClientTemplate(t.Context(), tpl)
	form := url.Values{"name": {"前台"}, "region": {itoa(east)}, "template": {itoa(tpl.ID)}, "user_" + itoa(p.ID): {"1001"},
		"dir_" + itoa(dirs[0].ID): {"wrong"}}
	if r := root.post("/clients", form); r.code != http.StatusBadRequest || !strings.Contains(r.body, "不符") {
		t.Errorf("wrong client password: %d", r.code)
	}
	form.Set("dir_"+itoa(dirs[0].ID), pw)
	cid := created(t, root.post("/clients", form), "/clients/")
	c, _ := h.st.ClientByID(t.Context(), cid)
	b, err := h.srv.buildDocument(&http.Request{}, c)
	if err != nil || len(b.problems) > 0 || b.doc.Directories[0].BindDn != "cn=desk,ou=accounts,dc=siplink" || b.doc.Directories[0].Password != pw {
		t.Errorf("document: %v %v", err, b.problems)
	}
	// The account cannot be deleted while the template uses it.
	r := root.post("/ldap-accounts/"+itoa(grant.ID)+"/delete", nil)
	if _, err := h.st.LDAPAccountByID(t.Context(), grant.ID); err != nil || r.code != http.StatusSeeOther {
		t.Errorf("account in use deleted: %d", r.code)
	}
	// Auto stays the default and switching back clears the account.
	created(t, root.post(base+"/directories/"+itoa(dirs[0].ID), url.Values{"enabled": {"1"},
		"maxResults": {"50"}, "credentials": {"auto"}}), base)
	if d, _ := h.st.DirectoryByID(t.Context(), id, dirs[0].ID); d.LDAPAccountID != nil || d.PasswordMode != "none" {
		t.Errorf("back to auto: %+v", d)
	}
}

// TestRecordingSettings: "默认录音" only counts while "通话录音" is on; both are audited.
func TestRecordingSettings(t *testing.T) {
	h := newHarness(t)
	root, _ := h.loggedIn("root", store.RoleSuper)
	id := created(t, root.post("/config-templates", url.Values{"name": {"录音"}, "region": {"0"}}), "/config-templates/")
	base := "/config-templates/" + itoa(id)
	if s := catalog.ParseSettings(""); !s.CallRecording || s.RecordByDefault {
		t.Fatalf("defaults: %+v", s)
	}
	media := url.Values{"tab": {"media"}, "videoSize": {"VGA"}, "videoFrameRate": {"0"}, "codecMode": {"default"}}
	post := func(v url.Values) (bool, bool) {
		t.Helper()
		if r := root.post(base+"/settings", v); r.code != http.StatusSeeOther {
			t.Fatalf("media: %d %s", r.code, r.body)
		}
		tpl, _ := h.st.ConfigTemplateByID(t.Context(), id)
		s := catalog.ParseSettings(tpl.SettingsJSON)
		return s.CallRecording, s.RecordByDefault
	}
	on := url.Values{"callRecording": {"1"}, "recordByDefault": {"1"}}
	for k, v := range media {
		on[k] = v
	}
	if rec, def := post(on); !rec || !def {
		t.Errorf("both on: %v %v", rec, def)
	}
	off := url.Values{"recordByDefault": {"1"}}
	for k, v := range media {
		off[k] = v
	}
	if rec, def := post(off); rec || def {
		t.Errorf("recording off keeps record-by-default: %v %v", rec, def)
	}
	page := root.get(base + "?tab=media").body
	if !strings.Contains(page, `name="recordByDefault" value="1" data-requires="callRecording" disabled`) {
		t.Error("record-by-default switch not disabled while recording is off")
	}
	if a := root.get("/audit").body; !strings.Contains(a, "通话录音") || !strings.Contains(a, "默认录音") {
		t.Error("recording changes not labelled in the audit list")
	}
}

// TestAutoAnswerSettings: auto answer of regular calls; its wait is one of the offered values,
// is kept while the switch is off, and both are audited.
func TestAutoAnswerSettings(t *testing.T) {
	h := newHarness(t)
	root, _ := h.loggedIn("root", store.RoleSuper)
	id := created(t, root.post("/config-templates", url.Values{"name": {"接听"}, "region": {"0"}}), "/config-templates/")
	base := "/config-templates/" + itoa(id)
	stored := func() (bool, int) {
		tpl, _ := h.st.ConfigTemplateByID(t.Context(), id)
		s := catalog.ParseSettings(tpl.SettingsJSON)
		return s.AutoAnswer, s.AutoAnswerSeconds
	}
	if on, sec := stored(); on || sec != 3 {
		t.Fatalf("defaults: %v %d", on, sec)
	}
	page := root.get(base + "?tab=general").body
	if !strings.Contains(page, `name="autoAnswerSeconds" class="compact" data-requires="autoAnswer" disabled`) ||
		!strings.Contains(page, `<option value="0">立即</option>`) || !strings.Contains(page, `<option value="3" selected>3 秒</option>`) {
		t.Error("wait select missing, enabled while auto answer is off, or not on the default")
	}
	if r := root.post(base+"/settings", url.Values{"tab": {"general"}, "autoAnswer": {"1"}, "autoAnswerSeconds": {"10"}}); r.code != http.StatusSeeOther {
		t.Fatalf("on: %d %s", r.code, r.body)
	}
	if on, sec := stored(); !on || sec != 10 {
		t.Errorf("on: %v %d", on, sec)
	}
	if r := root.post(base+"/settings", url.Values{"tab": {"general"}}); r.code != http.StatusSeeOther {
		t.Fatalf("off: %d %s", r.code, r.body)
	}
	if on, sec := stored(); on || sec != 10 {
		t.Errorf("off does not keep the wait: %v %d", on, sec)
	}
	if !strings.Contains(root.get(base+"?tab=general").body, `<option value="10" selected>10 秒</option>`) {
		t.Error("kept wait not shown")
	}
	for _, bad := range []string{"7", "-1", "abc", ""} {
		r := root.post(base+"/settings", url.Values{"tab": {"general"}, "autoAnswer": {"1"}, "autoAnswerSeconds": {bad}})
		if r.code != http.StatusBadRequest || !strings.Contains(r.body, "自动接听时间无效") {
			t.Errorf("wait %q: %d", bad, r.code)
		}
	}
	if on, sec := stored(); on || sec != 10 {
		t.Errorf("refused input stored: %v %d", on, sec)
	}
	if a := root.get("/audit").body; !strings.Contains(a, "自动接听") || !strings.Contains(a, "自动接听时间") {
		t.Error("auto answer changes not labelled in the audit list")
	}
}
