package ldapd

import (
	"context"
	"slices"
	"testing"
	"time"

	"github.com/go-ldap/ldap/v3"

	"siplink/internal/secret"
	"siplink/internal/store"
	"siplink/internal/testutil"
)

var testLDAPKey = [32]byte{1, 2, 3}

// client makes a configuration template with a hosted directory on book slug, a client
// template and a client; bound=true enrolls it.
func (e *env) client(name, slug string, bound bool) *store.Client {
	e.t.Helper()
	ctx := context.Background()
	r := &store.Region{Name: "r-" + name}
	must(e.t, e.st.CreateRegion(ctx, r))
	ct := &store.ConfigTemplate{Name: name, SettingsJSON: "{}", RefreshMinutes: 60}
	must(e.t, e.st.CreateConfigTemplate(ctx, ct))
	d := &store.Directory{TemplateID: ct.ID, Kind: store.KindHosted, BookID: &e.books[slug].ID, Name: slug, Enabled: true,
		PasswordMode: "none", MaxResults: 50}
	must(e.t, e.st.SaveDirectory(ctx, d, 50, nil))
	tpl := &store.ClientTemplate{Name: name, ConfigTemplateID: ct.ID}
	must(e.t, e.st.CreateClientTemplate(ctx, tpl))
	c := &store.Client{Name: name, RegionID: r.ID, ClientTemplateID: tpl.ID,
		EnrollExpiresAt: time.Now().Add(time.Hour).UTC().Format(store.TimeFormat)}
	token := secret.Token(32)
	must(e.t, e.st.SaveClient(ctx, c, nil, nil, nil, testutil.Box(e.t), token))
	if bound {
		var err error
		c, err = e.st.Enroll(ctx, secret.HashHex(token), secret.HashHex("device-"+name), "install")
		must(e.t, err)
	}
	return c
}

func must(t *testing.T, err error) {
	t.Helper()
	if err != nil {
		t.Fatal(err)
	}
}

func clientDN(c *store.Client) string { return "uid=" + c.PublicID + ",ou=clients," + testSuffix }

func TestClientBinds(t *testing.T) {
	e := newEnv(t, func(c *Config) { c.LDAPKey = testLDAPKey })
	active := e.client("desk", "staff", true)
	pending := e.client("spare", "staff", false)
	pw := secret.LDAPPassword(testLDAPKey, active.PublicID, active.LDAPGeneration)

	c := e.dialTLS()
	if err := c.Bind(clientDN(active), pw); err != nil {
		t.Fatalf("client bind: %v", err)
	}
	r, err := search(c, bookBase("staff"), ldap.ScopeWholeSubtree, 51, "(cn=*张*)")
	if err != nil || !slices.Equal(names(r), []string{"张伟"}) {
		t.Errorf("search its book: %v %v", err, names(r))
	}
	if _, err := search(c, bookBase("other"), ldap.ScopeWholeSubtree, 51, "(cn=*)"); code(err) != ldap.LDAPResultNoSuchObject {
		t.Errorf("foreign book: %v", err)
	}
	// Wrong password, a pending client and a reset generation are refused.
	for _, tc := range []struct{ dn, pw string }{
		{clientDN(active), pw[:31] + "x"},
		{clientDN(pending), secret.LDAPPassword(testLDAPKey, pending.PublicID, pending.LDAPGeneration)},
		{"uid=nosuchclnt,ou=clients," + testSuffix, pw},
	} {
		if err := e.dialTLS().Bind(tc.dn, tc.pw); code(err) != ldap.LDAPResultInvalidCredentials {
			t.Errorf("bind %s: %v", tc.dn, err)
		}
	}
	must(t, e.st.ResetLDAP(context.Background(), active.ID))
	if err := e.dialTLS().Bind(clientDN(active), pw); code(err) != ldap.LDAPResultInvalidCredentials {
		t.Errorf("old generation still binds: %v", err)
	}
	// A client deleted while connected reads nothing more (bound with the new password:
	// the reset alone already ends the old binding).
	active, err = e.st.ClientByPublicID(context.Background(), active.PublicID)
	must(t, err)
	must(t, c.Bind(clientDN(active), secret.LDAPPassword(testLDAPKey, active.PublicID, active.LDAPGeneration)))
	if _, err := search(c, bookBase("staff"), ldap.ScopeWholeSubtree, 51, "(cn=*)"); err != nil {
		t.Fatal(err)
	}
	must(t, e.st.DeleteClient(context.Background(), active))
	if _, err := search(c, bookBase("staff"), ldap.ScopeWholeSubtree, 51, "(cn=*)"); code(err) != ldap.LDAPResultNoSuchObject {
		t.Errorf("deleted client still reads: %v", err)
	}
	if reason, _ := e.st.Revoked(context.Background(), secret.HashHex("device-desk")); reason != store.RevokedDeleted {
		t.Errorf("revoked reason: %q", reason)
	}
}
