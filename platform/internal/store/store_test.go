package store_test

import (
	"context"
	"database/sql"
	"fmt"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"

	"siplink/internal/store"
	"siplink/internal/testutil"
)

func TestMigrateTwice(t *testing.T) {
	ctx := context.Background()
	path := filepath.Join(t.TempDir(), "x.db")
	st, err := store.Open(ctx, path, nil)
	if err != nil {
		t.Fatal(err)
	}
	v1, _ := st.SchemaVersion(ctx)
	st.Close()
	st, err = store.Open(ctx, path, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()
	v2, _ := st.SchemaVersion(ctx)
	if v1 < 1 || v1 != v2 {
		t.Errorf("schema versions %d %d", v1, v2)
	}
	var fk int
	st.DB.QueryRow(`PRAGMA foreign_keys`).Scan(&fk)
	var mode string
	st.DB.QueryRow(`PRAGMA journal_mode`).Scan(&mode)
	if fk != 1 || mode != "wal" {
		t.Errorf("foreign_keys=%d journal_mode=%s", fk, mode)
	}
}

// TestIdleConnections: connections given back after concurrent use stay open for the
// next requests instead of being closed down to the default of 2 idle ones.
func TestIdleConnections(t *testing.T) {
	ctx := context.Background()
	st := testutil.Store(t)
	var conns []*sql.Conn
	for range 8 {
		c, err := st.DB.Conn(ctx)
		if err != nil {
			t.Fatal(err)
		}
		conns = append(conns, c)
	}
	for _, c := range conns {
		c.Close()
	}
	if s := st.DB.Stats(); s.MaxOpenConnections != 8 || s.Idle != 8 || s.MaxIdleClosed != 0 {
		t.Errorf("pool after 8 concurrent connections: open limit %d, idle %d, closed as surplus %d", s.MaxOpenConnections, s.Idle, s.MaxIdleClosed)
	}
}

func TestLastSuperAndRegions(t *testing.T) {
	ctx := context.Background()
	st := testutil.Store(t)
	a := &store.Admin{Username: "Root", PasswordHash: "x", Role: store.RoleSuper}
	if err := st.CreateAdmin(ctx, a); err != nil {
		t.Fatal(err)
	}
	if err := st.CreateAdmin(ctx, &store.Admin{Username: "root", PasswordHash: "x", Role: store.RoleSuper}); err != store.ErrConflict {
		t.Errorf("case-insensitive unique user name: %v", err)
	}
	a.Disabled = true
	if err := st.UpdateAdmin(ctx, a); err != store.ErrLastSuper {
		t.Errorf("disable last super: %v", err)
	}
	a.Disabled, a.Role = false, store.RoleRegion
	if err := st.UpdateAdmin(ctx, a); err != store.ErrLastSuper {
		t.Errorf("demote last super: %v", err)
	}
	r := &store.Region{Name: "华东"}
	if err := st.CreateRegion(ctx, r); err != nil {
		t.Fatal(err)
	}
	b := &store.Admin{Username: "east", PasswordHash: "x", Role: store.RoleRegion, RegionIDs: []int64{r.ID}}
	if err := st.CreateAdmin(ctx, b); err != nil {
		t.Fatal(err)
	}
	if err := st.DeleteRegion(ctx, r.ID); err != store.ErrInUse {
		t.Errorf("delete region with admin: %v", err)
	}
	b.RegionIDs = nil
	if err := st.UpdateAdmin(ctx, b); err != nil {
		t.Fatal(err)
	}
	if err := st.DeleteRegion(ctx, r.ID); err != nil {
		t.Errorf("delete empty region: %v", err)
	}
}

func TestAuditAppendOnly(t *testing.T) {
	ctx := context.Background()
	st := testutil.Store(t)
	region := int64(5)
	e := &store.AuditEntry{AdminName: "x", Action: "region.create", RegionID: &region, Detail: map[string]any{"name": "a"}}
	if err := st.AddAudit(ctx, e); err != nil {
		t.Fatal(err)
	}
	if _, err := st.DB.Exec(`UPDATE audit_log SET action = 'x'`); err == nil {
		t.Error("audit row updated")
	}
	if _, err := st.DB.Exec(`DELETE FROM audit_log`); err == nil {
		t.Error("audit row deleted")
	}
	got, err := st.ListAudit(ctx, store.AuditFilter{RegionIDs: []int64{5}, OwnAdminID: 99})
	if err != nil || len(got) != 1 || got[0].Detail["name"] != "a" {
		t.Errorf("list: %v %v", got, err)
	}
	got, _ = st.ListAudit(ctx, store.AuditFilter{RegionIDs: []int64{6}, OwnAdminID: 99})
	if len(got) != 0 {
		t.Error("entry of another region visible")
	}
}

// TestMigrateClientsNameOrder: migration 0012 adds the index of the client lists' order
// (name ignoring case, then id), so that a page of all regions reads its rows instead of
// sorting every client; the order of the list stays the same.
func TestMigrateClientsNameOrder(t *testing.T) {
	ctx := context.Background()
	old, path := testutil.StoreAt(t, 11)
	must := func(err error) {
		t.Helper()
		if err != nil {
			t.Fatal(err)
		}
	}
	reg := &store.Region{Name: "华东"}
	must(old.CreateRegion(ctx, reg))
	cfg := &store.ConfigTemplate{Name: "标准", SettingsJSON: "{}", RefreshMinutes: 60}
	must(old.CreateConfigTemplate(ctx, cfg))
	tpl := &store.ClientTemplate{Name: "标准", ConfigTemplateID: cfg.ID}
	must(old.CreateClientTemplate(ctx, tpl))
	for _, name := range []string{"b", "C", "a", "B2"} {
		testutil.OldClient(t, old, testutil.Box(t), &store.Client{Name: name, RegionID: reg.ID, ClientTemplateID: tpl.ID}, nil, nil)
	}
	must(old.Close())

	st, err := store.Open(ctx, path, testutil.Box(t))
	must(err)
	defer st.Close()
	if v, _ := st.SchemaVersion(ctx); v < 12 {
		t.Fatalf("schema %d", v)
	}
	// The query of ListClients for a super administrator's page.
	rows, err := st.DB.QueryContext(ctx, `EXPLAIN QUERY PLAN SELECT id FROM clients c ORDER BY c.name COLLATE NOCASE, c.id LIMIT 50 OFFSET 0`)
	must(err)
	var plan []string
	for rows.Next() {
		var id, parent, unused int
		var detail string
		must(rows.Scan(&id, &parent, &unused, &detail))
		plan = append(plan, detail)
	}
	rows.Close()
	if p := strings.Join(plan, " | "); !strings.Contains(p, "clients_name_order") || strings.Contains(p, "TEMP B-TREE") {
		t.Errorf("plan of a page of all clients: %s", p)
	}
	list, total, err := st.ListClients(ctx, store.ClientFilter{All: true, Limit: 3})
	must(err)
	var names []string
	for _, c := range list {
		names = append(names, c.Name)
	}
	if total != 4 || strings.Join(names, ",") != "a,b,B2" {
		t.Errorf("first page: %v of %d", names, total)
	}
}

// TestClientReportsForLists: the reports of a list carry everything but the sealed last
// document, which only the report of one client (the device API) reads.
func TestClientReportsForLists(t *testing.T) {
	ctx := context.Background()
	st := testutil.Store(t)
	must := func(err error) {
		t.Helper()
		if err != nil {
			t.Fatal(err)
		}
	}
	reg := &store.Region{Name: "华东"}
	must(st.CreateRegion(ctx, reg))
	cfg := &store.ConfigTemplate{Name: "标准", SettingsJSON: "{}", RefreshMinutes: 60}
	must(st.CreateConfigTemplate(ctx, cfg))
	tpl := &store.ClientTemplate{Name: "标准", ConfigTemplateID: cfg.ID}
	must(st.CreateClientTemplate(ctx, tpl))
	c := &store.Client{Name: "前台", RegionID: reg.ID, ClientTemplateID: tpl.ID}
	testutil.OldClient(t, st, testutil.Box(t), c, nil, nil)
	want := &store.Report{ClientID: c.ID, LastSeenAt: "2026-11-20T08:00:00Z", LastIP: "192.0.2.1", AppVersion: "0.2.2", AppVersionCode: 6,
		UserAgent: "SipLink", AndroidRelease: "16", AndroidSDK: 36, DeviceModel: "Phone", SentVersion: "v1", RevisionKey: "k",
		LastDocEnc: "sealed", AppliedVersion: "v1", AppliedAt: "2026-11-20T08:00:01Z", ApplyError: "e", RegistrationsJSON: "[]", BuildError: "b"}
	must(st.SaveReport(ctx, want))
	one, err := st.ClientReport(ctx, c.ID)
	must(err)
	if *one != *want {
		t.Errorf("report of one client: %+v", one)
	}
	list, err := st.ClientReports(ctx, []int64{c.ID})
	must(err)
	lean := *want
	lean.LastDocEnc = ""
	if got := list[c.ID]; got == nil || *got != lean {
		t.Errorf("report of a list: %+v", got)
	}
}

// TestClientFilterOfflineAndPages: the offline filter follows the rule of Offline for the
// refresh interval of each client's configuration template, and every page of a list
// tells the same total, whether the count was asked or the page told it.
func TestClientFilterOfflineAndPages(t *testing.T) {
	ctx := context.Background()
	st := testutil.Store(t)
	must := func(err error) {
		t.Helper()
		if err != nil {
			t.Fatal(err)
		}
	}
	reg := &store.Region{Name: "华东"}
	must(st.CreateRegion(ctx, reg))
	now := time.Date(2026, 11, 20, 12, 0, 0, 0, time.UTC)
	var tpls []int64
	for _, minutes := range []int{5, 60, 1440} {
		cfg := &store.ConfigTemplate{Name: strconv.Itoa(minutes), SettingsJSON: "{}", RefreshMinutes: minutes}
		must(st.CreateConfigTemplate(ctx, cfg))
		tpl := &store.ClientTemplate{Name: cfg.Name, ConfigTemplateID: cfg.ID}
		must(st.CreateClientTemplate(ctx, tpl))
		tpls = append(tpls, tpl.ID)
	}
	refresh := map[int64]int{tpls[0]: 5, tpls[1]: 60, tpls[2]: 1440}
	// Last seen at these distances before now: around 3 intervals of each template.
	ago := []time.Duration{-1, 0, 14 * time.Minute, 15 * time.Minute, 15*time.Minute + time.Second, 3 * time.Hour,
		3*time.Hour + time.Second, 72 * time.Hour, 72*time.Hour + time.Second}
	want := map[string]bool{}
	for i, tpl := range tpls {
		for k, d := range ago {
			c := &store.Client{Name: fmt.Sprintf("c%d-%d", i, k), RegionID: reg.ID, ClientTemplateID: tpl}
			testutil.OldClient(t, st, testutil.Box(t), c, nil, nil)
			_, err := st.DB.ExecContext(ctx, `UPDATE clients SET status = 'active' WHERE id = ?`, c.ID)
			must(err)
			seen := ""
			if d >= 0 {
				seen = now.Add(-d).Format(store.TimeFormat)
			}
			if k == 1 { // bound, no report yet
				seen = ""
			} else {
				must(st.SaveReport(ctx, &store.Report{ClientID: c.ID, LastSeenAt: seen, RegistrationsJSON: "[]"}))
			}
			want[c.Name] = store.Offline(seen, refresh[tpl], now)
		}
	}
	testutil.OldClient(t, st, testutil.Box(t), &store.Client{Name: "pending", RegionID: reg.ID, ClientTemplateID: tpls[0]}, nil, nil)
	list, total, err := st.ListClients(ctx, store.ClientFilter{All: true, Status: store.StatusOffline, Now: now})
	must(err)
	got := map[string]bool{}
	for _, c := range list {
		got[c.Name] = true
	}
	for name, offline := range want {
		if got[name] != offline {
			t.Errorf("%s: offline %v, the rule says %v", name, got[name], offline)
		}
	}
	if total != len(list) || len(list) < 6 {
		t.Errorf("offline: %d of total %d", len(list), total)
	}
	// The dashboard counts by the same rule and lists the first ones in the list's order.
	att, err := st.ClientAttention(ctx, store.ClientFilter{All: true, Now: now}, 3)
	must(err)
	if att.Offline != len(list) || len(att.OfflineRows) != 3 || att.OfflineRows[0].Name != list[0].Name ||
		att.OfflineRows[2].Name != list[2].Name || att.Trouble != 0 || att.Healthy != len(want)-len(list) {
		t.Errorf("dashboard: %+v", att)
	}
	all := len(want) + 1
	for _, limit := range []int{1, 4, 7, all, all + 1} {
		for page := 0; page*limit <= all+limit; page++ {
			f := store.ClientFilter{All: true, Limit: limit, Offset: page * limit}
			list, total, err := st.ListClients(ctx, f)
			must(err)
			if total != all || len(list) != max(0, min(limit, all-page*limit)) {
				t.Errorf("limit %d page %d: %d rows, total %d of %d", limit, page, len(list), total, all)
			}
		}
		f := store.ClientFilter{All: true, Status: store.StatusOffline, Now: now, Limit: limit}
		if _, total, _ := st.ListClients(ctx, f); total != len(got) {
			t.Errorf("offline, limit %d: total %d of %d", limit, total, len(got))
		}
	}
}
