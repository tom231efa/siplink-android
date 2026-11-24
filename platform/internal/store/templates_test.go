package store_test

import (
	"context"
	"errors"
	"fmt"
	"strconv"
	"testing"
	"time"

	"siplink/internal/store"
	"siplink/internal/testutil"
)

func TestTemplateStore(t *testing.T) {
	st := testutil.Store(t)
	ctx := t.Context()
	r := &store.Region{Name: "华东"}
	if err := st.CreateRegion(ctx, r); err != nil {
		t.Fatal(err)
	}
	c := &store.ConfigTemplate{Name: "标准", SettingsJSON: "{}", RefreshMinutes: 60, RegionID: &r.ID}
	if err := st.CreateConfigTemplate(ctx, c); err != nil {
		t.Fatal(err)
	}
	if err := st.CreateConfigTemplate(ctx, &store.ConfigTemplate{Name: "标准", SettingsJSON: "{}", RefreshMinutes: 60, RegionID: &r.ID}); !errors.Is(err, store.ErrConflict) {
		t.Errorf("same name in a region: %v", err)
	}
	if err := st.CreateConfigTemplate(ctx, &store.ConfigTemplate{Name: "标准", SettingsJSON: "{}", RefreshMinutes: 60}); err != nil {
		t.Errorf("same name, global: %v", err)
	}
	var ids []int64
	for _, name := range []string{"甲", "乙", "丙"} {
		p := &store.Profile{TemplateID: c.ID, Name: name, Domain: "pbx.example.com", Transport: "UDP", Expires: 3600, Enabled: true}
		if err := st.SaveProfile(ctx, p, 3); err != nil {
			t.Fatal(err)
		}
		ids = append(ids, p.ID)
	}
	if err := st.SaveProfile(ctx, &store.Profile{TemplateID: c.ID, Name: "丁", Domain: "x", Transport: "UDP"}, 3); !errors.Is(err, store.ErrLimit) {
		t.Errorf("limit: %v", err)
	}
	ps, _ := st.Profiles(ctx, c.ID)
	if !ps[0].IsDefault || ps[1].IsDefault {
		t.Error("first profile is not the default")
	}
	if err := st.Reorder(ctx, "account_profiles", c.ID, []int64{ids[0], ids[2], ids[1]}); err != nil {
		t.Fatal(err)
	}
	ps, _ = st.Profiles(ctx, c.ID)
	if ps[1].Name != "丙" || ps[2].Name != "乙" {
		t.Errorf("order after reorder: %s %s %s", ps[0].Name, ps[1].Name, ps[2].Name)
	}
	for _, bad := range [][]int64{{ids[0], ids[2]}, {ids[0], ids[2], ids[2]}, {ids[0], ids[1], ids[2] + 100}} {
		if err := st.Reorder(ctx, "account_profiles", c.ID, bad); !errors.Is(err, store.ErrOrder) {
			t.Errorf("Reorder(%v) = %v, want ErrOrder", bad, err)
		}
	}
	if err := st.Reorder(ctx, "admins", c.ID, ids); err == nil {
		t.Error("Reorder accepted a table outside its list")
	}
	if err := st.DeleteProfile(ctx, c.ID, ids[0]); err != nil {
		t.Fatal(err)
	}
	ps, _ = st.Profiles(ctx, c.ID)
	if len(ps) != 2 || !ps[0].IsDefault {
		t.Error("default not handed on after delete")
	}
	before, _ := st.ConfigTemplateByID(ctx, c.ID)
	if before.Revision < 5 {
		t.Errorf("revision not bumped: %d", before.Revision)
	}

	k := &store.KeyTemplate{Name: "键"}
	if err := st.CreateKeyTemplate(ctx, k); err != nil {
		t.Fatal(err)
	}
	if err := st.AddKeys(ctx, k.ID, []*store.TemplateKey{{Label: "a", Number: "1", Action: "speed"}, {Label: "b", Number: "2", Action: "speed"}}, false, 2); err != nil {
		t.Fatal(err)
	}
	if err := st.AddKeys(ctx, k.ID, []*store.TemplateKey{{Label: "c", Number: "3", Action: "speed"}}, false, 2); !errors.Is(err, store.ErrLimit) {
		t.Errorf("key limit: %v", err)
	}
	if err := st.AddKeys(ctx, k.ID, []*store.TemplateKey{{Label: "c", Number: "3", Action: "speed"}}, true, 2); err != nil {
		t.Errorf("replace: %v", err)
	}
	ct := &store.ClientTemplate{Name: "前台", ConfigTemplateID: c.ID, KeyTemplateID: &k.ID, RegionID: &r.ID}
	if err := st.CreateClientTemplate(ctx, ct); err != nil {
		t.Fatal(err)
	}
	if err := st.DeleteConfigTemplate(ctx, c.ID); !errors.Is(err, store.ErrInUse) {
		t.Errorf("delete used configuration template: %v", err)
	}
	if err := st.DeleteKeyTemplate(ctx, k.ID); !errors.Is(err, store.ErrInUse) {
		t.Errorf("delete used key template: %v", err)
	}
	if u, _ := st.RegionUsage(ctx, r.ID); u.Templates != 2 {
		t.Errorf("region usage: %+v", u)
	}
	if err := st.ReportCodecs(ctx, "0.2.0", []string{"opus/48000/2", "PCMA/8000/1"}, []string{"H264/90000/1"}); err != nil {
		t.Fatal(err)
	}
	st.ReportCodecs(ctx, "0.2.1", []string{"opus/48000/2"}, nil)
	if got, _ := st.ReportedCodecs(ctx); len(got["audio"]) != 2 || len(got["video"]) != 1 {
		t.Errorf("reported codecs: %v", got)
	}
}

// TestGroupedCounts: the counts of the template lists, read for all templates at once,
// equal the counts of each template read alone; ListAdmins gives each administrator the
// regions AdminByID gives.
func TestGroupedCounts(t *testing.T) {
	st := testutil.Store(t)
	ctx := t.Context()
	must := func(err error) {
		t.Helper()
		if err != nil {
			t.Fatal(err)
		}
	}
	var regions []int64
	for _, name := range []string{"华东", "华西"} {
		r := &store.Region{Name: name}
		must(st.CreateRegion(ctx, r))
		regions = append(regions, r.ID)
	}
	var configs []*store.ConfigTemplate
	var tpls []*store.ClientTemplate
	for i, name := range []string{"甲", "乙", "丙"} {
		c := &store.ConfigTemplate{Name: name, SettingsJSON: "{}", RefreshMinutes: 60}
		must(st.CreateConfigTemplate(ctx, c))
		configs = append(configs, c)
		for k := range i {
			must(st.SaveProfile(ctx, &store.Profile{TemplateID: c.ID, Name: name + itoa(k), Domain: "pbx.example.com", Transport: "UDP",
				Expires: 3600, Enabled: true}, 10))
		}
		for k := range 2 {
			tpl := &store.ClientTemplate{Name: name + itoa(k), ConfigTemplateID: c.ID}
			must(st.CreateClientTemplate(ctx, tpl))
			tpls = append(tpls, tpl)
		}
	}
	for i := range 7 {
		tpl := tpls[i%3] // the templates of 甲 and the first of 乙
		testutil.OldClient(t, st, testutil.Box(t), &store.Client{Name: "c" + itoa(i), RegionID: regions[i%2], ClientTemplateID: tpl.ID}, nil, nil)
	}
	byTemplate, err := st.ClientCountsByTemplate(ctx)
	must(err)
	for _, tpl := range tpls {
		if n, _ := st.ClientCountOfTemplate(ctx, tpl.ID); byTemplate[tpl.ID] != n {
			t.Errorf("client template %s: %d clients, alone %d", tpl.Name, byTemplate[tpl.ID], n)
		}
	}
	byConfig, err := st.ClientCountsByConfigTemplate(ctx)
	must(err)
	profiles, err := st.ProfileCounts(ctx)
	must(err)
	for _, c := range configs {
		n, _ := st.ClientCountOfConfigTemplate(ctx, c.ID)
		ps, _ := st.Profiles(ctx, c.ID)
		if byConfig[c.ID] != n || profiles[c.ID] != len(ps) {
			t.Errorf("configuration template %s: %d clients and %d profiles, alone %d and %d", c.Name, byConfig[c.ID], profiles[c.ID], n, len(ps))
		}
	}
	if byConfig[configs[2].ID] != 0 || profiles[configs[0].ID] != 0 || byConfig[configs[0].ID] != 5 {
		t.Errorf("counts: %v %v", byConfig, profiles)
	}

	must(st.CreateAdmin(ctx, &store.Admin{Username: "root", PasswordHash: "x", Role: store.RoleSuper}))
	must(st.CreateAdmin(ctx, &store.Admin{Username: "both", PasswordHash: "x", Role: store.RoleRegion, RegionIDs: []int64{regions[1], regions[0]}}))
	must(st.CreateAdmin(ctx, &store.Admin{Username: "east", PasswordHash: "x", Role: store.RoleRegion, RegionIDs: regions[:1]}))
	admins, err := st.ListAdmins(ctx)
	must(err)
	for _, a := range admins {
		alone, err := st.AdminByID(ctx, a.ID)
		must(err)
		if fmt.Sprint(a.RegionIDs) != fmt.Sprint(alone.RegionIDs) || (a.RegionIDs == nil) != (alone.RegionIDs == nil) {
			t.Errorf("%s: regions %v, alone %v", a.Username, a.RegionIDs, alone.RegionIDs)
		}
	}
}

func itoa(n int) string { return strconv.Itoa(n) }

// TestReportCodecsWritesChanges: a phone reports its codecs on every sync; only keys not
// stored yet for its app version are written, so a sync with the known set only reads and
// does not wait for the database's write lock.
func TestReportCodecsWritesChanges(t *testing.T) {
	st := testutil.Store(t)
	ctx := t.Context()
	audio, video := []string{"opus/48000/2", "PCMA/8000/1", "opus/48000/2"}, []string{"H264/90000/1"}
	if err := st.ReportCodecs(ctx, "0.2.2", audio, video); err != nil {
		t.Fatal(err)
	}
	// Another writer holds the write lock (transactions begin IMMEDIATE).
	tx, err := st.DB.BeginTx(ctx, nil)
	if err != nil {
		t.Fatal(err)
	}
	short, cancel := context.WithTimeout(ctx, 300*time.Millisecond)
	start := time.Now()
	err = st.ReportCodecs(short, "0.2.2", audio, video)
	cancel()
	if err != nil || time.Since(start) > 250*time.Millisecond {
		t.Errorf("the known set waited for the write lock: %v after %v", err, time.Since(start))
	}
	tx.Rollback()
	if err := st.ReportCodecs(ctx, "0.2.2", append(audio, "G722/8000/1"), nil); err != nil {
		t.Fatal(err)
	}
	if err := st.ReportCodecs(ctx, "0.2.3", []string{"opus/48000/2"}, nil); err != nil {
		t.Fatal(err)
	}
	var rows int
	st.DB.QueryRowContext(ctx, `SELECT count(*) FROM reported_codecs`).Scan(&rows)
	if got, _ := st.ReportedCodecs(ctx); rows != 5 || len(got["audio"]) != 3 || len(got["video"]) != 1 {
		t.Errorf("reported codecs: %d rows, %v", rows, got)
	}
}

// TestReportCodecsLimit: reported_codecs stops growing at MaxReportedCodecs rows; keys
// already stored are still read without a write.
func TestReportCodecsLimit(t *testing.T) {
	st := testutil.Store(t)
	ctx := t.Context()
	tx, err := st.DB.BeginTx(ctx, nil)
	if err != nil {
		t.Fatal(err)
	}
	for i := range store.MaxReportedCodecs - 1 {
		if _, err := tx.ExecContext(ctx, `INSERT INTO reported_codecs(kind, key, app_version, first_seen) VALUES('audio', ?, 'filler', '')`,
			fmt.Sprintf("c%d/8000/1", i)); err != nil {
			t.Fatal(err)
		}
	}
	if err := tx.Commit(); err != nil {
		t.Fatal(err)
	}
	count := func() (n int) {
		st.DB.QueryRowContext(ctx, `SELECT count(*) FROM reported_codecs`).Scan(&n)
		return n
	}
	if err := st.ReportCodecs(ctx, "0.3.0", []string{"opus/48000/2", "PCMA/8000/1"}, []string{"H264/90000/1"}); err != nil {
		t.Fatal(err)
	}
	if n := count(); n != store.MaxReportedCodecs {
		t.Errorf("rows after a report into the last place: %d", n)
	}
	if err := st.ReportCodecs(ctx, "0.3.1", []string{"G722/8000/1"}, nil); err != nil || count() != store.MaxReportedCodecs {
		t.Errorf("a full table grew: %v, %d rows", err, count())
	}
	if err := st.ReportCodecs(ctx, "filler", []string{"c1/8000/1"}, nil); err != nil {
		t.Errorf("a known key in a full table: %v", err)
	}
}
