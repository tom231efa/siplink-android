package web

import (
	"bytes"
	"context"
	"crypto/x509"
	"io"
	"log/slog"
	"net/http"
	"net/http/cookiejar"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"
	"time"

	"siplink/internal/auth"
	"siplink/internal/config"
	"siplink/internal/pki"
	"siplink/internal/store"
	"siplink/internal/testutil"
)

type harness struct {
	t     *testing.T
	srv   *Server
	ts    *httptest.Server
	st    *store.Store
	clock *testutil.Clock
	logs  *bytes.Buffer
	roots *x509.CertPool // the "system" root store of the certificates
}

func newHarness(t *testing.T, mutate ...func(*config.Config)) *harness {
	t.Helper()
	testutil.FastHashes()
	cfg, err := config.LoadFrom(func(k string) (string, bool) {
		m := map[string]string{"SIPLINK_PUBLIC_URL": "https://platform.example.com", "SIPLINK_DATA_DIR": t.TempDir()}
		v, ok := m[k]
		return v, ok
	})
	if err != nil {
		t.Fatal(err)
	}
	for _, f := range mutate {
		f(cfg)
	}
	st := testutil.Store(t)
	clock := &testutil.Clock{T: time.Now().UTC().Truncate(time.Second)}
	st.Now = clock.Now
	logs := &bytes.Buffer{}
	srv, err := New(cfg, st, slog.New(slog.NewJSONHandler(logs, nil)), clock.Now)
	if err != nil {
		t.Fatal(err)
	}
	box := testutil.Box(t)
	ca, _, err := pki.Open(context.Background(), st, box, clock.Now)
	if err != nil {
		t.Fatal(err)
	}
	opt := pki.OptionsFrom(cfg)
	opt.Roots = x509.NewCertPool()
	certs, err := pki.OpenCerts(context.Background(), ca, opt)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := certs.Ensure(context.Background()); err != nil {
		t.Fatal(err)
	}
	srv.Ext = Extensions{Box: box, Certs: certs}
	copy(srv.Ext.LDAPKey[:], "ldap-key-ldap-key-ldap-key-ldap!")
	copy(srv.Ext.CodeKey[:], "code-key-code-key-code-key-code!")
	h := &harness{t: t, srv: srv, st: st, clock: clock, logs: logs, roots: opt.Roots}
	h.ts = httptest.NewTLSServer(srv.Handler())
	t.Cleanup(h.ts.Close)
	return h
}

// admin creates an administrator with a known password.
func (h *harness) admin(name, role string, regions ...int64) *store.Admin {
	h.t.Helper()
	hash, _ := auth.HashPassword("password-" + name)
	a := &store.Admin{Username: name, PasswordHash: hash, Role: role, RegionIDs: regions}
	if err := h.st.CreateAdmin(context.Background(), a); err != nil {
		h.t.Fatal(err)
	}
	return a
}

func (h *harness) region(name string) int64 {
	h.t.Helper()
	r := &store.Region{Name: name}
	if err := h.st.CreateRegion(context.Background(), r); err != nil {
		h.t.Fatal(err)
	}
	return r.ID
}

type client struct {
	h *harness
	c *http.Client
}

func (h *harness) client() *client {
	jar, _ := cookiejar.New(nil)
	base := *h.ts.Client() // copy: ts.Client() returns one shared client
	c := &base
	c.Jar = jar
	c.CheckRedirect = func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }
	return &client{h: h, c: c}
}

type resp struct {
	code     int
	body     string
	location string
	header   http.Header
}

func (c *client) do(req *http.Request) resp {
	c.h.t.Helper()
	r, err := c.c.Do(req)
	if err != nil {
		c.h.t.Fatal(err)
	}
	defer r.Body.Close()
	b, _ := io.ReadAll(r.Body)
	return resp{code: r.StatusCode, body: string(b), location: r.Header.Get("Location"), header: r.Header}
}

func (c *client) get(path string) resp {
	req, _ := http.NewRequest("GET", c.h.ts.URL+path, nil)
	return c.do(req)
}

func (c *client) post(path string, form url.Values, headers ...string) resp {
	req, _ := http.NewRequest("POST", c.h.ts.URL+path, strings.NewReader(form.Encode()))
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	for i := 0; i+1 < len(headers); i += 2 {
		req.Header.Set(headers[i], headers[i+1])
	}
	return c.do(req)
}

func (c *client) postBody(path, contentType string, body []byte) resp {
	req, _ := http.NewRequest("POST", c.h.ts.URL+path, bytes.NewReader(body))
	req.Header.Set("Content-Type", contentType)
	return c.do(req)
}

// login signs in with the password made by harness.admin.
func (c *client) login(name string) {
	c.h.t.Helper()
	r := c.post("/login", url.Values{"username": {name}, "password": {"password-" + name}})
	if r.code != http.StatusSeeOther {
		c.h.t.Fatalf("login %s: %d %s", name, r.code, r.body)
	}
}

func (h *harness) loggedIn(name, role string, regions ...int64) (*client, *store.Admin) {
	a := h.admin(name, role, regions...)
	c := h.client()
	c.login(name)
	return c, a
}
