//go:build interop

// Interoperability with OpenLDAP's client library (the one the Linphone SDK uses)
// and with OpenSSL. Run inside golang:1.27.3-trixie after
// "apt-get install -y ldap-utils openssl":  go test -tags interop -run Interop ./internal/ldapd/
package ldapd

import (
	"context"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/pem"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"siplink/internal/pki"
	"siplink/internal/testutil"
)

type runResult struct {
	out  string
	code int
}

func run(t *testing.T, env []string, stdin string, name string, args ...string) runResult {
	t.Helper()
	cmd := exec.Command(name, args...)
	cmd.Env = append(os.Environ(), env...)
	if stdin != "" {
		cmd.Stdin = strings.NewReader(stdin)
	}
	out, err := cmd.CombinedOutput()
	code := 0
	if ee, ok := err.(*exec.ExitError); ok {
		code = ee.ExitCode()
	} else if err != nil {
		t.Fatalf("%s: %v", name, err)
	}
	return runResult{string(out), code}
}

func port(addr string) string { _, p, _ := net.SplitHostPort(addr); return p }

func TestInteropLdapsearch(t *testing.T) {
	e := newEnv(t)
	dir := t.TempDir()
	caFile := filepath.Join(dir, "ca.pem")
	os.WriteFile(caFile, e.ca.CAPEM(), 0o644)
	tlsEnv := []string{"LDAPTLS_CACERT=" + caFile, "LDAPTLS_REQCERT=demand"}
	// libldap replaces "localhost" by the machine's own name before checking the
	// certificate, so use an address or a real name (SIPLINK_INTEROP_HOST, mapped to
	// 127.0.0.1 in /etc/hosts by the caller; the test certificate carries ldap.example.com).
	host := os.Getenv("SIPLINK_INTEROP_HOST")
	if host == "" {
		host = "127.0.0.1"
	}
	url := "ldap://" + host + ":" + port(e.addr)
	bind := []string{"-x", "-H", url, "-D", accountDN("phone1"), "-w", e.passwords["phone1"]}
	staff := bookBase("staff")

	// The exact filters of design §4.1, -z maxResults+1, -ZZ for StartTLS.
	r := run(t, tlsEnv, "", "ldapsearch", append(append([]string{"-ZZ"}, bind...),
		"-b", staff, "-s", "sub", "-z", "51", "-l", "5", sdk(sdkName, "张"))...)
	if r.code != 0 || !strings.Contains(r.out, "uid=") || !strings.Contains(r.out, "numEntries: 1") {
		t.Errorf("name search via ldapsearch -ZZ (exit %d):\n%s", r.code, r.out)
	}
	r = run(t, tlsEnv, "", "ldapsearch", append(append([]string{"-ZZ"}, bind...),
		"-b", staff, "-s", "sub", "-z", "51", sdk(sdkNumber, "13800001001"), "cn", "mobile")...)
	if r.code != 0 || !strings.Contains(r.out, "mobile: 138 0000 1001") {
		t.Errorf("number search (exit %d):\n%s", r.code, r.out)
	}
	r = run(t, tlsEnv, "", "ldapsearch", append(append([]string{"-ZZ"}, bind...),
		"-b", staff, "-s", "sub", "-z", "51", `(|(cn=*a\2ab*)(sn=*a\2ab*))`, "cn")...)
	if r.code != 0 || !strings.Contains(r.out, "numEntries: 1") {
		t.Errorf("escaped filter (exit %d):\n%s", r.code, r.out)
	}
	// Size limit: exit code 4 = sizeLimitExceeded, with the entries up to the limit.
	r = run(t, tlsEnv, "", "ldapsearch", append(append([]string{"-ZZ"}, bind...),
		"-b", staff, "-s", "sub", "-z", "2", "(telephoneNumber=*)", "cn")...)
	if r.code != 4 || !strings.Contains(r.out, "numEntries: 2") {
		t.Errorf("size limit (exit %d):\n%s", r.code, r.out)
	}
	// Foreign book: 32.
	r = run(t, tlsEnv, "", "ldapsearch", append(append([]string{"-ZZ"}, bind...), "-b", bookBase("other"), "(cn=*)")...)
	if r.code != 32 {
		t.Errorf("foreign book (exit %d):\n%s", r.code, r.out)
	}
	// A bind before StartTLS: 13.
	r = run(t, tlsEnv, "", "ldapsearch", append(bind, "-b", staff, "(cn=*)")...)
	if r.code != 13 {
		t.Errorf("bind without StartTLS (exit %d):\n%s", r.code, r.out)
	}
	// Root DSE anonymously without TLS.
	r = run(t, nil, "", "ldapsearch", "-x", "-H", url, "-b", "", "-s", "base", "+")
	if r.code != 0 || !strings.Contains(r.out, "supportedExtension: "+OIDStartTLS) {
		t.Errorf("root DSE (exit %d):\n%s", r.code, r.out)
	}
	// LDAPS listener.
	r = run(t, tlsEnv, "", "ldapsearch", "-x", "-H", "ldaps://"+host+":"+port(e.ldapsAddr), "-D", accountDN("phone1"),
		"-w", e.passwords["phone1"], "-b", staff, "(cn=王芳)", "cn")
	if r.code != 0 || !strings.Contains(r.out, "numEntries: 1") {
		t.Errorf("LDAPS (exit %d):\n%s", r.code, r.out)
	}
	// A wrong CA: the TLS library refuses.
	other := filepath.Join(dir, "other.pem")
	otherCA, _, err := pki.Open(t.Context(), testutil.Store(t), testutil.Box(t), nil)
	if err != nil {
		t.Fatal(err)
	}
	os.WriteFile(other, otherCA.CAPEM(), 0o644) // a second CA made now, which signed nothing here
	r = run(t, []string{"LDAPTLS_CACERT=" + other, "LDAPTLS_REQCERT=demand"}, "", "ldapsearch",
		append(append([]string{"-ZZ"}, bind...), "-b", staff, "(cn=*)")...)
	if r.code == 0 {
		t.Errorf("StartTLS accepted with a foreign CA:\n%s", r.out)
	}
}

func TestInteropOpenSSL(t *testing.T) {
	e := newEnv(t)
	dir := t.TempDir()
	caFile := filepath.Join(dir, "ca.pem")
	leafFile := filepath.Join(dir, "leaf.pem")
	os.WriteFile(caFile, e.ca.CAPEM(), 0o644)
	os.WriteFile(leafFile, pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: e.ca.Server().Raw}), 0o644)

	if r := run(t, nil, "", "openssl", "verify", "-CAfile", caFile, leafFile); r.code != 0 || !strings.Contains(r.out, "OK") {
		t.Errorf("openssl verify (exit %d): %s", r.code, r.out)
	}
	// StartTLS on the LDAP port.
	r := run(t, nil, "Q\n", "openssl", "s_client", "-connect", "127.0.0.1:"+port(e.addr), "-starttls", "ldap",
		"-servername", "localhost", "-verify_hostname", "localhost", "-verify_return_error", "-CAfile", caFile)
	if r.code != 0 || !strings.Contains(r.out, "Verify return code: 0 (ok)") {
		t.Errorf("s_client -starttls ldap (exit %d):\n%s", r.code, r.out)
	}
	// HTTPS with the same certificate source.
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	hs := &http.Server{Handler: http.NotFoundHandler(), TLSConfig: e.ca.ServerConfig()}
	go hs.ServeTLS(ln, "", "")
	defer hs.Close()
	r = run(t, nil, "Q\n", "openssl", "s_client", "-connect", ln.Addr().String(), "-servername", "localhost",
		"-verify_hostname", "localhost", "-verify_return_error", "-CAfile", caFile)
	if r.code != 0 || !strings.Contains(r.out, "Verify return code: 0 (ok)") {
		t.Errorf("s_client HTTPS (exit %d):\n%s", r.code, r.out)
	}
	// The pin recomputed with OpenSSL equals the platform's.
	r = run(t, nil, "", "sh", "-c", "openssl x509 -in "+caFile+" -pubkey -noout | openssl pkey -pubin -outform der | openssl dgst -sha256 -binary | base64 | tr '+/' '-_' | tr -d '=\\n'")
	if r.code != 0 || r.out != e.ca.Pin() {
		t.Errorf("pin from openssl %q, platform %q", r.out, e.ca.Pin())
	}
	sum := sha256.Sum256(e.ca.CA().RawSubjectPublicKeyInfo)
	if base64.RawURLEncoding.EncodeToString(sum[:]) != e.ca.Pin() {
		t.Error("pin differs from the SPKI hash")
	}
}

// TestInteropOwnCertificate: an operator's chain (leaf, intermediate, private root) uploaded
// to the platform is what StartTLS and HTTPS present, and OpenSSL verifies it against the
// root alone; the pin recomputed with OpenSSL is the root's.
func TestInteropOwnCertificate(t *testing.T) {
	ctx := context.Background()
	st, box := testutil.Store(t), testutil.Box(t)
	ca, _, err := pki.Open(ctx, st, box, nil)
	if err != nil {
		t.Fatal(err)
	}
	certs, err := pki.OpenCerts(ctx, ca, pki.Options{HTTPSHost: "localhost", LDAPHost: "localhost",
		Names: []string{"localhost"}, Roots: x509.NewCertPool()})
	if err != nil {
		t.Fatal(err)
	}
	from := time.Now().Add(-time.Hour)
	root := testutil.MakeCert(t, testutil.CertSpec{CN: "Interop Root", CA: true, From: from}, nil)
	inter := testutil.MakeCert(t, testutil.CertSpec{CN: "Interop Intermediate", CA: true, From: from}, root)
	leaf := testutil.MakeCert(t, testutil.CertSpec{CN: "localhost", Names: []string{"localhost", "127.0.0.1"}, From: from}, inter)
	own, err := pki.ParseOwn(testutil.ChainPEM(leaf, inter, root), testutil.KeyPEM(t, leaf))
	if err != nil {
		t.Fatal(err)
	}
	if _, err := certs.Assess(ctx, own); err != nil {
		t.Fatal(err)
	}
	if err := certs.Import(ctx, own); err != nil {
		t.Fatal(err)
	}
	if !certs.Paused() {
		t.Fatal("issuing not paused")
	}
	dir := t.TempDir()
	rootFile := filepath.Join(dir, "root.pem")
	os.WriteFile(rootFile, testutil.ChainPEM(root), 0o644)

	e := newEnv(t, func(c *Config) { c.TLS = certs.LDAPConfig() })
	r := run(t, nil, "Q\n", "openssl", "s_client", "-connect", "127.0.0.1:"+port(e.addr), "-starttls", "ldap",
		"-servername", "localhost", "-verify_hostname", "localhost", "-verify_return_error", "-CAfile", rootFile, "-showcerts")
	if r.code != 0 || !strings.Contains(r.out, "Verify return code: 0 (ok)") || !strings.Contains(r.out, "Interop Intermediate") {
		t.Errorf("s_client -starttls ldap (exit %d):\n%s", r.code, r.out)
	}
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	hs := &http.Server{Handler: http.NotFoundHandler(), TLSConfig: certs.HTTPSConfig()}
	go hs.ServeTLS(ln, "", "")
	defer hs.Close()
	r = run(t, nil, "Q\n", "openssl", "s_client", "-connect", ln.Addr().String(), "-servername", "localhost",
		"-verify_hostname", "localhost", "-verify_return_error", "-CAfile", rootFile)
	if r.code != 0 || !strings.Contains(r.out, "Verify return code: 0 (ok)") {
		t.Errorf("s_client HTTPS (exit %d):\n%s", r.code, r.out)
	}
	// The built-in CA does not verify it: the phones need the root's pin.
	caFile := filepath.Join(dir, "ca.pem")
	os.WriteFile(caFile, ca.CAPEM(), 0o644)
	r = run(t, nil, "Q\n", "openssl", "s_client", "-connect", ln.Addr().String(), "-servername", "localhost",
		"-verify_return_error", "-CAfile", caFile)
	if r.code == 0 {
		t.Errorf("verified with the built-in CA:\n%s", r.out)
	}
	r = run(t, nil, "", "sh", "-c", "openssl x509 -in "+rootFile+" -pubkey -noout | openssl pkey -pubin -outform der | openssl dgst -sha256 -binary | base64 | tr '+/' '-_' | tr -d '=\\n'")
	if r.code != 0 || r.out != certs.Pin() {
		t.Errorf("pin from openssl %q, platform %q", r.out, certs.Pin())
	}
}
