package ldapd

import (
	"context"
	"slices"
	"testing"

	"github.com/go-ldap/ldap/v3"

	"siplink/internal/secret"
)

// readsStaffOnly: the connection reads the staff book and is not anonymous, which would
// also read the public book.
func readsStaffOnly(t *testing.T, c *ldap.Conn) {
	t.Helper()
	r, err := search(c, testSuffix, ldap.ScopeWholeSubtree, 0, "(cn=*)")
	if err != nil || len(r.Entries) != 5 || slices.Contains(names(r), "前台") {
		t.Fatalf("bound search: %v %v", err, names(r))
	}
}

// readsAsAnonymous: the connection no longer reads the staff book, only what anyone reads.
func readsAsAnonymous(t *testing.T, c *ldap.Conn, what string) {
	t.Helper()
	if _, err := search(c, bookBase("staff"), ldap.ScopeWholeSubtree, 0, "(cn=*)"); code(err) != ldap.LDAPResultNoSuchObject {
		t.Errorf("%s: the old binding still reads: %v", what, err)
	}
	r, err := search(c, testSuffix, ldap.ScopeWholeSubtree, 0, "(cn=*)")
	if err != nil || !slices.Equal(names(r), []string{"前台"}) {
		t.Errorf("%s: not anonymous: %v %v", what, err, names(r))
	}
}

func TestClientResetEndsBinding(t *testing.T) {
	e := newEnv(t, func(c *Config) { c.LDAPKey = testLDAPKey })
	cl := e.client("desk", "staff", true)
	c := e.dialTLS()
	must(t, c.Bind(clientDN(cl), secret.LDAPPassword(testLDAPKey, cl.PublicID, cl.LDAPGeneration)))
	readsStaffOnly(t, c)
	readsStaffOnly(t, c) // an unchanged credential keeps working
	must(t, e.st.ResetLDAP(context.Background(), cl.ID))
	readsAsAnonymous(t, c, "after the reset")
	cl, err := e.st.ClientByPublicID(context.Background(), cl.PublicID)
	must(t, err)
	must(t, c.Bind(clientDN(cl), secret.LDAPPassword(testLDAPKey, cl.PublicID, cl.LDAPGeneration)))
	readsStaffOnly(t, c) // the new password binds again
}

func TestAccountResetEndsBinding(t *testing.T) {
	e := newEnv(t)
	c := e.login("phone1")
	readsStaffOnly(t, c)
	readsStaffOnly(t, c)
	a, err := e.st.LDAPAccountByName(context.Background(), "phone1")
	must(t, err)
	must(t, e.st.SetLDAPAccountPassword(context.Background(), a.ID, secret.HashHex(secret.RandomText(32))))
	readsAsAnonymous(t, c, "after the reset")
}

// A connection of a deleted account reads nothing the next account with its id is granted.
func TestAccountIDReuseEndsBinding(t *testing.T) {
	e := newEnv(t)
	ctx := context.Background()
	e.account("leaked", false, "staff")
	c := e.login("leaked")
	readsStaffOnly(t, c)
	old, err := e.st.LDAPAccountByName(ctx, "leaked")
	must(t, err)
	must(t, e.st.DeleteLDAPAccount(ctx, old.ID))
	e.account("fresh", false, "staff", "other")
	fresh, err := e.st.LDAPAccountByName(ctx, "fresh")
	must(t, err)
	if fresh.ID != old.ID {
		t.Fatalf("the new account got id %d, not the reused %d: the test proves nothing", fresh.ID, old.ID)
	}
	readsAsAnonymous(t, c, "after the id was reused")
	if _, err := search(c, bookBase("other"), ldap.ScopeWholeSubtree, 0, "(cn=*)"); code(err) != ldap.LDAPResultNoSuchObject {
		t.Errorf("the old binding reads the new account's book: %v", err)
	}
	if r, err := search(e.login("fresh"), bookBase("other"), ldap.ScopeWholeSubtree, 0, "(cn=*)"); err != nil || len(r.Entries) != 1 {
		t.Errorf("the new account's own binding: %v %v", err, names(r))
	}
}
