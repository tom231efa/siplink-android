package web

import (
	"html"
	"net/http"
	"net/url"
	"regexp"
	"strings"
	"testing"

	"siplink/internal/catalog"
	"siplink/internal/i18n"
	"siplink/internal/store"
)

// callsForm is the form of the calls tab as the page sends it, with the room size, the
// adaptation to the network and the STUN server given.
func callsForm(room, autoNetwork, stun string) url.Values {
	f := url.Values{"tab": {"calls"}, "dndMode": {"SOFTWARE"}, "callWaiting": {"1"}, "dtmfMode": {"RFC2833"},
		"forwardNoAnswerSeconds": {"20"}, "echoCancellation": {"1"}, "ipv6": {"1"}, "roomLimit": {room}, "stunServer": {stun}}
	if autoNetwork != "" {
		f.Set("autoNetwork", autoNetwork)
	}
	return f
}

// TestRoomAndNetworkSettings: the calls tab shows the room size with the call settings and
// the adaptation to the network and the STUN server with the client and media options; it
// refuses what the app would not take as given, saves and shows the rest, audits it, and a
// phone's document carries a field only while it differs from the app's default.
func TestRoomAndNetworkSettings(t *testing.T) {
	h := newHarness(t)
	east := h.region("华东")
	f := h.fixture("标准", nil)
	root, _ := h.loggedIn("root", store.RoleSuper)
	_, token := newClient(t, root, f, "前台", east, "1001")
	code, body, _ := h.deviceCall("/p/"+token, "", "")
	if code != http.StatusOK {
		t.Fatalf("enroll: %d", code)
	}
	first := decode(t, body)
	if strings.Contains(body, "roomLimit") || strings.Contains(body, "autoNetwork") || strings.Contains(body, "stunServer") {
		t.Error("a template at the defaults sends the new fields")
	}
	base := "/config-templates/" + itoa(f.config.ID)
	page := root.get(base + "?tab=calls").body
	for _, want := range []string{"<h2>会议室</h2>", "会议室人数上限", `name="roomLimit" value="30" min="2" max="30" required`,
		"自动适应网络", `name="autoNetwork" value="1" checked`, "STUN 服务器", `name="stunServer" value=""`, `placeholder="不使用"`} {
		if !strings.Contains(page, want) {
			t.Errorf("calls tab lacks %q", want)
		}
	}
	if strings.Index(page, "会议室人数上限") > strings.Index(page, "<h2>客户端与媒体</h2>") ||
		strings.Index(page, "自动适应网络") < strings.Index(page, "<h2>客户端与媒体</h2>") {
		t.Error("the fields are not in their sections")
	}

	zh := i18n.For(i18n.Source)
	room := html.EscapeString(zh.T("provision.settings.room_limit_range", "min", 2, "max", 30))
	stun := html.EscapeString(zh.T("provision.settings.stun_invalid"))
	for _, c := range []struct {
		form url.Values
		want string
	}{
		{callsForm("1", "1", ""), room}, {callsForm("31", "1", ""), room}, {callsForm("", "1", ""), room}, {callsForm("abc", "1", ""), room},
		{callsForm("30", "1", "stun:stun.example.com"), stun}, {callsForm("30", "1", "stun.example.com:70000"), stun},
	} {
		if r := root.post(base+"/settings", c.form); r.code != http.StatusBadRequest || !strings.Contains(r.body, c.want) {
			t.Errorf("%v: %d", c.form, r.code)
		}
	}
	if tpl, _ := h.st.ConfigTemplateByID(t.Context(), f.config.ID); strings.Contains(tpl.SettingsJSON, "roomLimit") {
		t.Fatal("a refused form was saved")
	}

	created(t, root.post(base+"/settings", callsForm("8", "", " stun.example.com:3478 ")), "/config-templates/")
	tpl, _ := h.st.ConfigTemplateByID(t.Context(), f.config.ID)
	s := catalog.ParseSettings(tpl.SettingsJSON)
	if s.RoomSize() != 8 || s.AutoNetworkOn() || s.StunServer != "stun.example.com:3478" {
		t.Errorf("saved: %s", tpl.SettingsJSON)
	}
	page = root.get(base + "?tab=calls").body
	if !strings.Contains(page, `name="roomLimit" value="8"`) || !strings.Contains(page, `name="autoNetwork" value="1">`) ||
		!strings.Contains(page, `name="stunServer" value="stun.example.com:3478"`) {
		t.Error("the page does not show the saved values")
	}
	code, body, _ = h.deviceCall("/api/device/v1/sync", first.DeviceKey, first.Version)
	if doc := decode(t, body); code != http.StatusOK || doc.Settings.RoomSize() != 8 || doc.Settings.AutoNetworkOn() ||
		doc.Settings.StunServer != "stun.example.com:3478" {
		t.Errorf("document after the change: %d %+v", code, doc.Settings)
	}
	audit, _ := h.st.ListAudit(t.Context(), store.AuditFilter{AllRegions: true, Action: "config_template.update", Limit: 5})
	if len(audit) == 0 || audit[0].Detail["roomLimit"] == nil || audit[0].Detail["autoNetwork"] == nil || audit[0].Detail["stunServer"] == nil {
		t.Fatalf("audit: %+v", audit)
	}
	if d := audit[0].Detail["roomLimit"].(map[string]any); d["old"] != float64(30) || d["new"] != float64(8) {
		t.Errorf("audit of the room size: %v", d)
	}
	if page := root.get("/audit").body; !strings.Contains(page, "会议室人数上限") || !strings.Contains(page, "STUN 服务器") {
		t.Error("audit labels")
	}

	// Back at the defaults the fields leave the document, which is again the first one.
	created(t, root.post(base+"/settings", callsForm("30", "1", "")), "/config-templates/")
	if code, _, _ := h.deviceCall("/api/device/v1/sync", first.DeviceKey, first.Version); code != http.StatusNoContent {
		t.Errorf("document back at the defaults: %d, want 204", code)
	}
	if d := auditDetail(t, h); d["roomLimit"].(map[string]any)["new"] != float64(30) || d["stunServer"].(map[string]any)["new"] != "" {
		t.Errorf("audit of the return to the defaults: %v", d)
	}
}

func auditDetail(t *testing.T, h *harness) map[string]any {
	t.Helper()
	audit, _ := h.st.ListAudit(t.Context(), store.AuditFilter{AllRegions: true, Action: "config_template.update", Limit: 1})
	if len(audit) == 0 {
		t.Fatal("no audit entry")
	}
	return audit[0].Detail
}

// TestRoomAndNetworkReadOnly: a region administrator sees a global template's fields
// disabled and cannot change them.
func TestRoomAndNetworkReadOnly(t *testing.T) {
	h := newHarness(t)
	east := h.region("华东")
	f := h.fixture("标准", nil)
	local, _ := h.loggedIn("east", store.RoleRegion, east)
	base := "/config-templates/" + itoa(f.config.ID)
	page := local.get(base + "?tab=calls").body
	form := regexp.MustCompile(`(?s)<fieldset class="plain bare" disabled>.*?</fieldset>`).FindString(page)
	for _, name := range []string{`name="roomLimit"`, `name="autoNetwork"`, `name="stunServer"`} {
		if !strings.Contains(form, name) {
			t.Errorf("%s not in the disabled form", name)
		}
	}
	if r := local.post(base+"/settings", callsForm("8", "", "stun.example.com")); r.code != http.StatusForbidden {
		t.Errorf("change by a region administrator: %d", r.code)
	}
	if tpl, _ := h.st.ConfigTemplateByID(t.Context(), f.config.ID); strings.Contains(tpl.SettingsJSON, "roomLimit") {
		t.Error("a refused change was saved")
	}
}

// TestNamesKeepTheirDirection: on a right-to-left page every name the console prints in a
// table, a chip, the breadcrumb or a heading carries dir="auto" (or sits in a <bdi>), so
// that a Chinese or Latin name is laid out, cut and faded in its own direction; the fade
// follows the element's direction.
func TestNamesKeepTheirDirection(t *testing.T) {
	h := newHarness(t)
	east := h.region("华东区")
	f := h.fixture("标准模板", nil)
	root, _ := h.loggedIn("root", store.RoleSuper)
	long := "华东区总部大楼三层东侧开放办公区共享话机"
	id, _ := newClient(t, root, f, long, east, "1001")
	gid := (&groupsWorld{t: t, h: h}).group(root, "Front desk", east)
	tid := (&groupsWorld{t: t, h: h}).tag(root, "Night shift", "sand")
	root.post("/clients/bulk", bulkForm("group", []int64{id}, "group", itoa(gid)))
	root.post("/clients/bulk", bulkForm("tag_add", []int64{id}, "tag", itoa(tid)))
	names := []string{long, "华东区", "标准模板", "Front desk", "Night shift"}
	element := regexp.MustCompile(`<(span|a|h1|h2|strong|dd|td|p)\b([^>]*)>([^<]+)<`)
	seen := map[string]int{}
	for _, path := range []string{"/clients", "/clients/" + itoa(id), "/clients/" + itoa(id) + "/edit", "/groups", "/tags",
		"/client-templates", "/config-templates", "/config-templates/" + itoa(f.config.ID), "/regions", "/audit", "/"} {
		req, _ := http.NewRequest("GET", h.ts.URL+path, nil)
		req.Header.Set("Accept-Language", "ar")
		body := root.do(req).body
		if !strings.Contains(body, `dir="rtl"`) {
			t.Fatalf("%s: not a right-to-left page", path)
		}
		for _, m := range element.FindAllStringSubmatch(body, -1) {
			text := strings.TrimSpace(html.UnescapeString(m[3]))
			for _, name := range names {
				if text != name {
					continue
				}
				seen[name]++
				if !strings.Contains(m[2], `dir="auto"`) {
					t.Errorf("%s: %q printed without dir=\"auto\": <%s%s>", path, name, m[1], m[2])
				}
			}
		}
	}
	for _, name := range names {
		if seen[name] < 3 {
			t.Errorf("%q printed %d times only", name, seen[name])
		}
	}
	t.Logf("names printed with dir=\"auto\": %v", seen)
	req, _ := http.NewRequest("GET", h.ts.URL+"/clients/"+itoa(id), nil)
	req.Header.Set("Accept-Language", "ar")
	body := root.do(req).body
	for _, want := range []string{`<h1 dir="auto" class="fade">` + long + `</h1>`, `<span dir="auto" class="crumb-here fade" aria-current="page">` + long + `</span>`,
		`<bdi>华东区</bdi>`, `<span dir="auto" class="fade">Night shift</span>`} {
		if !strings.Contains(body, want) {
			t.Errorf("client page lacks %q", want)
		}
	}
	css := root.get(assetPath(t, body, "shell.css")).body
	if !strings.Contains(css, ".fade.overflowing:dir(rtl):not(.mono)") || strings.Contains(css, `[dir="rtl"] .fade`) {
		t.Error("the fade does not follow the element's direction")
	}
}

// assetPath is the versioned path of a stylesheet the page links.
func assetPath(t *testing.T, page, name string) string {
	t.Helper()
	m := regexp.MustCompile(`href="(/static/` + regexp.QuoteMeta(name) + `\?v=[^"]+)"`).FindStringSubmatch(page)
	if m == nil {
		t.Fatalf("no link to %s", name)
	}
	return html.UnescapeString(m[1])
}
