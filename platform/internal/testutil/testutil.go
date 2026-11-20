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

// OldClient stores a client as schemas 7 to 10 did, for tests of later migrations (the
// store's code reads and writes the group of migration 0011): the row with c's fields,
// the sealed credentials and the per-client directory passwords. It sets c.ID.
func OldClient(t testing.TB, st *store.Store, box *secret.Box, c *store.Client, creds []store.CredentialInput, dirs map[int64]string) {
	t.Helper()
	ctx := context.Background()
	must := func(err error) {
		t.Helper()
		if err != nil {
			t.Fatal(err)
		}
	}
	now := st.NowText()
	res, err := st.DB.ExecContext(ctx, `INSERT INTO clients(region_id, client_template_id, name, note, public_id, status,
		forward_always, forward_busy, forward_no_answer, created_at, updated_at) VALUES(?,?,?,?,?,'pending',?,?,?,?,?)`,
		c.RegionID, c.ClientTemplateID, c.Name, c.Note, store.NewPublicID(), c.ForwardAlways, c.ForwardBusy, c.ForwardNoAnswer, now, now)
	must(err)
	c.ID, _ = res.LastInsertId()
	for _, in := range creds {
		enc, err := box.Seal([]byte(in.Password), store.CredentialAAD(c.ID, in.ProfileID))
		must(err)
		_, err = st.DB.ExecContext(ctx, `INSERT INTO client_credentials(client_id, profile_id, username, auth_user, password_enc,
			display_name, account_name) VALUES(?,?,?,?,?,?,?)`, c.ID, in.ProfileID, in.Username, in.AuthUser, enc, in.DisplayName, in.AccountName)
		must(err)
	}
	for dir, password := range dirs {
		enc, err := box.Seal([]byte(password), store.DirectoryPasswordAAD(c.ID, dir))
		must(err)
		_, err = st.DB.ExecContext(ctx, `INSERT INTO client_directory_passwords(client_id, directory_id, password_enc) VALUES(?,?,?)`,
			c.ID, dir, enc)
		must(err)
	}
}

// OldProfile stores a profile as schemas 4 to 12 did, for tests of later migrations (the
// store's code reads and writes the fast server timeout of migration 0013): the row with
// p's fields, the default kept as SaveProfile keeps it and the template's revision raised.
// It sets p.ID and p.Position.
func OldProfile(t testing.TB, st *store.Store, p *store.Profile) {
	t.Helper()
	ctx := context.Background()
	now := st.NowText()
	err := st.Tx(ctx, func(tx *sql.Tx) error {
		var n int
		if err := tx.QueryRowContext(ctx, `SELECT count(*), COALESCE(max(position), 0) FROM account_profiles WHERE config_template_id = ?`,
			p.TemplateID).Scan(&n, &p.Position); err != nil {
			return err
		}
		p.Position++
		res, err := tx.ExecContext(ctx, `INSERT INTO account_profiles(config_template_id, position, name, domain, port, server,
			backup_server, dns_server, transport, expires, subscribe_mwi, enabled, is_default, created_at, updated_at)
			VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)`, p.TemplateID, p.Position, p.Name, p.Domain, p.Port, p.Server, p.BackupServer,
			p.DNSServer, p.Transport, p.Expires, p.SubscribeMWI, p.Enabled, p.IsDefault || n == 0, now, now)
		if err != nil {
			return err
		}
		p.ID, _ = res.LastInsertId()
		if p.IsDefault {
			if _, err := tx.ExecContext(ctx, `UPDATE account_profiles SET is_default = (id = ?) WHERE config_template_id = ?`,
				p.ID, p.TemplateID); err != nil {
				return err
			}
		}
		_, err = tx.ExecContext(ctx, `UPDATE config_templates SET revision = revision + 1, updated_at = ? WHERE id = ?`, now, p.TemplateID)
		return err
	})
	if err != nil {
		t.Fatal(err)
	}
}

// ManyClients fills region with groups groups, tags tags and n clients of template, for
// tests of the client list at scale: client i is in group i mod (groups+1) (the last one
// meaning none) and carries three tags. It returns the ids of the groups and the tags.
func ManyClients(t testing.TB, st *store.Store, region, template int64, n, groups, tags int) (groupIDs, tagIDs []int64) {
	t.Helper()
	ctx := context.Background()
	must := func(err error) {
		t.Helper()
		if err != nil {
			t.Fatal(err)
		}
	}
	for i := range groups {
		g := &store.Group{Name: fmt.Sprintf("G%03d", i), RegionID: region}
		must(st.SaveGroup(ctx, g, groups))
		groupIDs = append(groupIDs, g.ID)
	}
	for i := range tags {
		tg := &store.Tag{Name: fmt.Sprintf("T%03d", i), Color: "gray"}
		must(st.CreateTag(ctx, tg, tags))
		tagIDs = append(tagIDs, tg.ID)
	}
	now := st.NowText()
	must(st.Tx(ctx, func(tx *sql.Tx) error {
		for i := range n {
			var group any
			if k := i % (groups + 1); k < groups {
				group = groupIDs[k]
			}
			res, err := tx.ExecContext(ctx, `INSERT INTO clients(region_id, client_template_id, name, note, public_id, status,
				group_id, created_at, updated_at) VALUES(?,?,?,'',?,'pending',?,?,?)`,
				region, template, fmt.Sprintf("C%05d", i), store.NewPublicID(), group, now, now)
			if err != nil {
				return err
			}
			id, _ := res.LastInsertId()
			for _, k := range []int{i % tags, (i/tags + 1 + i) % tags, (i*7 + 3) % tags} {
				if _, err := tx.ExecContext(ctx, `INSERT OR IGNORE INTO client_tags(client_id, tag_id) VALUES(?,?)`, id, tagIDs[k]); err != nil {
					return err
				}
			}
		}
		return nil
	}))
	return groupIDs, tagIDs
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
