package store

import (
	"context"
	"database/sql"
	"errors"
	"strings"

	"siplink/internal/secret"
)

// Two-step sign-in of administrators (design §6.8): TOTP, passkeys, the e-mail code
// method, one-time e-mail codes and recovery codes.

// TwoStep is the state of an administrator's two-step sign-in.
type TwoStep struct {
	TOTP         bool // switched on (confirmed); a pending set-up does not count
	Email        bool
	Passkeys     int
	RecoveryLeft int
}

// Methods counts the methods that are on (recovery codes are not a method).
func (t TwoStep) Methods() int {
	n := t.Passkeys
	if t.TOTP {
		n++
	}
	if t.Email {
		n++
	}
	return n
}

// TwoStepState loads the state of an administrator.
func (s *Store) TwoStepState(ctx context.Context, adminID int64) (TwoStep, error) {
	return twoStepState(ctx, s.DB, adminID)
}

func twoStepState(ctx context.Context, q querier, adminID int64) (TwoStep, error) {
	var t TwoStep
	err := q.QueryRowContext(ctx, `SELECT
		EXISTS (SELECT 1 FROM admin_totp WHERE admin_id = a.id AND confirmed = 1),
		a.two_step_email,
		(SELECT count(*) FROM admin_passkeys WHERE admin_id = a.id),
		(SELECT count(*) FROM admin_recovery_codes WHERE admin_id = a.id AND used_at IS NULL)
		FROM admins a WHERE a.id = ?`, adminID).Scan(&t.TOTP, &t.Email, &t.Passkeys, &t.RecoveryLeft)
	return t, mapErr(err)
}

// TwoStepAdmins returns the ids of administrators with at least one method on.
func (s *Store) TwoStepAdmins(ctx context.Context) (map[int64]bool, error) {
	rows, err := s.DB.QueryContext(ctx, `SELECT id FROM admins WHERE two_step_email = 1
		UNION SELECT admin_id FROM admin_totp WHERE confirmed = 1
		UNION SELECT admin_id FROM admin_passkeys`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := map[int64]bool{}
	for rows.Next() {
		var id int64
		if err := rows.Scan(&id); err != nil {
			return nil, err
		}
		out[id] = true
	}
	return out, rows.Err()
}

// dropRecoveryWithoutMethods deletes the recovery codes of an administrator who has no
// method left, so that a later first method makes a fresh set.
func dropRecoveryWithoutMethods(ctx context.Context, tx *sql.Tx, adminID int64) error {
	_, err := tx.ExecContext(ctx, `DELETE FROM admin_recovery_codes WHERE admin_id = ?1
		AND NOT EXISTS (SELECT 1 FROM admins WHERE id = ?1 AND two_step_email = 1)
		AND NOT EXISTS (SELECT 1 FROM admin_totp WHERE admin_id = ?1 AND confirmed = 1)
		AND NOT EXISTS (SELECT 1 FROM admin_passkeys WHERE admin_id = ?1)`, adminID)
	return err
}

// ---- TOTP ----

// TOTPRow is the stored TOTP secret of an administrator.
type TOTPRow struct {
	SecretEnc string
	Confirmed bool
	LastStep  int64
}

// TOTPAAD binds a sealed TOTP secret to its row.
func TOTPAAD(adminID int64) []byte { return secret.AAD("admin_totp", adminID, "secret_enc") }

// TOTP loads the TOTP row of an administrator.
func (s *Store) TOTP(ctx context.Context, adminID int64) (*TOTPRow, error) {
	r := &TOTPRow{}
	err := s.DB.QueryRowContext(ctx, `SELECT secret_enc, confirmed, last_step FROM admin_totp WHERE admin_id = ?`, adminID).
		Scan(&r.SecretEnc, &r.Confirmed, &r.LastStep)
	return r, mapErr(err)
}

// ErrTOTPOn is returned when a set-up is started while TOTP is already on.
var ErrTOTPOn = errors.New("store: TOTP is already on")

// PutPendingTOTP stores a new secret that waits for its first code, replacing an
// earlier pending one; it refuses while TOTP is on.
func (s *Store) PutPendingTOTP(ctx context.Context, adminID int64, secretEnc string) error {
	now := s.NowText()
	res, err := s.DB.ExecContext(ctx, `INSERT INTO admin_totp(admin_id, secret_enc, confirmed, last_step, created_at, updated_at)
		VALUES(?, ?, 0, 0, ?, ?) ON CONFLICT(admin_id) DO UPDATE SET secret_enc = excluded.secret_enc,
		updated_at = excluded.updated_at WHERE admin_totp.confirmed = 0`, adminID, secretEnc, now, now)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrTOTPOn
	}
	return nil
}

// ConfirmTOTP switches a pending secret on with the step of its first code. It names
// the sealed secret the code was checked against, so that a set-up started again in
// parallel cannot get confirmed by a code of the earlier secret.
func (s *Store) ConfirmTOTP(ctx context.Context, adminID, step int64, secretEnc string) error {
	res, err := s.DB.ExecContext(ctx, `UPDATE admin_totp SET confirmed = 1, last_step = ?, updated_at = ?
		WHERE admin_id = ? AND confirmed = 0 AND secret_enc = ?`, step, s.NowText(), adminID, secretEnc)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrNotFound
	}
	return nil
}

// UseTOTPStep records a used step; false when that step (or a later one) was used
// already, so that a code passes once even under parallel requests.
func (s *Store) UseTOTPStep(ctx context.Context, adminID, step int64) (bool, error) {
	res, err := s.DB.ExecContext(ctx, `UPDATE admin_totp SET last_step = ?, updated_at = ?
		WHERE admin_id = ? AND confirmed = 1 AND last_step < ?`, step, s.NowText(), adminID, step)
	if err != nil {
		return false, err
	}
	n, _ := res.RowsAffected()
	return n == 1, nil
}

// DropPendingTOTP drops a set-up that was not confirmed ("取消" on the set-up page).
func (s *Store) DropPendingTOTP(ctx context.Context, adminID int64) error {
	_, err := s.DB.ExecContext(ctx, `DELETE FROM admin_totp WHERE admin_id = ? AND confirmed = 0`, adminID)
	return err
}

// ---- e-mail address and the e-mail code method ----

// SetAdminEmail stores a new address ("" = none). A changed address is unconfirmed and
// the e-mail method goes off with it; when that method was on, check (if not nil) gets
// the state before, inside the same transaction, and may refuse the change.
func (s *Store) SetAdminEmail(ctx context.Context, adminID int64, email string, check func(before TwoStep) error) error {
	email = strings.TrimSpace(email)
	return s.Tx(ctx, func(tx *sql.Tx) error {
		var old string
		var on bool
		err := tx.QueryRowContext(ctx, `SELECT email, two_step_email FROM admins WHERE id = ?`, adminID).Scan(&old, &on)
		if err != nil || old == email {
			return mapErr(err)
		}
		if on && check != nil {
			before, err := twoStepState(ctx, tx, adminID)
			if err != nil {
				return err
			}
			if err := check(before); err != nil {
				return err
			}
		}
		if _, err := tx.ExecContext(ctx, `UPDATE admins SET email = ?, email_confirmed = 0, two_step_email = 0, updated_at = ?
			WHERE id = ?`, email, s.NowText(), adminID); err != nil {
			return err
		}
		if _, err := tx.ExecContext(ctx, `DELETE FROM admin_email_codes WHERE admin_id = ?`, adminID); err != nil {
			return err
		}
		return dropRecoveryWithoutMethods(ctx, tx, adminID)
	})
}

// ConfirmAdminEmail marks the address confirmed, if it is still the given one.
func (s *Store) ConfirmAdminEmail(ctx context.Context, adminID int64, email string) error {
	res, err := s.DB.ExecContext(ctx, `UPDATE admins SET email_confirmed = 1, updated_at = ? WHERE id = ? AND email = ? AND email <> ''`,
		s.NowText(), adminID, email)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrNotFound
	}
	return nil
}

// EnableTwoStepEmail switches the e-mail code method on; it needs a confirmed address.
func (s *Store) EnableTwoStepEmail(ctx context.Context, adminID int64) error {
	res, err := s.DB.ExecContext(ctx, `UPDATE admins SET two_step_email = 1, updated_at = ? WHERE id = ? AND email_confirmed = 1`,
		s.NowText(), adminID)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrNotFound
	}
	return nil
}

// Two-step methods that RemoveMethod removes.
const (
	MethodTOTP    = "totp"
	MethodEmail   = "email"
	MethodPasskey = "passkey"
)

// ErrLastMethod is what a check of RemoveMethod or SetAdminEmail returns when the policy
// keeps the method (§6.8).
var ErrLastMethod = errors.New("store: the last two-step method must stay")

// RemoveMethod switches one method off (for a passkey: deletes passkeyID) in one
// transaction with the decision: check gets the state before the removal and may refuse
// it, so that parallel removals cannot each see another method left. ErrNotFound when the
// method is not on.
func (s *Store) RemoveMethod(ctx context.Context, adminID int64, method string, passkeyID int64, check func(before TwoStep) error) error {
	return s.Tx(ctx, func(tx *sql.Tx) error {
		before, err := twoStepState(ctx, tx, adminID)
		if err != nil {
			return err
		}
		var q string
		args := []any{adminID}
		switch method {
		case MethodTOTP:
			if !before.TOTP {
				return ErrNotFound
			}
			q = `DELETE FROM admin_totp WHERE admin_id = ?`
		case MethodEmail:
			if !before.Email {
				return ErrNotFound
			}
			q = `UPDATE admins SET two_step_email = 0 WHERE id = ?`
		case MethodPasskey:
			var n int
			if err := tx.QueryRowContext(ctx, `SELECT count(*) FROM admin_passkeys WHERE admin_id = ? AND id = ?`, adminID, passkeyID).Scan(&n); err != nil {
				return err
			}
			if n == 0 {
				return ErrNotFound
			}
			q, args = `DELETE FROM admin_passkeys WHERE admin_id = ? AND id = ?`, append(args, passkeyID)
		default:
			return ErrNotFound
		}
		if err := check(before); err != nil {
			return err
		}
		if _, err := tx.ExecContext(ctx, q, args...); err != nil {
			return err
		}
		return dropRecoveryWithoutMethods(ctx, tx, adminID)
	})
}

// ---- one-time e-mail codes ----

// EmailCode is a live code sent by e-mail.
type EmailCode struct {
	AdminID   int64
	Purpose   string
	CodeHash  string
	Address   string
	ExpiresAt string
}

// PutEmailCode stores a new code, replacing the earlier one of the same purpose.
func (s *Store) PutEmailCode(ctx context.Context, c *EmailCode) error {
	_, err := s.DB.ExecContext(ctx, `INSERT INTO admin_email_codes(admin_id, purpose, code_hash, address, expires_at, tries, created_at)
		VALUES(?,?,?,?,?,0,?) ON CONFLICT(admin_id, purpose) DO UPDATE SET code_hash = excluded.code_hash,
		address = excluded.address, expires_at = excluded.expires_at, tries = 0, created_at = excluded.created_at`,
		c.AdminID, c.Purpose, c.CodeHash, c.Address, c.ExpiresAt, s.NowText())
	return err
}

// CheckEmailCode compares a code with the live one: a match uses it up; a miss counts a
// try, and the code is void after maxTries misses or when it has expired. It returns the
// address the code was sent to.
func (s *Store) CheckEmailCode(ctx context.Context, adminID int64, purpose, hash string, maxTries int) (string, bool, error) {
	var address string
	var ok bool
	err := s.Tx(ctx, func(tx *sql.Tx) error {
		var c EmailCode
		var tries int
		err := tx.QueryRowContext(ctx, `SELECT code_hash, address, expires_at, tries FROM admin_email_codes WHERE admin_id = ? AND purpose = ?`,
			adminID, purpose).Scan(&c.CodeHash, &c.Address, &c.ExpiresAt, &tries)
		if errors.Is(err, sql.ErrNoRows) {
			return nil
		}
		if err != nil {
			return err
		}
		del := `DELETE FROM admin_email_codes WHERE admin_id = ? AND purpose = ?`
		if c.ExpiresAt <= s.NowText() {
			_, err = tx.ExecContext(ctx, del, adminID, purpose)
			return err
		}
		if equalHex(c.CodeHash, hash) {
			address, ok = c.Address, true
			_, err = tx.ExecContext(ctx, del, adminID, purpose)
			return err
		}
		if tries+1 >= maxTries {
			_, err = tx.ExecContext(ctx, del, adminID, purpose)
			return err
		}
		_, err = tx.ExecContext(ctx, `UPDATE admin_email_codes SET tries = tries + 1 WHERE admin_id = ? AND purpose = ?`, adminID, purpose)
		return err
	})
	return address, ok, err
}

// equalHex compares two hex digests without an early exit.
func equalHex(a, b string) bool {
	if len(a) != len(b) {
		return false
	}
	var d byte
	for i := 0; i < len(a); i++ {
		d |= a[i] ^ b[i]
	}
	return d == 0
}

// ---- recovery codes ----

// ReplaceRecoveryCodes voids every recovery code of the administrator and stores new ones.
func (s *Store) ReplaceRecoveryCodes(ctx context.Context, adminID int64, hashes []string) error {
	now := s.NowText()
	return s.Tx(ctx, func(tx *sql.Tx) error {
		if _, err := tx.ExecContext(ctx, `DELETE FROM admin_recovery_codes WHERE admin_id = ?`, adminID); err != nil {
			return err
		}
		for _, h := range hashes {
			if _, err := tx.ExecContext(ctx, `INSERT INTO admin_recovery_codes(admin_id, code_hash, created_at) VALUES(?,?,?)`, adminID, h, now); err != nil {
				return err
			}
		}
		return nil
	})
}

// UseRecoveryCode spends an unused recovery code; false when there is none with that hash.
func (s *Store) UseRecoveryCode(ctx context.Context, adminID int64, hash string) (bool, error) {
	res, err := s.DB.ExecContext(ctx, `UPDATE admin_recovery_codes SET used_at = ? WHERE id =
		(SELECT id FROM admin_recovery_codes WHERE admin_id = ? AND code_hash = ? AND used_at IS NULL LIMIT 1)`,
		s.NowText(), adminID, hash)
	if err != nil {
		return false, err
	}
	n, _ := res.RowsAffected()
	return n == 1, nil
}

// ---- passkeys ----

// Passkey is one WebAuthn credential of an administrator.
type Passkey struct {
	ID             int64
	AdminID        int64
	Name           string
	CredentialID   string // base64url of the raw id
	CredentialJSON string
	LastUsedAt     string
	CreatedAt      string
}

// Passkeys lists the passkeys of an administrator, oldest first.
func (s *Store) Passkeys(ctx context.Context, adminID int64) ([]Passkey, error) {
	rows, err := s.DB.QueryContext(ctx, `SELECT id, admin_id, name, credential_id, credential_json, COALESCE(last_used_at, ''), created_at
		FROM admin_passkeys WHERE admin_id = ? ORDER BY id`, adminID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Passkey
	for rows.Next() {
		var p Passkey
		if err := rows.Scan(&p.ID, &p.AdminID, &p.Name, &p.CredentialID, &p.CredentialJSON, &p.LastUsedAt, &p.CreatedAt); err != nil {
			return nil, err
		}
		out = append(out, p)
	}
	return out, rows.Err()
}

// AddPasskey stores a new passkey; ErrConflict when the credential is known already.
func (s *Store) AddPasskey(ctx context.Context, p *Passkey) error {
	now := s.NowText()
	res, err := s.DB.ExecContext(ctx, `INSERT INTO admin_passkeys(admin_id, name, credential_id, credential_json, created_at, updated_at)
		VALUES(?,?,?,?,?,?)`, p.AdminID, p.Name, p.CredentialID, p.CredentialJSON, now, now)
	if err != nil {
		return mapErr(err)
	}
	p.ID, _ = res.LastInsertId()
	p.CreatedAt = now
	return nil
}

// UsePasskey stores the credential after a sign-in (new sign count) and its time.
func (s *Store) UsePasskey(ctx context.Context, id int64, credentialJSON string) error {
	now := s.NowText()
	_, err := s.DB.ExecContext(ctx, `UPDATE admin_passkeys SET credential_json = ?, last_used_at = ?, updated_at = ? WHERE id = ?`,
		credentialJSON, now, now, id)
	return err
}

// ---- reset ----

// ResetTwoStep removes every method, the recovery and e-mail codes of an administrator,
// marks the address unconfirmed and ends their sessions.
func (s *Store) ResetTwoStep(ctx context.Context, adminID int64) error {
	return s.Tx(ctx, func(tx *sql.Tx) error {
		res, err := tx.ExecContext(ctx, `UPDATE admins SET two_step_email = 0, email_confirmed = 0, updated_at = ? WHERE id = ?`, s.NowText(), adminID)
		if err != nil {
			return err
		}
		if n, _ := res.RowsAffected(); n == 0 {
			return ErrNotFound
		}
		for _, q := range []string{`DELETE FROM admin_totp WHERE admin_id = ?`, `DELETE FROM admin_passkeys WHERE admin_id = ?`,
			`DELETE FROM admin_recovery_codes WHERE admin_id = ?`, `DELETE FROM admin_email_codes WHERE admin_id = ?`,
			`DELETE FROM sessions WHERE admin_id = ?`} {
			if _, err := tx.ExecContext(ctx, q, adminID); err != nil {
				return err
			}
		}
		return nil
	})
}

// ---- policy ----

const metaTwoStepRequired = "two_step_required"

// TwoStepRequired reports whether region administrators must use two-step sign-in.
func (s *Store) TwoStepRequired(ctx context.Context) (bool, error) {
	v, err := s.Meta(ctx, metaTwoStepRequired)
	if errors.Is(err, ErrNotFound) {
		return false, nil
	}
	return v == "1", err
}

// SetTwoStepRequired switches the policy.
func (s *Store) SetTwoStepRequired(ctx context.Context, on bool) error {
	if !on {
		_, err := s.DB.ExecContext(ctx, `DELETE FROM meta WHERE key = ?`, metaTwoStepRequired)
		return err
	}
	return s.SetMeta(ctx, metaTwoStepRequired, "1")
}

// ---- SMTP settings ----

// MailRow is the stored SMTP configuration (one row).
type MailRow struct {
	Host        string
	Port        int
	Security    string
	Username    string
	PasswordEnc string
	FromAddress string
	FromName    string
	UpdatedAt   string
}

// MailPasswordAAD binds the sealed SMTP password to its row.
func MailPasswordAAD() []byte { return secret.AAD("mail_settings", int64(1), "password_enc") }

// MailSettings loads the SMTP configuration; an empty one with defaults when none was saved.
func (s *Store) MailSettings(ctx context.Context) (*MailRow, error) {
	m := &MailRow{Port: 587, Security: "starttls"}
	err := s.DB.QueryRowContext(ctx, `SELECT host, port, security, username, password_enc, from_address, from_name, updated_at
		FROM mail_settings WHERE id = 1`).Scan(&m.Host, &m.Port, &m.Security, &m.Username, &m.PasswordEnc, &m.FromAddress, &m.FromName, &m.UpdatedAt)
	if errors.Is(err, sql.ErrNoRows) {
		return m, nil
	}
	return m, err
}

// PutMailSettings stores the SMTP configuration.
func (s *Store) PutMailSettings(ctx context.Context, m *MailRow) error {
	_, err := s.DB.ExecContext(ctx, `INSERT INTO mail_settings(id, host, port, security, username, password_enc, from_address, from_name, updated_at)
		VALUES(1,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET host = excluded.host, port = excluded.port, security = excluded.security,
		username = excluded.username, password_enc = excluded.password_enc, from_address = excluded.from_address,
		from_name = excluded.from_name, updated_at = excluded.updated_at`,
		m.Host, m.Port, m.Security, m.Username, m.PasswordEnc, m.FromAddress, m.FromName, s.NowText())
	return err
}
