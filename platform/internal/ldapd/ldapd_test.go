package ldapd

import (
	"crypto/tls"
	"net"
	"slices"
	"strconv"
	"strings"
	"sync/atomic"
	"testing"

	"github.com/go-ldap/ldap/v3"

	"siplink/internal/store"
)

// The filters the Linphone SDK builds from the app's defaults (design §4.1).
const (
	sdkName   = "(|(cn=*%s*)(sn=*%s*))"
	sdkNumber = "(|(telephoneNumber=*%s*)(mobile=*%s*))"
)

func sdk(f, text string) string { return strings.ReplaceAll(f, "%s", text) }

func TestStartTLSAndBinds(t *testing.T) {
	e := newEnv(t)
	c := e.dial()
	// Before TLS: an anonymous bind works, a bind with a DN answers 13 even with the
	// right password, and searches other than the root DSE answer 13.
	if err := c.UnauthenticatedBind(""); err != nil {
		t.Errorf("anonymous bind before TLS: %v", err)
	}
	if err := c.Bind(accountDN("phone1"), e.passwords["phone1"]); code(err) != ldap.LDAPResultConfidentialityRequired {
		t.Errorf("bind before TLS: %v", err)
	}
	if _, err := search(c, bookBase("public"), ldap.ScopeWholeSubtree, 0, "(objectClass=*)"); code(err) != ldap.LDAPResultConfidentialityRequired {
		t.Errorf("search before TLS: %v", err)
	}
	r, err := search(c, "", ldap.ScopeBaseObject, 0, "(objectClass=*)")
	if err != nil || len(r.Entries) != 1 || r.Entries[0].GetAttributeValue("supportedExtension") != OIDStartTLS ||
		r.Entries[0].GetAttributeValue("namingContexts") != testSuffix || r.Entries[0].GetAttributeValue("supportedLDAPVersion") != "3" {
		t.Fatalf("root DSE before TLS: %v %+v", err, r)
	}
	// The same bind after StartTLS succeeds; the certificate was checked against the
	// platform's CA and the server name by the client configuration.
	if err := c.StartTLS(e.tlsConfig()); err != nil {
		t.Fatal(err)
	}
	if err := c.StartTLS(e.tlsConfig()); err == nil {
		t.Error("second StartTLS accepted")
	}
	if err := c.Bind(accountDN("phone1"), e.passwords["phone1"]); err != nil {
		t.Errorf("bind after TLS: %v", err)
	}
	for _, tc := range []struct {
		dn, pw string
		want   uint16
	}{
		{accountDN("phone1"), "wrong", ldap.LDAPResultInvalidCredentials},
		{accountDN("blocked"), e.passwords["blocked"], ldap.LDAPResultInvalidCredentials},
		{accountDN("nobody"), "x", ldap.LDAPResultInvalidCredentials},
		{"uid=abcdefghij,ou=clients," + testSuffix, "x", ldap.LDAPResultInvalidCredentials},
		{"cn=phone1,ou=accounts,dc=other", e.passwords["phone1"], ldap.LDAPResultInvalidCredentials},
		{accountDN("phone1"), "", ldap.LDAPResultUnwillingToPerform},
	} {
		var err error
		if tc.pw == "" {
			err = c.UnauthenticatedBind(tc.dn)
		} else {
			err = c.Bind(tc.dn, tc.pw)
		}
		if code(err) != tc.want {
			t.Errorf("bind %s: %v, want %d", tc.dn, err, tc.want)
		}
	}
	// A client verifying against another CA refuses the server.
	c2 := e.dial()
	if err := c2.StartTLS(&tls.Config{ServerName: "localhost"}); err == nil {
		t.Error("StartTLS verified without the platform CA")
	}
	c3 := e.dial()
	cfg := e.tlsConfig()
	cfg.ServerName = "wrong.example.com"
	if err := c3.StartTLS(cfg); err == nil {
		t.Error("StartTLS accepted a wrong server name")
	}
}

func TestRequireTLSOff(t *testing.T) {
	e := newEnv(t, func(c *Config) { c.RequireTLS = false })
	c := e.dial()
	if err := c.Bind(accountDN("phone1"), e.passwords["phone1"]); err != nil {
		t.Fatalf("bind without TLS when not required: %v", err)
	}
	r, err := search(c, bookBase("staff"), ldap.ScopeWholeSubtree, 0, sdk(sdkName, "张"))
	if err != nil || len(r.Entries) != 1 {
		t.Errorf("search without TLS: %v %v", err, names(r))
	}
}

func TestLDAPS(t *testing.T) {
	e := newEnv(t)
	c, err := ldap.DialURL("ldaps://"+e.ldapsAddr, ldap.DialWithTLSConfig(e.tlsConfig()))
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	if err := c.Bind(accountDN("phone1"), e.passwords["phone1"]); err != nil {
		t.Fatalf("bind over LDAPS: %v", err)
	}
	if err := c.StartTLS(e.tlsConfig()); err == nil {
		t.Error("StartTLS inside LDAPS accepted")
	}
	r, err := search(c, bookBase("staff"), ldap.ScopeWholeSubtree, 0, "(cn=王芳)")
	if err != nil || len(r.Entries) != 1 {
		t.Errorf("LDAPS search: %v %v", err, names(r))
	}
}

func TestSearchAccessAndScopes(t *testing.T) {
	e := newEnv(t)
	c := e.login("phone1")
	staff := bookBase("staff")

	r, err := search(c, staff, ldap.ScopeWholeSubtree, 51, sdk(sdkName, "张"))
	if err != nil || !slices.Equal(names(r), []string{"张伟"}) {
		t.Fatalf("name search: %v %v", err, names(r))
	}
	got := r.Entries[0]
	if got.DN != "uid="+itoa(e, "张伟")+","+staff || got.GetAttributeValue("sn") != "张" ||
		!slices.Equal(got.GetAttributeValues("objectClass"), []string{"top", "person", "organizationalPerson", "inetOrgPerson"}) ||
		got.GetAttributeValue("mobile") != "138 0000 1001" || got.GetAttributeValue("o") != "示例公司" {
		t.Errorf("entry: %s %v", got.DN, got.Attributes)
	}
	// Phone numbers match with spaces and dashes removed.
	for _, q := range []string{"13800001001", "138-0000-1001", "0000 1001"} {
		r, err = search(c, staff, ldap.ScopeWholeSubtree, 51, sdk(sdkNumber, ldap.EscapeFilter(q)))
		if err != nil || !slices.Equal(names(r), []string{"张伟"}) {
			t.Errorf("number %q: %v %v", q, err, names(r))
		}
	}
	// Name lookup by the last 8 digits of an incoming number (NameLookup.queries).
	r, _ = search(c, staff, ldap.ScopeWholeSubtree, 2, sdk(sdkNumber, "00001002"))
	if !slices.Equal(names(r), []string{"李娜"}) {
		t.Errorf("last 8 digits: %v", names(r))
	}
	// Escaped specials as the SDK sends them: \2a is a literal '*', \28 '('.
	r, _ = search(c, staff, ldap.ScopeWholeSubtree, 51, `(|(cn=*a\2ab*)(sn=*a\2ab*))`)
	if !slices.Equal(names(r), []string{"a*b (test)"}) {
		t.Errorf("escaped star: %v", names(r))
	}
	r, _ = search(c, staff, ldap.ScopeWholeSubtree, 51, `(cn=*\28test\29*)`)
	if len(r.Entries) != 1 {
		t.Errorf("escaped parentheses: %v", names(r))
	}
	// Case folding and space collapsing.
	r, _ = search(c, staff, ldap.ScopeWholeSubtree, 51, "(cn=ALICE   smith)")
	if len(r.Entries) != 1 {
		t.Errorf("caseIgnore equality: %v", names(r))
	}
	r, _ = search(c, staff, ldap.ScopeWholeSubtree, 51, "(&(objectClass=inetOrgPerson)(!(telephoneNumber=1001)))")
	if len(r.Entries) != 4 {
		t.Errorf("not: %v", names(r))
	}
	// Unknown attribute: Undefined, so neither it nor its negation matches.
	for _, f := range []string{"(foo=1)", "(!(foo=1))", "(foo=*1*)"} {
		if r, _ = search(c, staff, ldap.ScopeWholeSubtree, 51, f); len(r.Entries) != 0 {
			t.Errorf("%s: %v", f, names(r))
		}
	}
	if r, _ = search(c, staff, ldap.ScopeWholeSubtree, 51, "(!(foo=*))"); len(r.Entries) != 6 {
		t.Errorf("not present unknown: %d", len(r.Entries))
	}
	// Results come in name order.
	r, _ = search(c, staff, ldap.ScopeSingleLevel, 0, "(objectClass=*)", "cn")
	if want := []string{"a*b (test)", "Alice Smith", "张伟", "李娜", "王芳"}; !slices.Equal(names(r), want) {
		t.Errorf("one level order %v", names(r))
	}
	if len(r.Entries[0].Attributes) != 1 {
		t.Errorf("attribute selection: %v", r.Entries[0].Attributes)
	}
	// Scopes over the containers.
	for _, tc := range []struct {
		base  string
		scope int
		want  int
	}{
		{testSuffix, ldap.ScopeBaseObject, 1},
		{testSuffix, ldap.ScopeSingleLevel, 1},
		{testSuffix, ldap.ScopeWholeSubtree, 1 + 1 + 1 + 5}, // suffix, ou=books, staff, entries
		{"ou=books," + testSuffix, ldap.ScopeSingleLevel, 1},
		{staff, ldap.ScopeBaseObject, 1},
		{staff, ldap.ScopeWholeSubtree, 6},
		{"uid=" + itoa(e, "王芳") + "," + staff, ldap.ScopeBaseObject, 1},
		{"uid=" + itoa(e, "王芳") + "," + staff, ldap.ScopeSingleLevel, 0},
	} {
		r, err := search(c, tc.base, tc.scope, 0, "(objectClass=*)")
		if err != nil || len(r.Entries) != tc.want {
			t.Errorf("%s scope %d: %v %d entries", tc.base, tc.scope, err, len(r.Entries))
		}
	}
	// Foreign book, unknown book, missing entry, clients and accounts: 32.
	for _, base := range []string{bookBase("other"), bookBase("public"), bookBase("nope"),
		"uid=999999," + staff, "ou=clients," + testSuffix, "ou=accounts," + testSuffix, "dc=other", "o=x"} {
		if _, err := search(c, base, ldap.ScopeWholeSubtree, 0, "(objectClass=*)"); code(err) != ldap.LDAPResultNoSuchObject {
			t.Errorf("base %s: %v", base, err)
		}
	}
}

func itoa(e *env, name string) string {
	entries, _, _ := e.st.AllEntries(e.t.Context(), e.books["staff"].ID)
	for _, x := range entries {
		if x.Name == name {
			return strconv.FormatInt(x.ID, 10)
		}
	}
	return "0"
}

func TestSizeLimit(t *testing.T) {
	e := newEnv(t, func(c *Config) { c.MaxResults = 3 })
	c := e.login("phone1")
	r, err := search(c, bookBase("staff"), ldap.ScopeWholeSubtree, 2, "(telephoneNumber=*)")
	if code(err) != ldap.LDAPResultSizeLimitExceeded || r == nil || len(r.Entries) != 2 {
		t.Errorf("client limit: %v %v", err, names(r))
	}
	r, err = search(c, bookBase("staff"), ldap.ScopeWholeSubtree, 0, "(telephoneNumber=*)")
	if code(err) != ldap.LDAPResultSizeLimitExceeded || r == nil || len(r.Entries) != 3 {
		t.Errorf("server cap: %v %v", err, names(r))
	}
	r, err = search(c, bookBase("staff"), ldap.ScopeWholeSubtree, 3, "(cn=张伟)")
	if err != nil || len(r.Entries) != 1 {
		t.Errorf("below the limit: %v", err)
	}
}

func TestAnonymousAndChanges(t *testing.T) {
	e := newEnv(t)
	c := e.dialTLS()
	r, err := search(c, testSuffix, ldap.ScopeWholeSubtree, 0, "(cn=*)")
	if err != nil || !slices.Equal(names(r), []string{"前台"}) {
		t.Fatalf("anonymous: %v %v", err, names(r))
	}
	if _, err := search(c, bookBase("staff"), ldap.ScopeWholeSubtree, 0, "(cn=*)"); code(err) != ldap.LDAPResultNoSuchObject {
		t.Errorf("anonymous on a private book: %v", err)
	}
	// Turning anonymous access off and editing entries take effect at the next search.
	ctx := t.Context()
	pub := e.books["public"]
	pub.AllowAnonymous = false
	if err := e.st.UpdateBook(ctx, pub); err != nil {
		t.Fatal(err)
	}
	if _, err := search(c, bookBase("public"), ldap.ScopeWholeSubtree, 0, "(cn=*)"); code(err) != ldap.LDAPResultNoSuchObject {
		t.Errorf("after turning anonymous off: %v", err)
	}
	lc := e.login("phone1")
	search(lc, bookBase("staff"), ldap.ScopeWholeSubtree, 0, "(cn=*)") // warm the cache
	e.st.AddEntries(ctx, e.books["staff"].ID, []*store.Entry{{Name: "新同事", Phones: []string{"1009"}}}, false, 10000)
	r, _ = search(lc, bookBase("staff"), ldap.ScopeWholeSubtree, 0, "(cn=新同事)")
	if len(r.Entries) != 1 {
		t.Error("new entry not visible: the cache was not refreshed")
	}
	// Revoking a grant takes effect on the bound connection.
	a, _ := e.st.LDAPAccountByName(ctx, "phone1")
	a.BookIDs = nil
	e.st.UpdateLDAPAccount(ctx, a)
	if _, err := search(lc, bookBase("staff"), ldap.ScopeWholeSubtree, 0, "(cn=*)"); code(err) != ldap.LDAPResultNoSuchObject {
		t.Errorf("after revoking: %v", err)
	}
}

func TestUnsupportedOperations(t *testing.T) {
	e := newEnv(t)
	c := e.login("phone1")
	if err := c.Add(ldap.NewAddRequest("cn=x,"+testSuffix, nil)); code(err) != ldap.LDAPResultUnwillingToPerform {
		t.Errorf("add: %v", err)
	}
	if err := c.Del(ldap.NewDelRequest(bookBase("staff"), nil)); code(err) != ldap.LDAPResultUnwillingToPerform {
		t.Errorf("delete: %v", err)
	}
	mod := ldap.NewModifyRequest(bookBase("staff"), nil)
	mod.Replace("description", []string{"x"})
	if err := c.Modify(mod); code(err) != ldap.LDAPResultUnwillingToPerform {
		t.Errorf("modify: %v", err)
	}
	if _, err := c.Compare(bookBase("staff"), "ou", "staff"); code(err) != ldap.LDAPResultUnwillingToPerform {
		t.Errorf("compare: %v", err)
	}
	if _, err := c.PasswordModify(ldap.NewPasswordModifyRequest("", "a", "b")); code(err) != ldap.LDAPResultUnwillingToPerform {
		t.Errorf("password modify: %v", err)
	}
	// A critical control is refused, a non-critical one ignored.
	req := ldap.NewSearchRequest(bookBase("staff"), ldap.ScopeWholeSubtree, 0, 0, 5, false, "(cn=张伟)", nil,
		[]ldap.Control{ldap.NewControlString("1.2.3.4", true, "")})
	if _, err := c.Search(req); code(err) != ldap.LDAPResultUnavailableCriticalExtension {
		t.Errorf("critical control: %v", err)
	}
	req.Controls = []ldap.Control{ldap.NewControlString("1.2.3.4", false, "")}
	if r, err := c.Search(req); err != nil || len(r.Entries) != 1 {
		t.Errorf("non-critical control: %v", err)
	}
	// SASL bind.
	if err := c.ExternalBind(); code(err) != ldap.LDAPResultAuthMethodNotSupported {
		t.Errorf("SASL EXTERNAL: %v", err)
	}
}

func TestFailedBindThrottle(t *testing.T) {
	e := newEnv(t, func(c *Config) { c.FailLimit = 3 })
	c := e.dialTLS()
	for i := 0; i < 3; i++ {
		c.Bind(accountDN("phone1"), "wrong")
	}
	// Over the limit, even the right password is refused until the window passes.
	if err := c.Bind(accountDN("phone1"), e.passwords["phone1"]); code(err) != ldap.LDAPResultInvalidCredentials {
		t.Errorf("bind over the failure limit: %v", err)
	}
}

func TestConnectionLimits(t *testing.T) {
	e := newEnv(t, func(c *Config) { c.MaxConnsPerIP = 2 })
	e.dial()
	e.dial()
	c, err := ldap.DialURL("ldap://" + e.addr)
	if err == nil {
		// The server closes the third connection at once.
		if _, err = search(c, "", ldap.ScopeBaseObject, 0, "(objectClass=*)"); err == nil {
			t.Error("third connection from one address served")
		}
		c.Close()
	}
}

// writeCounter counts the writes to the connections it accepts.
type writeCounter struct {
	net.Listener
	writes atomic.Int64
}

func (l *writeCounter) Accept() (net.Conn, error) {
	c, err := l.Listener.Accept()
	if err != nil {
		return nil, err
	}
	return &countedConn{Conn: c, n: &l.writes}, nil
}

type countedConn struct {
	net.Conn
	n *atomic.Int64
}

func (c *countedConn) Write(b []byte) (int, error) {
	c.n.Add(1)
	return c.Conn.Write(b)
}

// TestSearchWritesOnce: the entries of a search and its result leave in one write when
// they fit the connection's buffer, not in one write each; a bind answers at once.
func TestSearchWritesOnce(t *testing.T) {
	e := newEnv(t, func(c *Config) { c.RequireTLS = false })
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	counter := &writeCounter{Listener: ln}
	go e.srv.Serve(counter, false)
	t.Cleanup(func() { ln.Close() })
	c, err := ldap.DialURL("ldap://" + ln.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	if err := c.Bind(accountDN("phone1"), e.passwords["phone1"]); err != nil || counter.writes.Load() != 1 {
		t.Fatalf("bind: %v, %d writes", err, counter.writes.Load())
	}
	r, err := search(c, bookBase("staff"), ldap.ScopeWholeSubtree, 0, "(objectClass=*)")
	if err != nil || len(r.Entries) < 5 {
		t.Fatalf("search: %v %v", err, names(r))
	}
	if n := counter.writes.Load() - 1; n != 1 {
		t.Errorf("%d entries and the result in %d writes", len(r.Entries), n)
	}
}
