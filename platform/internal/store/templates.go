package store

import (
	"context"
	"database/sql"
	"errors"
	"slices"
	"strings"

	"siplink/internal/secret"
)

// ConfigTemplate holds everything handed out except shortcut keys (design §2.1).
type ConfigTemplate struct {
	ID                 int64
	RegionID           *int64
	Name               string
	Note               string
	SettingsJSON       string // the settings object of the document (§3.5.3)
	CodecsJSON         string // the codecs object of the document, "" = SDK defaults
	CAPEMs             string // PEM bundle
	ConfigPasswordHash string // SHA-256 hex or ""
	RefreshMinutes     int
	Revision           int64
	CreatedAt          string
	UpdatedAt          string
}

// Profile is the server side of an account (account_profiles).
type Profile struct {
	ID           int64
	TemplateID   int64
	Position     int
	Name         string
	Domain       string
	Port         string
	Server       string
	BackupServer string
	DNSServer    string
	Transport    string
	Expires      int
	SubscribeMWI bool
	Enabled      bool
	IsDefault    bool
}

// Directory is the use of a directory in a template (template_directories): it refers
// to a hosted book or an external directory and adds what belongs to this use.
type Directory struct {
	ID             int64
	TemplateID     int64
	Position       int
	Kind           string // KindHosted / KindExternal; fixed with the source when the use is made
	BookID         *int64 // hosted
	ExternalID     *int64 // external
	Name           string // shown on the phone; "" = the directory's name
	Enabled        bool
	LineProfileID  *int64
	LookupIncoming bool
	LookupOutgoing bool
	MaxResults     int    // hosted only (an external directory has its own)
	PasswordMode   string // hosted bound by hand only: template / client; else none
	PasswordEnc    string // sealed, hosted bound by hand with password_mode = template only
	LDAPAccountID  *int64 // hosted only: bind with this service account; nil = each client's derived login

	Source   string             // name of the book or external directory (read only)
	External *ExternalDirectory // the external directory (read only), loaded with the use
}

// DisplayName is the name the phone shows: the use's own, else the directory's.
func (d *Directory) DisplayName() string {
	if d.Name != "" {
		return d.Name
	}
	return d.Source
}

// Password returns the password handling that applies to the use and, for mode
// template, the sealed password and its associated data: the external directory's, or
// the use's own for a hosted book bound by hand. Mode client passwords are the client's
// (DirectoryPasswordAAD).
func (d *Directory) Password() (mode, enc string, aad []byte) {
	if d.Kind == KindExternal {
		if d.External == nil {
			return "none", "", nil
		}
		return d.External.PasswordMode, d.External.PasswordEnc, ExternalDirectoryAAD(d.External.ID)
	}
	return d.PasswordMode, d.PasswordEnc, secret.AAD("template_directories", d.ID, "password_enc")
}

const configTemplateCols = `id, region_id, name, note, settings_json, codecs_json, ca_pems, config_password_hash,
	refresh_minutes, revision, created_at, updated_at`

func scanConfigTemplate(row interface{ Scan(...any) error }) (*ConfigTemplate, error) {
	t := &ConfigTemplate{}
	var region sql.NullInt64
	err := row.Scan(&t.ID, &region, &t.Name, &t.Note, &t.SettingsJSON, &t.CodecsJSON, &t.CAPEMs, &t.ConfigPasswordHash,
		&t.RefreshMinutes, &t.Revision, &t.CreatedAt, &t.UpdatedAt)
	t.RegionID = ptrID(region)
	return t, mapErr(err)
}

// scopeConds are the conditions for objects of a scope: global ones and those of the
// given regions (or all), narrowed to one region when the scope says so.
func scopeConds(sc BookScope) ([]string, []any) {
	var conds []string
	var args []any
	if !sc.All {
		cond := `region_id IS NULL`
		if len(sc.Regions) > 0 {
			cond = `(region_id IS NULL OR region_id IN (` + placeholders(len(sc.Regions)) + `))`
			args = append(args, int64Args(sc.Regions)...)
		}
		conds = append(conds, cond)
	}
	if sc.Region != 0 {
		conds, args = append(conds, `region_id = ?`), append(args, sc.Region)
	}
	return conds, args
}

// scopeWhere builds the WHERE part for objects of a scope.
func scopeWhere(sc BookScope) (string, []any) {
	conds, args := scopeConds(sc)
	if len(conds) == 0 {
		return "", nil
	}
	return ` WHERE ` + strings.Join(conds, ` AND `), args
}

// ListConfigTemplates returns the templates of a scope by name.
func (s *Store) ListConfigTemplates(ctx context.Context, sc BookScope) ([]*ConfigTemplate, error) {
	where, args := scopeWhere(sc)
	rows, err := s.DB.QueryContext(ctx, `SELECT `+configTemplateCols+` FROM config_templates`+where+` ORDER BY name COLLATE NOCASE, id`, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []*ConfigTemplate
	for rows.Next() {
		t, err := scanConfigTemplate(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, t)
	}
	return out, rows.Err()
}

// ConfigTemplateByID loads one template.
func (s *Store) ConfigTemplateByID(ctx context.Context, id int64) (*ConfigTemplate, error) {
	return scanConfigTemplate(s.DB.QueryRowContext(ctx, `SELECT `+configTemplateCols+` FROM config_templates WHERE id = ?`, id))
}

// CreateConfigTemplate inserts a template.
func (s *Store) CreateConfigTemplate(ctx context.Context, t *ConfigTemplate) error {
	now := s.NowText()
	res, err := s.DB.ExecContext(ctx, `INSERT INTO config_templates(region_id, name, note, settings_json, codecs_json, ca_pems,
		config_password_hash, refresh_minutes, created_at, updated_at) VALUES(?,?,?,?,?,?,?,?,?,?)`,
		nullID(t.RegionID), t.Name, t.Note, t.SettingsJSON, t.CodecsJSON, t.CAPEMs, t.ConfigPasswordHash, t.RefreshMinutes, now, now)
	if err != nil {
		return mapErr(err)
	}
	t.ID, _ = res.LastInsertId()
	t.Revision, t.CreatedAt, t.UpdatedAt = 1, now, now
	return nil
}

// UpdateConfigTemplate writes every column of the template row and bumps its revision.
func (s *Store) UpdateConfigTemplate(ctx context.Context, t *ConfigTemplate) error {
	res, err := s.DB.ExecContext(ctx, `UPDATE config_templates SET region_id = ?, name = ?, note = ?, settings_json = ?,
		codecs_json = ?, ca_pems = ?, config_password_hash = ?, refresh_minutes = ?, revision = revision + 1, updated_at = ?
		WHERE id = ?`, nullID(t.RegionID), t.Name, t.Note, t.SettingsJSON, t.CodecsJSON, t.CAPEMs, t.ConfigPasswordHash,
		t.RefreshMinutes, s.NowText(), t.ID)
	if err != nil {
		return mapErr(err)
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrNotFound
	}
	return nil
}

// ConfigTemplateUsers names the client templates that use a configuration template.
func (s *Store) ConfigTemplateUsers(ctx context.Context, id int64) ([]string, error) {
	return names(ctx, s.DB, `SELECT '客户端模板 ' || name FROM client_templates WHERE config_template_id = ? ORDER BY name`, id)
}

// DeleteConfigTemplate removes a template nothing uses.
func (s *Store) DeleteConfigTemplate(ctx context.Context, id int64) error {
	return s.Tx(ctx, func(tx *sql.Tx) error {
		users, err := names(ctx, tx, `SELECT name FROM client_templates WHERE config_template_id = ?`, id)
		if err != nil {
			return err
		}
		if len(users) > 0 {
			return ErrInUse
		}
		return deleteRow(ctx, tx, `DELETE FROM config_templates WHERE id = ?`, id)
	})
}

func names(ctx context.Context, q querier, query string, args ...any) ([]string, error) {
	rows, err := q.QueryContext(ctx, query, args...)
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

func deleteRow(ctx context.Context, q querier, query string, args ...any) error {
	res, err := q.ExecContext(ctx, query, args...)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrNotFound
	}
	return nil
}

func bumpConfigTemplate(ctx context.Context, q querier, id int64, now string) error {
	_, err := q.ExecContext(ctx, `UPDATE config_templates SET revision = revision + 1, updated_at = ? WHERE id = ?`, now, id)
	return err
}

// ---- account profiles ----

const profileCols = `id, config_template_id, position, name, domain, port, server, backup_server, dns_server, transport,
	expires, subscribe_mwi, enabled, is_default`

func scanProfile(row interface{ Scan(...any) error }) (*Profile, error) {
	p := &Profile{}
	err := row.Scan(&p.ID, &p.TemplateID, &p.Position, &p.Name, &p.Domain, &p.Port, &p.Server, &p.BackupServer, &p.DNSServer,
		&p.Transport, &p.Expires, &p.SubscribeMWI, &p.Enabled, &p.IsDefault)
	return p, mapErr(err)
}

// Profiles returns the profiles of a template in order.
func (s *Store) Profiles(ctx context.Context, templateID int64) ([]*Profile, error) {
	rows, err := s.DB.QueryContext(ctx, `SELECT `+profileCols+` FROM account_profiles WHERE config_template_id = ? ORDER BY position, id`, templateID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []*Profile
	for rows.Next() {
		p, err := scanProfile(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, p)
	}
	return out, rows.Err()
}

// ProfileByID loads one profile of a template.
func (s *Store) ProfileByID(ctx context.Context, templateID, id int64) (*Profile, error) {
	return scanProfile(s.DB.QueryRowContext(ctx, `SELECT `+profileCols+` FROM account_profiles WHERE id = ? AND config_template_id = ?`, id, templateID))
}

// SaveProfile inserts (ID 0) or updates a profile, keeping at most max per template
// and exactly one default when there are profiles.
func (s *Store) SaveProfile(ctx context.Context, p *Profile, max int) error {
	now := s.NowText()
	return s.Tx(ctx, func(tx *sql.Tx) error {
		if p.ID == 0 {
			var n, pos int
			if err := tx.QueryRowContext(ctx, `SELECT count(*), COALESCE(max(position), 0) FROM account_profiles WHERE config_template_id = ?`,
				p.TemplateID).Scan(&n, &pos); err != nil {
				return err
			}
			if n >= max {
				return ErrLimit
			}
			p.Position = pos + 1
			res, err := tx.ExecContext(ctx, `INSERT INTO account_profiles(config_template_id, position, name, domain, port, server,
				backup_server, dns_server, transport, expires, subscribe_mwi, enabled, is_default, created_at, updated_at)
				VALUES(?,?,?,?,?,?,?,?,?,?,?,?,0,?,?)`, p.TemplateID, p.Position, p.Name, p.Domain, p.Port, p.Server, p.BackupServer,
				p.DNSServer, p.Transport, p.Expires, boolInt(p.SubscribeMWI), boolInt(p.Enabled), now, now)
			if err != nil {
				return mapErr(err)
			}
			p.ID, _ = res.LastInsertId()
		} else if err := deleteRowLike(ctx, tx, `UPDATE account_profiles SET name = ?, domain = ?, port = ?, server = ?, backup_server = ?,
			dns_server = ?, transport = ?, expires = ?, subscribe_mwi = ?, enabled = ?, updated_at = ? WHERE id = ? AND config_template_id = ?`,
			p.Name, p.Domain, p.Port, p.Server, p.BackupServer, p.DNSServer, p.Transport, p.Expires, boolInt(p.SubscribeMWI),
			boolInt(p.Enabled), now, p.ID, p.TemplateID); err != nil {
			return err
		}
		if p.IsDefault {
			if err := setDefaultProfile(ctx, tx, p.TemplateID, p.ID); err != nil {
				return err
			}
		}
		if err := fixDefaultProfile(ctx, tx, p.TemplateID); err != nil {
			return err
		}
		return bumpConfigTemplate(ctx, tx, p.TemplateID, now)
	})
}

// deleteRowLike runs a statement that must touch one row.
func deleteRowLike(ctx context.Context, q querier, query string, args ...any) error {
	res, err := q.ExecContext(ctx, query, args...)
	if err != nil {
		return mapErr(err)
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrNotFound
	}
	return nil
}

func setDefaultProfile(ctx context.Context, q querier, templateID, id int64) error {
	_, err := q.ExecContext(ctx, `UPDATE account_profiles SET is_default = (id = ?) WHERE config_template_id = ?`, id, templateID)
	return err
}

// fixDefaultProfile makes the first profile the default when none is.
func fixDefaultProfile(ctx context.Context, q querier, templateID int64) error {
	var n int
	if err := q.QueryRowContext(ctx, `SELECT count(*) FROM account_profiles WHERE config_template_id = ? AND is_default = 1`, templateID).Scan(&n); err != nil {
		return err
	}
	if n > 0 {
		return nil
	}
	_, err := q.ExecContext(ctx, `UPDATE account_profiles SET is_default = 1 WHERE id = (SELECT id FROM account_profiles
		WHERE config_template_id = ? ORDER BY position, id LIMIT 1)`, templateID)
	return err
}

// SetDefaultProfile marks one profile as the default account.
func (s *Store) SetDefaultProfile(ctx context.Context, templateID, id int64) error {
	now := s.NowText()
	return s.Tx(ctx, func(tx *sql.Tx) error {
		if err := setDefaultProfile(ctx, tx, templateID, id); err != nil {
			return err
		}
		return bumpConfigTemplate(ctx, tx, templateID, now)
	})
}

// DeleteProfile removes a profile (its client credentials go with it).
func (s *Store) DeleteProfile(ctx context.Context, templateID, id int64) error {
	now := s.NowText()
	return s.Tx(ctx, func(tx *sql.Tx) error {
		if err := deleteRow(ctx, tx, `DELETE FROM account_profiles WHERE id = ? AND config_template_id = ?`, id, templateID); err != nil {
			return err
		}
		if err := fixDefaultProfile(ctx, tx, templateID); err != nil {
			return err
		}
		return bumpConfigTemplate(ctx, tx, templateID, now)
	})
}

// ErrOrder means the ids given to Reorder are not exactly the rows of the list.
var ErrOrder = errors.New("store: not the rows of the list")

// Reorder gives the rows of an ordered child table the order of ids, which must name
// every row of the parent once. table is one of account_profiles,
// template_directories, template_keys.
func (s *Store) Reorder(ctx context.Context, table string, parentID int64, ids []int64) error {
	parentCol, bump := map[string]string{
		"account_profiles":     "config_template_id",
		"template_directories": "config_template_id",
		"template_keys":        "key_template_id",
	}[table], bumpConfigTemplate
	if parentCol == "" {
		return errors.New("store: not an ordered table")
	}
	if table == "template_keys" {
		bump = bumpKeyTemplate
	}
	now := s.NowText()
	return s.Tx(ctx, func(tx *sql.Tx) error {
		have, err := orderedIDs(ctx, tx, `SELECT id FROM `+table+` WHERE `+parentCol+` = ? ORDER BY position, id`, parentID)
		if err != nil {
			return err
		}
		if !sameIDs(have, ids) {
			return ErrOrder
		}
		if slices.Equal(have, ids) {
			return nil
		}
		for pos, id := range ids {
			if _, err := tx.ExecContext(ctx, `UPDATE `+table+` SET position = ? WHERE id = ?`, pos+1, id); err != nil {
				return err
			}
		}
		return bump(ctx, tx, parentID, now)
	})
}

func orderedIDs(ctx context.Context, q querier, query string, args ...any) ([]int64, error) {
	rows, err := q.QueryContext(ctx, query, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []int64
	for rows.Next() {
		var id int64
		if err := rows.Scan(&id); err != nil {
			return nil, err
		}
		out = append(out, id)
	}
	return out, rows.Err()
}

// ---- directories ----

const directoryCols = `d.id, d.config_template_id, d.position, d.kind, d.book_id, d.external_id, d.name, d.enabled,
	d.password_mode, COALESCE(d.password_enc, ''), d.line_profile_id, d.lookup_incoming, d.lookup_outgoing, d.max_results,
	d.ldap_account_id, COALESCE((SELECT name FROM address_books WHERE id = d.book_id),
	(SELECT name FROM external_directories WHERE id = d.external_id), '')`

func scanDirectory(row interface{ Scan(...any) error }) (*Directory, error) {
	d := &Directory{}
	var book, external, line, account sql.NullInt64
	err := row.Scan(&d.ID, &d.TemplateID, &d.Position, &d.Kind, &book, &external, &d.Name, &d.Enabled, &d.PasswordMode,
		&d.PasswordEnc, &line, &d.LookupIncoming, &d.LookupOutgoing, &d.MaxResults, &account, &d.Source)
	d.BookID, d.ExternalID, d.LineProfileID, d.LDAPAccountID = ptrID(book), ptrID(external), ptrID(line), ptrID(account)
	return d, mapErr(err)
}

// queryDirectories loads uses with their external directories.
func (s *Store) queryDirectories(ctx context.Context, where string, args ...any) ([]*Directory, error) {
	rows, err := s.DB.QueryContext(ctx, `SELECT `+directoryCols+` FROM template_directories d WHERE `+where+` ORDER BY d.position, d.id`, args...)
	if err != nil {
		return nil, err
	}
	var out []*Directory
	var ids []int64
	for rows.Next() {
		d, err := scanDirectory(rows)
		if err != nil {
			rows.Close()
			return nil, err
		}
		out = append(out, d)
		if d.ExternalID != nil {
			ids = append(ids, *d.ExternalID)
		}
	}
	rows.Close()
	if err := rows.Err(); err != nil || len(ids) == 0 {
		return out, err
	}
	list, err := queryExternals(ctx, s.DB, ` WHERE id IN (`+placeholders(len(ids))+`)`, int64Args(ids)...)
	if err != nil {
		return nil, err
	}
	byID := map[int64]*ExternalDirectory{}
	for _, e := range list {
		byID[e.ID] = e
	}
	for _, d := range out {
		if d.ExternalID != nil {
			d.External = byID[*d.ExternalID]
		}
	}
	return out, nil
}

// Directories returns the directory uses of a template in order.
func (s *Store) Directories(ctx context.Context, templateID int64) ([]*Directory, error) {
	return s.queryDirectories(ctx, `d.config_template_id = ?`, templateID)
}

// DirectoryByID loads one directory use of a template.
func (s *Store) DirectoryByID(ctx context.Context, templateID, id int64) (*Directory, error) {
	list, err := s.queryDirectories(ctx, `d.id = ? AND d.config_template_id = ?`, id, templateID)
	if err != nil {
		return nil, err
	}
	if len(list) == 0 {
		return nil, ErrNotFound
	}
	return list[0], nil
}

// SaveDirectory inserts (ID 0) or updates a directory use, at most max per template.
// The kind and the directory it refers to are written only when the use is made. seal,
// when not nil, makes the sealed password of a hosted book bound by hand for the row id
// (the id is part of the encryption's associated data); nil keeps the stored password.
func (s *Store) SaveDirectory(ctx context.Context, d *Directory, max int, seal func(id int64) (string, error)) error {
	if d.Kind == KindExternal { // the external directory keeps the password
		d.PasswordMode, d.LDAPAccountID = "none", nil
	}
	now := s.NowText()
	return s.Tx(ctx, func(tx *sql.Tx) error {
		args := []any{d.Name, boolInt(d.Enabled), d.PasswordMode, nullID(d.LineProfileID), boolInt(d.LookupIncoming),
			boolInt(d.LookupOutgoing), d.MaxResults, nullID(d.LDAPAccountID), now}
		if d.ID == 0 {
			var n, pos int
			if err := tx.QueryRowContext(ctx, `SELECT count(*), COALESCE(max(position), 0) FROM template_directories WHERE config_template_id = ?`,
				d.TemplateID).Scan(&n, &pos); err != nil {
				return err
			}
			if n >= max {
				return ErrLimit
			}
			d.Position = pos + 1
			res, err := tx.ExecContext(ctx, `INSERT INTO template_directories(name, enabled, password_mode, line_profile_id,
				lookup_incoming, lookup_outgoing, max_results, ldap_account_id, updated_at, kind, book_id, external_id,
				config_template_id, position, created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)`,
				append(args, d.Kind, nullID(d.BookID), nullID(d.ExternalID), d.TemplateID, d.Position, now)...)
			if err != nil {
				return mapErr(err)
			}
			d.ID, _ = res.LastInsertId()
		} else if err := deleteRowLike(ctx, tx, `UPDATE template_directories SET name = ?, enabled = ?, password_mode = ?,
			line_profile_id = ?, lookup_incoming = ?, lookup_outgoing = ?, max_results = ?, ldap_account_id = ?, updated_at = ?
			WHERE id = ? AND config_template_id = ?`, append(args, d.ID, d.TemplateID)...); err != nil {
			return err
		}
		switch {
		case d.PasswordMode != "template":
			if _, err := tx.ExecContext(ctx, `UPDATE template_directories SET password_enc = NULL WHERE id = ?`, d.ID); err != nil {
				return err
			}
		case seal != nil:
			enc, err := seal(d.ID)
			if err != nil {
				return err
			}
			if _, err := tx.ExecContext(ctx, `UPDATE template_directories SET password_enc = ? WHERE id = ?`, enc, d.ID); err != nil {
				return err
			}
		}
		return bumpConfigTemplate(ctx, tx, d.TemplateID, now)
	})
}

// DeleteDirectory removes a directory of a template.
func (s *Store) DeleteDirectory(ctx context.Context, templateID, id int64) error {
	now := s.NowText()
	return s.Tx(ctx, func(tx *sql.Tx) error {
		if err := deleteRow(ctx, tx, `DELETE FROM template_directories WHERE id = ? AND config_template_id = ?`, id, templateID); err != nil {
			return err
		}
		return bumpConfigTemplate(ctx, tx, templateID, now)
	})
}

// ReportCodecs remembers codec keys a phone reported.
func (s *Store) ReportCodecs(ctx context.Context, appVersion string, audio, video []string) error {
	now := s.NowText()
	for kind, keys := range map[string][]string{"audio": audio, "video": video} {
		for _, k := range keys {
			if _, err := s.DB.ExecContext(ctx, `INSERT OR IGNORE INTO reported_codecs(kind, key, app_version, first_seen) VALUES(?,?,?,?)`,
				kind, k, appVersion, now); err != nil {
				return err
			}
		}
	}
	return nil
}

// ReportedCodecs returns the distinct codec keys phones reported, per kind.
func (s *Store) ReportedCodecs(ctx context.Context) (map[string][]string, error) {
	rows, err := s.DB.QueryContext(ctx, `SELECT DISTINCT kind, key FROM reported_codecs ORDER BY kind, key`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := map[string][]string{}
	for rows.Next() {
		var kind, key string
		if err := rows.Scan(&kind, &key); err != nil {
			return nil, err
		}
		out[kind] = append(out[kind], key)
	}
	return out, rows.Err()
}
