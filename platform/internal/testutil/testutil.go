// Package testutil has helpers shared by tests. It is imported only from _test files.
package testutil

import (
	"context"
	"database/sql"
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"sort"
	"strconv"
	"strings"
	"testing"
	"time"

	"siplink/internal/auth"
	"siplink/internal/secret"
	"siplink/internal/store"
)

// FastHashes lowers the argon2id cost for the duration of the test binary.
func FastHashes() { auth.HashParams = auth.Argon2Params{Memory: 1024, Time: 1, Threads: 1} }

// Store opens a fresh database file in a temporary directory.
func Store(t testing.TB) *store.Store {
	t.Helper()
	st, err := store.Open(context.Background(), filepath.Join(t.TempDir(), store.FileName), Box(t))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { st.Close() })
	return st
}

// StoreAt builds a database at an older schema from the migration files up to version
// and returns it with its path, for tests of later migrations. The caller closes it
// before opening the path with store.Open, which applies the rest.
func StoreAt(t testing.TB, version int) (*store.Store, string) {
	t.Helper()
	_, here, _, _ := runtime.Caller(0)
	files, err := filepath.Glob(filepath.Join(filepath.Dir(here), "..", "store", "migrations", "*.sql"))
	if err != nil || len(files) == 0 {
		t.Fatalf("migration files: %v", err)
	}
	sort.Strings(files)
	path := filepath.Join(t.TempDir(), store.FileName)
	db, err := sql.Open("sqlite", "file:"+path+"?_pragma=foreign_keys(1)&_pragma=busy_timeout(5000)")
	if err != nil {
		t.Fatal(err)
	}
	for _, f := range files {
		n, err := strconv.Atoi(strings.SplitN(filepath.Base(f), "_", 2)[0])
		if err != nil || n > version {
			continue
		}
		body, err := os.ReadFile(f)
		if err != nil {
			t.Fatal(err)
		}
		if _, err := db.Exec(string(body)); err != nil {
			t.Fatalf("%s: %v", filepath.Base(f), err)
		}
	}
	if _, err := db.Exec(`INSERT INTO meta(key, value) VALUES('schema_version', ?)`, fmt.Sprint(version)); err != nil {
		t.Fatal(err)
	}
	return &store.Store{DB: db, Now: time.Now}, path
}

// Box returns a secret box with a fixed test key.
func Box(t testing.TB) *secret.Box {
	var k [32]byte
	copy(k[:], "test-key-test-key-test-key-test!")
	b, err := secret.NewBox(k)
	if err != nil {
		t.Fatal(err)
	}
	return b
}

// Clock is a settable clock for tests.
type Clock struct{ T time.Time }

// Now returns the clock's time.
func (c *Clock) Now() time.Time { return c.T }

// Advance moves the clock.
func (c *Clock) Advance(d time.Duration) { c.T = c.T.Add(d) }
