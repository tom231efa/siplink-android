package web

import (
	"context"
	"html"
	"net/http"
	"net/url"
	"regexp"
	"slices"
	"strings"
	"testing"

	"siplink/internal/catalog"
	"siplink/internal/i18n"
	"siplink/internal/store"
)

// groupsWorld is two regions, a global client template, a super administrator and the
// region administrator of East.
type groupsWorld struct {
	t           *testing.T
	h           *harness
	east, west  int64
	f           *fixture
	root, local *client
}

func newGroupsWorld(t *testing.T) *groupsWorld {
	h := newHarness(t)
	w := &groupsWorld{t: t, h: h, east: h.region("华东"), west: h.region("华西")}
	w.f = h.fixture("标准", nil)
	w.root, _ = h.loggedIn("root", store.RoleSuper)
	w.local, _ = h.loggedIn("east", store.RoleRegion, w.east)
	return w
}

// zhText is a text of the console in Simplified Chinese as a page shows it.
func zhText(key string, args ...any) string {
	return html.EscapeString(i18n.For(i18n.Source).T(key, args...))
}

// follow opens the page a redirect leads to (and so shows its flash message).
func (c *client) follow(r resp) string {
	c.h.t.Helper()
	if r.code != http.StatusSeeOther {
		c.h.t.Fatalf("no redirect: %d %s", r.code, r.body)
	}
	return c.get(r.location).body
}

// group makes a group through the page and returns its id.
func (w *groupsWorld) group(c *client, name string, region int64) int64 {
	w.t.Helper()
	if r := c.post("/groups", url.Values{"name": {name}, "region": {itoa(region)}}); r.code != http.StatusSeeOther || r.location != "/groups" {
		w.t.Fatalf("group %s: %d %s", name, r.code, r.body)
	}
	groups, _ := w.h.st.ListGroups(context.Background(), store.BookScope{All: true, Region: region})
	for _, g := range groups {
		if g.Name == name {
			return g.ID
		}
	}
	w.t.Fatalf("group %s not stored", name)
	return 0
}

// tag makes a tag through the page and returns its id.
func (w *groupsWorld) tag(c *client, name, color string) int64 {
	w.t.Helper()
	if r := c.post("/tags", url.Values{"name": {name}, "color": {color}}); r.code != http.StatusSeeOther || r.location != "/tags" {
		w.t.Fatalf("tag %s: %d %s", name, r.code, r.body)
	}
	tags, _ := w.h.st.ListTags(context.Background())
	for _, t := range tags {
		if t.Name == name {
			return t.ID
		}
	}
	w.t.Fatalf("tag %s not stored", name)
	return 0
}

// clientForm is the form of a client with a group (0: none) and tags.
func (w *groupsWorld) clientForm(name string, region int64, user string, group int64, tags ...int64) url.Values {
	form := w.f.form(name, region, user)
	form.Set("group", itoa(group))
	for _, t := range tags {
		form.Add("tags", itoa(t))
	}
	return form
}

// client makes a client through the form.
func (w *groupsWorld) client(c *client, name string, region int64, user string, group int64, tags ...int64) int64 {
	w.t.Helper()
	return created(w.t, c.post("/clients", w.clientForm(name, region, user, group, tags...)), "/clients/")
}

func (w *groupsWorld) groupOf(id int64) *int64 {
	w.t.Helper()
	c, err := w.h.st.ClientByID(context.Background(), id)
	if err != nil {
		w.t.Fatal(err)
	}
	return c.GroupID
}

func (w *groupsWorld) tagsOf(id int64) []int64 {
	tags, _ := w.h.st.ClientTags(context.Background(), []int64{id})
	return tags[id]
}

func bulkForm(action string, ids []int64, extra ...string) url.Values {
	form := url.Values{"action": {action}}
	for _, id := range ids {
		form.Add("ids", itoa(id))
	}
	for i := 0; i+1 < len(extra); i += 2 {
		form.Set(extra[i], extra[i+1])
	}
	return form
}

// auditWith are the audit entries of an action whose detail names field ("" = any).
func auditWith(t *testing.T, h *harness, action, field string) []store.AuditEntry {
	entries, _ := h.st.ListAudit(t.Context(), store.AuditFilter{AllRegions: true, Limit: 500})
	var out []store.AuditEntry
	for _, e := range entries {
		if _, ok := e.Detail[field]; e.Action == action && (field == "" || ok) {
			out = append(out, e)
		}
	}
	return out
}

// TestGroupPermissions: a region administrator makes, sees, changes and uses only the
// groups of their regions; a client is only in a group of its region; moving a client
// leaves its group behind; a bulk request with anything it may not do changes nothing.
func TestGroupPermissions(t *testing.T) {
	w := newGroupsWorld(t)
	ctx := t.Context()
	front := w.group(w.local, "前台组", w.east)
	depot := w.group(w.root, "西部仓库", w.west)
	w.group(w.local, "Lobby", w.east)
	w.group(w.root, "LOBBY", w.west) // the same name in another region

	for _, c := range []struct {
		form url.Values
		want string
	}{
		{url.Values{"name": {"X"}, "region": {itoa(w.west)}}, zhText("clients.form.region_required")},
		{url.Values{"name": {"lobby"}, "region": {itoa(w.east)}}, zhText("groups.name_taken")},
		{url.Values{"name": {"a‮b"}, "region": {itoa(w.east)}}, zhText("names.bidi_control")},
		{url.Values{"name": {strings.Repeat("长", catalog.MaxGroupName+1)}, "region": {itoa(w.east)}},
			zhText("catalog.name_too_long", "max", catalog.MaxGroupName)},
	} {
		r := w.local.post("/groups", c.form)
		if r.code != http.StatusBadRequest || !strings.Contains(r.body, c.want) || !strings.Contains(r.body, `id="group-new" data-form-dialog data-return="/groups" aria-labelledby="group-new-title" open`) {
			t.Errorf("group %v: %d, want %q in the open dialog", c.form, r.code, c.want)
		}
	}

	// Groups of other regions do not exist for the region administrator.
	if page := w.local.get("/groups").body; !strings.Contains(page, "前台组") || strings.Contains(page, "西部仓库") {
		t.Error("groups page of the region administrator")
	}
	if page := w.local.get("/groups?edit=" + itoa(depot)).body; strings.Contains(page, "西部仓库") {
		t.Error("edit dialog of another region's group")
	}
	for _, path := range []string{"/groups/" + itoa(depot), "/groups/" + itoa(depot) + "/delete"} {
		if r := w.local.post(path, url.Values{"name": {"x"}}); r.code != http.StatusNotFound {
			t.Errorf("%s by the region administrator: %d", path, r.code)
		}
	}
	if page := w.local.get("/clients").body; !strings.Contains(page, "前台组") || strings.Contains(page, "西部仓库") {
		t.Error("group filter of the region administrator")
	}
	if page := w.local.get("/clients/new?template=" + itoa(w.f.tpl.ID)).body; !strings.Contains(page, "前台组") || strings.Contains(page, "西部仓库") {
		t.Error("client form of the region administrator")
	}

	// The form takes a group of the client's region only.
	a := w.client(w.local, "甲", w.east, "1001", front)
	if g := w.groupOf(a); g == nil || *g != front {
		t.Fatalf("group of a new client: %v", g)
	}
	if r := w.local.post("/clients", w.clientForm("乙", w.east, "1002", depot)); r.code != http.StatusBadRequest ||
		!strings.Contains(r.body, zhText("clients.form.group_invalid")) {
		t.Errorf("group of another region in the form: %d", r.code)
	}
	if page := w.root.get("/clients/" + itoa(a)).body; !strings.Contains(page, `href="/clients?group=`+itoa(front)+`"`) {
		t.Error("client page without its group")
	}

	// Moving a client: the form of the new region offers that region's groups only, and
	// the old group is refused.
	edit := w.root.get("/clients/" + itoa(a) + "/edit?region=" + itoa(w.west)).body
	if !strings.Contains(edit, "西部仓库") || strings.Contains(edit, "前台组") {
		t.Error("groups of the form after a change of region")
	}
	moved := w.clientForm("甲", w.west, "1001", front)
	if r := w.root.post("/clients/"+itoa(a), moved); r.code != http.StatusBadRequest {
		t.Errorf("move with the old group: %d", r.code)
	}
	moved.Set("group", "0")
	created(t, w.root.post("/clients/"+itoa(a), moved), "/clients/")
	if c, _ := w.h.st.ClientByID(ctx, a); c.RegionID != w.west || c.GroupID != nil {
		t.Errorf("moved client: region %d group %v", c.RegionID, c.GroupID)
	}

	// Bulk: everything of the request must be the administrator's, or nothing happens.
	b := w.client(w.local, "乙", w.east, "1002", 0)
	c := w.client(w.root, "丙", w.west, "1003", 0)
	forbidden := zhText("clients.bulk.forbidden")
	for _, form := range []url.Values{
		bulkForm("group", []int64{b}, "group", itoa(depot)),         // a group of another region
		bulkForm("group", []int64{b, c}, "group", itoa(front)),      // a client of another region
		bulkForm("group", []int64{b}, "group", "abc"),               // no group
		bulkForm("group", []int64{b}, "group", "-1"),                // no group
		bulkForm("group", []int64{b, 999999}, "group", itoa(front)), // no such client
	} {
		if page := w.local.follow(w.local.post("/clients/bulk", form)); !strings.Contains(page, forbidden) {
			t.Errorf("bulk %v: not refused", form)
		}
	}
	odd := bulkForm("group", []int64{b}, "group", itoa(front))
	odd.Add("ids", "x")
	if page := w.local.follow(w.local.post("/clients/bulk", odd)); !strings.Contains(page, forbidden) {
		t.Error("bulk with an id that is no number: not refused")
	}
	mixed := bulkForm("group", []int64{b, c}, "group", itoa(front))
	if page := w.root.follow(w.root.post("/clients/bulk", mixed)); !strings.Contains(page, zhText("clients.bulk.group_region", "group", "前台组", "name", "丙")) {
		t.Error("bulk group for clients of two regions: not refused")
	}
	if w.groupOf(b) != nil || w.groupOf(c) != nil {
		t.Fatal("a refused bulk request changed a group")
	}

	d := w.client(w.local, "丁", w.east, "1004", front)
	page := w.local.follow(w.local.post("/clients/bulk", bulkForm("group", []int64{b, d}, "group", itoa(front))))
	if !strings.Contains(page, zhText("clients.bulk.group_set", "n", 2)) || w.groupOf(b) == nil || *w.groupOf(b) != front {
		t.Error("bulk group")
	}
	w.local.post("/clients/bulk", bulkForm("group", []int64{b}, "group", "0"))
	if w.groupOf(b) != nil {
		t.Error("bulk group none")
	}

	// Deleting a group leaves its clients without one; a region's own administrator may.
	if page := w.local.get("/groups").body; !strings.Contains(page, html.EscapeString(i18n.For(i18n.Source).T("groups.delete_confirm_used", "name", "前台组", "n", 1))) {
		t.Error("delete confirmation without the number of clients")
	}
	w.local.follow(w.local.post("/groups/"+itoa(front), url.Values{"name": {"前台"}}))
	w.local.follow(w.local.post("/groups/"+itoa(front)+"/delete", nil))
	if w.groupOf(d) != nil {
		t.Error("client keeps a deleted group")
	}

	// The audit log: the group's life, and one entry per client whose group changed
	// (b set and cleared, d unchanged by the bulk request).
	for action, n := range map[string]int{"group.create": 4, "group.update": 1, "group.delete": 1} {
		if got := len(auditWith(t, w.h, action, "")); got != n {
			t.Errorf("audit %s: %d, want %d", action, got, n)
		}
	}
	if del := auditWith(t, w.h, "group.delete", "clients"); len(del) != 1 || del[0].Detail["clients"] != float64(1) {
		t.Errorf("audit of the delete: %+v", del)
	}
	var changed []string
	for _, e := range auditWith(t, w.h, "client.update", "group") {
		changed = append(changed, e.ObjectName)
	}
	if slices.Sort(changed); !slices.Equal(changed, []string{"乙", "乙", "甲"}) {
		t.Errorf("audit of group changes: %v", changed)
	}
}

// TestTagPermissions: tag names, colours and limits; a region administrator may change
// or delete a tag only when no client outside their regions carries it.
func TestTagPermissions(t *testing.T) {
	w := newGroupsWorld(t)
	ctx := t.Context()
	vip := w.tag(w.local, "VIP", "sand")
	shared := w.tag(w.root, "Shared", "teal")
	odd := w.tag(w.local, "Odd", "red") // not in the palette: the default colour
	if tg, _ := w.h.st.TagByID(ctx, odd); tg.Color != catalog.TagColors[0] {
		t.Errorf("colour outside the palette: %q", tg.Color)
	}
	for name, want := range map[string]string{
		"vip":                       zhText("tags.name_taken"),
		"a;b":                       zhText("catalog.tag.separator"),
		"a；b":                       zhText("catalog.tag.separator"),
		"a⁦b":                       zhText("names.bidi_control"),
		"   ":                       zhText("catalog.name_required"),
		"0123456789012345678901234": zhText("catalog.name_too_long", "max", catalog.MaxTagName),
	} {
		if r := w.local.post("/tags", url.Values{"name": {name}, "color": {"gray"}}); r.code != http.StatusBadRequest || !strings.Contains(r.body, want) {
			t.Errorf("tag %q: %d, want %q", name, r.code, want)
		}
	}

	a := w.client(w.local, "甲", w.east, "1001", 0, vip, shared)
	w.client(w.root, "丙", w.west, "1003", 0, shared)
	page := w.local.get("/tags").body
	locked := `disabled title="` + zhText("tags.locked", "name", "Shared") + `"`
	if !strings.Contains(page, locked) || strings.Contains(page, `href="/tags?edit=`+itoa(shared)+`"`) ||
		!strings.Contains(page, `href="/tags?edit=`+itoa(vip)+`"`) {
		t.Error("tags page of the region administrator: Shared must be locked, VIP not")
	}
	refused := zhText("tags.locked", "name", "Shared")
	for _, path := range []string{"/tags/" + itoa(shared), "/tags/" + itoa(shared) + "/delete"} {
		if page := w.local.follow(w.local.post(path, url.Values{"name": {"Mine"}, "color": {"clay"}})); !strings.Contains(page, refused) {
			t.Errorf("%s by the region administrator: not refused", path)
		}
	}
	if tg, err := w.h.st.TagByID(ctx, shared); err != nil || tg.Name != "Shared" || tg.Color != "teal" {
		t.Fatalf("a refused change changed the tag: %+v %v", tg, err)
	}
	w.local.follow(w.local.post("/tags/"+itoa(vip), url.Values{"name": {"Gold"}, "color": {"clay"}}))
	if tg, _ := w.h.st.TagByID(ctx, vip); tg.Name != "Gold" || tg.Color != "clay" {
		t.Errorf("rename by the region administrator: %+v", tg)
	}
	w.root.follow(w.root.post("/tags/"+itoa(shared), url.Values{"name": {"Common"}, "color": {"steel"}}))
	if tg, _ := w.h.st.TagByID(ctx, shared); tg.Name != "Common" {
		t.Errorf("rename by the super administrator: %+v", tg)
	}
	if page := w.root.get("/clients/" + itoa(a)).body; !strings.Contains(page, `class="chip c-clay"`) || !strings.Contains(page, ">Gold<") {
		t.Error("client page without its tags")
	}

	// At most ten tags per client, in the form and in the bulk bar.
	var many []int64
	for i := range catalog.MaxTagsPerClient + 1 {
		many = append(many, w.tag(w.root, "T"+itoa(int64(i)), "gray"))
	}
	if r := w.local.post("/clients", w.clientForm("乙", w.east, "1002", 0, many...)); r.code != http.StatusBadRequest ||
		!strings.Contains(r.body, zhText("clients.tags.too_many", "max", catalog.MaxTagsPerClient)) {
		t.Errorf("eleven tags in the form: %d", r.code)
	}
	full := w.client(w.local, "乙", w.east, "1002", 0, many[:catalog.MaxTagsPerClient]...)
	add := bulkForm("tag_add", []int64{a, full}, "tag", itoa(many[catalog.MaxTagsPerClient]))
	if page := w.local.follow(w.local.post("/clients/bulk", add)); !strings.Contains(page, zhText("clients.bulk.tags_full", "name", "乙", "max", catalog.MaxTagsPerClient)) {
		t.Error("bulk tag beyond the limit: not refused")
	}
	if len(w.tagsOf(a)) != 2 {
		t.Error("a refused bulk request added a tag")
	}
	add.Set("tag", itoa(many[0])) // full has it already: no change for it
	if page := w.local.follow(w.local.post("/clients/bulk", add)); !strings.Contains(page, zhText("clients.bulk.tag_added", "name", "T0", "n", 2)) ||
		len(w.tagsOf(a)) != 3 || len(w.tagsOf(full)) != catalog.MaxTagsPerClient {
		t.Error("bulk tag add")
	}
	remove := bulkForm("tag_remove", []int64{a, full}, "tag", itoa(many[0]))
	if page := w.local.follow(w.local.post("/clients/bulk", remove)); !strings.Contains(page, zhText("clients.bulk.tag_removed", "name", "T0", "n", 2)) ||
		len(w.tagsOf(a)) != 2 || len(w.tagsOf(full)) != catalog.MaxTagsPerClient-1 {
		t.Error("bulk tag remove")
	}
	if page := w.local.follow(w.local.post("/clients/bulk", bulkForm("tag_add", []int64{a}, "tag", "999999"))); !strings.Contains(page, zhText("clients.bulk.forbidden")) {
		t.Error("bulk with an unknown tag: not refused")
	}

	// Deleting a tag takes it off its clients; the region administrator may delete a tag
	// used in their regions only.
	w.local.follow(w.local.post("/tags/"+itoa(vip)+"/delete", nil))
	if slices.Contains(w.tagsOf(a), vip) {
		t.Error("client keeps a deleted tag")
	}

	// At most 500 tags.
	tags, _ := w.h.st.ListTags(ctx)
	for i := len(tags); i < catalog.MaxTags; i++ {
		if err := w.h.st.CreateTag(ctx, &store.Tag{Name: "fill-" + itoa(int64(i)), Color: "gray"}, catalog.MaxTags); err != nil {
			t.Fatal(err)
		}
	}
	if r := w.local.post("/tags", url.Values{"name": {"one more"}}); r.code != http.StatusBadRequest ||
		!strings.Contains(r.body, zhText("tags.limit", "max", catalog.MaxTags)) {
		t.Errorf("tag beyond the limit: %d", r.code)
	}

	// The audit log: the tags' life, and the clients whose tags changed, one entry each.
	for action, n := range map[string]int{"tag.create": 3 + catalog.MaxTagsPerClient + 1, "tag.update": 2, "tag.delete": 1} {
		if got := len(auditWith(t, w.h, action, "")); got != n {
			t.Errorf("audit %s: %d, want %d", action, got, n)
		}
	}
	if up := auditWith(t, w.h, "tag.update", "color"); len(up) != 2 {
		t.Errorf("audit of colour changes: %d", len(up))
	}
	// One by the bulk add (a gets T0; full had it already), two by the remove.
	if got := len(auditWith(t, w.h, "client.update", "tags")); got != 3 {
		t.Errorf("audit of tag changes: %d, want 3", got)
	}
}

var clientRowLink = regexp.MustCompile(`<a class="fade" href="/clients/\d+">([^<]+)</a>`)

// listed are the names of the clients a list page shows.
func listed(body string) []string {
	var out []string
	for _, m := range clientRowLink.FindAllStringSubmatch(body, -1) {
		out = append(out, html.UnescapeString(m[1]))
	}
	slices.Sort(out)
	return out
}

// TestClientFilters: the list's group and tag filters live in the URL, combine with the
// others and with each other (every tag chosen), survive paging, and ignore what the
// administrator may not see.
func TestClientFilters(t *testing.T) {
	w := newGroupsWorld(t)
	ctx := t.Context()
	g1 := w.group(w.root, "前台组", w.east)
	g2 := w.group(w.root, "西部仓库", w.west)
	x, y := w.tag(w.root, "Xray", "olive"), w.tag(w.root, "Yank", "sage")
	w.client(w.root, "Alpha", w.east, "1001", g1, x, y)
	w.client(w.root, "Bravo", w.east, "1002", 0, x)
	w.client(w.root, "Charlie", w.west, "1003", g2, y)
	for _, c := range []struct {
		c     *client
		query string
		want  []string
	}{
		{w.root, "", []string{"Alpha", "Bravo", "Charlie"}},
		{w.root, "group=" + itoa(g1), []string{"Alpha"}},
		{w.root, "group=none", []string{"Bravo"}},
		{w.root, "tag=" + itoa(x), []string{"Alpha", "Bravo"}},
		{w.root, "tag=" + itoa(x) + "&tag=" + itoa(y), []string{"Alpha"}},
		{w.root, "tag=" + itoa(y) + "&group=" + itoa(g2), []string{"Charlie"}},
		{w.root, "tag=" + itoa(y) + "&q=Bra", nil},
		{w.root, "tag=999999", []string{"Alpha", "Bravo", "Charlie"}}, // unknown: ignored
		{w.local, "group=" + itoa(g2), []string{"Alpha", "Bravo"}},    // another region's: ignored
		{w.local, "tag=" + itoa(y), []string{"Alpha"}},
	} {
		if got := listed(c.c.get("/clients?" + c.query).body); !slices.Equal(got, c.want) {
			t.Errorf("/clients?%s: %v, want %v", c.query, got, c.want)
		}
	}
	page := w.root.get("/clients?group=" + itoa(g1)).body
	if !strings.Contains(page, `class="chip c-olive"`) || !strings.Contains(page, `<option value="`+itoa(g1)+`" selected>`) {
		t.Error("list without chips or the chosen group")
	}
	if page := w.root.get("/clients?tag=" + itoa(x)).body; !strings.Contains(page, `value="`+itoa(x)+`" checked`) {
		t.Error("tag filter does not show its choice")
	}

	// Paging keeps the group and the tags.
	for i := range clientsPerPage {
		c := &store.Client{Name: "Zulu " + itoa(int64(100+i)), RegionID: w.east, ClientTemplateID: w.f.tpl.ID, GroupID: &g1,
			EnrollExpiresAt: "2099-01-01T00:00:00Z"}
		if err := w.h.st.SaveClient(ctx, c, nil, nil, []int64{x}, w.h.srv.Ext.Box, "token-"+c.Name); err != nil {
			t.Fatal(err)
		}
	}
	page = w.root.get("/clients?group=" + itoa(g1) + "&tag=" + itoa(x)).body
	m := regexp.MustCompile(`href="/clients\?([^"]*)page=2"`).FindStringSubmatch(page)
	if m == nil {
		t.Fatal("no second page")
	}
	next, _ := url.ParseQuery(html.UnescapeString(m[1]))
	if next.Get("group") != itoa(g1) || !slices.Equal(next["tag"], []string{itoa(x)}) {
		t.Errorf("paging query: %q", m[1])
	}
	if got := listed(w.root.get("/clients?" + html.UnescapeString(m[1]) + "page=2").body); len(got) != 1 {
		t.Errorf("second page: %v", got)
	}
}

// TestDocumentIgnoresGroupsTags: groups and tags are for the console; a phone's document
// does not change when they do (the device API answers 204 to the version it has).
func TestDocumentIgnoresGroupsTags(t *testing.T) {
	w := newGroupsWorld(t)
	id, token := newClient(t, w.root, w.f, "前台", w.east, "1001")
	code, body, _ := w.h.deviceCall("/p/"+token, "", "")
	if code != http.StatusOK {
		t.Fatalf("enroll: %d", code)
	}
	doc := decode(t, body)
	g := w.group(w.root, "前台组", w.east)
	tg := w.tag(w.root, "Lobby", "sand")
	w.root.follow(w.root.post("/clients/bulk", bulkForm("group", []int64{id}, "group", itoa(g))))
	w.root.follow(w.root.post("/clients/bulk", bulkForm("tag_add", []int64{id}, "tag", itoa(tg))))
	if code, _, _ := w.h.deviceCall("/api/device/v1/sync", doc.DeviceKey, doc.Version); code != http.StatusNoContent {
		t.Errorf("sync after bulk changes: %d", code)
	}
	other := w.tag(w.root, "Other", "gray")
	created(t, w.root.post("/clients/"+itoa(id), w.clientForm("前台", w.east, "1001", 0, other)), "/clients/")
	if tags := w.tagsOf(id); !slices.Equal(tags, []int64{other}) || w.groupOf(id) != nil {
		t.Fatalf("form: tags %v group %v", tags, w.groupOf(id))
	}
	if code, _, _ := w.h.deviceCall("/api/device/v1/sync", doc.DeviceKey, doc.Version); code != http.StatusNoContent {
		t.Errorf("sync after a form with other group and tags: %d", code)
	}
	_, body, _ = w.h.deviceCall("/api/device/v1/sync", doc.DeviceKey, "")
	if strings.Contains(body, "Other") || strings.Contains(body, "前台组") {
		t.Error("the document names a group or tag")
	}
	audit := auditWith(t, w.h, "client.update", "tags")
	if len(audit) != 2 {
		t.Fatalf("audit of tag changes: %d", len(audit))
	}
	if d := audit[0].Detail["tags"].(map[string]any); !slices.Equal(anyStrings(d["old"]), []string{"Lobby"}) || !slices.Equal(anyStrings(d["new"]), []string{"Other"}) {
		t.Errorf("audit of the form's tags: %+v", d)
	}
}

func anyStrings(v any) []string {
	var out []string
	list, _ := v.([]any)
	for _, s := range list {
		out = append(out, s.(string))
	}
	return out
}
