// Package testutil has helpers shared by tests. It is imported only from _test files.
package testutil

import (
	"context"
	"path/filepath"
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
	st, err := store.Open(context.Background(), filepath.Join(t.TempDir(), store.FileName))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { st.Close() })
	return st
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
