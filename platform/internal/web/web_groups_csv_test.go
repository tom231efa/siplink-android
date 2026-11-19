package web

import (
	"encoding/csv"
	"net/http"
	"net/url"
	"slices"
	"strings"
	"testing"
	"time"

	"siplink/internal/catalog"
	"siplink/internal/i18n"
	"siplink/internal/store"
	"siplink/internal/testutil"
)

// csvLine writes one CSV line with the values of a header's columns ("" for the others).
func csvLine(header []string, values map[string]string) string {
	var b strings.Builder
	w := csv.NewWriter(&b)
	row := make([]string, len(header))
	for i, col := range header {
		row[i] = values[col]
	}
	w.Write(row)
	w.Flush()
	return b.String()
}

// TestClientCSVGroupsTags: the header download has the columns of group and tags in the
// page's language; a file written under it imports group (by name, ignoring case, of the
// client's region) and tags (";" or "；", known ones reused ignoring case, new ones made
// with the default colour), and the dry run refuses what does not fit, line by line.
func TestClientCSVGroupsTags(t *testing.T) {
	h := newHarness(t)
	ctx := t.Context()
	east, west := h.region("East"), h.region("West")
	f := h.fixture("Standard", nil)
	root, _ := h.loggedIn("root", store.RoleSuper)
	w := &groupsWorld{t: t, h: h, east: east, west: west, f: f, root: root}
	front := w.group(root, "Front", east)
	w.group(root, "Depot", west)
	vip := w.tag(root, "VIP", "sand")

	headerOf := func(lang string) []string {
		req, _ := http.NewRequest("GET", h.ts.URL+"/clients/import/template.csv?template="+itoa(f.tpl.ID), nil)
		req.Header.Set("Accept-Language", lang)
		r := root.do(req)
		cols, err := csv.NewReader(strings.NewReader(strings.TrimPrefix(r.body, "\xef\xbb\xbf"))).Read()
		if r.code != http.StatusOK || err != nil {
			t.Fatalf("%s header: %d %v", lang, r.code, err)
		}
		return cols
	}
	confirm := func(lang, data string) {
		t.Helper()
		r := uploadCSV(root, lang, data)
		key := importKey.FindStringSubmatch(r.body)
		if key == nil {
			t.Fatalf("%s dry run: %d %s", lang, r.code, r.body)
		}
		if r := root.post("/clients/import/confirm", url.Values{"key": {key[1]}}); r.code != http.StatusSeeOther {
			t.Fatalf("%s confirm: %d", lang, r.code)
		}
	}
	clientNamed := func(name string) *store.Client {
		t.Helper()
		list, _, _ := h.st.ListClients(ctx, store.ClientFilter{All: true, Query: name})
		if len(list) != 1 {
			t.Fatalf("client %s: %d found", name, len(list))
		}
		return list[0]
	}
	tagNamesOf := func(id int64) []string {
		all, _ := h.st.ListTags(ctx)
		return tagNames(tagsByID(all), w.tagsOf(id))
	}

	// Chinese: the header names 分组 and 标签.
	zh := headerOf("zh-CN")
	if !slices.Contains(zh, "分组") || !slices.Contains(zh, "标签") {
		t.Fatalf("Chinese header: %v", zh)
	}
	confirm("zh-CN", strings.Join(zh, ",")+"\n"+csvLine(zh, map[string]string{"名称": "甲", "区域": "East", "客户端模板": "Standard",
		"分组": "front", "标签": "vip；新标签; Lobby ;VIP", "总部用户名": "2001", "总部密码": "pw"}))
	a := clientNamed("甲")
	if a.GroupID == nil || *a.GroupID != front || !slices.Equal(tagNamesOf(a.ID), []string{"Lobby", "VIP", "新标签"}) {
		t.Fatalf("imported from Chinese: group %v tags %v", a.GroupID, tagNamesOf(a.ID))
	}
	all, _ := h.st.ListTags(ctx)
	for _, tg := range all {
		if want := map[bool]string{true: "sand", false: catalog.TagColors[0]}[tg.ID == vip]; tg.Color != want {
			t.Errorf("colour of %s: %s, want %s", tg.Name, tg.Color, want)
		}
	}
	if len(all) != 3 {
		t.Errorf("tags after the import: %d", len(all))
	}

	// English: group and tags, in any case.
	en := headerOf("en")
	if !slices.Contains(en, "group") || !slices.Contains(en, "tags") {
		t.Fatalf("English header: %v", en)
	}
	head := strings.Join(en, ",")
	head = strings.Replace(strings.Replace(head, "group", "Group", 1), "tags", "TAGS", 1)
	confirm("en", head+"\n"+csvLine(en, map[string]string{"name": "Beta", "region": "East", "client_template": "Standard",
		"group": "FRONT", "tags": "lobby;Night", "总部 username": "2002", "总部 password": "pw"}))
	b := clientNamed("Beta")
	if b.GroupID == nil || *b.GroupID != front || !slices.Equal(tagNamesOf(b.ID), []string{"Lobby", "Night"}) {
		t.Fatalf("imported from English: group %v tags %v", b.GroupID, tagNamesOf(b.ID))
	}
	// An old file without the columns imports as before: no group, no tags.
	confirm("en", "name,region,client_template\nGamma,East,Standard\n")
	if c := clientNamed("Gamma"); c.GroupID != nil || len(w.tagsOf(c.ID)) != 0 {
		t.Error("old file: group or tags")
	}

	// The audit entry of an imported client names its group and tags.
	var detail map[string]any
	for _, e := range auditWith(t, h, "client.create", "group") {
		if e.ObjectName == "Beta" {
			detail = e.Detail
		}
	}
	if detail == nil || detail["group"] != "Front" || !slices.Equal(anyStrings(detail["tags"]), []string{"Lobby", "Night"}) {
		t.Errorf("audit of an imported client: %+v", detail)
	}

	// A group deleted between the dry run and the confirmation voids the dry run: nothing
	// is written, not even its new tags.
	late := w.group(root, "Late", east)
	r := uploadCSV(root, "en", "name,region,client_template,group,tags\nEpsilon,East,Standard,Late,Brand-new\n")
	key := importKey.FindStringSubmatch(r.body)
	if key == nil {
		t.Fatalf("dry run with the group Late: %d", r.code)
	}
	if _, err := h.st.DeleteGroup(ctx, late); err != nil {
		t.Fatal(err)
	}
	if page := root.follow(root.post("/clients/import/confirm", url.Values{"key": {key[1]}})); !strings.Contains(page, zhText("clients.import.expired")) {
		t.Error("confirmation after the group's deletion: not refused")
	}
	if tags, _ := h.st.ListTags(ctx); len(tags) != 4 {
		t.Errorf("tags after the refused confirmation: %d", len(tags))
	}

	// The dry run refuses each line that does not fit and names the reason; nothing is written.
	eleven := make([]string, catalog.MaxTagsPerClient+1)
	for i := range eleven {
		eleven[i] = "N" + itoa(int64(i))
	}
	lines := map[string]string{
		"Depot":                                 zhText("clients.import.group_unknown", "name", "Depot"), // a group of West, in East
		"Nowhere":                               zhText("clients.import.group_unknown", "name", "Nowhere"),
		"\x00tags:a‮b":                          zhText("clients.import.tag_invalid", "name", "a‮b", "problem", i18n.For(i18n.Source).T("names.bidi_control")),
		"\x00tags:" + strings.Join(eleven, ";"): zhText("clients.tags.too_many", "max", catalog.MaxTagsPerClient),
	}
	for value, want := range lines {
		values := map[string]string{"名称": "丁", "区域": "East", "客户端模板": "Standard"}
		if tags, ok := strings.CutPrefix(value, "\x00tags:"); ok {
			values["标签"] = tags
		} else {
			values["分组"] = value
		}
		r := uploadCSV(root, "zh-CN", strings.Join(zh, ",")+"\n"+csvLine(zh, values))
		if r.code != http.StatusOK || importKey.MatchString(r.body) || !strings.Contains(r.body, want) {
			t.Errorf("line with %q: %d, want %q", value, r.code, want)
		}
	}
	// A column twice refuses the file.
	for data, column := range map[string]string{
		"名称,区域,客户端模板,分组,分组\nA,East,Standard,Front,Front\n":             "分组",
		"name,region,client_template,tags,Tags\nA,East,Standard,x,y\n": "Tags",
	} {
		if r := uploadCSV(root, "zh-CN", data); r.code != http.StatusBadRequest ||
			!strings.Contains(r.body, zhText("clients.import.duplicate_column", "column", column)) {
			t.Errorf("column %s twice: %d", column, r.code)
		}
	}
	// More tags than allowed in all refuse the file.
	all, _ = h.st.ListTags(ctx)
	for i := len(all); i < catalog.MaxTags-1; i++ {
		if err := h.st.CreateTag(ctx, &store.Tag{Name: "fill-" + itoa(int64(i)), Color: "gray"}, catalog.MaxTags); err != nil {
			t.Fatal(err)
		}
	}
	if r := uploadCSV(root, "zh-CN", "name,region,client_template,tags\nDelta,East,Standard,new1;new2\n"); r.code != http.StatusBadRequest ||
		!strings.Contains(r.body, zhText("tags.limit", "max", catalog.MaxTags)) {
		t.Errorf("import beyond the tag limit: %d", r.code)
	}
	if list, _, _ := h.st.ListClients(ctx, store.ClientFilter{All: true}); len(list) != 3 {
		t.Errorf("clients after the refusals: %d", len(list))
	}
}

// TestClientListAtScale: 5000 clients with 100 groups and 200 tags; the list, its filters
// and the page of groups and tags answer within a second each.
func TestClientListAtScale(t *testing.T) {
	if testing.Short() {
		t.Skip("scale test")
	}
	h := newHarness(t)
	east := h.region("East")
	f := h.fixture("Standard", nil)
	root, _ := h.loggedIn("root", store.RoleSuper)
	groups, tags := testutil.ManyClients(t, h.st, east, f.tpl.ID, 5000, catalog.MaxGroupsPerRegion, 200)
	for _, path := range []string{
		"/clients",
		"/clients?page=60",
		"/clients?tag=" + itoa(tags[7]),
		"/clients?tag=" + itoa(tags[7]) + "&tag=" + itoa(tags[64]),
		"/clients?group=" + itoa(groups[3]) + "&tag=" + itoa(tags[3]),
		"/clients?group=none&q=C04",
		"/clients/new?template=" + itoa(f.tpl.ID),
		"/groups",
		"/tags",
	} {
		start := time.Now()
		r := root.get(path)
		took := time.Since(start)
		t.Logf("%-40s %4d ms", path, took.Milliseconds())
		if r.code != http.StatusOK || took > time.Second {
			t.Errorf("%s: %d in %v", path, r.code, took)
		}
	}
	if got := listed(root.get("/clients?tag=" + itoa(tags[7])).body); len(got) == 0 {
		t.Error("tag filter at scale: no clients")
	}
}
