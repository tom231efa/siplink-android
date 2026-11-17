package store

import (
	"context"
	"database/sql"
	"errors"
)

// Session is one login of an administrator. Only the SHA-256 of the token is stored.
type Session struct {
	TokenHash  string
	AdminID    int64
	CreatedAt  string
	ExpiresAt  string // absolute end
	LastSeenAt string
	IP         string
	UserAgent  string
	Stage      string // "" complete, StageSecond waiting for the second step (§6.8)
}

// StageSecond marks a session that has passed the password and waits for the second step.
const StageSecond = "second"

// CreateSession stores a session.
func (s *Store) CreateSession(ctx context.Context, se *Session) error {
	_, err := s.DB.ExecContext(ctx, `INSERT INTO sessions(token_hash, admin_id, created_at, expires_at, last_seen_at, ip, user_agent, stage)
		VALUES(?,?,?,?,?,?,?,?)`, se.TokenHash, se.AdminID, se.CreatedAt, se.ExpiresAt, se.LastSeenAt, se.IP, se.UserAgent, se.Stage)
	return mapErr(err)
}

// SessionByHash loads a session.
func (s *Store) SessionByHash(ctx context.Context, hash string) (*Session, error) {
	se := &Session{}
	err := s.DB.QueryRowContext(ctx, `SELECT token_hash, admin_id, created_at, expires_at, last_seen_at, ip, user_agent, stage
		FROM sessions WHERE token_hash = ?`, hash).
		Scan(&se.TokenHash, &se.AdminID, &se.CreatedAt, &se.ExpiresAt, &se.LastSeenAt, &se.IP, &se.UserAgent, &se.Stage)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	return se, err
}

// TouchSession updates last_seen_at.
func (s *Store) TouchSession(ctx context.Context, hash, at string) error {
	_, err := s.DB.ExecContext(ctx, `UPDATE sessions SET last_seen_at = ? WHERE token_hash = ?`, at, hash)
	return err
}

// DeleteSession removes one session.
func (s *Store) DeleteSession(ctx context.Context, hash string) error {
	_, err := s.DB.ExecContext(ctx, `DELETE FROM sessions WHERE token_hash = ?`, hash)
	return err
}

// DeleteAdminSessions removes every session of an administrator.
func (s *Store) DeleteAdminSessions(ctx context.Context, adminID int64) error {
	_, err := s.DB.ExecContext(ctx, `DELETE FROM sessions WHERE admin_id = ?`, adminID)
	return err
}

// DeleteOtherSessions removes every session of an administrator but the one of keepHash.
func (s *Store) DeleteOtherSessions(ctx context.Context, adminID int64, keepHash string) error {
	_, err := s.DB.ExecContext(ctx, `DELETE FROM sessions WHERE admin_id = ? AND token_hash <> ?`, adminID, keepHash)
	return err
}

// PurgeSessions removes sessions past their absolute end or idle since before idleBefore.
func (s *Store) PurgeSessions(ctx context.Context, now, idleBefore string) (int64, error) {
	res, err := s.DB.ExecContext(ctx, `DELETE FROM sessions WHERE expires_at <= ? OR last_seen_at <= ?`, now, idleBefore)
	if err != nil {
		return 0, err
	}
	return res.RowsAffected()
}
