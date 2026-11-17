package store

import (
	"context"
	"database/sql"
	"encoding/json"
	"strings"
)

// AuditEntry is one line of the append-only audit trail.
type AuditEntry struct {
	ID         int64
	At         string
	AdminID    *int64
	AdminName  string
	RegionID   *int64
	Action     string
	ObjectType string
	ObjectID   *int64
	ObjectName string
	Detail     map[string]any
	DetailJSON string
	IP         string
}

// AddAudit appends an entry. Detail must never carry secret values.
func (s *Store) AddAudit(ctx context.Context, e *AuditEntry) error {
	return addAudit(ctx, s.DB, s.NowText(), e)
}

// AddAuditTx appends an entry inside a transaction.
func (s *Store) AddAuditTx(ctx context.Context, tx *sql.Tx, e *AuditEntry) error {
	return addAudit(ctx, tx, s.NowText(), e)
}

func addAudit(ctx context.Context, q querier, now string, e *AuditEntry) error {
	detail := "{}"
	if len(e.Detail) > 0 {
		b, err := json.Marshal(e.Detail)
		if err != nil {
			return err
		}
		detail = string(b)
	}
	e.At, e.DetailJSON = now, detail
	res, err := q.ExecContext(ctx, `INSERT INTO audit_log(at, admin_id, admin_name, region_id, action, object_type,
		object_id, object_name, detail_json, ip) VALUES(?,?,?,?,?,?,?,?,?,?)`,
		now, nullID(e.AdminID), e.AdminName, nullID(e.RegionID), e.Action, e.ObjectType, nullID(e.ObjectID),
		e.ObjectName, detail, e.IP)
	if err != nil {
		return err
	}
	e.ID, _ = res.LastInsertId()
	return nil
}

// AuditFilter selects audit entries.
type AuditFilter struct {
	// Scope: when AllRegions is false only entries whose region is in RegionIDs or
	// whose admin is OwnAdminID are returned.
	AllRegions bool
	RegionIDs  []int64
	OwnAdminID int64

	AdminID    int64  // 0 = any
	RegionID   int64  // 0 = any
	ObjectType string // "" = any
	Action     string // prefix, "" = any
	From, To   string // stored time text, inclusive / exclusive, "" = open
	Limit      int
	Offset     int
}

// ListAudit returns entries newest first.
func (s *Store) ListAudit(ctx context.Context, f AuditFilter) ([]AuditEntry, error) {
	var where []string
	var args []any
	if !f.AllRegions {
		cond := `admin_id = ?`
		args = append(args, f.OwnAdminID)
		if len(f.RegionIDs) > 0 {
			cond = `(region_id IN (` + placeholders(len(f.RegionIDs)) + `) OR admin_id = ?)`
			args = append(int64Args(f.RegionIDs), f.OwnAdminID)
		}
		where = append(where, cond)
	}
	if f.AdminID != 0 {
		where, args = append(where, `admin_id = ?`), append(args, f.AdminID)
	}
	if f.RegionID != 0 {
		where, args = append(where, `region_id = ?`), append(args, f.RegionID)
	}
	if f.ObjectType != "" {
		where, args = append(where, `object_type = ?`), append(args, f.ObjectType)
	}
	if f.Action != "" {
		where, args = append(where, `action LIKE ? ESCAPE '\'`), append(args, likePrefix(f.Action))
	}
	if f.From != "" {
		where, args = append(where, `at >= ?`), append(args, f.From)
	}
	if f.To != "" {
		where, args = append(where, `at < ?`), append(args, f.To)
	}
	q := `SELECT id, at, admin_id, admin_name, region_id, action, object_type, object_id, object_name, detail_json, ip FROM audit_log`
	if len(where) > 0 {
		q += ` WHERE ` + strings.Join(where, ` AND `)
	}
	if f.Limit <= 0 {
		f.Limit = 50
	}
	q += ` ORDER BY id DESC LIMIT ? OFFSET ?`
	args = append(args, f.Limit, f.Offset)
	rows, err := s.DB.QueryContext(ctx, q, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []AuditEntry
	for rows.Next() {
		var e AuditEntry
		var admin, region, object sql.NullInt64
		if err := rows.Scan(&e.ID, &e.At, &admin, &e.AdminName, &region, &e.Action, &e.ObjectType, &object,
			&e.ObjectName, &e.DetailJSON, &e.IP); err != nil {
			return nil, err
		}
		e.AdminID, e.RegionID, e.ObjectID = ptrID(admin), ptrID(region), ptrID(object)
		_ = json.Unmarshal([]byte(e.DetailJSON), &e.Detail)
		out = append(out, e)
	}
	return out, rows.Err()
}

func likePrefix(s string) string {
	r := strings.NewReplacer(`\`, `\\`, `%`, `\%`, `_`, `\_`)
	return r.Replace(s) + "%"
}
