package store

import (
	"context"
	"database/sql"
	"errors"
	"strings"

	"golang.org/x/text/cases"
)

// Groups and tags of clients (design §2.6): management data of the console only.

// NameKey is a name folded for comparison (Unicode case folding): group names are unique
// in their region and tag names unique, both ignoring case.
func NameKey(name string) string { return cases.Fold().String(strings.TrimSpace(name)) }

// Group is a group of clients of one region.
type Group struct {
	ID        int64
	RegionID  int64
	Name      string
	CreatedAt string
	UpdatedAt string
}

const groupCols = `id, region_id, name, created_at, updated_at`

func scanGroup(row interface{ Scan(...any) error }) (*Group, error) {
	g := &Group{}
	err := row.Scan(&g.ID, &g.RegionID, &g.Name, &g.CreatedAt, &g.UpdatedAt)
	return g, mapErr(err)
}

// ListGroups returns the groups of the scope's regions (a group is never global), ordered
// by name.
func (s *Store) ListGroups(ctx context.Context, sc BookScope) ([]*Group, error) {
	where, args := scopeWhere(sc)
	rows, err := s.DB.QueryContext(ctx, `SELECT `+groupCols+` FROM client_groups`+where+` ORDER BY name_key, id`, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []*Group
	for rows.Next() {
		g, err := scanGroup(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, g)
	}
	return out, rows.Err()
}

// GroupByID loads one group.
func (s *Store) GroupByID(ctx context.Context, id int64) (*Group, error) {
	return scanGroup(s.DB.QueryRowContext(ctx, `SELECT `+groupCols+` FROM client_groups WHERE id = ?`, id))
}

// GroupClients counts the clients of every group that has any.
func (s *Store) GroupClients(ctx context.Context) (map[int64]int, error) {
	rows, err := s.DB.QueryContext(ctx, `SELECT group_id, count(*) FROM clients WHERE group_id IS NOT NULL GROUP BY group_id`)
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

// SaveGroup creates a group (ID 0), while its region has fewer than max, or renames one;
// the region of a group never changes. ErrConflict: the name is taken in the region;
// ErrLimit: the region has max groups.
func (s *Store) SaveGroup(ctx context.Context, g *Group, max int) error {
	now := s.NowText()
	return s.Tx(ctx, func(tx *sql.Tx) error {
		if g.ID != 0 {
			g.UpdatedAt = now
			return deleteRowLike(ctx, tx, `UPDATE client_groups SET name = ?, name_key = ?, updated_at = ? WHERE id = ?`,
				g.Name, NameKey(g.Name), now, g.ID)
		}
		var n int
		if err := tx.QueryRowContext(ctx, `SELECT count(*) FROM client_groups WHERE region_id = ?`, g.RegionID).Scan(&n); err != nil {
			return err
		}
		if n >= max {
			return ErrLimit
		}
		res, err := tx.ExecContext(ctx, `INSERT INTO client_groups(region_id, name, name_key, created_at, updated_at) VALUES(?,?,?,?,?)`,
			g.RegionID, g.Name, NameKey(g.Name), now, now)
		if err != nil {
			return mapErr(err)
		}
		g.ID, _ = res.LastInsertId()
		g.CreatedAt, g.UpdatedAt = now, now
		return nil
	})
}

// DeleteGroup removes a group; its clients are left without one. It returns how many
// clients were in it.
func (s *Store) DeleteGroup(ctx context.Context, id int64) (int, error) {
	var n int
	err := s.Tx(ctx, func(tx *sql.Tx) error {
		if err := tx.QueryRowContext(ctx, `SELECT count(*) FROM clients WHERE group_id = ?`, id).Scan(&n); err != nil {
			return err
		}
		return deleteRow(ctx, tx, `DELETE FROM client_groups WHERE id = ?`, id)
	})
	return n, err
}

// Tag is a label of clients, one list shared by every region.
type Tag struct {
	ID        int64
	Name      string
	Color     string
	CreatedAt string
	UpdatedAt string
}

const tagCols = `id, name, color, created_at, updated_at`

func scanTag(row interface{ Scan(...any) error }) (*Tag, error) {
	t := &Tag{}
	err := row.Scan(&t.ID, &t.Name, &t.Color, &t.CreatedAt, &t.UpdatedAt)
	return t, mapErr(err)
}

// ListTags returns every tag, ordered by name.
func (s *Store) ListTags(ctx context.Context) ([]*Tag, error) {
	rows, err := s.DB.QueryContext(ctx, `SELECT `+tagCols+` FROM tags ORDER BY name_key, id`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []*Tag
	for rows.Next() {
		t, err := scanTag(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, t)
	}
	return out, rows.Err()
}

// TagByID loads one tag.
func (s *Store) TagByID(ctx context.Context, id int64) (*Tag, error) {
	return scanTag(s.DB.QueryRowContext(ctx, `SELECT `+tagCols+` FROM tags WHERE id = ?`, id))
}

// TagUse counts, per tag, the clients that carry it in the given regions (In; all
// regions when all is true) and outside them (Out, the clients that keep a region
// administrator from changing the tag, §2.4).
type TagUse struct{ In, Out int }

// TagClients counts the clients of every tag that has any, by region as TagUse says.
func (s *Store) TagClients(ctx context.Context, all bool, regions []int64) (map[int64]TagUse, error) {
	in, args := regionCond(all, regions)
	rows, err := s.DB.QueryContext(ctx, `SELECT x.tag_id, sum(CASE WHEN `+in+` THEN 1 ELSE 0 END), sum(CASE WHEN `+in+` THEN 0 ELSE 1 END)
		FROM client_tags x JOIN clients c ON c.id = x.client_id GROUP BY x.tag_id`, append(args, args...)...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := map[int64]TagUse{}
	for rows.Next() {
		var id int64
		var u TagUse
		if err := rows.Scan(&id, &u.In, &u.Out); err != nil {
			return nil, err
		}
		out[id] = u
	}
	return out, rows.Err()
}

// regionCond is the condition "c.region_id is one of regions" (always true for all).
func regionCond(all bool, regions []int64) (string, []any) {
	switch {
	case all:
		return "1", nil
	case len(regions) == 0:
		return "0", nil
	}
	return "c.region_id IN (" + placeholders(len(regions)) + ")", int64Args(regions)
}

// guardTag refuses a change of a tag with ErrInUse when, unless all is true, a client
// outside regions carries it.
func guardTag(ctx context.Context, tx *sql.Tx, id int64, all bool, regions []int64) error {
	if all {
		return nil
	}
	in, args := regionCond(false, regions)
	var n int
	if err := tx.QueryRowContext(ctx, `SELECT count(*) FROM client_tags x JOIN clients c ON c.id = x.client_id
		WHERE x.tag_id = ? AND NOT (`+in+`)`, append([]any{id}, args...)...).Scan(&n); err != nil {
		return err
	}
	if n > 0 {
		return ErrInUse
	}
	return nil
}

// CreateTag adds a tag while there are fewer than max. ErrConflict: the name is taken;
// ErrLimit: there are max tags.
func (s *Store) CreateTag(ctx context.Context, t *Tag, max int) error {
	return s.Tx(ctx, func(tx *sql.Tx) error { return createTag(ctx, tx, t, max, s.NowText()) })
}

func createTag(ctx context.Context, tx *sql.Tx, t *Tag, max int, now string) error {
	var n int
	if err := tx.QueryRowContext(ctx, `SELECT count(*) FROM tags`).Scan(&n); err != nil {
		return err
	}
	if n >= max {
		return ErrLimit
	}
	res, err := tx.ExecContext(ctx, `INSERT INTO tags(name, name_key, color, created_at, updated_at) VALUES(?,?,?,?,?)`,
		t.Name, NameKey(t.Name), t.Color, now, now)
	if err != nil {
		return mapErr(err)
	}
	t.ID, _ = res.LastInsertId()
	t.CreatedAt, t.UpdatedAt = now, now
	return nil
}

// EnsureTags returns the tags of the given names, adding those that do not exist with
// color, all in one transaction (the client import). ErrLimit: more than max tags.
func (s *Store) EnsureTags(ctx context.Context, names []string, color string, max int) (map[string]*Tag, error) {
	out := map[string]*Tag{}
	err := s.Tx(ctx, func(tx *sql.Tx) error {
		for _, name := range names {
			key := NameKey(name)
			if out[key] != nil {
				continue
			}
			t, err := scanTag(tx.QueryRowContext(ctx, `SELECT `+tagCols+` FROM tags WHERE name_key = ?`, key))
			if errors.Is(err, ErrNotFound) {
				t = &Tag{Name: strings.TrimSpace(name), Color: color}
				err = createTag(ctx, tx, t, max, s.NowText())
			}
			if err != nil {
				return err
			}
			out[key] = t
		}
		return nil
	})
	return out, err
}

// UpdateTag renames or recolours a tag. Unless all is true, a tag that a client outside
// regions carries is refused with ErrInUse (§2.4). ErrConflict: the name is taken.
func (s *Store) UpdateTag(ctx context.Context, t *Tag, all bool, regions []int64) error {
	now := s.NowText()
	return s.Tx(ctx, func(tx *sql.Tx) error {
		if err := guardTag(ctx, tx, t.ID, all, regions); err != nil {
			return err
		}
		t.UpdatedAt = now
		return deleteRowLike(ctx, tx, `UPDATE tags SET name = ?, name_key = ?, color = ?, updated_at = ? WHERE id = ?`,
			t.Name, NameKey(t.Name), t.Color, now, t.ID)
	})
}

// DeleteTag removes a tag from every client and the list, under the rule of UpdateTag.
// It returns how many clients carried it.
func (s *Store) DeleteTag(ctx context.Context, id int64, all bool, regions []int64) (int, error) {
	var n int
	err := s.Tx(ctx, func(tx *sql.Tx) error {
		if err := guardTag(ctx, tx, id, all, regions); err != nil {
			return err
		}
		if err := tx.QueryRowContext(ctx, `SELECT count(*) FROM client_tags WHERE tag_id = ?`, id).Scan(&n); err != nil {
			return err
		}
		return deleteRow(ctx, tx, `DELETE FROM tags WHERE id = ?`, id)
	})
	return n, err
}

// ClientTags returns the tag ids of clients, by client id, in the order of the tags'
// names.
func (s *Store) ClientTags(ctx context.Context, ids []int64) (map[int64][]int64, error) {
	out := map[int64][]int64{}
	if len(ids) == 0 {
		return out, nil
	}
	rows, err := s.DB.QueryContext(ctx, `SELECT x.client_id, x.tag_id FROM client_tags x JOIN tags t ON t.id = x.tag_id
		WHERE x.client_id IN (`+placeholders(len(ids))+`) ORDER BY t.name_key, t.id`, int64Args(ids)...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	for rows.Next() {
		var c, t int64
		if err := rows.Scan(&c, &t); err != nil {
			return nil, err
		}
		out[c] = append(out[c], t)
	}
	return out, rows.Err()
}

// setClientTags makes tags the client's tags.
func setClientTags(ctx context.Context, tx *sql.Tx, clientID int64, tags []int64) error {
	if _, err := tx.ExecContext(ctx, `DELETE FROM client_tags WHERE client_id = ?`, clientID); err != nil {
		return err
	}
	for _, t := range tags {
		if _, err := tx.ExecContext(ctx, `INSERT OR IGNORE INTO client_tags(client_id, tag_id) VALUES(?,?)`, clientID, t); err != nil {
			return err
		}
	}
	return nil
}

// SetClientsGroup puts clients into a group, or into none (nil). The caller checked that
// every client is of the group's region; the database refuses it otherwise.
func (s *Store) SetClientsGroup(ctx context.Context, ids []int64, group *int64) error {
	now := s.NowText()
	return s.Tx(ctx, func(tx *sql.Tx) error {
		for _, id := range ids {
			if _, err := tx.ExecContext(ctx, `UPDATE clients SET group_id = ?, updated_at = ? WHERE id = ?`, nullID(group), now, id); err != nil {
				return err
			}
		}
		return nil
	})
}

// AddClientsTag puts a tag on clients. ErrLimit, and nothing written, when a client would
// carry more than max tags.
func (s *Store) AddClientsTag(ctx context.Context, ids []int64, tag int64, max int) error {
	return s.Tx(ctx, func(tx *sql.Tx) error {
		for _, id := range ids {
			if _, err := tx.ExecContext(ctx, `INSERT OR IGNORE INTO client_tags(client_id, tag_id) VALUES(?,?)`, id, tag); err != nil {
				return err
			}
			var n int
			if err := tx.QueryRowContext(ctx, `SELECT count(*) FROM client_tags WHERE client_id = ?`, id).Scan(&n); err != nil {
				return err
			}
			if n > max {
				return ErrLimit
			}
		}
		return nil
	})
}

// RemoveClientsTag takes a tag off clients.
func (s *Store) RemoveClientsTag(ctx context.Context, ids []int64, tag int64) error {
	if len(ids) == 0 {
		return nil
	}
	_, err := s.DB.ExecContext(ctx, `DELETE FROM client_tags WHERE tag_id = ? AND client_id IN (`+placeholders(len(ids))+`)`,
		append([]any{tag}, int64Args(ids)...)...)
	return err
}
