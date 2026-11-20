package store

import (
	"context"
	"database/sql"
)

// KeyTemplate holds shortcut keys (design §2.1).
type KeyTemplate struct {
	ID        int64
	RegionID  *int64
	Name      string
	Note      string
	Revision  int64
	KeyCount  int
	CreatedAt string
	UpdatedAt string
}

// TemplateKey is one shortcut key of a key template; Action and Icon are the words of
// the app's CSV format (ShortcutRules.typeWord / iconWords).
type TemplateKey struct {
	ID       int64
	Position int
	Label    string
	Number   string
	Action   string
	Category string
	Line     string
	Icon     string
}

// ClientTemplate is one configuration template plus at most one key template.
type ClientTemplate struct {
	ID               int64
	RegionID         *int64
	Name             string
	Note             string
	ConfigTemplateID int64
	KeyTemplateID    *int64
	Revision         int64
	CreatedAt        string
	UpdatedAt        string
}

func bumpKeyTemplate(ctx context.Context, q querier, id int64, now string) error {
	_, err := q.ExecContext(ctx, `UPDATE key_templates SET revision = revision + 1, updated_at = ? WHERE id = ?`, now, id)
	return err
}

const keyTemplateCols = `id, region_id, name, note, revision,
	(SELECT count(*) FROM template_keys k WHERE k.key_template_id = key_templates.id), created_at, updated_at`

func scanKeyTemplate(row interface{ Scan(...any) error }) (*KeyTemplate, error) {
	t := &KeyTemplate{}
	var region sql.NullInt64
	err := row.Scan(&t.ID, &region, &t.Name, &t.Note, &t.Revision, &t.KeyCount, &t.CreatedAt, &t.UpdatedAt)
	t.RegionID = ptrID(region)
	return t, mapErr(err)
}

// ListKeyTemplates returns the key templates of a scope.
func (s *Store) ListKeyTemplates(ctx context.Context, sc BookScope) ([]*KeyTemplate, error) {
	where, args := scopeWhere(sc)
	rows, err := s.DB.QueryContext(ctx, `SELECT `+keyTemplateCols+` FROM key_templates`+where+` ORDER BY name COLLATE NOCASE, id`, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []*KeyTemplate
	for rows.Next() {
		t, err := scanKeyTemplate(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, t)
	}
	return out, rows.Err()
}

// KeyTemplateByID loads one key template.
func (s *Store) KeyTemplateByID(ctx context.Context, id int64) (*KeyTemplate, error) {
	return scanKeyTemplate(s.DB.QueryRowContext(ctx, `SELECT `+keyTemplateCols+` FROM key_templates WHERE id = ?`, id))
}

// CreateKeyTemplate inserts a key template.
func (s *Store) CreateKeyTemplate(ctx context.Context, t *KeyTemplate) error {
	now := s.NowText()
	res, err := s.DB.ExecContext(ctx, `INSERT INTO key_templates(region_id, name, note, created_at, updated_at) VALUES(?,?,?,?,?)`,
		nullID(t.RegionID), t.Name, t.Note, now, now)
	if err != nil {
		return mapErr(err)
	}
	t.ID, _ = res.LastInsertId()
	t.Revision, t.CreatedAt, t.UpdatedAt = 1, now, now
	return nil
}

// UpdateKeyTemplate writes region, name and note.
func (s *Store) UpdateKeyTemplate(ctx context.Context, t *KeyTemplate) error {
	return deleteRowLike(ctx, s.DB, `UPDATE key_templates SET region_id = ?, name = ?, note = ?, revision = revision + 1,
		updated_at = ? WHERE id = ?`, nullID(t.RegionID), t.Name, t.Note, s.NowText(), t.ID)
}

// KeyTemplateUsers lists the client templates that use a key template.
func (s *Store) KeyTemplateUsers(ctx context.Context, id int64) ([]Use, error) {
	return uses(ctx, s.DB, `SELECT 'client_template', name FROM client_templates WHERE key_template_id = ? ORDER BY name`, id)
}

// DeleteKeyTemplate removes a key template nothing uses.
func (s *Store) DeleteKeyTemplate(ctx context.Context, id int64) error {
	return s.Tx(ctx, func(tx *sql.Tx) error {
		users, err := names(ctx, tx, `SELECT name FROM client_templates WHERE key_template_id = ?`, id)
		if err != nil {
			return err
		}
		if len(users) > 0 {
			return ErrInUse
		}
		return deleteRow(ctx, tx, `DELETE FROM key_templates WHERE id = ?`, id)
	})
}

// Keys returns the keys of a key template in order.
func (s *Store) Keys(ctx context.Context, templateID int64) ([]*TemplateKey, error) {
	rows, err := s.DB.QueryContext(ctx, `SELECT id, position, label, number, action, category, line, icon FROM template_keys
		WHERE key_template_id = ? ORDER BY position, id`, templateID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []*TemplateKey
	for rows.Next() {
		k := &TemplateKey{}
		if err := rows.Scan(&k.ID, &k.Position, &k.Label, &k.Number, &k.Action, &k.Category, &k.Line, &k.Icon); err != nil {
			return nil, err
		}
		out = append(out, k)
	}
	return out, rows.Err()
}

// SaveKey inserts (ID 0) or updates one key, at most max per template.
func (s *Store) SaveKey(ctx context.Context, templateID int64, k *TemplateKey, max int) error {
	now := s.NowText()
	return s.Tx(ctx, func(tx *sql.Tx) error {
		if k.ID == 0 {
			var n, pos int
			if err := tx.QueryRowContext(ctx, `SELECT count(*), COALESCE(max(position), 0) FROM template_keys WHERE key_template_id = ?`,
				templateID).Scan(&n, &pos); err != nil {
				return err
			}
			if n >= max {
				return ErrLimit
			}
			k.Position = pos + 1
			res, err := tx.ExecContext(ctx, `INSERT INTO template_keys(key_template_id, position, label, number, action, category, line, icon)
				VALUES(?,?,?,?,?,?,?,?)`, templateID, k.Position, k.Label, k.Number, k.Action, k.Category, k.Line, k.Icon)
			if err != nil {
				return err
			}
			k.ID, _ = res.LastInsertId()
		} else if err := deleteRowLike(ctx, tx, `UPDATE template_keys SET label = ?, number = ?, action = ?, category = ?, line = ?, icon = ?
			WHERE id = ? AND key_template_id = ?`, k.Label, k.Number, k.Action, k.Category, k.Line, k.Icon, k.ID, templateID); err != nil {
			return err
		}
		return bumpKeyTemplate(ctx, tx, templateID, now)
	})
}

// DeleteKey removes one key.
func (s *Store) DeleteKey(ctx context.Context, templateID, id int64) error {
	now := s.NowText()
	return s.Tx(ctx, func(tx *sql.Tx) error {
		if err := deleteRow(ctx, tx, `DELETE FROM template_keys WHERE id = ? AND key_template_id = ?`, id, templateID); err != nil {
			return err
		}
		return bumpKeyTemplate(ctx, tx, templateID, now)
	})
}

// AddKeys appends keys, or replaces all keys, in one transaction (CSV import).
func (s *Store) AddKeys(ctx context.Context, templateID int64, keys []*TemplateKey, replace bool, max int) error {
	now := s.NowText()
	return s.Tx(ctx, func(tx *sql.Tx) error {
		if replace {
			if _, err := tx.ExecContext(ctx, `DELETE FROM template_keys WHERE key_template_id = ?`, templateID); err != nil {
				return err
			}
		}
		var n, pos int
		if err := tx.QueryRowContext(ctx, `SELECT count(*), COALESCE(max(position), 0) FROM template_keys WHERE key_template_id = ?`,
			templateID).Scan(&n, &pos); err != nil {
			return err
		}
		if n+len(keys) > max {
			return ErrLimit
		}
		for i, k := range keys {
			k.Position = pos + i + 1
			res, err := tx.ExecContext(ctx, `INSERT INTO template_keys(key_template_id, position, label, number, action, category, line, icon)
				VALUES(?,?,?,?,?,?,?,?)`, templateID, k.Position, k.Label, k.Number, k.Action, k.Category, k.Line, k.Icon)
			if err != nil {
				return err
			}
			k.ID, _ = res.LastInsertId()
		}
		return bumpKeyTemplate(ctx, tx, templateID, now)
	})
}

// ---- client templates ----

const clientTemplateCols = `id, region_id, name, note, config_template_id, key_template_id, revision, created_at, updated_at`

func scanClientTemplate(row interface{ Scan(...any) error }) (*ClientTemplate, error) {
	t := &ClientTemplate{}
	var region, keys sql.NullInt64
	err := row.Scan(&t.ID, &region, &t.Name, &t.Note, &t.ConfigTemplateID, &keys, &t.Revision, &t.CreatedAt, &t.UpdatedAt)
	t.RegionID, t.KeyTemplateID = ptrID(region), ptrID(keys)
	return t, mapErr(err)
}

// ListClientTemplates returns the client templates of a scope.
func (s *Store) ListClientTemplates(ctx context.Context, sc BookScope) ([]*ClientTemplate, error) {
	where, args := scopeWhere(sc)
	rows, err := s.DB.QueryContext(ctx, `SELECT `+clientTemplateCols+` FROM client_templates`+where+` ORDER BY name COLLATE NOCASE, id`, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []*ClientTemplate
	for rows.Next() {
		t, err := scanClientTemplate(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, t)
	}
	return out, rows.Err()
}

// ClientTemplateByID loads one client template.
func (s *Store) ClientTemplateByID(ctx context.Context, id int64) (*ClientTemplate, error) {
	return scanClientTemplate(s.DB.QueryRowContext(ctx, `SELECT `+clientTemplateCols+` FROM client_templates WHERE id = ?`, id))
}

// ClientTemplatesUsingKeys returns the client templates that use a key template.
func (s *Store) ClientTemplatesUsingKeys(ctx context.Context, keyTemplateID int64) ([]*ClientTemplate, error) {
	rows, err := s.DB.QueryContext(ctx, `SELECT `+clientTemplateCols+` FROM client_templates WHERE key_template_id = ?`, keyTemplateID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []*ClientTemplate
	for rows.Next() {
		t, err := scanClientTemplate(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, t)
	}
	return out, rows.Err()
}

// CreateClientTemplate inserts a client template.
func (s *Store) CreateClientTemplate(ctx context.Context, t *ClientTemplate) error {
	now := s.NowText()
	res, err := s.DB.ExecContext(ctx, `INSERT INTO client_templates(region_id, name, note, config_template_id, key_template_id,
		created_at, updated_at) VALUES(?,?,?,?,?,?,?)`, nullID(t.RegionID), t.Name, t.Note, t.ConfigTemplateID, nullID(t.KeyTemplateID), now, now)
	if err != nil {
		return mapErr(err)
	}
	t.ID, _ = res.LastInsertId()
	t.Revision, t.CreatedAt, t.UpdatedAt = 1, now, now
	return nil
}

// UpdateClientTemplate writes every column and bumps the revision.
func (s *Store) UpdateClientTemplate(ctx context.Context, t *ClientTemplate) error {
	return deleteRowLike(ctx, s.DB, `UPDATE client_templates SET region_id = ?, name = ?, note = ?, config_template_id = ?,
		key_template_id = ?, revision = revision + 1, updated_at = ? WHERE id = ?`,
		nullID(t.RegionID), t.Name, t.Note, t.ConfigTemplateID, nullID(t.KeyTemplateID), s.NowText(), t.ID)
}

// DeleteClientTemplate removes a client template no client uses.
func (s *Store) DeleteClientTemplate(ctx context.Context, id int64) error {
	return s.Tx(ctx, func(tx *sql.Tx) error {
		n, err := clientsOfTemplate(ctx, tx, id)
		if err != nil {
			return err
		}
		if n > 0 {
			return ErrInUse
		}
		return deleteRow(ctx, tx, `DELETE FROM client_templates WHERE id = ?`, id)
	})
}

// ClientCountOfTemplate returns how many clients use a client template.
func (s *Store) ClientCountOfTemplate(ctx context.Context, id int64) (int, error) {
	return clientsOfTemplate(ctx, s.DB, id)
}

// ClientCountOfConfigTemplate returns how many clients use a configuration template.
func (s *Store) ClientCountOfConfigTemplate(ctx context.Context, id int64) (int, error) {
	return clientsOfConfigTemplate(ctx, s.DB, id)
}

// ClientCountsByTemplate returns how many clients use each client template, by its id
// (a template without clients is missing).
func (s *Store) ClientCountsByTemplate(ctx context.Context) (map[int64]int, error) {
	return s.countsBy(ctx, `SELECT client_template_id, count(*) FROM clients GROUP BY client_template_id`)
}

// ClientCountsByConfigTemplate returns how many clients use each configuration template,
// by its id (a template without clients is missing).
func (s *Store) ClientCountsByConfigTemplate(ctx context.Context) (map[int64]int, error) {
	return s.countsBy(ctx, `SELECT t.config_template_id, count(*) FROM clients c JOIN client_templates t ON t.id = c.client_template_id
		GROUP BY t.config_template_id`)
}

// countsBy runs a query of (id, count) rows and returns the counts by id.
func (s *Store) countsBy(ctx context.Context, query string, args ...any) (map[int64]int, error) {
	rows, err := s.DB.QueryContext(ctx, query, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := map[int64]int{}
	for rows.Next() {
		var id int64
		var n int
		if err := rows.Scan(&id, &n); err != nil {
			return nil, err
		}
		out[id] = n
	}
	return out, rows.Err()
}
