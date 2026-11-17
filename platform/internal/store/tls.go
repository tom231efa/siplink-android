package store

import (
	"context"
)

// TLSMaterial is a certificate and its sealed private key (addendum 2, B.3).
type TLSMaterial struct {
	Name      string // "ca", "server" or "own" (the operator's certificate, §6.7)
	CertPEM   string
	KeyEnc    string
	CreatedAt string
	UpdatedAt string
}

// TLSMaterial loads one stored certificate.
func (s *Store) TLSMaterial(ctx context.Context, name string) (*TLSMaterial, error) {
	m := &TLSMaterial{}
	err := s.DB.QueryRowContext(ctx, `SELECT name, cert_pem, key_enc, created_at, updated_at FROM tls_material WHERE name = ?`, name).
		Scan(&m.Name, &m.CertPEM, &m.KeyEnc, &m.CreatedAt, &m.UpdatedAt)
	return m, mapErr(err)
}

// InsertTLSMaterial stores a certificate that must not exist yet (the CA is made once).
func (s *Store) InsertTLSMaterial(ctx context.Context, m *TLSMaterial) error {
	now := s.NowText()
	_, err := s.DB.ExecContext(ctx, `INSERT INTO tls_material(name, cert_pem, key_enc, created_at, updated_at) VALUES(?,?,?,?,?)`,
		m.Name, m.CertPEM, m.KeyEnc, now, now)
	return mapErr(err)
}

// PutTLSMaterial stores or replaces a certificate.
func (s *Store) PutTLSMaterial(ctx context.Context, m *TLSMaterial) error {
	now := s.NowText()
	_, err := s.DB.ExecContext(ctx, `INSERT INTO tls_material(name, cert_pem, key_enc, created_at, updated_at) VALUES(?,?,?,?,?)
		ON CONFLICT(name) DO UPDATE SET cert_pem = excluded.cert_pem, key_enc = excluded.key_enc, updated_at = excluded.updated_at`,
		m.Name, m.CertPEM, m.KeyEnc, now, now)
	return err
}

// DeleteTLSMaterial removes a stored certificate; a missing one is not an error.
func (s *Store) DeleteTLSMaterial(ctx context.Context, name string) error {
	_, err := s.DB.ExecContext(ctx, `DELETE FROM tls_material WHERE name = ?`, name)
	return err
}
