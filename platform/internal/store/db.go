// Package store keeps all state in one SQLite file (design §2.2). Plain SQL, one
// file per area.
package store

import (
	"context"
	"database/sql"
	"embed"
	"errors"
	"fmt"
	"io/fs"
	"net/url"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"time"

	"siplink/internal/secret"

	_ "modernc.org/sqlite" // registers the "sqlite" driver
)

//go:embed migrations/*.sql
var migrationFiles embed.FS

// TimeFormat is the stored form of every timestamp: UTC, second precision, so that
// text comparison equals time comparison.
const TimeFormat = "2006-01-02T15:04:05Z"

// ErrNotFound is returned when a row does not exist.
var ErrNotFound = errors.New("store: not found")

// ErrConflict is returned for a unique constraint violation.
var ErrConflict = errors.New("store: already exists")

// Store wraps the database.
type Store struct {
	DB  *sql.DB
	Now func() time.Time
}

// FileName is the name of the database inside the data directory.
const FileName = "siplink.db"

// maxConns is the number of connections to the database, open and idle alike.
const maxConns = 8

// Open opens (and creates) the database file and applies pending migrations. box is the
// secret box of the key file: a migration that moves sealed values to other rows needs it
// (goSteps).
func Open(ctx context.Context, path string, box *secret.Box) (*Store, error) {
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return nil, err
	}
	q := url.Values{}
	q.Add("_pragma", "foreign_keys(1)")
	q.Add("_pragma", "journal_mode(WAL)")
	q.Add("_pragma", "busy_timeout(5000)")
	q.Add("_pragma", "synchronous(NORMAL)")
	q.Set("_txlock", "immediate")
	db, err := sql.Open("sqlite", "file:"+path+"?"+q.Encode())
	if err != nil {
		return nil, err
	}
	// As many idle connections as open ones: with the default of 2, concurrent requests
	// close and reopen connections (each open runs the pragmas above).
	db.SetMaxOpenConns(maxConns)
	db.SetMaxIdleConns(maxConns)
	s := &Store{DB: db, Now: time.Now}
	if err := s.Migrate(ctx, box); err != nil {
		db.Close()
		return nil, err
	}
	// The database holds hashes and sealed secrets: owner only.
	for _, f := range []string{path, path + "-wal", path + "-shm"} {
		_ = os.Chmod(f, 0o600)
	}
	return s, nil
}

// Close closes the database.
func (s *Store) Close() error { return s.DB.Close() }

// NowText returns the current time in the stored form.
func (s *Store) NowText() string { return s.Now().UTC().Format(TimeFormat) }

// SchemaVersion returns the number of the last applied migration.
func (s *Store) SchemaVersion(ctx context.Context) (int, error) {
	var exists int
	if err := s.DB.QueryRowContext(ctx, `SELECT count(*) FROM sqlite_master WHERE type='table' AND name='meta'`).Scan(&exists); err != nil {
		return 0, err
	}
	if exists == 0 {
		return 0, nil
	}
	v, err := s.Meta(ctx, "schema_version")
	if errors.Is(err, ErrNotFound) {
		return 0, nil
	}
	if err != nil {
		return 0, err
	}
	return strconv.Atoi(v)
}

// goSteps are the parts of migrations written in Go, by migration number. Each runs in
// the transaction of its migration, after the migration's SQL.
var goSteps = map[int]func(ctx context.Context, tx *sql.Tx, box *secret.Box, now string) error{
	8: moveExternalDirectories,
}

// Migrate applies every embedded migration newer than the schema version, each in
// its own transaction.
func (s *Store) Migrate(ctx context.Context, box *secret.Box) error {
	names, err := fs.Glob(migrationFiles, "migrations/*.sql")
	if err != nil {
		return err
	}
	sort.Strings(names)
	current, err := s.SchemaVersion(ctx)
	if err != nil {
		return err
	}
	for _, name := range names {
		base := filepath.Base(name)
		n, err := strconv.Atoi(strings.SplitN(base, "_", 2)[0])
		if err != nil {
			return fmt.Errorf("migration %s: bad number", base)
		}
		if n <= current {
			continue
		}
		body, err := migrationFiles.ReadFile(name)
		if err != nil {
			return err
		}
		err = s.Tx(ctx, func(tx *sql.Tx) error {
			if _, err := tx.ExecContext(ctx, string(body)); err != nil {
				return fmt.Errorf("migration %s: %w", base, err)
			}
			if step := goSteps[n]; step != nil {
				if err := step(ctx, tx, box, s.NowText()); err != nil {
					return fmt.Errorf("migration %s: %w", base, err)
				}
			}
			_, err := tx.ExecContext(ctx, `INSERT INTO meta(key, value) VALUES('schema_version', ?)
				ON CONFLICT(key) DO UPDATE SET value = excluded.value`, strconv.Itoa(n))
			return err
		})
		if err != nil {
			return err
		}
	}
	return nil
}

// Tx runs fn inside a transaction and commits when fn returns nil.
func (s *Store) Tx(ctx context.Context, fn func(tx *sql.Tx) error) error {
	tx, err := s.DB.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	if err := fn(tx); err != nil {
		tx.Rollback()
		return err
	}
	return tx.Commit()
}

// Meta reads a value of the meta table.
func (s *Store) Meta(ctx context.Context, key string) (string, error) {
	var v string
	err := s.DB.QueryRowContext(ctx, `SELECT value FROM meta WHERE key = ?`, key).Scan(&v)
	if errors.Is(err, sql.ErrNoRows) {
		return "", ErrNotFound
	}
	return v, err
}

// SetMeta writes a value of the meta table.
func (s *Store) SetMeta(ctx context.Context, key, value string) error {
	_, err := s.DB.ExecContext(ctx, `INSERT INTO meta(key, value) VALUES(?, ?)
		ON CONFLICT(key) DO UPDATE SET value = excluded.value`, key, value)
	return err
}

// isUnique reports whether err is a unique constraint violation.
func isUnique(err error) bool {
	return err != nil && strings.Contains(err.Error(), "UNIQUE constraint failed")
}

// mapErr turns driver errors into the store's errors.
func mapErr(err error) error {
	switch {
	case err == nil:
		return nil
	case errors.Is(err, sql.ErrNoRows):
		return ErrNotFound
	case isUnique(err):
		return ErrConflict
	}
	return err
}

func boolInt(b bool) int {
	if b {
		return 1
	}
	return 0
}

// nullID turns a nil region into SQL NULL.
func nullID(id *int64) any {
	if id == nil {
		return nil
	}
	return *id
}

func ptrID(n sql.NullInt64) *int64 {
	if !n.Valid {
		return nil
	}
	v := n.Int64
	return &v
}

// ParseTime reads a stored timestamp; the zero time for an empty or bad value.
func ParseTime(s string) time.Time {
	t, err := time.Parse(TimeFormat, s)
	if err != nil {
		return time.Time{}
	}
	return t
}

// placeholders returns "?,?,?" for n values.
func placeholders(n int) string {
	if n <= 0 {
		return ""
	}
	return strings.Repeat("?,", n-1) + "?"
}

func int64Args(ids []int64) []any {
	out := make([]any, len(ids))
	for i, id := range ids {
		out[i] = id
	}
	return out
}

func stringArgs(values []string) []any {
	out := make([]any, len(values))
	for i, v := range values {
		out[i] = v
	}
	return out
}
