package rules

import "testing"

func TestHostedSecurity(t *testing.T) {
	for _, c := range []struct {
		host    string
		require bool
		want    LdapSecurity
		ok      bool
	}{
		{"ldap.example.com", true, LdapSecurityStartTLS, true},
		{"ldap.example.com", false, LdapSecurityStartTLS, true},
		{"192.0.2.10", true, LdapSecurityNone, false},
		{"192.0.2.10", false, LdapSecurityNone, true},
		{"[2001:db8::1]", false, LdapSecurityNone, true},
		{"2001:db8::1", true, LdapSecurityNone, false},
	} {
		got, ok := HostedSecurity(c.host, c.require)
		if got != c.want || ok != c.ok {
			t.Errorf("HostedSecurity(%q, %v) = %s %v", c.host, c.require, got, ok)
		}
		if !ok {
			continue
		}
		// What is handed out always passes the ported app rule.
		d := DefaultLdapDirectory()
		d.Name, d.Host, d.BaseDn, d.Security = "托管", c.host, "ou=staff,ou=books,dc=siplink", got
		for _, e := range ValidateLdap(d, nil, true) {
			if e == LdapStartTLSNeedsHostName {
				t.Errorf("%q: the app would refuse %s", c.host, got)
			}
		}
	}
}
