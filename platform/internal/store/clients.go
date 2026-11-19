package store

import (
	"context"
	"crypto/rand"
	"database/sql"
	"fmt"
	"strings"
	"time"

	"siplink/internal/secret"
)

// Client statuses.
const (
	StatusPending = "pending"
	StatusActive  = "active"
	// StatusOffline is not stored: it selects the active clients that are Offline
	// (ClientFilter.Status).
	StatusOffline = "offline"
)

// Client is one installed SipLink (design §2.1).
type Client struct {
	ID               int64
	RegionID         int64
	ClientTemplateID int64
	Name             string
	Note             string
	PublicID         string
	Status           string
	EnrollTokenEnc   string
	EnrollExpiresAt  string
	DeviceKeyHash    string
	BoundAt          string
	InstallID        string
	LDAPGeneration   int64
	ForwardAlways    *string
	ForwardBusy      *string
	ForwardNoAnswer  *string
	GroupID          *int64 // its group, of its own region (§2.6)
	Revision         int64
	CreatedAt        string
	UpdatedAt        string
}

// Credential is a client's account on one profile; PasswordEnc is sealed.
type Credential struct {
	ProfileID   int64
	Username    string
	AuthUser    string
	PasswordEnc string
	DisplayName string
	AccountName *string
}

// CredentialInput is what a form or CSV gives for one profile. An empty Username removes
// the credential; an empty Password keeps the stored one.
type CredentialInput struct {
	ProfileID   int64
	Username    string
	AuthUser    string
	Password    string
	DisplayName string
	AccountName *string
}

// Sealer encrypts a value for a column (secret.Box).
type Sealer interface {
	Seal(plaintext, aad []byte) (string, error)
}

// CredentialAAD and DirectoryPasswordAAD bind ciphertext to its row (§6.1).
func CredentialAAD(clientID, profileID int64) []byte {
	return secret.AAD("client_credentials", fmt.Sprintf("%d/%d", clientID, profileID), "password_enc")
}

func DirectoryPasswordAAD(clientID, directoryID int64) []byte {
	return secret.AAD("client_directory_passwords", fmt.Sprintf("%d/%d", clientID, directoryID), "password_enc")
}

// EnrollTokenAAD binds the sealed enrollment token to its client.
func EnrollTokenAAD(clientID int64) []byte {
	return secret.AAD("clients", clientID, "enroll_token_enc")
}

const clientCols = `id, region_id, client_template_id, name, note, public_id, status, COALESCE(enroll_token_enc, ''),
	COALESCE(enroll_expires_at, ''), COALESCE(device_key_hash, ''), COALESCE(bound_at, ''), install_id, ldap_generation,
	forward_always, forward_busy, forward_no_answer, group_id, revision, created_at, updated_at`

func scanClient(row interface{ Scan(...any) error }) (*Client, error) {
	c := &Client{}
	var fa, fb, fn sql.NullString
	var group sql.NullInt64
	err := row.Scan(&c.ID, &c.RegionID, &c.ClientTemplateID, &c.Name, &c.Note, &c.PublicID, &c.Status, &c.EnrollTokenEnc,
		&c.EnrollExpiresAt, &c.DeviceKeyHash, &c.BoundAt, &c.InstallID, &c.LDAPGeneration, &fa, &fb, &fn, &group,
		&c.Revision, &c.CreatedAt, &c.UpdatedAt)
	c.ForwardAlways, c.ForwardBusy, c.ForwardNoAnswer = nullText(fa), nullText(fb), nullText(fn)
	c.GroupID = ptrID(group)
	return c, mapErr(err)
}

func nullText(n sql.NullString) *string {
	if !n.Valid {
		return nil
	}
	return &n.String
}

func textArg(p *string) any {
	if p == nil {
		return nil
	}
	return *p
}

// NewPublicID returns 10 random characters of lowercase base32 (the LDAP uid).
func NewPublicID() string {
	const alphabet = "abcdefghijklmnopqrstuvwxyz234567"
	b := make([]byte, 10)
	if _, err := rand.Read(b); err != nil {
		panic(err)
	}
	for i := range b {
		b[i] = alphabet[b[i]&31]
	}
	return string(b)
}

// ClientFilter selects clients for the list.
type ClientFilter struct {
	All      bool
	Regions  []int64
	Region   int64
	Template int64
	Group    int64   // a group (0 = any)
	NoGroup  bool    // only clients without a group
	Tags     []int64 // only clients that carry every one of these tags
	Status   string
	Now      time.Time // the moment StatusOffline is judged at
	Query    string    // name or SIP user name, substring
	IDs      []int64
	Limit    int
	Offset   int
}

func (f ClientFilter) where() (string, []any) {
	var conds []string
	var args []any
	if !f.All {
		if len(f.Regions) == 0 {
			return " WHERE 0", nil
		}
		conds = append(conds, "c.region_id IN ("+placeholders(len(f.Regions))+")")
		args = append(args, int64Args(f.Regions)...)
	}
	if f.Region != 0 {
		conds, args = append(conds, "c.region_id = ?"), append(args, f.Region)
	}
	if f.Template != 0 {
		conds, args = append(conds, "c.client_template_id = ?"), append(args, f.Template)
	}
	switch {
	case f.NoGroup:
		conds = append(conds, "c.group_id IS NULL")
	case f.Group != 0:
		conds, args = append(conds, "c.group_id = ?"), append(args, f.Group)
	}
	for _, t := range f.Tags {
		conds, args = append(conds, "c.id IN (SELECT client_id FROM client_tags WHERE tag_id = ?)"), append(args, t)
	}
	switch f.Status {
	case "":
	case StatusOffline:
		conds = append(conds, "c.status = ?", offlineSQL)
		args = append(args, StatusActive, f.Now.UTC().Format(TimeFormat))
	default:
		conds, args = append(conds, "c.status = ?"), append(args, f.Status)
	}
	if len(f.IDs) > 0 {
		conds = append(conds, "c.id IN ("+placeholders(len(f.IDs))+")")
		args = append(args, int64Args(f.IDs)...)
	}
	if q := strings.TrimSpace(f.Query); q != "" {
		like := "%" + strings.NewReplacer(`\`, `\\`, "%", `\%`, "_", `\_`).Replace(q) + "%"
		conds = append(conds, `(c.name LIKE ? ESCAPE '\' OR EXISTS (SELECT 1 FROM client_credentials k WHERE k.client_id = c.id AND k.username LIKE ? ESCAPE '\'))`)
		args = append(args, like, like)
	}
	if len(conds) == 0 {
		return "", nil
	}
	return " WHERE " + strings.Join(conds, " AND "), args
}

// ListClients returns one page of clients and the total count.
func (s *Store) ListClients(ctx context.Context, f ClientFilter) ([]*Client, int, error) {
	where, args := f.where()
	var total int
	if err := s.DB.QueryRowContext(ctx, `SELECT count(*) FROM clients c`+where, args...).Scan(&total); err != nil {
		return nil, 0, err
	}
	q := `SELECT ` + clientCols + ` FROM clients c` + where + ` ORDER BY c.name COLLATE NOCASE, c.id`
	if f.Limit > 0 {
		q += fmt.Sprintf(" LIMIT %d OFFSET %d", f.Limit, f.Offset)
	}
	rows, err := s.DB.QueryContext(ctx, q, args...)
	if err != nil {
		return nil, 0, err
	}
	defer rows.Close()
	var out []*Client
	for rows.Next() {
		c, err := scanClient(rows)
		if err != nil {
			return nil, 0, err
		}
		out = append(out, c)
	}
	return out, total, rows.Err()
}

func (s *Store) clientWhere(ctx context.Context, cond string, args ...any) (*Client, error) {
	return scanClient(s.DB.QueryRowContext(ctx, `SELECT `+clientCols+` FROM clients WHERE `+cond, args...))
}

// ClientByID returns one client.
func (s *Store) ClientByID(ctx context.Context, id int64) (*Client, error) {
	return s.clientWhere(ctx, "id = ?", id)
}

// ClientByPublicID returns the client of an LDAP uid.
func (s *Store) ClientByPublicID(ctx context.Context, publicID string) (*Client, error) {
	return s.clientWhere(ctx, "public_id = ?", publicID)
}

// ClientByDeviceKey returns the bound client of a device key hash.
func (s *Store) ClientByDeviceKey(ctx context.Context, hash string) (*Client, error) {
	return s.clientWhere(ctx, "device_key_hash = ?", hash)
}

// SaveClient creates or updates a client with its credentials, directory passwords and
// tags in one transaction. creds lists every profile of the client's configuration
// template (credentials of other profiles are removed); dirs maps directory ids of
// password_mode=client to a new password ("" keeps the stored one); tags are the client's
// tag ids (nil keeps the stored ones of an existing client). The caller checked that the
// group is of the client's region. On create, enrollToken is the new token, stored hashed
// and sealed.
func (s *Store) SaveClient(ctx context.Context, c *Client, creds []CredentialInput, dirs map[int64]string, tags []int64, box Sealer, enrollToken string) error {
	now := s.NowText()
	return s.Tx(ctx, func(tx *sql.Tx) error {
		if c.ID == 0 {
			c.PublicID, c.Status, c.LDAPGeneration, c.Revision = NewPublicID(), StatusPending, 1, 1
			res, err := tx.ExecContext(ctx, `INSERT INTO clients(region_id, client_template_id, name, note, public_id, status,
				forward_always, forward_busy, forward_no_answer, group_id, created_at, updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)`,
				c.RegionID, c.ClientTemplateID, c.Name, c.Note, c.PublicID, c.Status,
				textArg(c.ForwardAlways), textArg(c.ForwardBusy), textArg(c.ForwardNoAnswer), nullID(c.GroupID), now, now)
			if err != nil {
				return mapErr(err)
			}
			c.ID, _ = res.LastInsertId()
			c.CreatedAt = now
			if err := setEnrollment(ctx, tx, c, enrollToken, box); err != nil {
				return err
			}
		} else {
			res, err := tx.ExecContext(ctx, `UPDATE clients SET region_id = ?, client_template_id = ?, name = ?, note = ?,
				forward_always = ?, forward_busy = ?, forward_no_answer = ?, group_id = ?, revision = revision + 1, updated_at = ?
				WHERE id = ?`,
				c.RegionID, c.ClientTemplateID, c.Name, c.Note, textArg(c.ForwardAlways), textArg(c.ForwardBusy),
				textArg(c.ForwardNoAnswer), nullID(c.GroupID), now, c.ID)
			if err != nil {
				return mapErr(err)
			}
			if n, _ := res.RowsAffected(); n == 0 {
				return ErrNotFound
			}
		}
		c.UpdatedAt = now
		if err := saveCredentials(ctx, tx, c.ID, creds, box); err != nil {
			return err
		}
		if tags != nil {
			if err := setClientTags(ctx, tx, c.ID, tags); err != nil {
				return err
			}
		}
		return saveDirectoryPasswords(ctx, tx, c.ID, dirs, box)
	})
}

func saveCredentials(ctx context.Context, tx *sql.Tx, clientID int64, creds []CredentialInput, box Sealer) error {
	keep := []any{clientID}
	for _, in := range creds {
		if in.Username == "" {
			continue
		}
		keep = append(keep, in.ProfileID)
		if _, err := tx.ExecContext(ctx, `INSERT INTO client_credentials(client_id, profile_id, username, auth_user, display_name, account_name)
			VALUES(?,?,?,?,?,?) ON CONFLICT(client_id, profile_id) DO UPDATE SET username = excluded.username,
			auth_user = excluded.auth_user, display_name = excluded.display_name, account_name = excluded.account_name`,
			clientID, in.ProfileID, in.Username, in.AuthUser, in.DisplayName, textArg(in.AccountName)); err != nil {
			return mapErr(err)
		}
		if in.Password == "" {
			continue
		}
		enc, err := box.Seal([]byte(in.Password), CredentialAAD(clientID, in.ProfileID))
		if err != nil {
			return err
		}
		if _, err := tx.ExecContext(ctx, `UPDATE client_credentials SET password_enc = ? WHERE client_id = ? AND profile_id = ?`,
			enc, clientID, in.ProfileID); err != nil {
			return err
		}
	}
	_, err := tx.ExecContext(ctx, `DELETE FROM client_credentials WHERE client_id = ? AND profile_id NOT IN (`+
		strings.TrimSuffix("0,"+placeholders(len(keep)-1), ",")+`)`, keep...)
	return err
}

func saveDirectoryPasswords(ctx context.Context, tx *sql.Tx, clientID int64, dirs map[int64]string, box Sealer) error {
	for id, pw := range dirs {
		if pw == "" {
			continue
		}
		enc, err := box.Seal([]byte(pw), DirectoryPasswordAAD(clientID, id))
		if err != nil {
			return err
		}
		if _, err := tx.ExecContext(ctx, `INSERT INTO client_directory_passwords(client_id, directory_id, password_enc) VALUES(?,?,?)
			ON CONFLICT(client_id, directory_id) DO UPDATE SET password_enc = excluded.password_enc`, clientID, id, enc); err != nil {
			return mapErr(err)
		}
	}
	return nil
}

// Credentials returns the stored credentials of a client by profile id.
func (s *Store) Credentials(ctx context.Context, clientID int64) (map[int64]*Credential, error) {
	rows, err := s.DB.QueryContext(ctx, `SELECT profile_id, username, auth_user, password_enc, display_name, account_name
		FROM client_credentials WHERE client_id = ?`, clientID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := map[int64]*Credential{}
	for rows.Next() {
		k := &Credential{}
		var name sql.NullString
		if err := rows.Scan(&k.ProfileID, &k.Username, &k.AuthUser, &k.PasswordEnc, &k.DisplayName, &name); err != nil {
			return nil, err
		}
		k.AccountName = nullText(name)
		out[k.ProfileID] = k
	}
	return out, rows.Err()
}

// DirectoryPasswords returns the sealed client passwords by directory id.
func (s *Store) DirectoryPasswords(ctx context.Context, clientID int64) (map[int64]string, error) {
	rows, err := s.DB.QueryContext(ctx, `SELECT directory_id, password_enc FROM client_directory_passwords WHERE client_id = ?`, clientID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := map[int64]string{}
	for rows.Next() {
		var id int64
		var enc string
		if err := rows.Scan(&id, &enc); err != nil {
			return nil, err
		}
		out[id] = enc
	}
	return out, rows.Err()
}

func clientsOfTemplate(ctx context.Context, q querier, id int64) (int, error) {
	var n int
	err := q.QueryRowContext(ctx, `SELECT count(*) FROM clients WHERE client_template_id = ?`, id).Scan(&n)
	return n, err
}

func clientsOfConfigTemplate(ctx context.Context, q querier, id int64) (int, error) {
	var n int
	err := q.QueryRowContext(ctx, `SELECT count(*) FROM clients c JOIN client_templates t ON t.id = c.client_template_id
		WHERE t.config_template_id = ?`, id).Scan(&n)
	return n, err
}

// CountClients returns counts by status for a filter (dashboard).
func (s *Store) CountClients(ctx context.Context, f ClientFilter) (map[string]int, error) {
	where, args := f.where()
	rows, err := s.DB.QueryContext(ctx, `SELECT c.status, count(*) FROM clients c`+where+` GROUP BY c.status`, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := map[string]int{}
	for rows.Next() {
		var st string
		var n int
		if err := rows.Scan(&st, &n); err != nil {
			return nil, err
		}
		out[st] = n
	}
	return out, rows.Err()
}
