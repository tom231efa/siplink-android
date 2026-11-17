// Package backup copies the database with VACUUM INTO, daily and on demand, and keeps
// the newest files (design §6.6). The key file is not part of a backup.
package backup

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"log/slog"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"
)

// Dir is the backup directory inside the data directory.
func Dir(dataDir string) string { return filepath.Join(dataDir, "backups") }

const prefix, suffix = "siplink-", ".db"

// File is one backup file.
type File struct {
	Name    string
	Size    int64
	ModTime time.Time
}

// To writes a consistent copy of the database to path, which must not exist yet.
func To(ctx context.Context, db *sql.DB, path string) error {
	if _, err := os.Stat(path); err == nil {
		return fmt.Errorf("%s already exists", path)
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return err
	}
	if _, err := db.ExecContext(ctx, `VACUUM INTO ?`, path); err != nil {
		return err
	}
	return os.Chmod(path, 0o600)
}

// Now writes a backup into dir named after the local date (and time when a file of
// that day exists already) and returns its name.
func Now(ctx context.Context, db *sql.DB, dir string, now time.Time) (string, error) {
	name := prefix + now.Format("2006-01-02") + suffix
	if _, err := os.Stat(filepath.Join(dir, name)); err == nil {
		name = prefix + now.Format("2006-01-02-150405") + suffix
	}
	return name, To(ctx, db, filepath.Join(dir, name))
}

// List returns the backups of dir, newest first.
func List(dir string) ([]File, error) {
	entries, err := os.ReadDir(dir)
	if errors.Is(err, os.ErrNotExist) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	var out []File
	for _, e := range entries {
		if e.IsDir() || !strings.HasPrefix(e.Name(), prefix) || !strings.HasSuffix(e.Name(), suffix) {
			continue
		}
		info, err := e.Info()
		if err != nil {
			continue
		}
		out = append(out, File{Name: e.Name(), Size: info.Size(), ModTime: info.ModTime()})
	}
	sort.Slice(out, func(i, j int) bool { return out[i].ModTime.After(out[j].ModTime) })
	return out, nil
}

// Prune keeps the newest keep backups of dir and removes the others, one by one.
func Prune(dir string, keep int) ([]string, error) {
	list, err := List(dir)
	if err != nil || len(list) <= keep {
		return nil, err
	}
	var removed []string
	for _, f := range list[keep:] {
		if err := os.Remove(filepath.Join(dir, f.Name)); err != nil {
			return removed, err
		}
		removed = append(removed, f.Name)
	}
	return removed, nil
}

// NextRun returns the next time of day at (HH:MM, local) after now.
func NextRun(now time.Time, at string) time.Time {
	t, err := time.Parse("15:04", at)
	if err != nil {
		t = time.Date(0, 1, 1, 3, 30, 0, 0, time.UTC)
	}
	next := time.Date(now.Year(), now.Month(), now.Day(), t.Hour(), t.Minute(), 0, 0, now.Location())
	if !next.After(now) {
		next = next.AddDate(0, 0, 1)
	}
	return next
}

// Daily makes a backup every day at the given time and prunes old ones until ctx ends;
// done is called after each attempt (for the audit log).
func Daily(ctx context.Context, db *sql.DB, dir, at string, keep int, log *slog.Logger, done func(name string, err error)) {
	for {
		wait := time.Until(NextRun(time.Now(), at))
		select {
		case <-ctx.Done():
			return
		case <-time.After(wait):
		}
		name, err := Now(ctx, db, dir, time.Now())
		if err != nil {
			log.Error("backup failed", "err", err)
		} else {
			log.Info("backup written", "file", name)
			if removed, err := Prune(dir, keep); err != nil {
				log.Error("backup prune failed", "err", err)
			} else if len(removed) > 0 {
				log.Info("old backups removed", "files", removed)
			}
		}
		if done != nil {
			done(name, err)
		}
	}
}
