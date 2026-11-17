package store

import (
	"context"
	"database/sql"
	"errors"
	"strings"
)

// Region is one region.
type Region struct {
	ID        int64
	Name      string
	Note      string
	CreatedAt string
	UpdatedAt string
}

// RegionUsage counts what still refers to a region.
type RegionUsage struct {
	Admins       int
	Books        int
	LDAPAccounts int
	Templates    int
	Clients      int
}

// Empty reports whether nothing refers to the region.
func (u RegionUsage) Empty() bool { return u == RegionUsage{} }

// ErrInUse is returned when an object is still referred to.
var ErrInUse = errors.New("store: still in use")

// ListRegions returns regions ordered by name; ids limits the result when not nil.
func (s *Store) ListRegions(ctx context.Context, ids []int64, all bool) ([]Region, error) {
	q := `SELECT id, name, note, created_at, updated_at FROM regions`
	var args []any
	if !all {
		if len(ids) == 0 {
			return nil, nil
		}
		q += ` WHERE id IN (` + placeholders(len(ids)) + `)`
		args = int64Args(ids)
	}
	rows, err := s.DB.QueryContext(ctx, q+` ORDER BY name COLLATE NOCASE`, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Region
	for rows.Next() {
		var r Region
		if err := rows.Scan(&r.ID, &r.Name, &r.Note, &r.CreatedAt, &r.UpdatedAt); err != nil {
			return nil, err
		}
		out = append(out, r)
	}
	return out, rows.Err()
}

// RegionByID loads one region.
func (s *Store) RegionByID(ctx context.Context, id int64) (*Region, error) {
	r := &Region{}
	err := s.DB.QueryRowContext(ctx, `SELECT id, name, note, created_at, updated_at FROM regions WHERE id = ?`, id).
		Scan(&r.ID, &r.Name, &r.Note, &r.CreatedAt, &r.UpdatedAt)
	return r, mapErr(err)
}

// RegionByName loads one region by name, case-insensitively.
func (s *Store) RegionByName(ctx context.Context, name string) (*Region, error) {
	r := &Region{}
	err := s.DB.QueryRowContext(ctx, `SELECT id, name, note, created_at, updated_at FROM regions WHERE name = ?`, strings.TrimSpace(name)).
		Scan(&r.ID, &r.Name, &r.Note, &r.CreatedAt, &r.UpdatedAt)
	return r, mapErr(err)
}

// CreateRegion inserts a region.
func (s *Store) CreateRegion(ctx context.Context, r *Region) error {
	now := s.NowText()
	res, err := s.DB.ExecContext(ctx, `INSERT INTO regions(name, note, created_at, updated_at) VALUES(?,?,?,?)`,
		r.Name, r.Note, now, now)
	if err != nil {
		return mapErr(err)
	}
	r.ID, _ = res.LastInsertId()
	r.CreatedAt, r.UpdatedAt = now, now
	return nil
}

// UpdateRegion renames a region and changes its note.
func (s *Store) UpdateRegion(ctx context.Context, r *Region) error {
	res, err := s.DB.ExecContext(ctx, `UPDATE regions SET name = ?, note = ?, updated_at = ? WHERE id = ?`,
		r.Name, r.Note, s.NowText(), r.ID)
	if err != nil {
		return mapErr(err)
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrNotFound
	}
	return nil
}

// RegionUsage counts the objects that refer to a region.
func (s *Store) RegionUsage(ctx context.Context, id int64) (RegionUsage, error) {
	return regionUsage(ctx, s.DB, id)
}

func regionUsage(ctx context.Context, q querier, id int64) (RegionUsage, error) {
	var u RegionUsage
	err := q.QueryRowContext(ctx, `SELECT (SELECT count(*) FROM admin_regions WHERE region_id = ?),
		(SELECT count(*) FROM address_books WHERE region_id = ?),
		(SELECT count(*) FROM ldap_accounts WHERE region_id = ?),
		(SELECT count(*) FROM config_templates WHERE region_id = ?) + (SELECT count(*) FROM key_templates WHERE region_id = ?)
			+ (SELECT count(*) FROM client_templates WHERE region_id = ?),
		(SELECT count(*) FROM clients WHERE region_id = ?)`, id, id, id, id, id, id, id).
		Scan(&u.Admins, &u.Books, &u.LDAPAccounts, &u.Templates, &u.Clients)
	return u, err
}

// DeleteRegion removes a region when nothing refers to it.
func (s *Store) DeleteRegion(ctx context.Context, id int64) error {
	return s.Tx(ctx, func(tx *sql.Tx) error {
		u, err := regionUsage(ctx, tx, id)
		if err != nil {
			return err
		}
		if !u.Empty() {
			return ErrInUse
		}
		res, err := tx.ExecContext(ctx, `DELETE FROM regions WHERE id = ?`, id)
		if err != nil {
			return err
		}
		if n, _ := res.RowsAffected(); n == 0 {
			return ErrNotFound
		}
		return nil
	})
}
