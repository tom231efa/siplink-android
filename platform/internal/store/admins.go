package store

import (
	"context"
	"database/sql"
	"errors"
	"strings"
)

// Roles of administrators.
const (
	RoleSuper  = "super"
	RoleRegion = "region"
)

// Admin is one administrator.
type Admin struct {
	ID                 int64
	Username           string
	DisplayName        string
	PasswordHash       string
	Role               string
	Disabled           bool
	MustChangePassword bool
	LastLoginAt        string
	Email              string // "" = none (§6.8)
	EmailConfirmed     bool
	TwoStepEmail       bool   // the e-mail code method is on
	Language           string // the console's language (i18n code), "" = none chosen (§5.5)
	CreatedAt          string
	UpdatedAt          string
	RegionIDs          []int64
}

// ErrLastSuper is returned when a change would leave no enabled super administrator.
var ErrLastSuper = errors.New("store: the last super administrator cannot be disabled or demoted")

const adminCols = `id, username, display_name, password_hash, role, disabled, must_change_password,
	COALESCE(last_login_at, ''), created_at, updated_at, email, email_confirmed, two_step_email, language`

func scanAdmin(row interface{ Scan(...any) error }) (*Admin, error) {
	a := &Admin{}
	err := row.Scan(&a.ID, &a.Username, &a.DisplayName, &a.PasswordHash, &a.Role, &a.Disabled,
		&a.MustChangePassword, &a.LastLoginAt, &a.CreatedAt, &a.UpdatedAt, &a.Email, &a.EmailConfirmed, &a.TwoStepEmail, &a.Language)
	return a, mapErr(err)
}

func (s *Store) loadAdminRegions(ctx context.Context, q querier, a *Admin) error {
	rows, err := q.QueryContext(ctx, `SELECT region_id FROM admin_regions WHERE admin_id = ? ORDER BY region_id`, a.ID)
	if err != nil {
		return err
	}
	defer rows.Close()
	a.RegionIDs = nil
	for rows.Next() {
		var id int64
		if err := rows.Scan(&id); err != nil {
			return err
		}
		a.RegionIDs = append(a.RegionIDs, id)
	}
	return rows.Err()
}

// querier is satisfied by *sql.DB and *sql.Tx.
type querier interface {
	QueryContext(ctx context.Context, query string, args ...any) (*sql.Rows, error)
	QueryRowContext(ctx context.Context, query string, args ...any) *sql.Row
	ExecContext(ctx context.Context, query string, args ...any) (sql.Result, error)
}

// AdminByID loads an administrator with their regions.
func (s *Store) AdminByID(ctx context.Context, id int64) (*Admin, error) {
	a, err := scanAdmin(s.DB.QueryRowContext(ctx, `SELECT `+adminCols+` FROM admins WHERE id = ?`, id))
	if err != nil {
		return nil, err
	}
	return a, s.loadAdminRegions(ctx, s.DB, a)
}

// AdminByUsername loads an administrator by name, case-insensitively.
func (s *Store) AdminByUsername(ctx context.Context, username string) (*Admin, error) {
	a, err := scanAdmin(s.DB.QueryRowContext(ctx, `SELECT `+adminCols+` FROM admins WHERE username = ?`, strings.TrimSpace(username)))
	if err != nil {
		return nil, err
	}
	return a, s.loadAdminRegions(ctx, s.DB, a)
}

// ListAdmins returns all administrators ordered by name.
func (s *Store) ListAdmins(ctx context.Context) ([]*Admin, error) {
	rows, err := s.DB.QueryContext(ctx, `SELECT `+adminCols+` FROM admins ORDER BY username COLLATE NOCASE`)
	if err != nil {
		return nil, err
	}
	var out []*Admin
	for rows.Next() {
		a, err := scanAdmin(rows)
		if err != nil {
			rows.Close()
			return nil, err
		}
		out = append(out, a)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return nil, err
	}
	for _, a := range out {
		if err := s.loadAdminRegions(ctx, s.DB, a); err != nil {
			return nil, err
		}
	}
	return out, nil
}

// CountAdmins returns the number of administrators.
func (s *Store) CountAdmins(ctx context.Context) (int, error) {
	var n int
	err := s.DB.QueryRowContext(ctx, `SELECT count(*) FROM admins`).Scan(&n)
	return n, err
}

// CreateAdmin inserts an administrator and their regions.
func (s *Store) CreateAdmin(ctx context.Context, a *Admin) error {
	now := s.NowText()
	return s.Tx(ctx, func(tx *sql.Tx) error {
		res, err := tx.ExecContext(ctx, `INSERT INTO admins(username, display_name, password_hash, role, disabled,
			must_change_password, created_at, updated_at) VALUES(?,?,?,?,?,?,?,?)`,
			strings.TrimSpace(a.Username), a.DisplayName, a.PasswordHash, a.Role, boolInt(a.Disabled),
			boolInt(a.MustChangePassword), now, now)
		if err != nil {
			return mapErr(err)
		}
		a.ID, _ = res.LastInsertId()
		a.CreatedAt, a.UpdatedAt = now, now
		return setAdminRegions(ctx, tx, a.ID, a.Role, a.RegionIDs)
	})
}

func setAdminRegions(ctx context.Context, tx *sql.Tx, adminID int64, role string, regionIDs []int64) error {
	if _, err := tx.ExecContext(ctx, `DELETE FROM admin_regions WHERE admin_id = ?`, adminID); err != nil {
		return err
	}
	if role != RoleRegion {
		return nil
	}
	for _, rid := range regionIDs {
		if _, err := tx.ExecContext(ctx, `INSERT INTO admin_regions(admin_id, region_id) VALUES(?, ?)`, adminID, rid); err != nil {
			return mapErr(err)
		}
	}
	return nil
}

// enabledSupers counts enabled super administrators.
func enabledSupers(ctx context.Context, q querier) (int, error) {
	var n int
	err := q.QueryRowContext(ctx, `SELECT count(*) FROM admins WHERE role = 'super' AND disabled = 0`).Scan(&n)
	return n, err
}

// UpdateAdmin writes display name, role, disabled flag and regions. It refuses to
// leave no enabled super administrator. Sessions end when regions, role or the
// disabled flag change.
func (s *Store) UpdateAdmin(ctx context.Context, a *Admin) error {
	now := s.NowText()
	return s.Tx(ctx, func(tx *sql.Tx) error {
		old, err := scanAdmin(tx.QueryRowContext(ctx, `SELECT `+adminCols+` FROM admins WHERE id = ?`, a.ID))
		if err != nil {
			return err
		}
		if err := s.loadAdminRegions(ctx, tx, old); err != nil {
			return err
		}
		if _, err := tx.ExecContext(ctx, `UPDATE admins SET display_name = ?, role = ?, disabled = ?, updated_at = ? WHERE id = ?`,
			a.DisplayName, a.Role, boolInt(a.Disabled), now, a.ID); err != nil {
			return err
		}
		if old.Role == RoleSuper && !old.Disabled {
			n, err := enabledSupers(ctx, tx)
			if err != nil {
				return err
			}
			if n == 0 {
				return ErrLastSuper
			}
		}
		if err := setAdminRegions(ctx, tx, a.ID, a.Role, a.RegionIDs); err != nil {
			return err
		}
		if old.Role != a.Role || old.Disabled != a.Disabled || !sameIDs(old.RegionIDs, regionsFor(a)) {
			_, err = tx.ExecContext(ctx, `DELETE FROM sessions WHERE admin_id = ?`, a.ID)
		}
		return err
	})
}

func regionsFor(a *Admin) []int64 {
	if a.Role != RoleRegion {
		return nil
	}
	return a.RegionIDs
}

// sameIDs reports whether b holds exactly the ids of a, each once, in any order.
func sameIDs(a, b []int64) bool {
	if len(a) != len(b) {
		return false
	}
	set := map[int64]bool{}
	for _, v := range a {
		set[v] = true
	}
	for _, v := range b {
		if !set[v] {
			return false
		}
		delete(set, v)
	}
	return true
}

// SetAdminPassword stores a new hash and ends every session of the administrator.
func (s *Store) SetAdminPassword(ctx context.Context, id int64, hash string, mustChange bool) error {
	now := s.NowText()
	return s.Tx(ctx, func(tx *sql.Tx) error {
		res, err := tx.ExecContext(ctx, `UPDATE admins SET password_hash = ?, must_change_password = ?, updated_at = ? WHERE id = ?`,
			hash, boolInt(mustChange), now, id)
		if err != nil {
			return err
		}
		if n, _ := res.RowsAffected(); n == 0 {
			return ErrNotFound
		}
		_, err = tx.ExecContext(ctx, `DELETE FROM sessions WHERE admin_id = ?`, id)
		return err
	})
}

// TouchLogin records a successful login.
func (s *Store) TouchLogin(ctx context.Context, id int64) error {
	_, err := s.DB.ExecContext(ctx, `UPDATE admins SET last_login_at = ? WHERE id = ?`, s.NowText(), id)
	return err
}

// SetAdminLanguage stores the console language an administrator chose (§5.5). It is a
// display setting: it does not touch updated_at.
func (s *Store) SetAdminLanguage(ctx context.Context, id int64, code string) error {
	_, err := s.DB.ExecContext(ctx, `UPDATE admins SET language = ? WHERE id = ?`, code, id)
	return err
}

// AdminNamesByRegion returns the user names of administrators assigned to a region.
func (s *Store) AdminNamesByRegion(ctx context.Context, regionID int64) ([]string, error) {
	rows, err := s.DB.QueryContext(ctx, `SELECT a.username FROM admins a JOIN admin_regions r ON r.admin_id = a.id
		WHERE r.region_id = ? ORDER BY a.username COLLATE NOCASE`, regionID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []string
	for rows.Next() {
		var n string
		if err := rows.Scan(&n); err != nil {
			return nil, err
		}
		out = append(out, n)
	}
	return out, rows.Err()
}
