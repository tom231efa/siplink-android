package pki

import (
	"context"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"encoding/asn1"
	"encoding/base64"
	"testing"
	"time"

	"siplink/internal/testutil"
)

var (
	oidAKI = asn1.ObjectIdentifier{2, 5, 29, 35}
	oidBC  = asn1.ObjectIdentifier{2, 5, 29, 19}
	oidKU  = asn1.ObjectIdentifier{2, 5, 29, 15}
	oidSKI = asn1.ObjectIdentifier{2, 5, 29, 14}
)

var names = []string{"platform.example.com", "ldap.example.com", "192.0.2.10", "localhost", "127.0.0.1", "::1"}

func ext(t *testing.T, der []byte, oid asn1.ObjectIdentifier) (value []byte, critical, found bool) {
	t.Helper()
	// Parse the DER independently of crypto/x509's own fields.
	var cert struct {
		TBS struct {
			Raw        asn1.RawContent
			Version    int `asn1:"optional,explicit,default:0,tag:0"`
			Serial     asn1.RawValue
			SigAlg     asn1.RawValue
			Issuer     asn1.RawValue
			Validity   asn1.RawValue
			Subject    asn1.RawValue
			PublicKey  asn1.RawValue
			Extensions []struct {
				ID       asn1.ObjectIdentifier
				Critical bool `asn1:"optional"`
				Value    []byte
			} `asn1:"optional,explicit,tag:3"`
		}
		SigAlg asn1.RawValue
		Sig    asn1.BitString
	}
	if rest, err := asn1.Unmarshal(der, &cert); err != nil || len(rest) != 0 {
		t.Fatalf("parse DER: %v", err)
	}
	for _, e := range cert.TBS.Extensions {
		if e.ID.Equal(oid) {
			return e.Value, e.Critical, true
		}
	}
	return nil, false, false
}

func TestCAOnceAndExtensions(t *testing.T) {
	ctx := context.Background()
	st, box := testutil.Store(t), testutil.Box(t)
	clock := &testutil.Clock{T: time.Date(2026, 11, 16, 10, 0, 0, 0, time.UTC)}
	a, created, err := Open(ctx, st, box, clock.Now)
	if err != nil || !created {
		t.Fatalf("open: %v %v", created, err)
	}
	b, created, err := Open(ctx, st, box, clock.Now)
	if err != nil || created || string(a.CA().Raw) != string(b.CA().Raw) {
		t.Fatalf("CA not reused: created=%v err=%v", created, err)
	}
	ca := a.CA()
	if !ca.NotBefore.Equal(clock.T.Add(-time.Hour)) || !ca.NotAfter.Equal(clock.T.Add(-time.Hour).Add(20*365*24*time.Hour)) {
		t.Errorf("CA validity %v - %v", ca.NotBefore, ca.NotAfter)
	}
	if ca.Subject.CommonName[:len("SipLink CA ")] != "SipLink CA " || len(ca.Subject.CommonName) != len("SipLink CA ")+8 ||
		len(ca.Subject.Organization) != 1 || ca.Subject.Organization[0] != "SipLink" {
		t.Errorf("CA subject %v", ca.Subject)
	}
	if ca.SerialNumber.Sign() <= 0 || ca.SerialNumber.BitLen() > 128 {
		t.Errorf("serial %v", ca.SerialNumber)
	}
	if !ca.IsCA || ca.MaxPathLen != 0 || !ca.MaxPathLenZero || ca.KeyUsage != x509.KeyUsageCertSign|x509.KeyUsageCRLSign {
		t.Errorf("CA constraints/usage wrong")
	}
	if _, _, found := ext(t, ca.Raw, oidAKI); found {
		t.Error("root carries an AuthorityKeyIdentifier (2.5.29.35)")
	}
	if _, crit, found := ext(t, ca.Raw, oidBC); !found || !crit {
		t.Error("root BasicConstraints missing or not critical")
	}
	if _, crit, found := ext(t, ca.Raw, oidKU); !found || !crit {
		t.Error("root KeyUsage missing or not critical")
	}
	skiVal, _, found := ext(t, ca.Raw, oidSKI)
	var ski []byte
	if !found {
		t.Fatal("root has no SubjectKeyIdentifier")
	}
	asn1.Unmarshal(skiVal, &ski)

	if _, err := a.Ensure(ctx, names); err != nil {
		t.Fatal(err)
	}
	leaf := a.Server()
	akiVal, _, found := ext(t, leaf.Raw, oidAKI)
	if !found {
		t.Fatal("leaf has no AuthorityKeyIdentifier")
	}
	var aki struct {
		KeyID []byte `asn1:"optional,tag:0"`
	}
	if _, err := asn1.Unmarshal(akiVal, &aki); err != nil || string(aki.KeyID) != string(ski) || len(ski) == 0 {
		t.Errorf("leaf AKI keyIdentifier %x != root SKI %x (%v)", aki.KeyID, ski, err)
	}
	if _, _, found := ext(t, leaf.Raw, oidSKI); !found {
		t.Error("leaf has no SubjectKeyIdentifier")
	}
	if _, crit, found := ext(t, leaf.Raw, oidKU); !found || !crit || leaf.KeyUsage != x509.KeyUsageDigitalSignature {
		t.Error("leaf KeyUsage")
	}
	if leaf.IsCA || len(leaf.ExtKeyUsage) != 1 || leaf.ExtKeyUsage[0] != x509.ExtKeyUsageServerAuth {
		t.Error("leaf CA flag or EKU")
	}
	if leaf.Subject.CommonName != "platform.example.com" {
		t.Errorf("leaf CN %q", leaf.Subject.CommonName)
	}
	if !leaf.NotAfter.Equal(clock.T.Add(-time.Hour).Add(397 * 24 * time.Hour)) {
		t.Errorf("leaf validity %v", leaf.NotAfter)
	}
}

func TestNamesVerifyAndReissue(t *testing.T) {
	ctx := context.Background()
	st, box := testutil.Store(t), testutil.Box(t)
	clock := &testutil.Clock{T: time.Date(2026, 11, 16, 10, 0, 0, 0, time.UTC)}
	a, _, err := Open(ctx, st, box, clock.Now)
	if err != nil {
		t.Fatal(err)
	}
	if reason, err := a.Ensure(ctx, names); err != nil || reason != "missing" {
		t.Fatalf("first issue: %q %v", reason, err)
	}
	leaf := a.Server()
	if len(leaf.DNSNames) != 3 || len(leaf.IPAddresses) != 3 {
		t.Errorf("SANs dns=%v ip=%v", leaf.DNSNames, leaf.IPAddresses)
	}
	for _, ip := range leaf.IPAddresses {
		if ip.String() == "192.0.2.10" {
			goto found
		}
	}
	t.Error("192.0.2.10 not an iPAddress entry")
found:
	pool := x509.NewCertPool()
	pool.AddCert(a.CA())
	for _, name := range []string{"platform.example.com", "ldap.example.com", "192.0.2.10", "localhost", "::1"} {
		if _, err := leaf.Verify(x509.VerifyOptions{DNSName: name, Roots: pool, CurrentTime: clock.T,
			KeyUsages: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth}}); err != nil {
			t.Errorf("verify %s: %v", name, err)
		}
	}
	if _, err := leaf.Verify(x509.VerifyOptions{DNSName: "other.example.com", Roots: pool, CurrentTime: clock.T}); err == nil {
		t.Error("verified for a name it does not carry")
	}
	// Same names: nothing to do; reopening keeps the stored certificate.
	if reason, _ := a.Ensure(ctx, names); reason != "" {
		t.Errorf("unexpected re-issue: %s", reason)
	}
	b, _, _ := Open(ctx, st, box, clock.Now)
	if string(b.Server().Raw) != string(leaf.Raw) {
		t.Error("server certificate not reloaded from the database")
	}
	// Changed names.
	if reason, err := a.Ensure(ctx, append(names, "extra.example.com")); err != nil || reason != "names changed" {
		t.Errorf("names change: %q %v", reason, err)
	}
	pinBefore := a.Pin()
	// Near expiry: 31 days left = fine, 29 days left = re-issue.
	clock.T = a.Server().NotAfter.Add(-31 * 24 * time.Hour)
	if reason, _ := a.Ensure(ctx, append(names, "extra.example.com")); reason != "" {
		t.Errorf("31 days left: %q", reason)
	}
	clock.T = a.Server().NotAfter.Add(-29 * 24 * time.Hour)
	old := a.Server()
	if reason, err := a.Ensure(ctx, append(names, "extra.example.com")); err != nil || reason != "expires soon" {
		t.Errorf("29 days left: %q %v", reason, err)
	}
	if a.Server().SerialNumber.Cmp(old.SerialNumber) == 0 || !a.Server().NotAfter.After(old.NotAfter) {
		t.Error("certificate not replaced")
	}
	if a.Pin() != pinBefore {
		t.Error("pin changed with the server certificate")
	}
	// Swapped in without a restart.
	got, err := a.GetCertificate(&tls.ClientHelloInfo{})
	if err != nil || string(got.Leaf.Raw) != string(a.Server().Raw) || len(got.Certificate) != 2 ||
		string(got.Certificate[1]) != string(a.CA().Raw) {
		t.Error("GetCertificate does not send leaf + CA")
	}
}

func TestPinIndependent(t *testing.T) {
	ctx := context.Background()
	a, _, err := Open(ctx, testutil.Store(t), testutil.Box(t), nil)
	if err != nil {
		t.Fatal(err)
	}
	der, err := x509.MarshalPKIXPublicKey(a.CA().PublicKey)
	if err != nil {
		t.Fatal(err)
	}
	sum := sha256.Sum256(der)
	want := base64.RawURLEncoding.EncodeToString(sum[:])
	if a.Pin() != want || len(want) != 43 {
		t.Errorf("pin %q want %q", a.Pin(), want)
	}
	if f := Fingerprint(a.CA()); len(f) != 95 || f[2] != ':' {
		t.Errorf("fingerprint form %q", f)
	}
}

func TestTLSHandshake(t *testing.T) {
	ctx := context.Background()
	a, _, _ := Open(ctx, testutil.Store(t), testutil.Box(t), nil)
	if _, err := a.Ensure(ctx, names); err != nil {
		t.Fatal(err)
	}
	ln, err := tls.Listen("tcp", "127.0.0.1:0", a.ServerConfig())
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			c.(*tls.Conn).Handshake()
			c.Close()
		}
	}()
	pool := x509.NewCertPool()
	pool.AddCert(a.CA())
	c, err := tls.Dial("tcp", ln.Addr().String(), &tls.Config{RootCAs: pool, ServerName: "localhost"})
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	chain := c.ConnectionState().PeerCertificates
	if len(chain) != 2 || Pin(chain[1]) != a.Pin() {
		t.Errorf("chain length %d", len(chain))
	}
	// TLS 1.1 is refused.
	if _, err := tls.Dial("tcp", ln.Addr().String(), &tls.Config{RootCAs: pool, ServerName: "localhost", MaxVersion: tls.VersionTLS11}); err == nil {
		t.Error("TLS 1.1 accepted")
	}
}
