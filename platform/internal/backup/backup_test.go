package backup

import (
	"context"
	"database/sql"
	"os"
	"path/filepath"
	"testing"
	"time"

	"siplink/internal/store"
	"siplink/internal/testutil"
)

func TestBackupAndPrune(t *testing.T) {
	st := testutil.Store(t)
	ctx := context.Background()
	if err := st.CreateRegion(ctx, &store.Region{Name: "华东"}); err != nil {
		t.Fatal(err)
	}
	dir := filepath.Join(t.TempDir(), "backups")
	day := time.Date(2026, 11, 16, 3, 30, 0, 0, time.Local)
	name, err := Now(ctx, st.DB, dir, day)
	if err != nil || name != "siplink-2026-11-16.db" {
		t.Fatalf("%q %v", name, err)
	}
	second, err := Now(ctx, st.DB, dir, day.Add(time.Hour))
	if err != nil || second != "siplink-2026-11-16-043000.db" {
		t.Fatalf("%q %v", second, err)
	}
	info, _ := os.Stat(filepath.Join(dir, name))
	if info.Mode().Perm() != 0o600 {
		t.Errorf("mode %v", info.Mode())
	}
	// The copy is a working database with the data.
	db, err := sql.Open("sqlite", filepath.Join(dir, name))
	if err != nil {
		t.Fatal(err)
	}
	var n int
	if err := db.QueryRow(`SELECT count(*) FROM regions`).Scan(&n); err != nil || n != 1 {
		t.Errorf("regions in backup: %d %v", n, err)
	}
	db.Close()
	for i := 1; i <= 3; i++ {
		if _, err := Now(ctx, st.DB, dir, day.AddDate(0, 0, i)); err != nil {
			t.Fatal(err)
		}
		os.Chtimes(filepath.Join(dir, "siplink-"+day.AddDate(0, 0, i).Format("2006-01-02")+".db"), time.Now(), day.AddDate(0, 0, i))
	}
	os.Chtimes(filepath.Join(dir, name), time.Now(), day)
	os.Chtimes(filepath.Join(dir, second), time.Now(), day.Add(time.Hour))
	removed, err := Prune(dir, 3)
	if err != nil || len(removed) != 2 {
		t.Fatalf("pruned %v %v", removed, err)
	}
	list, _ := List(dir)
	if len(list) != 3 || list[0].Name != "siplink-2026-11-19.db" {
		t.Errorf("left: %+v", list)
	}
}

func TestNextRun(t *testing.T) {
	loc := time.FixedZone("CST", 8*3600)
	now := time.Date(2026, 11, 16, 2, 0, 0, 0, loc)
	if got := NextRun(now, "03:30"); !got.Equal(time.Date(2026, 11, 16, 3, 30, 0, 0, loc)) {
		t.Error(got)
	}
	if got := NextRun(now.Add(2*time.Hour), "03:30"); !got.Equal(time.Date(2026, 11, 17, 3, 30, 0, 0, loc)) {
		t.Error(got)
	}
}
