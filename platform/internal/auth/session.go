package auth

import (
	"context"
	"errors"
	"time"

	"siplink/internal/secret"
	"siplink/internal/store"
)

// Session lifetimes (design §6.2).
const (
	IdleTimeout     = 2 * time.Hour
	AbsoluteTimeout = 12 * time.Hour
	touchEvery      = time.Minute
)

// ErrNoSession is returned for a missing, expired or revoked session.
var ErrNoSession = errors.New("auth: no valid session")

// Sessions creates and checks administrator sessions.
type Sessions struct {
	St  *store.Store
	Now func() time.Time
}

// Create starts a session and returns the token for the cookie.
func (m *Sessions) Create(ctx context.Context, adminID int64, ip, userAgent string) (string, error) {
	token := secret.Token(32)
	now := m.Now().UTC()
	if len(userAgent) > 200 {
		userAgent = userAgent[:200]
	}
	err := m.St.CreateSession(ctx, &store.Session{
		TokenHash:  secret.HashHex(token),
		AdminID:    adminID,
		CreatedAt:  now.Format(store.TimeFormat),
		ExpiresAt:  now.Add(AbsoluteTimeout).Format(store.TimeFormat),
		LastSeenAt: now.Format(store.TimeFormat),
		IP:         ip,
		UserAgent:  userAgent,
	})
	return token, err
}

// Check returns the administrator of a valid session token and refreshes its idle timer.
func (m *Sessions) Check(ctx context.Context, token string) (*store.Admin, *store.Session, error) {
	if token == "" {
		return nil, nil, ErrNoSession
	}
	hash := secret.HashHex(token)
	se, err := m.St.SessionByHash(ctx, hash)
	if errors.Is(err, store.ErrNotFound) {
		return nil, nil, ErrNoSession
	}
	if err != nil {
		return nil, nil, err
	}
	now := m.Now().UTC()
	last := store.ParseTime(se.LastSeenAt)
	if !now.Before(store.ParseTime(se.ExpiresAt)) || now.Sub(last) >= IdleTimeout {
		_ = m.St.DeleteSession(ctx, hash)
		return nil, nil, ErrNoSession
	}
	admin, err := m.St.AdminByID(ctx, se.AdminID)
	if errors.Is(err, store.ErrNotFound) {
		return nil, nil, ErrNoSession
	}
	if err != nil {
		return nil, nil, err
	}
	if admin.Disabled {
		_ = m.St.DeleteAdminSessions(ctx, admin.ID)
		return nil, nil, ErrNoSession
	}
	if now.Sub(last) >= touchEvery {
		_ = m.St.TouchSession(ctx, hash, now.Format(store.TimeFormat))
	}
	return admin, se, nil
}

// Destroy ends the session of a token.
func (m *Sessions) Destroy(ctx context.Context, token string) error {
	if token == "" {
		return nil
	}
	return m.St.DeleteSession(ctx, secret.HashHex(token))
}

// Purge removes expired sessions.
func (m *Sessions) Purge(ctx context.Context) (int64, error) {
	now := m.Now().UTC()
	return m.St.PurgeSessions(ctx, now.Format(store.TimeFormat), now.Add(-IdleTimeout).Format(store.TimeFormat))
}
