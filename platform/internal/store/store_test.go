package store_test

import (
	"context"
	"database/sql"
	"path/filepath"
	"testing"

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
