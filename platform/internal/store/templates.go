package store

import (
	"context"
	"database/sql"
	"errors"
	"slices"
	"strings"
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

// Directory is an external LDAP server or a hosted book of a template.
type Directory struct {
	ID               int64
	TemplateID       int64
	Position         int
	Kind             string // external / hosted
	BookID           *int64
	Name             string
	Enabled          bool
	Host             string
	Port             int
	Security         string
	BindDN           string
	PasswordMode     string // none / template / client
	PasswordEnc      string // sealed, password_mode = template only
	BaseDN           string
	LineProfileID    *int64
	LookupIncoming   bool
	LookupOutgoing   bool
	MaxResults       int
	NameAttributes   string
	NumberAttributes string
	NameFilter       string
	NumberFilter     string
	LDAPAccountID    *int64 // hosted only: bind with this service account; nil = each client's derived login
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

// scopeWhere builds the WHERE part for objects of a region or global.
func scopeWhere(sc BookScope) (string, []any) {
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
	if len(where) == 0 {
		return "", nil
	}
	return ` WHERE ` + strings.Join(where, ` AND `), args
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

const directoryCols = `id, config_template_id, position, kind, book_id, name, enabled, host, port, security, bind_dn,
	password_mode, COALESCE(password_enc, ''), base_dn, line_profile_id, lookup_incoming, lookup_outgoing, max_results,
	name_attributes, number_attributes, name_filter, number_filter, ldap_account_id`

func scanDirectory(row interface{ Scan(...any) error }) (*Directory, error) {
	d := &Directory{}
	var book, line, account sql.NullInt64
	err := row.Scan(&d.ID, &d.TemplateID, &d.Position, &d.Kind, &book, &d.Name, &d.Enabled, &d.Host, &d.Port, &d.Security,
		&d.BindDN, &d.PasswordMode, &d.PasswordEnc, &d.BaseDN, &line, &d.LookupIncoming, &d.LookupOutgoing, &d.MaxResults,
		&d.NameAttributes, &d.NumberAttributes, &d.NameFilter, &d.NumberFilter, &account)
	d.BookID, d.LineProfileID, d.LDAPAccountID = ptrID(book), ptrID(line), ptrID(account)
	return d, mapErr(err)
}

// Directories returns the directories of a template in order.
func (s *Store) Directories(ctx context.Context, templateID int64) ([]*Directory, error) {
	rows, err := s.DB.QueryContext(ctx, `SELECT `+directoryCols+` FROM template_directories WHERE config_template_id = ? ORDER BY position, id`, templateID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []*Directory
	for rows.Next() {
		d, err := scanDirectory(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, d)
	}
	return out, rows.Err()
}

// DirectoryByID loads one directory of a template.
func (s *Store) DirectoryByID(ctx context.Context, templateID, id int64) (*Directory, error) {
	return scanDirectory(s.DB.QueryRowContext(ctx, `SELECT `+directoryCols+` FROM template_directories WHERE id = ? AND config_template_id = ?`, id, templateID))
}

// SaveDirectory inserts (ID 0) or updates a directory, at most max per template. seal,
// when not nil, makes the sealed template password for the row id (the id is part of
// the encryption's associated data); nil keeps the stored password.
func (s *Store) SaveDirectory(ctx context.Context, d *Directory, max int, seal func(id int64) (string, error)) error {
	now := s.NowText()
	return s.Tx(ctx, func(tx *sql.Tx) error {
		args := []any{d.Kind, nullID(d.BookID), d.Name, boolInt(d.Enabled), d.Host, d.Port, d.Security, d.BindDN, d.PasswordMode,
			d.BaseDN, nullID(d.LineProfileID), boolInt(d.LookupIncoming), boolInt(d.LookupOutgoing), d.MaxResults,
			d.NameAttributes, d.NumberAttributes, d.NameFilter, d.NumberFilter, nullID(d.LDAPAccountID), now}
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
			res, err := tx.ExecContext(ctx, `INSERT INTO template_directories(kind, book_id, name, enabled, host, port, security, bind_dn,
				password_mode, base_dn, line_profile_id, lookup_incoming, lookup_outgoing, max_results, name_attributes, number_attributes,
				name_filter, number_filter, ldap_account_id, updated_at, config_template_id, position, created_at)
				VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)`, append(args, d.TemplateID, d.Position, now)...)
			if err != nil {
				return mapErr(err)
			}
			d.ID, _ = res.LastInsertId()
		} else if err := deleteRowLike(ctx, tx, `UPDATE template_directories SET kind = ?, book_id = ?, name = ?, enabled = ?, host = ?,
			port = ?, security = ?, bind_dn = ?, password_mode = ?, base_dn = ?, line_profile_id = ?, lookup_incoming = ?,
			lookup_outgoing = ?, max_results = ?, name_attributes = ?, number_attributes = ?, name_filter = ?, number_filter = ?,
			ldap_account_id = ?, updated_at = ? WHERE id = ? AND config_template_id = ?`, append(args, d.ID, d.TemplateID)...); err != nil {
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
