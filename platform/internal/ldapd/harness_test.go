package ldapd

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"fmt"
	"io"
	"log/slog"
	"net"
	"testing"
	"time"

	"github.com/go-ldap/ldap/v3"

	"siplink/internal/pki"
	"siplink/internal/secret"
	"siplink/internal/store"
	"siplink/internal/testutil"
)

const testSuffix = "dc=siplink"

type env struct {
	t         *testing.T
	st        *store.Store
	srv       *Server
	ca        *pki.Authority
	addr      string // LDAP
	ldapsAddr string
	pool      *x509.CertPool
	books     map[string]*store.Book
	passwords map[string]string // service account -> password
}

type envOpt func(*Config)

func newEnv(t *testing.T, opts ...envOpt) *env {
	t.Helper()
	ctx := context.Background()
	st, box := testutil.Store(t), testutil.Box(t)
	ca, _, err := pki.Open(ctx, st, box, nil)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := ca.Ensure(ctx, []string{"ldap.example.com", "localhost", "127.0.0.1", "::1"}); err != nil {
		t.Fatal(err)
	}
	cfg := Config{Suffix: testSuffix, RequireTLS: true, TLS: ca.ServerConfig(),
		FailDelay: time.Millisecond, SlowDelay: 5 * time.Millisecond}
	for _, o := range opts {
		o(&cfg)
	}
	e := &env{t: t, st: st, ca: ca, books: map[string]*store.Book{}, passwords: map[string]string{}}
	e.srv = New(cfg, st, slog.New(slog.NewTextHandler(io.Discard, nil)))
	e.pool = x509.NewCertPool()
	e.pool.AddCert(ca.CA())
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	lns, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	e.addr, e.ldapsAddr = ln.Addr().String(), lns.Addr().String()
	go e.srv.Serve(ln, false)
	go e.srv.Serve(tls.NewListener(lns, cfg.TLS), true)
	t.Cleanup(func() { ln.Close(); lns.Close(); e.srv.Close() })
	e.seed()
	return e
}

// seed creates three books and two service accounts.
func (e *env) seed() {
	ctx := context.Background()
	people := []*store.Entry{
		{Name: "张伟", Surname: "张", GivenName: "伟", Phones: []string{"1001"}, Mobiles: []string{"138 0000 1001"},
			Organization: "示例公司", Department: "研发部", Email: "zhang@example.com"},
		{Name: "李娜", Phones: []string{"1002"}, Mobiles: []string{"139-0000-1002"}},
		{Name: "王芳", Phones: []string{"1003"}},
		{Name: "Alice Smith", Phones: []string{"+1 555 0100"}},
		{Name: "a*b (test)", Phones: []string{"1005"}},
	}
	for _, spec := range []struct {
		slug string
		anon bool
		n    []*store.Entry
	}{{"staff", false, people}, {"other", false, []*store.Entry{{Name: "赵六", Phones: []string{"2001"}}}},
		{"public", true, []*store.Entry{{Name: "前台", Phones: []string{"8000"}}}}} {
		b := &store.Book{Slug: spec.slug, Name: spec.slug, AllowAnonymous: spec.anon}
		if err := e.st.CreateBook(ctx, b, 500); err != nil {
			e.t.Fatal(err)
		}
		if err := e.st.AddEntries(ctx, b.ID, spec.n, false, 10000); err != nil {
			e.t.Fatal(err)
		}
		e.books[spec.slug], _ = e.st.BookByID(ctx, b.ID)
	}
	e.account("phone1", false, "staff")
	e.account("blocked", true, "staff")
}

func (e *env) account(name string, disabled bool, slugs ...string) {
	pw := secret.RandomText(32)
	a := &store.LDAPAccount{Name: name, PasswordHash: secret.HashHex(pw), Disabled: disabled}
	for _, s := range slugs {
		a.BookIDs = append(a.BookIDs, e.books[s].ID)
	}
	if err := e.st.CreateLDAPAccount(context.Background(), a); err != nil {
		e.t.Fatal(err)
	}
	e.passwords[name] = pw
}

func accountDN(name string) string { return "cn=" + name + ",ou=accounts," + testSuffix }

// dial connects without TLS.
func (e *env) dial() *ldap.Conn {
	e.t.Helper()
	c, err := ldap.DialURL("ldap://" + e.addr)
	if err != nil {
		e.t.Fatal(err)
	}
	e.t.Cleanup(func() { c.Close() })
	return c
}

func (e *env) tlsConfig() *tls.Config {
	return &tls.Config{RootCAs: e.pool, ServerName: "localhost", MinVersion: tls.VersionTLS12}
}

// dialTLS connects and runs StartTLS, verifying the certificate against the CA.
func (e *env) dialTLS() *ldap.Conn {
	e.t.Helper()
	c := e.dial()
	if err := c.StartTLS(e.tlsConfig()); err != nil {
		e.t.Fatal(err)
	}
	return c
}

// login connects with StartTLS and binds as a service account.
func (e *env) login(name string) *ldap.Conn {
	e.t.Helper()
	c := e.dialTLS()
	if err := c.Bind(accountDN(name), e.passwords[name]); err != nil {
		e.t.Fatal(err)
	}
	return c
}

func search(c *ldap.Conn, base string, scope, size int, filter string, attrs ...string) (*ldap.SearchResult, error) {
	return c.Search(ldap.NewSearchRequest(base, scope, ldap.NeverDerefAliases, size, 5, false, filter, attrs, nil))
}

func code(err error) uint16 {
	if err == nil {
		return 0
	}
	if le, ok := err.(*ldap.Error); ok {
		return le.ResultCode
	}
	return 9999
}

func names(r *ldap.SearchResult) []string {
	var out []string
	if r == nil {
		return nil
	}
	for _, e := range r.Entries {
		if cn := e.GetAttributeValue("cn"); cn != "" {
			out = append(out, cn)
		} else {
			out = append(out, e.DN)
		}
	}
	return out
}

func bookBase(slug string) string { return fmt.Sprintf("ou=%s,ou=books,%s", slug, testSuffix) }
