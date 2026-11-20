package store

import (
	"context"
	"database/sql"
	"strconv"
	"strings"
	"time"

	"siplink/internal/secret"
)

// Revocation reasons (design §3.3).
const (
	RevokedDeleted = "deleted"
	RevokedUnbound = "unbound"
)

// setEnrollment stores a new enrollment token for c (hash for lookup, sealed for the QR)
// with c.EnrollExpiresAt as its end.
func setEnrollment(ctx context.Context, tx *sql.Tx, c *Client, token string, box Sealer) error {
	enc, err := box.Seal([]byte(token), EnrollTokenAAD(c.ID))
	if err != nil {
		return err
	}
	c.EnrollTokenEnc = enc
	_, err = tx.ExecContext(ctx, `UPDATE clients SET enroll_token_hash = ?, enroll_token_enc = ?, enroll_expires_at = ? WHERE id = ?`,
		secret.HashHex(token), enc, c.EnrollExpiresAt, c.ID)
	return err
}

func revoke(ctx context.Context, tx *sql.Tx, c *Client, reason, now string) error {
	if c.DeviceKeyHash == "" {
		return nil
	}
	_, err := tx.ExecContext(ctx, `INSERT INTO revoked_devices(device_key_hash, client_name, reason, revoked_at) VALUES(?,?,?,?)
		ON CONFLICT(device_key_hash) DO UPDATE SET reason = excluded.reason, revoked_at = excluded.revoked_at`,
		c.DeviceKeyHash, c.Name, reason, now)
	return err
}

// Unbind revokes the client's device key (reason unbound), sets it back to pending and
// gives it a new enrollment token ("重新生成二维码", and leave from the phone).
func (s *Store) Unbind(ctx context.Context, c *Client, token string, expires time.Time, box Sealer) error {
	now := s.NowText()
	return s.Tx(ctx, func(tx *sql.Tx) error {
		if err := revoke(ctx, tx, c, RevokedUnbound, now); err != nil {
			return err
		}
		if _, err := tx.ExecContext(ctx, `UPDATE clients SET status = 'pending', device_key_hash = NULL, bound_at = NULL,
			install_id = '', revision = revision + 1, updated_at = ? WHERE id = ?`, now, c.ID); err != nil {
			return err
		}
		c.Status, c.DeviceKeyHash, c.BoundAt, c.InstallID = StatusPending, "", "", ""
		c.EnrollExpiresAt = expires.UTC().Format(TimeFormat)
		if _, err := tx.ExecContext(ctx, `DELETE FROM client_reports WHERE client_id = ?`, c.ID); err != nil {
			return err
		}
		return setEnrollment(ctx, tx, c, token, box)
	})
}

// DeleteClient removes a client; its device key is kept as revoked (reason deleted).
func (s *Store) DeleteClient(ctx context.Context, c *Client) error {
	now := s.NowText()
	return s.Tx(ctx, func(tx *sql.Tx) error {
		if err := revoke(ctx, tx, c, RevokedDeleted, now); err != nil {
			return err
		}
		return deleteRow(ctx, tx, `DELETE FROM clients WHERE id = ?`, c.ID)
	})
}

// ResetLDAP bumps the client's LDAP generation, so its derived password changes.
func (s *Store) ResetLDAP(ctx context.Context, id int64) error {
	return deleteRowLike(ctx, s.DB, `UPDATE clients SET ldap_generation = ldap_generation + 1, revision = revision + 1,
		updated_at = ? WHERE id = ?`, s.NowText(), id)
}

// SetClientTemplate moves clients to another client template (bulk action); credentials
// of profiles outside the new configuration template are removed.
func (s *Store) SetClientTemplate(ctx context.Context, ids []int64, templateID int64) error {
	now := s.NowText()
	return s.Tx(ctx, func(tx *sql.Tx) error {
		for _, id := range ids {
			if _, err := tx.ExecContext(ctx, `UPDATE clients SET client_template_id = ?, revision = revision + 1, updated_at = ?
				WHERE id = ?`, templateID, now, id); err != nil {
				return err
			}
			if _, err := tx.ExecContext(ctx, `DELETE FROM client_credentials WHERE client_id = ? AND profile_id NOT IN
				(SELECT p.id FROM account_profiles p JOIN client_templates t ON t.config_template_id = p.config_template_id
				 WHERE t.id = ?)`, id, templateID); err != nil {
				return err
			}
		}
		return nil
	})
}

// Enroll binds a phone: the token must be valid and unexpired; the device key hash is
// stored and the token is cleared (single use). ErrNotFound when the token is not valid.
func (s *Store) Enroll(ctx context.Context, tokenHash, deviceKeyHash, installID string) (*Client, error) {
	now := s.NowText()
	var c *Client
	err := s.Tx(ctx, func(tx *sql.Tx) error {
		var err error
		c, err = scanClient(tx.QueryRowContext(ctx, `SELECT `+clientCols+` FROM clients
			WHERE enroll_token_hash = ? AND status = 'pending' AND enroll_expires_at > ?`, tokenHash, now))
		if err != nil {
			return err
		}
		_, err = tx.ExecContext(ctx, `UPDATE clients SET status = 'active', device_key_hash = ?, bound_at = ?, install_id = ?,
			enroll_token_hash = NULL, enroll_token_enc = NULL, enroll_expires_at = NULL, updated_at = ? WHERE id = ?`,
			deviceKeyHash, now, installID, now, c.ID)
		c.Status, c.DeviceKeyHash, c.BoundAt, c.InstallID, c.EnrollTokenEnc = StatusActive, deviceKeyHash, now, installID, ""
		return err
	})
	return c, err
}

// ClientByEnrollToken returns the pending client of a valid, unexpired token hash.
func (s *Store) ClientByEnrollToken(ctx context.Context, tokenHash string) (*Client, error) {
	return s.clientWhere(ctx, "enroll_token_hash = ? AND status = 'pending' AND enroll_expires_at > ?", tokenHash, s.NowText())
}

// Revoked returns the reason a device key was revoked, or "" when it was not.
func (s *Store) Revoked(ctx context.Context, deviceKeyHash string) (string, error) {
	var reason string
	err := s.DB.QueryRowContext(ctx, `SELECT reason FROM revoked_devices WHERE device_key_hash = ?`, deviceKeyHash).Scan(&reason)
	if err == sql.ErrNoRows {
		return "", nil
	}
	return reason, err
}

// PurgeRevoked forgets revoked keys older than before.
func (s *Store) PurgeRevoked(ctx context.Context, before time.Time) (int64, error) {
	res, err := s.DB.ExecContext(ctx, `DELETE FROM revoked_devices WHERE revoked_at < ?`, before.UTC().Format(TimeFormat))
	if err != nil {
		return 0, err
	}
	return res.RowsAffected()
}

// Report is what the platform knows about a client's phone (§3.4) and what it sent.
type Report struct {
	ClientID          int64
	LastSeenAt        string
	LastIP            string
	AppVersion        string
	AppVersionCode    int64
	UserAgent         string // the User-Agent header of the phone's last request
	AndroidRelease    string
	AndroidSDK        int64
	DeviceModel       string
	SentVersion       string
	RevisionKey       string
	LastDocEnc        string
	AppliedVersion    string
	AppliedAt         string
	ApplyError        string
	RegistrationsJSON string
	BuildError        string
}

const reportCols = `client_id, last_seen_at, last_ip, app_version, app_version_code, user_agent, android_release, android_sdk,
	device_model, sent_version, revision_key, last_doc_enc, applied_version, applied_at, apply_error, registrations_json, build_error`

// reportListCols are the columns of reportCols without the sealed last document (about
// 4 KB a client), which only the device API opens: LastDocEnc reads as "".
var reportListCols = strings.Replace(reportCols, "last_doc_enc", "''", 1)

func scanReport(row interface{ Scan(...any) error }) (*Report, error) {
	r := &Report{}
	err := row.Scan(&r.ClientID, &r.LastSeenAt, &r.LastIP, &r.AppVersion, &r.AppVersionCode, &r.UserAgent, &r.AndroidRelease, &r.AndroidSDK,
		&r.DeviceModel, &r.SentVersion, &r.RevisionKey, &r.LastDocEnc, &r.AppliedVersion, &r.AppliedAt, &r.ApplyError,
		&r.RegistrationsJSON, &r.BuildError)
	return r, mapErr(err)
}

// ClientReport returns the report of a client (an empty one when there is none yet).
func (s *Store) ClientReport(ctx context.Context, clientID int64) (*Report, error) {
	r, err := scanReport(s.DB.QueryRowContext(ctx, `SELECT `+reportCols+` FROM client_reports WHERE client_id = ?`, clientID))
	if err == ErrNotFound {
		return &Report{ClientID: clientID, RegistrationsJSON: "[]"}, nil
	}
	return r, err
}

// ClientReports returns the reports of several clients by client id, for lists: without
// the last document sent (LastDocEnc is "").
func (s *Store) ClientReports(ctx context.Context, ids []int64) (map[int64]*Report, error) {
	out := map[int64]*Report{}
	if len(ids) == 0 {
		return out, nil
	}
	rows, err := s.DB.QueryContext(ctx, `SELECT `+reportListCols+` FROM client_reports WHERE client_id IN (`+placeholders(len(ids))+`)`,
		int64Args(ids)...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	for rows.Next() {
		r, err := scanReport(rows)
		if err != nil {
			return nil, err
		}
		out[r.ClientID] = r
	}
	return out, rows.Err()
}

// SaveReport writes a whole report (overwritten on every sync).
func (s *Store) SaveReport(ctx context.Context, r *Report) error {
	_, err := s.DB.ExecContext(ctx, `INSERT INTO client_reports(`+reportCols+`) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
		ON CONFLICT(client_id) DO UPDATE SET last_seen_at = excluded.last_seen_at, last_ip = excluded.last_ip,
		app_version = excluded.app_version, app_version_code = excluded.app_version_code, user_agent = excluded.user_agent,
		android_release = excluded.android_release, android_sdk = excluded.android_sdk, device_model = excluded.device_model,
		sent_version = excluded.sent_version, revision_key = excluded.revision_key, last_doc_enc = excluded.last_doc_enc,
		applied_version = excluded.applied_version, applied_at = excluded.applied_at, apply_error = excluded.apply_error,
		registrations_json = excluded.registrations_json, build_error = excluded.build_error`,
		r.ClientID, r.LastSeenAt, r.LastIP, r.AppVersion, r.AppVersionCode, r.UserAgent, r.AndroidRelease, r.AndroidSDK, r.DeviceModel,
		r.SentVersion, r.RevisionKey, r.LastDocEnc, r.AppliedVersion, r.AppliedAt, r.ApplyError, r.RegistrationsJSON, r.BuildError)
	return err
}

// ClientBooks returns the hosted books an active client may read over LDAP: those its
// configuration template refers to (design §4.3). ok is false for an unknown or
// pending client.
func (s *Store) ClientBooks(ctx context.Context, publicID string) (generation int64, books []*Book, ok bool, err error) {
	c, err := s.ClientByPublicID(ctx, publicID)
	if err == ErrNotFound {
		return 0, nil, false, nil
	}
	if err != nil || c.Status != StatusActive {
		return 0, nil, false, err
	}
	books, err = s.queryBooks(ctx, `SELECT DISTINCT `+prefixed("b.", bookCols)+` FROM address_books b
		JOIN template_directories d ON d.book_id = b.id AND d.kind = 'hosted'
		JOIN client_templates t ON t.config_template_id = d.config_template_id
		WHERE t.id = ? ORDER BY b.name COLLATE NOCASE, b.id`, c.ClientTemplateID)
	return c.LDAPGeneration, books, err == nil, err
}

// RevisionKey sums up everything stored that a client's document depends on: the
// client, its templates and the hosted books they refer to (§3.5.1 cheap path).
func (s *Store) RevisionKey(ctx context.Context, clientID int64) (string, error) {
	var key string
	err := s.DB.QueryRowContext(ctx, `SELECT 'c' || c.revision || '.' || c.ldap_generation || '|t' || t.id || '.' || t.revision ||
		'|f' || f.id || '.' || f.revision || '|k' || COALESCE(k.id || '.' || k.revision, '-') || '|r' || r.name ||
		'|b' || COALESCE((SELECT group_concat(b.id || '.' || b.revision, ',') FROM template_directories d
			JOIN address_books b ON b.id = d.book_id WHERE d.config_template_id = f.id), '') ||
		'|a' || COALESCE((SELECT group_concat(a.id || '.' || a.updated_at || '.' || a.disabled || '.' ||
			(SELECT count(*) FROM ldap_account_books g WHERE g.account_id = a.id AND g.book_id = d.book_id), ',') FROM template_directories d
			JOIN ldap_accounts a ON a.id = d.ldap_account_id WHERE d.config_template_id = f.id), '')
		FROM clients c JOIN client_templates t ON t.id = c.client_template_id
		JOIN config_templates f ON f.id = t.config_template_id
		LEFT JOIN key_templates k ON k.id = t.key_template_id
		JOIN regions r ON r.id = c.region_id WHERE c.id = ?`, clientID).Scan(&key)
	return key, mapErr(err)
}

// OfflineFactor: a bound client not seen for more than this many refresh intervals of
// its configuration template is offline.
const OfflineFactor = 3

// Offline reports whether a bound client last seen at lastSeen (stored time, "" = never)
// is offline at now. offlineSQL is the same rule for ClientFilter.
func Offline(lastSeen string, refreshMinutes int, now time.Time) bool {
	seen := ParseTime(lastSeen)
	return seen.IsZero() || now.Sub(seen) > OfflineFactor*time.Duration(refreshMinutes)*time.Minute
}

// offlineSQL is true for a client of the outer query "c" that has not been seen since
// now (the parameter) minus OfflineFactor refresh intervals.
var offlineSQL = `NOT EXISTS (SELECT 1 FROM client_reports r, client_templates t, config_templates f
	WHERE r.client_id = c.id AND t.id = c.client_template_id AND f.id = t.config_template_id
	AND r.last_seen_at >= strftime('%Y-%m-%dT%H:%M:%SZ', ?, '-' || (` + strconv.Itoa(OfflineFactor) + ` * f.refresh_minutes) || ' minutes'))`

// RefreshMinutes returns the refresh interval of each client's configuration template.
func (s *Store) RefreshMinutes(ctx context.Context, ids []int64) (map[int64]int, error) {
	out := map[int64]int{}
	if len(ids) == 0 {
		return out, nil
	}
	rows, err := s.DB.QueryContext(ctx, `SELECT c.id, f.refresh_minutes FROM clients c
		JOIN client_templates t ON t.id = c.client_template_id JOIN config_templates f ON f.id = t.config_template_id
		WHERE c.id IN (`+placeholders(len(ids))+`)`, int64Args(ids)...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	for rows.Next() {
		var id int64
		var m int
		if err := rows.Scan(&id, &m); err != nil {
			return nil, err
		}
		out[id] = m
	}
	return out, rows.Err()
}

// Attention is an active client with something to look at (dashboard).
type Attention struct {
	ID             int64
	Name           string
	LastSeenAt     string
	RefreshMinutes int
	ApplyError     string
	BuildError     string
	Registrations  string
}

// ActiveClients returns the active clients of a filter with what their reports say.
func (s *Store) ActiveClients(ctx context.Context, f ClientFilter) ([]Attention, error) {
	f.Status = StatusActive
	where, args := f.where()
	rows, err := s.DB.QueryContext(ctx, `SELECT c.id, c.name, COALESCE(r.last_seen_at, ''), f.refresh_minutes,
		COALESCE(r.apply_error, ''), COALESCE(r.build_error, ''), COALESCE(r.registrations_json, '[]')
		FROM clients c JOIN client_templates t ON t.id = c.client_template_id
		JOIN config_templates f ON f.id = t.config_template_id
		LEFT JOIN client_reports r ON r.client_id = c.id`+where+` ORDER BY c.name COLLATE NOCASE, c.id`, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Attention
	for rows.Next() {
		var a Attention
		if err := rows.Scan(&a.ID, &a.Name, &a.LastSeenAt, &a.RefreshMinutes, &a.ApplyError, &a.BuildError, &a.Registrations); err != nil {
			return nil, err
		}
		out = append(out, a)
	}
	return out, rows.Err()
}
