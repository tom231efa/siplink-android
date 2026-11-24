package web

import (
	"strings"
	"testing"

	"siplink/internal/store"
)

// TestNewQRCodeEndsLDAPPassword: after a new QR code and a new enrollment the document
// carries another LDAP password for the internal directory, so the old phone's no longer
// works (§4.3).
func TestNewQRCodeEndsLDAPPassword(t *testing.T) {
	h := newHarness(t)
	east := h.region("华东")
	f := h.fixture("标准", nil)
	root, _ := h.loggedIn("root", store.RoleSuper)
	id, token := newClient(t, root, f, "前台", east, "1001")
	hostedPassword := func(token string) string {
		t.Helper()
		code, body, _ := h.deviceCall("/p/"+token, "", "")
		if code != 200 {
			t.Fatalf("enroll: %d %s", code, body)
		}
		for _, d := range decode(t, body).Directories {
			if strings.Contains(d.BindDn, "ou=clients") {
				return d.Password
			}
		}
		t.Fatal("no internal directory in the document")
		return ""
	}
	before := hostedPassword(token)
	root.post("/clients/"+itoa(id)+"/unbind", nil)
	m := enrollLink.FindStringSubmatch(root.get("/clients/" + itoa(id)).body)
	if m == nil {
		t.Fatal("no new enrollment link")
	}
	if after := hostedPassword(m[1]); after == "" || after == before {
		t.Errorf("LDAP password after a new enrollment: %q, before %q", after, before)
	}
}
