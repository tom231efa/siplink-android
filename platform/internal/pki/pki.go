// Package pki is the platform's built-in certificate authority (addendum 2, B):
// one CA made at the first start and kept for ever, and a server certificate it
// issues and renews by itself. Both live in the database; the private keys are
// sealed with the secret box. Certs (certs.go) chooses per listener between the
// operator's certificates and the built-in one (design §6.7).
package pki

import (
	"context"
	"crypto"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha1"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/asn1"
	"encoding/base64"
	"encoding/hex"
	"encoding/pem"
	"errors"
	"fmt"
	"math/big"
	"net"
	"slices"
	"strings"
	"sync"
	"time"

	"siplink/internal/secret"
	"siplink/internal/store"
)

// Validity and renewal rules of B.1 and B.2.
const (
	CAValidity     = 20 * 365 * 24 * time.Hour
	ServerValidity = 397 * 24 * time.Hour
	RenewBefore    = 30 * 24 * time.Hour
	Backdate       = time.Hour
)

const (
	nameCA     = "ca"
	nameServer = "server"
)

// Authority holds the CA and the current server certificate.
type Authority struct {
	st  *store.Store
	box *secret.Box
	now func() time.Time

	caCert *x509.Certificate
	caKey  crypto.Signer

	mu     sync.RWMutex
	server *tls.Certificate
}

// NewKey makes every private key of the CA. It is the one place to change the key type.
func NewKey() (crypto.Signer, error) {
	return ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
}

// Open loads the CA from the database or makes it when none is stored. now is the
// clock (injected for tests); nil means time.Now.
func Open(ctx context.Context, st *store.Store, box *secret.Box, now func() time.Time) (a *Authority, created bool, err error) {
	if now == nil {
		now = time.Now
	}
	a = &Authority{st: st, box: box, now: now}
	m, err := st.TLSMaterial(ctx, nameCA)
	if errors.Is(err, store.ErrNotFound) {
		if err := a.createCA(ctx); err != nil {
			return nil, false, err
		}
		m, err = st.TLSMaterial(ctx, nameCA)
		created = true
	}
	if err != nil {
		return nil, false, err
	}
	a.caCert, a.caKey, err = a.decode(m)
	if err != nil {
		return nil, false, fmt.Errorf("pki: stored CA: %w", err)
	}
	if m, err := st.TLSMaterial(ctx, nameServer); err == nil {
		cert, key, err := a.decode(m)
		if err == nil {
			a.server = a.chain(cert, key)
		}
	} else if !errors.Is(err, store.ErrNotFound) {
		return nil, false, err
	}
	return a, created, nil
}

func serial() (*big.Int, error) {
	// 128 random bits, positive.
	return rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 128))
}

func keyID(pub crypto.PublicKey) ([]byte, error) {
	der, err := x509.MarshalPKIXPublicKey(pub)
	if err != nil {
		return nil, err
	}
	var spki struct {
		Algorithm pkix.AlgorithmIdentifier
		PublicKey asn1.BitString
	}
	if _, err := asn1.Unmarshal(der, &spki); err != nil {
		return nil, err
	}
	sum := sha1.Sum(spki.PublicKey.Bytes) // RFC 5280 4.2.1.2 method 1
	return sum[:], nil
}

func (a *Authority) createCA(ctx context.Context) error {
	key, err := NewKey()
	if err != nil {
		return err
	}
	ski, err := keyID(key.Public())
	if err != nil {
		return err
	}
	sn, err := serial()
	if err != nil {
		return err
	}
	now := a.now()
	tmpl := &x509.Certificate{
		SerialNumber: sn,
		Subject: pkix.Name{
			CommonName:   "SipLink CA " + hex.EncodeToString(ski)[:8],
			Organization: []string{"SipLink"},
		},
		NotBefore:             now.Add(-Backdate),
		NotAfter:              now.Add(-Backdate).Add(CAValidity),
		KeyUsage:              x509.KeyUsageCertSign | x509.KeyUsageCRLSign,
		BasicConstraintsValid: true,
		IsCA:                  true,
		MaxPathLen:            0,
		MaxPathLenZero:        true,
		SubjectKeyId:          ski,
		// No AuthorityKeyId: Go adds none for a self-signed certificate whose template
		// leaves it empty, which is what B.1 requires.
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, key.Public(), key)
	if err != nil {
		return err
	}
	m, err := a.encode(nameCA, der, key)
	if err != nil {
		return err
	}
	return a.st.InsertTLSMaterial(ctx, m)
}

func (a *Authority) encode(name string, der []byte, key crypto.Signer) (*store.TLSMaterial, error) {
	pkcs8, err := x509.MarshalPKCS8PrivateKey(key)
	if err != nil {
		return nil, err
	}
	sealed, err := a.box.Seal(pkcs8, secret.AAD("tls_material", name, "key_enc"))
	if err != nil {
		return nil, err
	}
	return &store.TLSMaterial{
		Name:    name,
		CertPEM: string(pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der})),
		KeyEnc:  sealed,
	}, nil
}

func (a *Authority) decode(m *store.TLSMaterial) (*x509.Certificate, crypto.Signer, error) {
	block, _ := pem.Decode([]byte(m.CertPEM))
	if block == nil {
		return nil, nil, errors.New("no PEM certificate")
	}
	cert, err := x509.ParseCertificate(block.Bytes)
	if err != nil {
		return nil, nil, err
	}
	pkcs8, err := a.box.Open(m.KeyEnc, secret.AAD("tls_material", m.Name, "key_enc"))
	if err != nil {
		return nil, nil, err
	}
	k, err := x509.ParsePKCS8PrivateKey(pkcs8)
	if err != nil {
		return nil, nil, err
	}
	signer, ok := k.(crypto.Signer)
	if !ok {
		return nil, nil, errors.New("stored key cannot sign")
	}
	return cert, signer, nil
}

func (a *Authority) chain(leaf *x509.Certificate, key crypto.Signer) *tls.Certificate {
	return &tls.Certificate{
		Certificate: [][]byte{leaf.Raw, a.caCert.Raw},
		PrivateKey:  key,
		Leaf:        leaf,
	}
}

// SplitNames sorts names into DNS names and addresses.
func SplitNames(names []string) (dns []string, ips []net.IP) {
	for _, n := range names {
		if ip := net.ParseIP(n); ip != nil {
			ips = append(ips, ip)
		} else {
			dns = append(dns, strings.ToLower(n))
		}
	}
	return dns, ips
}

// certNames returns the names of a certificate in a comparable form.
func certNames(c *x509.Certificate) []string {
	var out []string
	for _, d := range c.DNSNames {
		out = append(out, "dns:"+strings.ToLower(d))
	}
	for _, ip := range c.IPAddresses {
		out = append(out, "ip:"+ip.String())
	}
	slices.Sort(out)
	return out
}

func wantNames(names []string) []string {
	dns, ips := SplitNames(names)
	var out []string
	for _, d := range dns {
		out = append(out, "dns:"+d)
	}
	for _, ip := range ips {
		out = append(out, "ip:"+ip.String())
	}
	slices.Sort(out)
	return slices.Compact(out)
}

// NeedsIssue reports why the server certificate must be issued again, or "".
func (a *Authority) NeedsIssue(names []string) string {
	a.mu.RLock()
	cur := a.server
	a.mu.RUnlock()
	switch {
	case cur == nil:
		return "missing"
	case !slices.Equal(certNames(cur.Leaf), wantNames(names)):
		return "names changed"
	case cur.Leaf.NotAfter.Sub(a.now()) < RenewBefore:
		return "expires soon"
	case !bytesEqual(cur.Leaf.AuthorityKeyId, a.caCert.SubjectKeyId):
		return "other CA"
	}
	return ""
}

func bytesEqual(a, b []byte) bool { return string(a) == string(b) }

// Ensure issues the server certificate when NeedsIssue says so. It returns the reason, or "".
func (a *Authority) Ensure(ctx context.Context, names []string) (string, error) {
	reason := a.NeedsIssue(names)
	if reason == "" {
		return "", nil
	}
	return reason, a.Issue(ctx, names)
}

// Issue makes a new server certificate for names and swaps it in.
func (a *Authority) Issue(ctx context.Context, names []string) error {
	if len(names) == 0 {
		return errors.New("pki: no names for the server certificate")
	}
	key, err := NewKey()
	if err != nil {
		return err
	}
	ski, err := keyID(key.Public())
	if err != nil {
		return err
	}
	sn, err := serial()
	if err != nil {
		return err
	}
	dns, ips := SplitNames(names)
	now := a.now()
	tmpl := &x509.Certificate{
		SerialNumber:          sn,
		Subject:               pkix.Name{CommonName: names[0]},
		NotBefore:             now.Add(-Backdate),
		NotAfter:              now.Add(-Backdate).Add(ServerValidity),
		KeyUsage:              x509.KeyUsageDigitalSignature,
		ExtKeyUsage:           []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
		BasicConstraintsValid: true,
		IsCA:                  false,
		SubjectKeyId:          ski,
		AuthorityKeyId:        a.caCert.SubjectKeyId,
		DNSNames:              dns,
		IPAddresses:           ips,
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, a.caCert, key.Public(), a.caKey)
	if err != nil {
		return err
	}
	leaf, err := x509.ParseCertificate(der)
	if err != nil {
		return err
	}
	m, err := a.encode(nameServer, der, key)
	if err != nil {
		return err
	}
	if err := a.st.PutTLSMaterial(ctx, m); err != nil {
		return err
	}
	a.mu.Lock()
	a.server = a.chain(leaf, key)
	a.mu.Unlock()
	return nil
}

// Reload reads the stored server certificate again when it differs from the one in use.
func (a *Authority) Reload(ctx context.Context) error {
	m, err := a.st.TLSMaterial(ctx, nameServer)
	if errors.Is(err, store.ErrNotFound) {
		return nil
	}
	if err != nil {
		return err
	}
	// Called once a minute: the key is opened only when the certificate changed.
	if block, _ := pem.Decode([]byte(m.CertPEM)); block != nil {
		if cur := a.Server(); cur != nil && bytesEqual(cur.Raw, block.Bytes) {
			return nil
		}
	}
	cert, key, err := a.decode(m)
	if err != nil {
		return err
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	if a.server == nil || !bytesEqual(a.server.Leaf.Raw, cert.Raw) {
		a.server = a.chain(cert, key)
	}
	return nil
}

// GetCertificate serves the current server certificate (tls.Config.GetCertificate).
func (a *Authority) GetCertificate(*tls.ClientHelloInfo) (*tls.Certificate, error) {
	a.mu.RLock()
	defer a.mu.RUnlock()
	if a.server == nil {
		return nil, errors.New("pki: no server certificate")
	}
	return a.server, nil
}

// Server returns the current server certificate, or nil.
func (a *Authority) Server() *x509.Certificate {
	a.mu.RLock()
	defer a.mu.RUnlock()
	if a.server == nil {
		return nil
	}
	return a.server.Leaf
}

// CA returns the CA certificate.
func (a *Authority) CA() *x509.Certificate { return a.caCert }

// CAPEM returns the CA certificate as PEM.
func (a *Authority) CAPEM() []byte {
	return pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: a.caCert.Raw})
}

// Pin returns the pin of the CA (B.4).
func (a *Authority) Pin() string { return Pin(a.caCert) }

// Pin is base64url without padding of SHA-256 over the DER SubjectPublicKeyInfo.
func Pin(c *x509.Certificate) string {
	sum := sha256.Sum256(c.RawSubjectPublicKeyInfo)
	return base64.RawURLEncoding.EncodeToString(sum[:])
}

// Fingerprint is the SHA-256 of the DER certificate written the way the app shows
// it: upper-case hex pairs joined by ':' (CertificateTools.fingerprint in the app).
func Fingerprint(c *x509.Certificate) string {
	sum := sha256.Sum256(c.Raw)
	parts := make([]string, len(sum))
	for i, b := range sum {
		parts[i] = fmt.Sprintf("%02X", b)
	}
	return strings.Join(parts, ":")
}

// ServerConfig returns a TLS configuration that presents this CA's server certificate.
func (a *Authority) ServerConfig() *tls.Config {
	return &tls.Config{MinVersion: tls.VersionTLS12, GetCertificate: a.GetCertificate}
}
