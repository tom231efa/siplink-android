package store

import (
	"context"
	"database/sql"
	"strings"
)

// LDAPAccount is a service account for desk phones and tools (design §4.3).
type LDAPAccount struct {
	ID           int64
	RegionID     *int64
	Name         string
	PasswordHash string // SHA-256 hex of a 32-character random secret
	Note         string
	Disabled     bool
	BookIDs      []int64
	CreatedAt    string
	UpdatedAt    string
}

const ldapAccountCols = `id, region_id, name, password_hash, note, disabled, created_at, updated_at`

func scanLDAPAccount(row interface{ Scan(...any) error }) (*LDAPAccount, error) {
	a := &LDAPAccount{}
	var region sql.NullInt64
	err := row.Scan(&a.ID, &region, &a.Name, &a.PasswordHash, &a.Note, &a.Disabled, &a.CreatedAt, &a.UpdatedAt)
	a.RegionID = ptrID(region)
	return a, mapErr(err)
}

func (s *Store) loadAccountBooks(ctx context.Context, a *LDAPAccount) error {
	rows, err := s.DB.QueryContext(ctx, `SELECT book_id FROM ldap_account_books WHERE account_id = ? ORDER BY book_id`, a.ID)
	if err != nil {
		return err
	}
	defer rows.Close()
	a.BookIDs = nil
	for rows.Next() {
		var id int64
		if err := rows.Scan(&id); err != nil {
			return err
		}
		a.BookIDs = append(a.BookIDs, id)
	}
	return rows.Err()
}

// ListLDAPAccounts returns the accounts of a scope (same rules as books) by name.
func (s *Store) ListLDAPAccounts(ctx context.Context, sc BookScope) ([]*LDAPAccount, error) {
	q := `SELECT ` + ldapAccountCols + ` FROM ldap_accounts`
	var where []string
	var args []any
	if !sc.All {
		cond := `region_id IS NULL`
		if len(sc.Regions) > 0 {
			cond = `(region_id IS NULL OR region_id IN (` + placeholders(len(sc.Regions)) + `))`
			args = append(args, int64Args(sc.Regions)...)
		}
		where = append(where, cond)
	}
	if sc.Region != 0 {
		where, args = append(where, `region_id = ?`), append(args, sc.Region)
	}
	if len(where) > 0 {
		q += ` WHERE ` + strings.Join(where, ` AND `)
	}
	rows, err := s.DB.QueryContext(ctx, q+` ORDER BY name`, args...)
	if err != nil {
		return nil, err
	}
	var out []*LDAPAccount
	for rows.Next() {
		a, err := scanLDAPAccount(rows)
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
		if err := s.loadAccountBooks(ctx, a); err != nil {
			return nil, err
		}
	}
	return out, nil
}

// LDAPAccountByID loads one account with its books.
func (s *Store) LDAPAccountByID(ctx context.Context, id int64) (*LDAPAccount, error) {
	a, err := scanLDAPAccount(s.DB.QueryRowContext(ctx, `SELECT `+ldapAccountCols+` FROM ldap_accounts WHERE id = ?`, id))
	if err != nil {
		return nil, err
	}
	return a, s.loadAccountBooks(ctx, a)
}

// LDAPAccountByName loads one account by name (without books).
func (s *Store) LDAPAccountByName(ctx context.Context, name string) (*LDAPAccount, error) {
	return scanLDAPAccount(s.DB.QueryRowContext(ctx, `SELECT `+ldapAccountCols+` FROM ldap_accounts WHERE name = ?`, name))
}

func setAccountBooks(ctx context.Context, tx *sql.Tx, id int64, books []int64) error {
	if _, err := tx.ExecContext(ctx, `DELETE FROM ldap_account_books WHERE account_id = ?`, id); err != nil {
		return err
	}
	for _, b := range books {
		if _, err := tx.ExecContext(ctx, `INSERT INTO ldap_account_books(account_id, book_id) VALUES(?, ?)`, id, b); err != nil {
			return mapErr(err)
		}
	}
	return nil
}

// CreateLDAPAccount inserts an account and its books.
func (s *Store) CreateLDAPAccount(ctx context.Context, a *LDAPAccount) error {
	now := s.NowText()
	return s.Tx(ctx, func(tx *sql.Tx) error {
		res, err := tx.ExecContext(ctx, `INSERT INTO ldap_accounts(region_id, name, password_hash, note, disabled, created_at, updated_at)
			VALUES(?,?,?,?,?,?,?)`, nullID(a.RegionID), a.Name, a.PasswordHash, a.Note, boolInt(a.Disabled), now, now)
		if err != nil {
			return mapErr(err)
		}
		a.ID, _ = res.LastInsertId()
		a.CreatedAt, a.UpdatedAt = now, now
		return setAccountBooks(ctx, tx, a.ID, a.BookIDs)
	})
}

// UpdateLDAPAccount writes region, note, disabled flag and books.
func (s *Store) UpdateLDAPAccount(ctx context.Context, a *LDAPAccount) error {
	now := s.NowText()
	return s.Tx(ctx, func(tx *sql.Tx) error {
		res, err := tx.ExecContext(ctx, `UPDATE ldap_accounts SET region_id = ?, note = ?, disabled = ?, updated_at = ? WHERE id = ?`,
			nullID(a.RegionID), a.Note, boolInt(a.Disabled), now, a.ID)
		if err != nil {
			return err
		}
		if n, _ := res.RowsAffected(); n == 0 {
			return ErrNotFound
		}
		return setAccountBooks(ctx, tx, a.ID, a.BookIDs)
	})
}

// SetLDAPAccountPassword stores a new password hash.
func (s *Store) SetLDAPAccountPassword(ctx context.Context, id int64, hash string) error {
	res, err := s.DB.ExecContext(ctx, `UPDATE ldap_accounts SET password_hash = ?, updated_at = ? WHERE id = ?`, hash, s.NowText(), id)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrNotFound
	}
	return nil
}

// DeleteLDAPAccount removes an account and its grants; ErrInUse while a configuration
// template binds a hosted book with it.
func (s *Store) DeleteLDAPAccount(ctx context.Context, id int64) error {
	return s.Tx(ctx, func(tx *sql.Tx) error {
		users, err := names(ctx, tx, `SELECT t.name FROM template_directories d JOIN config_templates t ON t.id = d.config_template_id
			WHERE d.ldap_account_id = ?`, id)
		if err != nil {
			return err
		}
		if len(users) > 0 {
			return ErrInUse
		}
		return deleteRow(ctx, tx, `DELETE FROM ldap_accounts WHERE id = ?`, id)
	})
}

// LDAPAccountUsers names the configuration templates that bind a hosted book with an account.
func (s *Store) LDAPAccountUsers(ctx context.Context, id int64) ([]string, error) {
	return names(ctx, s.DB, `SELECT DISTINCT t.name FROM template_directories d JOIN config_templates t ON t.id = d.config_template_id
		WHERE d.ldap_account_id = ? ORDER BY t.name`, id)
}

// AccountRegionsOfBook returns the regions (nil = global) of accounts granted a book.
func (s *Store) AccountRegionsOfBook(ctx context.Context, bookID int64) ([]*int64, error) {
	rows, err := s.DB.QueryContext(ctx, `SELECT a.region_id FROM ldap_accounts a JOIN ldap_account_books g ON g.account_id = a.id
		WHERE g.book_id = ?`, bookID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []*int64
	for rows.Next() {
		var r sql.NullInt64
		if err := rows.Scan(&r); err != nil {
			return nil, err
		}
		out = append(out, ptrID(r))
	}
	return out, rows.Err()
}

// BooksOfAccount returns the books an enabled account may read.
func (s *Store) BooksOfAccount(ctx context.Context, accountID int64) ([]*Book, error) {
	return s.queryBooks(ctx, `SELECT `+prefixed("b.", bookCols)+` FROM address_books b
		JOIN ldap_account_books g ON g.book_id = b.id JOIN ldap_accounts a ON a.id = g.account_id
		WHERE a.id = ? AND a.disabled = 0 ORDER BY b.name COLLATE NOCASE, b.id`, accountID)
}

// AnonymousBooks returns the books anyone may read.
func (s *Store) AnonymousBooks(ctx context.Context) ([]*Book, error) {
	return s.queryBooks(ctx, `SELECT `+bookCols+` FROM address_books WHERE allow_anonymous = 1 ORDER BY name COLLATE NOCASE, id`)
}

func (s *Store) queryBooks(ctx context.Context, q string, args ...any) ([]*Book, error) {
	rows, err := s.DB.QueryContext(ctx, q, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []*Book
	for rows.Next() {
		b, err := scanBook(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, b)
	}
	return out, rows.Err()
}

func prefixed(prefix, cols string) string {
	parts := strings.Split(cols, ",")
	for i, p := range parts {
		parts[i] = prefix + strings.TrimSpace(p)
	}
	return strings.Join(parts, ", ")
}
