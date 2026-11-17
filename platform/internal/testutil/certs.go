package testutil

import (
	"crypto"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/asn1"
	"encoding/pem"
	"math/big"
	"net"
	"testing"
	"time"
)

// Cert is a certificate made at test time with its key. Nothing of it is committed.
type Cert struct {
	Cert *x509.Certificate
	Key  crypto.Signer
}

// CertSpec describes a test certificate. Zero values give a server certificate valid
// for a year from From.
type CertSpec struct {
	CN       string
	Names    []string // DNS names and literal addresses
	From     time.Time
	Days     int
	CA       bool
	EKU      []x509.ExtKeyUsage // nil: serverAuth for a leaf, none for a CA
	NoEKU    bool               // leave the extension out
	EmptyAKI bool               // an AuthorityKeyIdentifier that is an empty SEQUENCE
}

// MakeCert makes a certificate signed by parent, or self-signed when parent is nil.
func MakeCert(t testing.TB, spec CertSpec, parent *Cert) *Cert {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	if spec.From.IsZero() {
		spec.From = time.Now().Add(-time.Hour)
	}
	if spec.Days == 0 {
		spec.Days = 365
	}
	sn, err := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 64))
	if err != nil {
		t.Fatal(err)
	}
	tmpl := &x509.Certificate{
		SerialNumber:          sn.Add(sn, big.NewInt(1)),
		Subject:               pkix.Name{CommonName: spec.CN},
		NotBefore:             spec.From,
		NotAfter:              spec.From.Add(time.Duration(spec.Days) * 24 * time.Hour),
		BasicConstraintsValid: true,
		IsCA:                  spec.CA,
	}
	if spec.CA {
		tmpl.KeyUsage = x509.KeyUsageCertSign | x509.KeyUsageCRLSign
	} else {
		tmpl.KeyUsage = x509.KeyUsageDigitalSignature
		tmpl.ExtKeyUsage = []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth}
	}
	if spec.EKU != nil {
		tmpl.ExtKeyUsage = spec.EKU
	}
	if spec.NoEKU {
		tmpl.ExtKeyUsage = nil
	}
	for _, n := range spec.Names {
		if ip := net.ParseIP(n); ip != nil {
			tmpl.IPAddresses = append(tmpl.IPAddresses, ip)
		} else {
			tmpl.DNSNames = append(tmpl.DNSNames, n)
		}
	}
	if spec.EmptyAKI {
		empty, _ := asn1.Marshal(struct{}{})
		tmpl.ExtraExtensions = append(tmpl.ExtraExtensions, pkix.Extension{Id: asn1.ObjectIdentifier{2, 5, 29, 35}, Value: empty})
	}
	signer, signerCert := crypto.Signer(key), tmpl
	if parent != nil {
		signer, signerCert = parent.Key, parent.Cert
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, signerCert, key.Public(), signer)
	if err != nil {
		t.Fatal(err)
	}
	c, err := x509.ParseCertificate(der)
	if err != nil {
		t.Fatal(err)
	}
	return &Cert{Cert: c, Key: key}
}

// ChainPEM writes certificates as one PEM text, in the order given.
func ChainPEM(certs ...*Cert) []byte {
	var out []byte
	for _, c := range certs {
		out = append(out, pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: c.Cert.Raw})...)
	}
	return out
}

// KeyPEM writes the key of c as PKCS#8 PEM.
func KeyPEM(t testing.TB, c *Cert) []byte {
	t.Helper()
	der, err := x509.MarshalPKCS8PrivateKey(c.Key)
	if err != nil {
		t.Fatal(err)
	}
	return pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: der})
}
