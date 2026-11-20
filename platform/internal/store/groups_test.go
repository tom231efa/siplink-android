package store_test

import (
	"context"
	"errors"
	"fmt"
	"slices"
	"testing"

	"siplink/internal/secret"
	"siplink/internal/store"
	"siplink/internal/testutil"
)

// groupWorld is two regions with a client template each.
type groupWorld struct {
	t          *testing.T
	st         *store.Store
	box        *secret.Box
	east, west int64
	tpl        int64
}

func newGroupWorld(t *testing.T, st *store.Store) *groupWorld {
	w := &groupWorld{t: t, st: st, box: testutil.Box(t)}
	ctx := context.Background()
	for _, r := range []*int64{&w.east, &w.west} {
		reg := &store.Region{Name: "R" + secret.RandomText(6)}
		w.must(st.CreateRegion(ctx, reg))
		*r = reg.ID
	}
	cfg := &store.ConfigTemplate{Name: "标准", SettingsJSON: "{}", RefreshMinutes: 60}
	w.must(st.CreateConfigTemplate(ctx, cfg))
	tpl := &store.ClientTemplate{Name: "标准", ConfigTemplateID: cfg.ID}
	w.must(st.CreateClientTemplate(ctx, tpl))
	w.tpl = tpl.ID
	return w
}

func (w *groupWorld) must(err error) {
	w.t.Helper()
	if err != nil {
		w.t.Fatal(err)
	}
}

func (w *groupWorld) client(name string, region int64, group *int64, tags []int64) *store.Client {
	w.t.Helper()
	c := &store.Client{Name: name, RegionID: region, ClientTemplateID: w.tpl, GroupID: group, EnrollExpiresAt: "2099-01-01T00:00:00Z"}
	w.must(w.st.SaveClient(context.Background(), c, nil, nil, tags, w.box, "token-"+name))
	return c
}

func (w *groupWorld) group(name string, region int64) *store.Group {
	w.t.Helper()
	g := &store.Group{Name: name, RegionID: region}
	w.must(w.st.SaveGroup(context.Background(), g, 100))
	return g
}

func (w *groupWorld) tag(name string) *store.Tag {
	w.t.Helper()
	tg := &store.Tag{Name: name, Color: "sand"}
	w.must(w.st.CreateTag(context.Background(), tg, 500))
	return tg
}

// names lists the clients of a filter by name.
func (w *groupWorld) names(f store.ClientFilter) []string {
	w.t.Helper()
	f.All = true
	list, _, err := w.st.ListClients(context.Background(), f)
	w.must(err)
	var out []string
	for _, c := range list {
		out = append(out, c.Name)
	}
	return out
}

// TestMigrateGroupsTags: a database of schema 10 with clients upgrades to groups and tags;
// its clients have none and keep everything else.
func TestMigrateGroupsTags(t *testing.T) {
	ctx := context.Background()
	old, path := testutil.StoreAt(t, 10)
	w := &groupWorld{t: t, st: old, box: testutil.Box(t)}
	reg := &store.Region{Name: "华东"}
	w.must(old.CreateRegion(ctx, reg))
	cfg := &store.ConfigTemplate{Name: "标准", SettingsJSON: "{}", RefreshMinutes: 60}
	w.must(old.CreateConfigTemplate(ctx, cfg))
	tpl := &store.ClientTemplate{Name: "标准", ConfigTemplateID: cfg.ID}
	w.must(old.CreateClientTemplate(ctx, tpl))
	c := &store.Client{Name: "前台", RegionID: reg.ID, ClientTemplateID: tpl.ID, Note: "一楼"}
	testutil.OldClient(t, old, w.box, c, nil, nil)
	var revision int64
	w.must(old.DB.QueryRow(`SELECT revision FROM clients WHERE id = ?`, c.ID).Scan(&revision))
	w.must(old.Close())

	st, err := store.Open(ctx, path, w.box)
	w.must(err)
	defer st.Close()
	if v, _ := st.SchemaVersion(ctx); v < 11 {
		t.Fatalf("schema %d", v)
	}
	got, err := st.ClientByID(ctx, c.ID)
	w.must(err)
	if got.GroupID != nil || got.Name != "前台" || got.Note != "一楼" || got.Revision != revision {
		t.Errorf("client after the migration: %+v", got)
	}
	w.st = st
	g := w.group("前台组", reg.ID)
	vip := w.tag("VIP")
	w.must(st.SetClientsGroup(ctx, []int64{c.ID}, &g.ID))
	w.must(st.AddClientsTag(ctx, []int64{c.ID}, vip.ID, 10))
	if names := w.names(store.ClientFilter{Group: g.ID, Tags: []int64{vip.ID}}); !slices.Equal(names, []string{"前台"}) {
		t.Errorf("filters after the migration: %v", names)
	}
	if after, _ := st.ClientByID(ctx, c.ID); after.Revision != revision {
		t.Error("a group or tag changed the client's revision (the document)")
	}
}

// TestGroupRules: names unique per region ignoring case in any script, the limit per
// region, a group only for clients of its region, deleting a group or a region.
func TestGroupRules(t *testing.T) {
	ctx := context.Background()
	w := newGroupWorld(t, testutil.Store(t))
	st := w.st
	sales := w.group("Склад", w.east)
	if err := st.SaveGroup(ctx, &store.Group{Name: "склад", RegionID: w.east}, 100); !errors.Is(err, store.ErrConflict) {
		t.Errorf("same name ignoring case in a region: %v", err)
	}
	w.group("склад", w.west) // another region
	if err := st.SaveGroup(ctx, &store.Group{Name: "B", RegionID: w.east}, 1); !errors.Is(err, store.ErrLimit) {
		t.Errorf("limit per region: %v", err)
	}
	renamed := &store.Group{ID: sales.ID, Name: "Lager", RegionID: w.west} // the region of a group never changes
	w.must(st.SaveGroup(ctx, renamed, 100))
	if g, _ := st.GroupByID(ctx, sales.ID); g.Name != "Lager" || g.RegionID != w.east {
		t.Errorf("rename: %+v", g)
	}

	a := w.client("a", w.east, &sales.ID, nil)
	b := w.client("b", w.east, nil, nil)
	// The database refuses a group of another region, on insert and on a move.
	if err := st.SaveClient(ctx, &store.Client{Name: "x", RegionID: w.west, ClientTemplateID: w.tpl, GroupID: &sales.ID},
		nil, nil, nil, w.box, "t-x"); err == nil {
		t.Error("client of another region put into the group")
	}
	if _, err := st.DB.Exec(`UPDATE clients SET region_id = ? WHERE id = ?`, w.west, a.ID); err == nil {
		t.Error("client moved away with its group")
	}
	if err := st.SetClientsGroup(ctx, []int64{b.ID}, &sales.ID); err != nil {
		t.Fatal(err)
	}
	counts, _ := st.GroupClients(ctx)
	if counts[sales.ID] != 2 {
		t.Errorf("clients of the group: %v", counts)
	}
	if n, err := st.DeleteGroup(ctx, sales.ID); err != nil || n != 2 {
		t.Errorf("delete: %d %v", n, err)
	}
	if c, _ := st.ClientByID(ctx, a.ID); c.GroupID != nil {
		t.Error("client keeps a deleted group")
	}
	if names := w.names(store.ClientFilter{NoGroup: true}); len(names) != 2 {
		t.Errorf("clients without a group: %v", names)
	}

	// An empty region goes with its groups.
	empty := &store.Region{Name: "空"}
	w.must(st.CreateRegion(ctx, empty))
	w.group("G", empty.ID)
	w.must(st.DeleteRegion(ctx, empty.ID))
	if gs, _ := st.ListGroups(ctx, store.BookScope{All: true, Region: empty.ID}); len(gs) != 0 {
		t.Errorf("groups of a deleted region: %d", len(gs))
	}
}

// TestTagRules: names unique ignoring case, the limit, the import's EnsureTags, the
// limit per client, and the rule that keeps a region administrator from changing a tag
// that clients of other regions carry.
func TestTagRules(t *testing.T) {
	ctx := context.Background()
	w := newGroupWorld(t, testutil.Store(t))
	st := w.st
	vip, lobby := w.tag("VIP"), w.tag("Lobby")
	if err := st.CreateTag(ctx, &store.Tag{Name: "vip", Color: "gray"}, 500); !errors.Is(err, store.ErrConflict) {
		t.Errorf("same name ignoring case: %v", err)
	}
	if err := st.CreateTag(ctx, &store.Tag{Name: "new", Color: "gray"}, 2); !errors.Is(err, store.ErrLimit) {
		t.Errorf("limit: %v", err)
	}
	got, err := st.EnsureTags(ctx, []string{"vip", " Neu ", "NEU"}, "gray", 500)
	w.must(err)
	if got[store.NameKey("VIP")].ID != vip.ID || got[store.NameKey("neu")] == nil || got[store.NameKey("neu")].Name != "Neu" ||
		got[store.NameKey("neu")].Color != "gray" {
		t.Errorf("ensure: %+v", got)
	}
	if _, err := st.EnsureTags(ctx, []string{"one more"}, "gray", 3); !errors.Is(err, store.ErrLimit) {
		t.Errorf("ensure beyond the limit: %v", err)
	}

	a := w.client("a", w.east, nil, []int64{lobby.ID, vip.ID})
	b := w.client("b", w.west, nil, []int64{lobby.ID})
	w.client("c", w.east, nil, nil)
	tags, _ := st.ClientTags(ctx, []int64{a.ID, b.ID})
	if !slices.Equal(tags[a.ID], []int64{lobby.ID, vip.ID}) || !slices.Equal(tags[b.ID], []int64{lobby.ID}) {
		t.Errorf("tags by name: %v", tags)
	}
	if names := w.names(store.ClientFilter{Tags: []int64{lobby.ID, vip.ID}}); !slices.Equal(names, []string{"a"}) {
		t.Errorf("every tag: %v", names)
	}
	if names := w.names(store.ClientFilter{Tags: []int64{lobby.ID}, Region: w.west}); !slices.Equal(names, []string{"b"}) {
		t.Errorf("tag and region: %v", names)
	}
	use, _ := st.TagClients(ctx, false, []int64{w.east})
	if use[lobby.ID] != (store.TagUse{In: 1, Out: 1}) || use[vip.ID] != (store.TagUse{In: 1}) {
		t.Errorf("use: %+v", use)
	}

	// A region administrator of East: Lobby is on a client of West, VIP only on East's.
	east := []int64{w.east}
	if err := st.UpdateTag(ctx, &store.Tag{ID: lobby.ID, Name: "Hall", Color: "teal"}, false, east); !errors.Is(err, store.ErrInUse) {
		t.Errorf("rename of a tag used outside: %v", err)
	}
	if _, err := st.DeleteTag(ctx, lobby.ID, false, east); !errors.Is(err, store.ErrInUse) {
		t.Errorf("delete of a tag used outside: %v", err)
	}
	w.must(st.UpdateTag(ctx, &store.Tag{ID: vip.ID, Name: "VIP+", Color: "teal"}, false, east))
	w.must(st.UpdateTag(ctx, &store.Tag{ID: lobby.ID, Name: "Hall", Color: "clay"}, true, nil))
	if n, err := st.DeleteTag(ctx, lobby.ID, true, nil); err != nil || n != 2 {
		t.Errorf("delete by a super administrator: %d %v", n, err)
	}
	if tags, _ := st.ClientTags(ctx, []int64{a.ID}); !slices.Equal(tags[a.ID], []int64{vip.ID}) {
		t.Errorf("tags after the delete: %v", tags)
	}

	// At most max tags per client; a refused bulk addition writes nothing.
	var many []int64
	for i := range 3 {
		many = append(many, w.tag("T"+string(rune('a'+i))).ID)
	}
	w.must(st.AddClientsTag(ctx, []int64{a.ID}, many[0], 3))
	if err := st.AddClientsTag(ctx, []int64{b.ID, a.ID}, many[1], 2); !errors.Is(err, store.ErrLimit) {
		t.Errorf("limit per client: %v", err)
	}
	if tags, _ := st.ClientTags(ctx, []int64{b.ID}); len(tags[b.ID]) != 0 {
		t.Errorf("a refused addition wrote: %v", tags)
	}
	w.must(st.RemoveClientsTag(ctx, []int64{a.ID}, many[0]))
	w.must(st.SaveClient(ctx, a, nil, nil, nil, w.box, "")) // nil keeps the tags
	if tags, _ := st.ClientTags(ctx, []int64{a.ID}); !slices.Equal(tags[a.ID], []int64{vip.ID}) {
		t.Errorf("tags kept by nil: %v", tags)
	}
}

// BenchmarkClientList: a page of a list of 5000 clients with 100 groups and 200 tags,
// filtered by a group and two tags, with the tags of its clients (what /clients reads).
func BenchmarkClientList(b *testing.B) {
	ctx := context.Background()
	st := testutil.Store(b)
	reg := &store.Region{Name: "East"}
	cfg := &store.ConfigTemplate{Name: "Standard", SettingsJSON: "{}", RefreshMinutes: 60}
	if err := errors.Join(st.CreateRegion(ctx, reg), st.CreateConfigTemplate(ctx, cfg)); err != nil {
		b.Fatal(err)
	}
	tpl := &store.ClientTemplate{Name: "Standard", ConfigTemplateID: cfg.ID}
	if err := st.CreateClientTemplate(ctx, tpl); err != nil {
		b.Fatal(err)
	}
	groups, tags := testutil.ManyClients(b, st, reg.ID, tpl.ID, 5000, 100, 200)
	for _, f := range []store.ClientFilter{
		{All: true, Limit: 50},
		{All: true, Limit: 50, Tags: []int64{tags[7], tags[64]}},
		{All: true, Limit: 50, Group: groups[3], Tags: []int64{tags[3]}},
	} {
		b.Run(fmt.Sprintf("group=%d,tags=%d", f.Group, len(f.Tags)), func(b *testing.B) {
			for b.Loop() {
				list, _, err := st.ListClients(ctx, f)
				ids := make([]int64, len(list))
				for i, c := range list {
					ids[i] = c.ID
				}
				if _, err2 := st.ClientTags(ctx, ids); err != nil || err2 != nil {
					b.Fatal(err, err2)
				}
			}
		})
	}
}
