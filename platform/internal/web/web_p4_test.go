package web

import (
	"net/http"
	"net/url"
	"strings"
	"testing"

	"siplink/internal/secret"
	"siplink/internal/store"
)

func TestLDAPAccounts(t *testing.T) {
	h := newHarness(t)
	east, west := h.region("华东"), h.region("华西")
	eastBook, westBook, global := h.book("east", &east), h.book("west", &west), h.book("global", nil)
	local, _ := h.loggedIn("east", store.RoleRegion, east)
	root, _ := h.loggedIn("root", store.RoleSuper)

	// A region account may use books of its region and global ones only.
	r := local.post("/ldap-accounts", url.Values{"name": {"desk1"}, "region": {itoa(east)}, "books": {itoa(westBook.ID)}})
	if r.code != http.StatusBadRequest {
		t.Errorf("grant a book of another region: %d", r.code)
	}
	r = local.post("/ldap-accounts", url.Values{"name": {"desk1"}, "region": {"0"}, "books": {itoa(global.ID)}})
	if r.code != http.StatusBadRequest {
		t.Errorf("region admin creates a global account: %d", r.code)
	}
	r = local.post("/ldap-accounts", url.Values{"name": {"desk1"}, "region": {itoa(east)},
		"books": {itoa(eastBook.ID), itoa(global.ID)}})
	m := oncePassword.FindStringSubmatch(r.body)
	if r.code != 200 || m == nil || len(m[1]) != 32 || !strings.Contains(r.body, "cn=desk1,ou=accounts,dc=siplink") {
		t.Fatalf("create account: %d", r.code)
	}
	a, err := h.st.LDAPAccountByName(t.Context(), "desk1")
	if err != nil || a.PasswordHash != secret.HashHex(m[1]) {
		t.Fatal("stored hash does not match the shown password")
	}
	// A global account cannot use a region book.
	if r := root.post("/ldap-accounts", url.Values{"name": {"g1"}, "region": {"0"}, "books": {itoa(eastBook.ID)}}); r.code != http.StatusBadRequest {
		t.Errorf("global account with a region book: %d", r.code)
	}
	// Other regions cannot see or touch it.
	other, _ := h.loggedIn("west", store.RoleRegion, west)
	if r := other.get("/ldap-accounts/" + itoa(a.ID)); r.code != http.StatusNotFound {
		t.Errorf("other region sees the account: %d", r.code)
	}
	if r := other.get("/ldap-accounts"); strings.Contains(r.body, "desk1") {
		t.Error("account listed for another region")
	}
	// Moving the granted book to another region is refused; the book cannot be deleted.
	r = root.post("/books/"+itoa(eastBook.ID), url.Values{"name": {"east"}, "region": {itoa(west)}})
	if r.code != http.StatusBadRequest || !strings.Contains(r.body, "LDAP 账号") {
		t.Errorf("move a granted book: %d", r.code)
	}
	root.post("/books/"+itoa(eastBook.ID)+"/delete", nil)
	if _, err := h.st.BookByID(t.Context(), eastBook.ID); err != nil {
		t.Error("granted book deleted")
	}
	// Reset shows a new password once; delete removes the account.
	r = local.post("/ldap-accounts/"+itoa(a.ID)+"/reset", nil)
	m2 := oncePassword.FindStringSubmatch(r.body)
	if m2 == nil || m2[1] == m[1] {
		t.Fatal("reset did not show a new password")
	}
	if r := local.post("/ldap-accounts/"+itoa(a.ID)+"/delete", nil); r.code != http.StatusSeeOther {
		t.Errorf("delete: %d", r.code)
	}
	if r := root.post("/books/"+itoa(eastBook.ID)+"/delete", nil); r.code != http.StatusSeeOther {
		t.Errorf("delete book after the account went: %d", r.code)
	}
	entries, _ := h.st.ListAudit(t.Context(), store.AuditFilter{AllRegions: true, Limit: 200})
	for _, e := range entries {
		if strings.Contains(e.DetailJSON, m[1]) || strings.Contains(e.DetailJSON, m2[1]) {
			t.Error("LDAP password in the audit log")
		}
	}
}
