package store

import (
	"context"
	"database/sql"
	"strings"

	"siplink/internal/secret"
)

// Kinds of directories (design §2.1): an address book hosted by the platform or an
// external LDAP server.
const (
	KindHosted   = "hosted"
	KindExternal = "external"
)

// ExternalDirectory is how phones reach another LDAP server (external_directories).
type ExternalDirectory struct {
	ID               int64
	RegionID         *int64
	Name             string
	Note             string
	Host             string
	Port             int
	Security         string
	BindDN           string
	PasswordMode     string // none / template (kept here for every client) / client
	PasswordEnc      string // sealed, password_mode = template only
	BaseDN           string
	MaxResults       int
	NameAttributes   string
	NumberAttributes string
	NameFilter       string
	NumberFilter     string
	CreatedAt        string
	UpdatedAt        string
}

// ExternalDirectoryAAD binds the sealed password of an external directory to its row (§6.1).
func ExternalDirectoryAAD(id int64) []byte {
	return secret.AAD("external_directories", id, "password_enc")
}

const externalCols = `id, region_id, name, note, host, port, security, bind_dn, password_mode, COALESCE(password_enc, ''),
	base_dn, max_results, name_attributes, number_attributes, name_filter, number_filter, created_at, updated_at`

func scanExternal(row interface{ Scan(...any) error }) (*ExternalDirectory, error) {
	e := &ExternalDirectory{}
	var region sql.NullInt64
	err := row.Scan(&e.ID, &region, &e.Name, &e.Note, &e.Host, &e.Port, &e.Security, &e.BindDN, &e.PasswordMode, &e.PasswordEnc,
		&e.BaseDN, &e.MaxResults, &e.NameAttributes, &e.NumberAttributes, &e.NameFilter, &e.NumberFilter, &e.CreatedAt, &e.UpdatedAt)
	e.RegionID = ptrID(region)
	return e, mapErr(err)
}

func queryExternals(ctx context.Context, q querier, where string, args ...any) ([]*ExternalDirectory, error) {
	rows, err := q.QueryContext(ctx, `SELECT `+externalCols+` FROM external_directories`+where+` ORDER BY name COLLATE NOCASE, id`, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []*ExternalDirectory
	for rows.Next() {
		e, err := scanExternal(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, e)
	}
	return out, rows.Err()
}

// ListExternalDirectories returns the external directories of a scope by name.
func (s *Store) ListExternalDirectories(ctx context.Context, sc BookScope) ([]*ExternalDirectory, error) {
	where, args := scopeWhere(sc)
	return queryExternals(ctx, s.DB, where, args...)
}

// ExternalDirectoryByID loads one external directory.
func (s *Store) ExternalDirectoryByID(ctx context.Context, id int64) (*ExternalDirectory, error) {
	return scanExternal(s.DB.QueryRowContext(ctx, `SELECT `+externalCols+` FROM external_directories WHERE id = ?`, id))
}

// SaveExternalDirectory inserts (ID 0) or updates an external directory. seal, when not
// nil, makes the sealed password for the row id; nil keeps the stored one. A mode other
// than template drops it. Every configuration template that uses the directory gets a new
// revision, so its phones fetch the change.
func (s *Store) SaveExternalDirectory(ctx context.Context, e *ExternalDirectory, seal func(id int64) (string, error)) error {
	now := s.NowText()
	return s.Tx(ctx, func(tx *sql.Tx) error {
		args := []any{nullID(e.RegionID), e.Name, e.Note, e.Host, e.Port, e.Security, e.BindDN, e.PasswordMode, e.BaseDN, e.MaxResults,
			e.NameAttributes, e.NumberAttributes, e.NameFilter, e.NumberFilter, now}
		if e.ID == 0 {
			res, err := tx.ExecContext(ctx, `INSERT INTO external_directories(region_id, name, note, host, port, security, bind_dn,
				password_mode, base_dn, max_results, name_attributes, number_attributes, name_filter, number_filter, updated_at,
				created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)`, append(args, now)...)
			if err != nil {
				return mapErr(err)
			}
			e.ID, _ = res.LastInsertId()
			e.CreatedAt = now
		} else if err := deleteRowLike(ctx, tx, `UPDATE external_directories SET region_id = ?, name = ?, note = ?, host = ?, port = ?,
			security = ?, bind_dn = ?, password_mode = ?, base_dn = ?, max_results = ?, name_attributes = ?, number_attributes = ?,
			name_filter = ?, number_filter = ?, updated_at = ? WHERE id = ?`, append(args, e.ID)...); err != nil {
			return err
		}
		e.UpdatedAt = now
		switch {
		case e.PasswordMode != "template":
			e.PasswordEnc = ""
			if _, err := tx.ExecContext(ctx, `UPDATE external_directories SET password_enc = NULL WHERE id = ?`, e.ID); err != nil {
				return err
			}
		case seal != nil:
			enc, err := seal(e.ID)
			if err != nil {
				return err
			}
			e.PasswordEnc = enc
			if _, err := tx.ExecContext(ctx, `UPDATE external_directories SET password_enc = ? WHERE id = ?`, enc, e.ID); err != nil {
				return err
			}
		}
		_, err := tx.ExecContext(ctx, `UPDATE config_templates SET revision = revision + 1, updated_at = ?
			WHERE id IN (SELECT config_template_id FROM template_directories WHERE external_id = ?)`, now, e.ID)
		return err
	})
}

// sourceCol names the column of template_directories that refers to a kind of directory.
func sourceCol(kind string) string {
	if kind == KindExternal {
		return "external_id"
	}
	return "book_id"
}

// DirectoryTemplates names the configuration templates that use a directory.
func (s *Store) DirectoryTemplates(ctx context.Context, kind string, id int64) ([]string, error) {
	return names(ctx, s.DB, `SELECT DISTINCT t.name FROM config_templates t JOIN template_directories d ON d.config_template_id = t.id
		WHERE d.`+sourceCol(kind)+` = ? ORDER BY t.name COLLATE NOCASE`, id)
}

// DirectoryClients counts the clients whose configuration template uses a directory.
func (s *Store) DirectoryClients(ctx context.Context, kind string, id int64) (int, error) {
	var n int
	err := s.DB.QueryRowContext(ctx, `SELECT count(*) FROM clients c JOIN client_templates t ON t.id = c.client_template_id
		WHERE t.config_template_id IN (SELECT config_template_id FROM template_directories WHERE `+sourceCol(kind)+` = ?)`, id).Scan(&n)
	return n, err
}

// DirectoryTemplateRegions returns the regions (nil = global) of the configuration
// templates that use a directory, for the ownership rule of moves (§2.3).
func (s *Store) DirectoryTemplateRegions(ctx context.Context, kind string, id int64) ([]*int64, error) {
	rows, err := s.DB.QueryContext(ctx, `SELECT DISTINCT t.region_id FROM config_templates t
		JOIN template_directories d ON d.config_template_id = t.id WHERE d.`+sourceCol(kind)+` = ?`, id)
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

// DeleteExternalDirectory removes an external directory no template uses.
func (s *Store) DeleteExternalDirectory(ctx context.Context, id int64) error {
	return s.Tx(ctx, func(tx *sql.Tx) error {
		var n int
		if err := tx.QueryRowContext(ctx, `SELECT count(*) FROM template_directories WHERE external_id = ?`, id).Scan(&n); err != nil {
			return err
		}
		if n > 0 {
			return ErrInUse
		}
		return deleteRow(ctx, tx, `DELETE FROM external_directories WHERE id = ?`, id)
	})
}

// DirectoryListRow is one row of the 通讯录 list: an address book or an external directory.
type DirectoryListRow struct {
	Kind       string
	ID         int64
	RegionID   *int64
	Name       string
	Slug       string // hosted
	EntryCount int    // hosted
	Host       string // external
	Port       int    // external
	Templates  int    // configuration templates that use it
}

// DirectoryFilter selects rows of the 通讯录 list.
type DirectoryFilter struct {
	Scope  BookScope
	Query  string // in the name, a book's slug or an external directory's host
	Kind   string // "" = both
	Limit  int
	Offset int
}

// ListDirectories returns one page of address books and external directories together,
// by name, and the number of matches.
func (s *Store) ListDirectories(ctx context.Context, f DirectoryFilter) ([]DirectoryListRow, int, error) {
	conds, args := scopeConds(f.Scope)
	if f.Kind != "" {
		conds, args = append(conds, `kind = ?`), append(args, f.Kind)
	}
	if q := strings.TrimSpace(f.Query); q != "" {
		like := "%" + strings.NewReplacer(`\`, `\\`, `%`, `\%`, `_`, `\_`).Replace(q) + "%"
		conds = append(conds, `(name LIKE ? ESCAPE '\' OR slug LIKE ? ESCAPE '\' OR host LIKE ? ESCAPE '\')`)
		args = append(args, like, like, like)
	}
	from := ` FROM (
		SELECT 'hosted' AS kind, b.id, b.region_id, b.name, b.slug, b.entry_count, '' AS host, 0 AS port,
			(SELECT count(DISTINCT d.config_template_id) FROM template_directories d WHERE d.book_id = b.id) AS uses
		FROM address_books b
		UNION ALL
		SELECT 'external', e.id, e.region_id, e.name, '', 0, e.host, e.port,
			(SELECT count(DISTINCT d.config_template_id) FROM template_directories d WHERE d.external_id = e.id)
		FROM external_directories e)`
	where := ""
	if len(conds) > 0 {
		where = ` WHERE ` + strings.Join(conds, ` AND `)
	}
	var total int
	if err := s.DB.QueryRowContext(ctx, `SELECT count(*)`+from+where, args...).Scan(&total); err != nil {
		return nil, 0, err
	}
	rows, err := s.DB.QueryContext(ctx, `SELECT kind, id, region_id, name, slug, entry_count, host, port, uses`+from+where+
		` ORDER BY name COLLATE NOCASE, kind, id LIMIT ? OFFSET ?`, append(args, f.Limit, f.Offset)...)
	if err != nil {
		return nil, 0, err
	}
	defer rows.Close()
	var out []DirectoryListRow
	for rows.Next() {
		var d DirectoryListRow
		var region sql.NullInt64
		if err := rows.Scan(&d.Kind, &d.ID, &region, &d.Name, &d.Slug, &d.EntryCount, &d.Host, &d.Port, &d.Templates); err != nil {
			return nil, 0, err
		}
		d.RegionID = ptrID(region)
		out = append(out, d)
	}
	return out, total, rows.Err()
}
