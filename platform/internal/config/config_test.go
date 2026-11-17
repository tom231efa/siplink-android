package config

import (
	"net/netip"
	"os"
	"path/filepath"
	"testing"
)

func envOf(m map[string]string) Lookup {
	return func(k string) (string, bool) { v, ok := m[k]; return v, ok }
}

func TestDefaults(t *testing.T) {
	c, err := LoadFrom(envOf(map[string]string{"SIPLINK_PUBLIC_URL": "https://platform.example.com/", "SIPLINK_DATA_DIR": "/tmp/x"}))
	if err != nil {
		t.Fatal(err)
	}
	if c.PublicURL != "https://platform.example.com" || c.PublicHost != "platform.example.com" {
		t.Errorf("public url %q host %q", c.PublicURL, c.PublicHost)
	}
	if c.HTTPListen != ":8080" || c.LDAPListen != ":1389" || c.LDAPSListen != "" || c.LDAPSuffix != "dc=siplink" {
		t.Errorf("listeners %q %q %q %q", c.HTTPListen, c.LDAPListen, c.LDAPSListen, c.LDAPSuffix)
	}
	if c.TLSMode != TLSAuto || !c.HTTPSListener() || !c.LDAPRequireTLS {
		t.Errorf("TLS defaults: mode %q require %v", c.TLSMode, c.LDAPRequireTLS)
	}
	if c.LDAPPublicHost != "platform.example.com" || c.LDAPPublicPort != 389 {
		t.Errorf("ldap public %q %d", c.LDAPPublicHost, c.LDAPPublicPort)
	}
	if c.MaxBooks != 500 || c.MaxBookEntries != 10000 || c.LDAPMaxResults != 1000 || c.LDAPCacheEntries != 1000000 {
		t.Errorf("limits %d %d %d %d", c.MaxBooks, c.MaxBookEntries, c.LDAPMaxResults, c.LDAPCacheEntries)
	}
	if c.KeyFile != filepath.Join("/tmp/x", "key") && c.KeyFile != "/run/secrets/siplink-key" {
		t.Errorf("key file %q", c.KeyFile)
	}
}

func TestErrors(t *testing.T) {
	cases := []map[string]string{
		{},
		{"SIPLINK_PUBLIC_URL": "ftp://x"},
		{"SIPLINK_PUBLIC_URL": "https://x/path"},
		{"SIPLINK_PUBLIC_URL": "https://x", "SIPLINK_TLS": "maybe"},
		{"SIPLINK_PUBLIC_URL": "https://x", "SIPLINK_MAX_BOOKS": "501"},
		{"SIPLINK_PUBLIC_URL": "https://x", "SIPLINK_MAX_BOOK_ENTRIES": "10001"},
		{"SIPLINK_PUBLIC_URL": "https://x", "SIPLINK_LDAP_MAX_RESULTS": "0"},
		{"SIPLINK_PUBLIC_URL": "https://x", "SIPLINK_TLS_CERT_FILE": "a.pem"},
		{"SIPLINK_PUBLIC_URL": "https://x", "SIPLINK_LDAP_REQUIRE_TLS": "yes please"},
		{"SIPLINK_PUBLIC_URL": "https://x", "SIPLINK_TRUSTED_PROXIES": "not-an-ip"},
		{"SIPLINK_PUBLIC_URL": "https://x", "SIPLINK_BACKUP_TIME": "25:00"},
	}
	for i, m := range cases {
		if _, err := LoadFrom(envOf(m)); err == nil {
			t.Errorf("case %d %v: no error", i, m)
		}
	}
}

func TestFileAndOverrides(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "siplink.conf")
	body := "# comment\nSIPLINK_PUBLIC_URL=https://file.example.com\nSIPLINK_LDAP_SUFFIX=\"dc=example,dc=com\"\nSIPLINK_TLS=off\n"
	if err := os.WriteFile(path, []byte(body), 0o600); err != nil {
		t.Fatal(err)
	}
	c, err := LoadFrom(envOf(map[string]string{"SIPLINK_CONFIG_FILE": path, "SIPLINK_TLS": "auto",
		"SIPLINK_TRUSTED_PROXIES": "192.0.2.0/24, 198.51.100.7", "SIPLINK_TLS_EXTRA_NAMES": "pbx.example.com, 192.0.2.10"}))
	if err != nil {
		t.Fatal(err)
	}
	if c.PublicHost != "file.example.com" || c.LDAPSuffix != "dc=example,dc=com" || c.TLSMode != TLSAuto {
		t.Errorf("file/env merge: %q %q %q", c.PublicHost, c.LDAPSuffix, c.TLSMode)
	}
	for _, a := range []string{"192.0.2.55", "198.51.100.7", "::ffff:192.0.2.1"} {
		if !c.IsTrustedProxy(netip.MustParseAddr(a)) {
			t.Errorf("%s should be trusted", a)
		}
	}
	if c.IsTrustedProxy(netip.MustParseAddr("198.51.100.8")) {
		t.Error("198.51.100.8 trusted")
	}
	want := []string{"file.example.com", "pbx.example.com", "192.0.2.10", "localhost", "127.0.0.1", "::1"}
	got := c.CertNames()
	if len(got) != len(want) {
		t.Fatalf("names %v", got)
	}
	for i := range want {
		if got[i] != want[i] {
			t.Errorf("names %v want %v", got, want)
		}
	}
}
