package pki

import (
	"context"
	"crypto/ed25519"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"encoding/pem"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"siplink/internal/store"
	"siplink/internal/testutil"
)

// certEnv is a built-in CA with a choice of certificates and a settable clock. Every
// certificate of these tests is made at test time.
type certEnv struct {
	t     *testing.T
	st    *store.Store
	clock *testutil.Clock
	certs *Certs
	opt   Options
}

func newCertEnv(t *testing.T, mutate ...func(*Options)) *certEnv {
	t.Helper()
	ctx := context.Background()
	st, box := testutil.Store(t), testutil.Box(t)
	clock := &testutil.Clock{T: time.Date(2026, 11, 16, 10, 0, 0, 0, time.UTC)}
	st.Now = clock.Now
	ca, _, err := Open(ctx, st, box, clock.Now)
	if err != nil {
		t.Fatal(err)
	}
	opt := Options{HTTPSHost: "platform.example.com", LDAPHost: "ldap.example.com", Names: names, Roots: x509.NewCertPool()}
	for _, m := range mutate {
		m(&opt)
	}
	certs, err := OpenCerts(ctx, ca, opt)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := certs.Ensure(ctx); err != nil {
		t.Fatal(err)
	}
	return &certEnv{t: t, st: st, clock: clock, certs: certs, opt: opt}
}

// mk makes a certificate valid from an hour before the test clock.
func (e *certEnv) mk(spec testutil.CertSpec, parent *testutil.Cert) *testutil.Cert {
	if spec.From.IsZero() {
		spec.From = e.clock.T.Add(-time.Hour)
	}
	return testutil.MakeCert(e.t, spec, parent)
}

// chain makes a private root, an intermediate and a leaf for names.
func (e *certEnv) chain(names ...string) (leaf, inter, root *testutil.Cert) {
	root = e.mk(testutil.CertSpec{CN: "Private Root", CA: true, Days: 3650}, nil)
	inter = e.mk(testutil.CertSpec{CN: "Private Intermediate", CA: true, Days: 1825}, root)
	leaf = e.mk(testutil.CertSpec{CN: names[0], Names: names, Days: 90}, inter)
	return leaf, inter, root
}

// assess parses and checks a chain and its key.
func (e *certEnv) assess(key *testutil.Cert, chain ...*testutil.Cert) (*Assessment, error) {
	o, err := ParseOwn(testutil.ChainPEM(chain...), testutil.KeyPEM(e.t, key))
	if err != nil {
		return nil, err
	}
	return e.certs.Assess(context.Background(), o)
}

// use imports a chain that must pass the checks.
func (e *certEnv) use(chain ...*testutil.Cert) *Assessment {
	e.t.Helper()
	a, err := e.assess(chain[0], chain...)
	if err != nil {
		e.t.Fatalf("assess: %v", err)
	}
	if err := e.certs.Import(context.Background(), a.Own); err != nil {
		e.t.Fatal(err)
	}
	return a
}

func both() []string { return []string{"platform.example.com", "ldap.example.com"} }

func wantRefusal(t *testing.T, err error, part string) {
	t.Helper()
	var r *Refusal
	if !errors.As(err, &r) || !strings.Contains(r.Reason, part) {
		t.Errorf("want a refusal with %q, got %v", part, err)
	}
}

func TestOwnParseRefusals(t *testing.T) {
	e := newCertEnv(t)
	leaf, inter, root := e.chain(both()...)
	other := e.mk(testutil.CertSpec{CN: "other", Names: both()}, inter)
	key := testutil.KeyPEM(t, leaf)
	block, _ := pem.Decode(key)
	_, edKey, _ := ed25519.GenerateKey(rand.Reader)
	edDER, _ := x509.MarshalPKCS8PrivateKey(edKey)
	selfLeaf := e.mk(testutil.CertSpec{CN: "self", Names: both()}, nil)
	cases := []struct {
		name       string
		chain, key []byte
		want       string
	}{
		{"no PEM", []byte("hello"), key, "未找到 PEM 证书"},
		{"bad DER", pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: []byte{1, 2, 3}}), key, "第 1 个证书无法解析"},
		{"key in chain", append(testutil.ChainPEM(leaf, inter), key...), key, "证书链文件中包含私钥"},
		{"no key", testutil.ChainPEM(leaf), []byte("nothing"), "私钥文件中未找到"},
		{"encrypted PKCS#8", testutil.ChainPEM(leaf), pem.EncodeToMemory(&pem.Block{Type: "ENCRYPTED PRIVATE KEY", Bytes: block.Bytes}), "私钥已加密"},
		{"encrypted legacy", testutil.ChainPEM(leaf), pem.EncodeToMemory(&pem.Block{Type: "EC PRIVATE KEY",
			Headers: map[string]string{"Proc-Type": "4,ENCRYPTED", "DEK-Info": "AES-128-CBC,00"}, Bytes: block.Bytes}), "私钥已加密"},
		{"Ed25519", testutil.ChainPEM(leaf), pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: edDER}), "仅支持 RSA 和 ECDSA"},
		{"other key", testutil.ChainPEM(leaf, inter), testutil.KeyPEM(t, other), "不匹配"},
		{"root first", testutil.ChainPEM(root, inter, leaf), testutil.KeyPEM(t, root), "自签名证书，仅可置于链末尾"},
		{"wrong order", testutil.ChainPEM(leaf, root, inter), key, "第 1 个证书不是由第 2 个证书签发的"},
		{"self-signed in the middle", testutil.ChainPEM(selfLeaf, root), testutil.KeyPEM(t, selfLeaf), "仅可置于链末尾"},
	}
	for _, c := range cases {
		_, err := ParseOwn(c.chain, c.key)
		if wantRefusal(t, err, c.want); t.Failed() {
			t.Fatalf("case %s", c.name)
		}
	}
	if _, err := ParseOwn(testutil.ChainPEM(leaf, inter, root), key); err != nil {
		t.Errorf("a good chain: %v", err)
	}
}

func TestOwnAssessRefusals(t *testing.T) {
	e := newCertEnv(t)
	_, inter, root := e.chain(both()...)
	day := 24 * time.Hour
	oldRoot := e.mk(testutil.CertSpec{CN: "Old Root", CA: true, From: e.clock.T.Add(-400 * day), Days: 365}, nil)
	cases := []struct {
		name string
		leaf *testutil.Cert
		rest []*testutil.Cert
		want string
	}{
		{"not yet valid", e.mk(testutil.CertSpec{CN: "a", Names: both(), From: e.clock.T.Add(day)}, inter), []*testutil.Cert{inter, root}, "服务器证书尚未生效"},
		{"expired", e.mk(testutil.CertSpec{CN: "a", Names: both(), From: e.clock.T.Add(-100 * day), Days: 90}, inter), []*testutil.Cert{inter, root}, "服务器证书已过期"},
		{"expired root", e.mk(testutil.CertSpec{CN: "a", Names: both(), From: e.clock.T.Add(-100 * day), Days: 300}, oldRoot), []*testutil.Cert{oldRoot}, "第 2 个证书已过期"},
		{"client only", e.mk(testutil.CertSpec{CN: "a", Names: both(), EKU: []x509.ExtKeyUsage{x509.ExtKeyUsageClientAuth}}, inter), []*testutil.Cert{inter, root}, "serverAuth"},
		{"no SAN", e.mk(testutil.CertSpec{CN: "platform.example.com"}, inter), []*testutil.Cert{inter, root}, "SAN"},
		{"other host", e.mk(testutil.CertSpec{CN: "a", Names: []string{"ldap.example.com"}}, inter), []*testutil.Cert{inter, root}, "不包含公开地址的主机名 platform.example.com"},
		{"no root", e.mk(testutil.CertSpec{CN: "a", Names: both()}, inter), []*testutil.Cert{inter}, "且不含根证书"},
	}
	for _, c := range cases {
		_, err := e.assess(c.leaf, append([]*testutil.Cert{c.leaf}, c.rest...)...)
		if wantRefusal(t, err, c.want); t.Failed() {
			t.Fatalf("case %s", c.name)
		}
	}
	// No extended key usage at all is what the app accepts too.
	plain := e.mk(testutil.CertSpec{CN: "a", Names: both(), NoEKU: true}, inter)
	if _, err := e.assess(plain, plain, inter, root); err != nil {
		t.Errorf("leaf without EKU: %v", err)
	}
	// With HTTPS behind a proxy, a certificate that does not name the LDAP host serves nothing.
	off := newCertEnv(t, func(o *Options) { o.HTTPSOff = true })
	_, inter2, root2 := off.chain(both()...)
	https := off.mk(testutil.CertSpec{CN: "a", Names: []string{"platform.example.com"}}, inter2)
	_, err := off.assess(https, https, inter2, root2)
	wantRefusal(t, err, "无服务使用此证书：HTTPS 由反向代理提供")
}

func served(t *testing.T, cfg *tls.Config) *x509.Certificate {
	t.Helper()
	c, err := cfg.GetCertificate(&tls.ClientHelloInfo{})
	if err != nil {
		t.Fatal(err)
	}
	return c.Leaf
}

func TestPrecedenceAndPause(t *testing.T) {
	ctx := context.Background()
	e := newCertEnv(t)
	c := e.certs
	builtin := c.CA().Server()
	if h, l := c.Sources(); h != SourceBuiltin || l != SourceBuiltin || c.Paused() || served(t, c.HTTPSConfig()) != builtin {
		t.Fatalf("start: %s %s paused=%v", h, l, c.Paused())
	}

	// A certificate for the public host only: HTTPS uses it, LDAP keeps the built-in one.
	leaf, inter, root := e.chain("platform.example.com")
	a := e.use(leaf, inter, root)
	if !a.HTTPS || a.LDAP || len(a.Warnings) != 1 || !strings.Contains(a.Warnings[0], "LDAP 继续使用内置 CA") {
		t.Fatalf("assessment: %+v", a)
	}
	if h, l := c.Sources(); h != SourceOwn || l != SourceBuiltin || c.Paused() {
		t.Fatalf("https only: %s %s", h, l)
	}
	if !served(t, c.HTTPSConfig()).Equal(leaf.Cert) {
		t.Error("HTTPS does not present the uploaded certificate")
	}
	if !served(t, c.LDAPConfig()).Equal(builtin) {
		t.Error("LDAP left the built-in certificate")
	}

	// One for both names: issuing pauses; the check does nothing even when the built-in
	// certificate would be renewed; reissuing is refused.
	leaf, inter, root = e.chain(both()...)
	e.use(leaf, inter, root)
	if h, l := c.Sources(); h != SourceOwn || l != SourceOwn || !c.Paused() || c.Mode() != "使用自有证书（自动签发已暂停）" {
		t.Fatalf("both: %s %s %s", h, l, c.Mode())
	}
	if !served(t, c.LDAPConfig()).Equal(leaf.Cert) {
		t.Error("LDAP does not present the uploaded certificate")
	}
	e.clock.Advance(380 * 24 * time.Hour)
	if reason, err := c.Ensure(ctx); reason != "" || err != nil || !c.CA().Server().Equal(builtin) {
		t.Fatalf("paused check issued: %q %v", reason, err)
	}
	if err := c.Issue(ctx); !errors.Is(err, ErrPaused) {
		t.Errorf("issue while paused: %v", err)
	}
	if _, err := e.st.TLSMaterial(ctx, nameCA); err != nil {
		t.Error("the CA must stay stored")
	}

	// Back to the built-in CA: the check runs at once and renews.
	reason, err := c.Remove(ctx)
	if err != nil || reason != "expires soon" || c.CA().Server().Equal(builtin) || c.Paused() {
		t.Fatalf("remove: %q %v", reason, err)
	}
	if h, l := c.Sources(); h != SourceBuiltin || l != SourceBuiltin {
		t.Errorf("after remove: %s %s", h, l)
	}
}

func writePair(t *testing.T, dir, name string, chain ...*testutil.Cert) (string, string) {
	certFile, keyFile := filepath.Join(dir, name+".pem"), filepath.Join(dir, name+".key")
	if err := os.WriteFile(certFile, testutil.ChainPEM(chain...), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(keyFile, testutil.KeyPEM(t, chain[0]), 0o600); err != nil {
		t.Fatal(err)
	}
	return certFile, keyFile
}

func TestFilesComeFirst(t *testing.T) {
	dir := t.TempDir()
	var fileLeaf *testutil.Cert
	e := newCertEnv(t, func(o *Options) {
		root := testutil.MakeCert(t, testutil.CertSpec{CN: "File Root", CA: true, From: time.Date(2026, 11, 1, 0, 0, 0, 0, time.UTC)}, nil)
		fileLeaf = testutil.MakeCert(t, testutil.CertSpec{CN: "files", Names: []string{"platform.example.com"},
			From: time.Date(2026, 11, 1, 0, 0, 0, 0, time.UTC), Days: 40}, root)
		o.HTTPSCertFile, o.HTTPSKeyFile = writePair(t, dir, "https", fileLeaf, root)
	})
	c := e.certs
	if h, l := c.Sources(); h != SourceFiles || l != SourceBuiltin || c.Paused() {
		t.Fatalf("files: %s %s", h, l)
	}
	if !served(t, c.HTTPSConfig()).Equal(fileLeaf.Cert) {
		t.Error("HTTPS does not present the file")
	}
	// The files' private root is pinned (a private chain from files works like an upload).
	files, _ := c.Files()
	if c.Pin() != Pin(files.Anchor) {
		t.Error("pin of the files' root")
	}
	// An upload naming both hosts: files keep HTTPS, the upload takes LDAP, issuing pauses.
	leaf, inter, root := e.chain(both()...)
	a := e.use(leaf, inter, root)
	if a.HTTPS || !a.LDAP {
		t.Errorf("assessment with files: %+v", a)
	}
	if h, l := c.Sources(); h != SourceFiles || l != SourceOwn || !c.Paused() || !strings.Contains(c.Mode(), "并用") {
		t.Errorf("files and upload: %s %s %s", h, l, c.Mode())
	}
	// The files' certificate is reminded too; the upload is far from its expiry.
	e.clock.Advance(20 * 24 * time.Hour)
	n := c.Notices(30)
	if len(n) != 1 || n[0].Level != LevelWarn || !strings.Contains(n[0].Text, "证书文件将在") {
		t.Errorf("notices: %+v", n)
	}
}

func TestPinAndDocumentCAs(t *testing.T) {
	pemOf := func(c *x509.Certificate) string {
		return string(pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: c.Raw}))
	}
	// Built-in.
	e := newCertEnv(t)
	ca := e.certs.CA()
	if e.certs.Pin() != ca.Pin() || fmt.Sprint(e.certs.DocumentCAs()) != fmt.Sprint([]string{string(ca.CAPEM())}) {
		t.Error("built-in pin or CAs")
	}
	// A private root at the end of the chain: pinned and handed out instead.
	leaf, inter, root := e.chain(both()...)
	e.use(leaf, inter, root)
	if e.certs.Pin() != Pin(root.Cert) || fmt.Sprint(e.certs.DocumentCAs()) != fmt.Sprint([]string{pemOf(root.Cert)}) {
		t.Error("private root pin or CAs")
	}
	// The HTTPS listener alone: its root first, then the built-in CA of LDAP.
	https := e.mk(testutil.CertSpec{CN: "h", Names: []string{"platform.example.com"}}, inter)
	e.use(https, inter, root)
	if got := e.certs.DocumentCAs(); len(got) != 2 || got[0] != pemOf(root.Cert) || got[1] != string(ca.CAPEM()) {
		t.Errorf("mixed CAs: %d", len(got))
	}

	// A public-style chain: its root is in the system store and not in the chain.
	p := newCertEnv(t)
	pubLeaf, pubInter, pubRoot := p.chain(both()...)
	p.opt.Roots.AddCert(pubRoot.Cert)
	a := p.use(pubLeaf, pubInter)
	if !a.Own.Public || p.certs.Pin() != "" || len(p.certs.DocumentCAs()) != 0 {
		t.Errorf("public chain: public=%v pin=%q CAs=%d", a.Own.Public, p.certs.Pin(), len(p.certs.DocumentCAs()))
	}
	// The same with the public root appended: still no pin.
	p.use(pubLeaf, pubInter, pubRoot)
	if p.certs.Pin() != "" {
		t.Error("public root pinned")
	}

	// A self-signed server certificate: the app's check pins it directly (CertificatePin.check
	// takes any certificate of the chain with the pin, the leaf included). Accepted with a warning.
	s := newCertEnv(t)
	self := s.mk(testutil.CertSpec{CN: "self", Names: both()}, nil)
	a = s.use(self)
	if s.certs.Pin() != Pin(self.Cert) || len(a.Warnings) != 1 || !strings.Contains(a.Warnings[0], "自签名服务器证书") {
		t.Errorf("self-signed leaf: pin=%q warnings=%v", s.certs.Pin(), a.Warnings)
	}

	// HTTPS behind a proxy: no pin; LDAP's CA still goes to the documents.
	off := newCertEnv(t, func(o *Options) { o.HTTPSOff = true })
	if off.certs.Pin() != "" || len(off.certs.DocumentCAs()) != 1 || off.certs.HTTPSConfig() != nil {
		t.Error("TLS off")
	}
}

func TestEmptyAKIWarning(t *testing.T) {
	e := newCertEnv(t)
	root := e.mk(testutil.CertSpec{CN: "Tool Root", CA: true, EmptyAKI: true}, nil)
	leaf := e.mk(testutil.CertSpec{CN: "a", Names: both()}, root)
	a, err := e.assess(leaf, leaf, root)
	if err != nil {
		t.Fatal(err)
	}
	if fmt.Sprint(a.Own.EmptyAKI) != "[2]" || len(a.Warnings) != 1 || !strings.Contains(a.Warnings[0], "mbedTLS 3.6.0") {
		t.Errorf("empty AKI: %v %v", a.Own.EmptyAKI, a.Warnings)
	}
}

func TestReminder(t *testing.T) {
	ctx := context.Background()
	e := newCertEnv(t)
	c := e.certs
	if c.ReminderDays(ctx) != DefaultReminderDays {
		t.Error("default reminder days")
	}
	for _, bad := range []int{0, 366} {
		if err := c.SetReminderDays(ctx, bad); err == nil {
			t.Errorf("%d days accepted", bad)
		}
	}
	// The built-in certificate has no notice, even close to its renewal.
	e.clock.Advance(390 * 24 * time.Hour)
	if n := c.Notices(30); len(n) != 0 {
		t.Errorf("built-in notices: %+v", n)
	}
	e.clock.Advance(-390 * 24 * time.Hour)

	leaf, inter, root := e.chain(both()...) // valid for 90 days from an hour ago
	e.use(leaf, inter, root)
	if n := c.Notices(30); len(n) != 0 {
		t.Errorf("early notice: %+v", n)
	}
	e.clock.Advance(61 * 24 * time.Hour)
	n := c.Notices(30)
	if len(n) != 1 || n[0].Level != LevelWarn || n[0].DaysLeft != 28 || !strings.Contains(n[0].Text, "自有证书将在 28 天后") {
		t.Fatalf("warning: %+v", n)
	}
	if err := c.SetReminderDays(ctx, 20); err != nil || c.ReminderDays(ctx) != 20 || len(c.Notices(20)) != 0 {
		t.Errorf("20 days: %v", err)
	}
	e.clock.Advance(30 * 24 * time.Hour)
	n = c.Notices(20)
	if len(n) != 1 || n[0].Level != LevelError || !strings.Contains(n[0].Text, "证书已过期，设备将无法同步") {
		t.Fatalf("expired: %+v", n)
	}
}

// bind stores n clients with status (active = bound, pending = waiting for the scan).
func (e *certEnv) bind(n int, status string) {
	e.t.Helper()
	ctx := context.Background()
	r := &store.Region{Name: "华东"}
	cfg := &store.ConfigTemplate{Name: "c", SettingsJSON: "{}", RefreshMinutes: 60}
	if err := e.st.CreateRegion(ctx, r); err != nil {
		e.t.Fatal(err)
	}
	if err := e.st.CreateConfigTemplate(ctx, cfg); err != nil {
		e.t.Fatal(err)
	}
	tpl := &store.ClientTemplate{Name: "t", ConfigTemplateID: cfg.ID}
	if err := e.st.CreateClientTemplate(ctx, tpl); err != nil {
		e.t.Fatal(err)
	}
	for i := range n {
		if _, err := e.st.DB.ExecContext(ctx, `INSERT INTO clients(region_id, client_template_id, name, public_id, status, created_at, updated_at)
			VALUES(?, ?, ?, ?, ?, '', '')`, r.ID, tpl.ID, fmt.Sprint(status, i), fmt.Sprint(status, i), status); err != nil {
			e.t.Fatal(err)
		}
	}
}

func TestRescanNeedsConfirmation(t *testing.T) {
	ctx := context.Background()
	e := newCertEnv(t)
	leaf, inter, root := e.chain(both()...)
	// No bound phone: nothing to confirm.
	if a, err := e.assess(leaf, leaf, inter, root); err != nil || a.Rescan != 0 || len(a.Warnings) != 0 {
		t.Fatalf("no clients: %+v %v", a, err)
	}
	e.bind(3, store.StatusActive)
	// Phones that pinned the built-in CA cannot follow to a private root.
	a, err := e.assess(leaf, leaf, inter, root)
	if err != nil || a.Rescan != 3 || len(a.Warnings) != 1 || !strings.Contains(a.Warnings[0], "已绑定的 3 台设备") {
		t.Fatalf("private root: %+v %v", a, err)
	}
	e.use(leaf, inter, root)
	// A renewal from the same private root keeps them.
	renewed := e.mk(testutil.CertSpec{CN: "renewed", Names: both(), Days: 200}, inter)
	if a, err := e.assess(renewed, renewed, inter, root); err != nil || a.Rescan != 0 {
		t.Errorf("renewal: %+v %v", a, err)
	}
	// Returning to the built-in CA locks them out as well.
	if w, n, err := e.certs.RemovalWarnings(ctx); err != nil || n != 3 || len(w) != 1 || !strings.Contains(w[0], "已绑定的 3 台设备") {
		t.Errorf("removal impact: %d %v %v", n, w, err)
	}
	// A public chain is trusted by every phone.
	pubRoot := e.mk(testutil.CertSpec{CN: "Public Root", CA: true}, nil)
	e.opt.Roots.AddCert(pubRoot.Cert)
	pub := e.mk(testutil.CertSpec{CN: "pub", Names: both()}, pubRoot)
	if a, err := e.assess(pub, pub); err != nil || a.Rescan != 0 {
		t.Errorf("public: %+v %v", a, err)
	}
	e.use(pub)
	// From a public chain back to the built-in CA: phones without a pinned CA cannot follow.
	if _, n, _ := e.certs.RemovalWarnings(ctx); n != 3 {
		t.Errorf("removal from public: %d", n)
	}
}

func TestStoredAndReloaded(t *testing.T) {
	ctx := context.Background()
	e := newCertEnv(t)
	leaf, inter, root := e.chain(both()...)
	e.use(leaf, inter, root)
	m, err := e.st.TLSMaterial(ctx, nameOwn)
	if err != nil || strings.Contains(m.KeyEnc, "PRIVATE KEY") || strings.Contains(m.CertPEM, "PRIVATE KEY") {
		t.Fatalf("stored: %v", err)
	}
	// A second process (the CLI) sees it; a removal there reaches the first at its reload.
	other, err := OpenCerts(ctx, e.certs.CA(), e.opt)
	if err != nil || other.Uploaded() == nil || !other.Uploaded().Leaf().Equal(leaf.Cert) || !other.Paused() {
		t.Fatalf("reopen: %v", err)
	}
	if _, err := other.Remove(ctx); err != nil {
		t.Fatal(err)
	}
	if err := e.certs.Reload(ctx); err != nil || e.certs.Uploaded() != nil || e.certs.Paused() {
		t.Errorf("reload: %v", err)
	}
}

func TestReprintWarning(t *testing.T) {
	ctx := context.Background()
	e := newCertEnv(t)
	e.bind(2, store.StatusPending)
	leaf, inter, root := e.chain(both()...)
	a, err := e.assess(leaf, leaf, inter, root)
	if err != nil || a.Rescan != 0 || len(a.Warnings) != 1 || !strings.Contains(a.Warnings[0], "2 个待上线客户端") {
		t.Fatalf("new pin: %+v %v", a, err)
	}
	e.use(leaf, inter, root)
	// A renewal under the same root keeps the pin.
	renewed := e.mk(testutil.CertSpec{CN: "renewed", Names: both(), Days: 200}, inter)
	if a, err := e.assess(renewed, renewed, inter, root); err != nil || len(a.Warnings) != 0 {
		t.Errorf("same pin: %+v %v", a, err)
	}
	// Back to the built-in CA changes it again.
	if w, n, err := e.certs.RemovalWarnings(ctx); err != nil || n != 0 || len(w) != 1 || !strings.Contains(w[0], "二维码中的 pin 将改变") {
		t.Errorf("removal: %v %d %v", w, n, err)
	}
}
