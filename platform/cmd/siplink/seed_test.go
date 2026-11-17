package main

import (
	"bytes"
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/pem"
	"math/big"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"testing"
	"time"

	"siplink/internal/catalog"
	"siplink/internal/provision"
	"siplink/internal/store"
)

var seedURL = regexp.MustCompile(`^https://platform\.example\.com/p/[A-Za-z0-9_-]{43}\?pin=[A-Za-z0-9_-]{43}\n$`)

// TestSeed runs the example spec of docs/examples twice against a fresh data directory.
func TestSeed(t *testing.T) {
	dir := t.TempDir()
	t.Setenv("SIPLINK_PUBLIC_URL", "https://platform.example.com")
	t.Setenv("SIPLINK_DATA_DIR", dir)
	t.Setenv("SIPLINK_KEY_FILE", filepath.Join(dir, "key"))
	t.Setenv("SIPLINK_LOG_LEVEL", "error")
	t.Setenv("SEED_SIP_PASSWORD", "test-sip-password")
	t.Setenv("SEED_LDAP_PASSWORD", "test-ldap-password")

	// The spec's files, next to a copy of it: a CA made now, the example CSVs.
	key, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	tpl := &x509.Certificate{SerialNumber: big.NewInt(1), Subject: pkix.Name{CommonName: "Test CA"}, NotBefore: time.Now(),
		NotAfter: time.Now().Add(time.Hour), IsCA: true, BasicConstraintsValid: true}
	der, _ := x509.CreateCertificate(rand.Reader, tpl, tpl, &key.PublicKey, key)
	os.WriteFile(filepath.Join(dir, "ca.pem"), pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der}), 0o600)
	for _, f := range []string{"seed-people.csv", "seed-keys.csv", "seed.example.json"} {
		b, err := os.ReadFile(filepath.Join("../../docs/examples", f))
		if err != nil {
			t.Fatal(err)
		}
		if f == "seed.example.json" { // paths in the spec are relative to where seed runs; make them absolute here
			s := strings.NewReplacer(`"ca.pem"`, `"`+filepath.Join(dir, "ca.pem")+`"`,
				`"seed-people.csv"`, `"`+filepath.Join(dir, "seed-people.csv")+`"`,
				`"seed-keys.csv"`, `"`+filepath.Join(dir, "seed-keys.csv")+`"`).Replace(string(b))
			b = []byte(s)
		}
		os.WriteFile(filepath.Join(dir, f), b, 0o600)
	}
	spec := filepath.Join(dir, "seed.example.json")
	var out bytes.Buffer
	if err := seedCommand([]string{spec}, &out); err != nil {
		t.Fatal(err)
	}
	first := out.String()
	if !seedURL.MatchString(first) {
		t.Fatalf("url: %q", first)
	}
	out.Reset()
	if err := seedCommand([]string{spec}, &out); err != nil {
		t.Fatalf("second run: %v", err)
	}
	if !seedURL.MatchString(out.String()) || out.String() == first {
		t.Errorf("second url: %q", out.String())
	}

	ctx := context.Background()
	e, err := open(ctx)
	if err != nil {
		t.Fatal(err)
	}
	defer e.st.Close()
	clients, _, _ := e.st.ListClients(ctx, store.ClientFilter{All: true})
	cfgs, _ := e.st.ListConfigTemplates(ctx, store.BookScope{All: true})
	if len(clients) != 1 || len(cfgs) != 1 {
		t.Fatalf("objects after two runs: %d clients, %d templates", len(clients), len(cfgs))
	}
	b, _ := e.st.BookBySlug(ctx, "staff")
	if b == nil || b.EntryCount != 3 {
		t.Errorf("book: %+v", b)
	}
	// The seeded client gets a valid document with the hosted and the external directory.
	p := catalog.Platform{Name: "x", SyncURL: "https://platform.example.com/api/device/v1/sync", Box: e.box, LDAPKey: e.keys.LDAP,
		Hosted: catalog.Hosted{Host: e.cfg.LDAPPublicHost, Port: e.cfg.LDAPPublicPort, Suffix: e.cfg.LDAPSuffix, RequireTLS: true}}
	in, err := catalog.Assemble(ctx, e.st, p, clients[0])
	if err != nil {
		t.Fatal(err)
	}
	d, problems := provision.Build(in)
	if len(problems) > 0 || len(d.Accounts) != 1 || d.Accounts[0].Password != "test-sip-password" || len(d.Directories) != 2 ||
		d.Directories[1].Password != "test-ldap-password" || len(d.Shortcuts) != 3 || d.Shortcuts[0].Line != "总部" || len(d.CACertificates) != 1 {
		t.Errorf("document: %v %+v", problems, d)
	}
}
