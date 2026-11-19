package rules

import (
	"reflect"
	"siplink/internal/i18n"
	"strings"
	"testing"
)

// Tests ported from android/app/src/test/java/com/sky/siplink/core/logic/LdapRulesTest.kt.

func validLdap() LdapDirectory {
	d := DefaultLdapDirectory()
	d.Host, d.BaseDn = "ldap.example.com", "dc=example,dc=com"
	return d
}

func ldapErrs(d LdapDirectory, otherNames []string, isNew bool) []LdapError {
	return ValidateLdap(NormalizeLdap(d), otherNames, isNew)
}

func wantLdap(t *testing.T, got []LdapError, want ...LdapError) {
	t.Helper()
	if want == nil {
		want = []LdapError{}
	}
	if !reflect.DeepEqual(got, want) {
		t.Errorf("want %v, got %v", want, got)
	}
}

// LdapRulesTest.`defaults plus server and base are valid`
func TestLdapDefaultsAreValid(t *testing.T) {
	wantLdap(t, ldapErrs(validLdap(), nil, true))
}

// LdapRulesTest.`normalize takes a typed address apart and strips noise`
func TestLdapNormalize(t *testing.T) {
	d := DefaultLdapDirectory()
	d.Name = " 总部 "
	d.Host = " ldap://ldap.example.com:1389/ "
	d.BaseDn = " dc=example,dc=com "
	d.NameAttributes = "cn, sn  displayName"
	d.NumberAttributes = " telephoneNumber,mobile  telephoneNumber "
	d.NameFilter = " (cn=%) "
	n := NormalizeLdap(d)
	got := []any{n.Name, n.Host, n.Port, n.BaseDn, n.NameAttributes, n.NumberAttributes, n.NameFilter}
	want := []any{"总部", "ldap.example.com", 1389, "dc=example,dc=com", "cn sn displayName", "telephoneNumber mobile", "(cn=%)"}
	if !reflect.DeepEqual(got, want) {
		t.Errorf("got %v", got)
	}
	// A host alone keeps the port it has.
	v := validLdap()
	v.Port = 1636
	if NormalizeLdap(v).Port != 1636 || NormalizeLdap(validLdap()).Port != LdapDefaultPort {
		t.Error("port of a host alone changed")
	}
}

// LdapRulesTest.`ldaps is rejected in favour of StartTLS`
func TestLdapLdapsRejected(t *testing.T) {
	d := validLdap()
	d.Host = "ldaps://ldap.example.com"
	wantLdap(t, ldapErrs(d, nil, true), LdapHostLdaps)
}

// LdapRulesTest.`the server has to be a host and the port a port`
func TestLdapServerAndPort(t *testing.T) {
	cases := []struct {
		host string
		port int
		want []LdapError
	}{
		{" ", LdapDefaultPort, []LdapError{LdapHostEmpty}},
		{"ldap example", LdapDefaultPort, []LdapError{LdapHostInvalid}},
		{"http://ldap.example.com", LdapDefaultPort, []LdapError{LdapHostInvalid}},
		{"ldap.example.com:ldap", LdapDefaultPort, []LdapError{LdapHostInvalid}},
		{"ldap.example.com", 0, []LdapError{LdapPortOutOfRange}},
		{"ldap.example.com:70000", LdapDefaultPort, []LdapError{LdapPortOutOfRange}},
		{"192.0.2.10", LdapDefaultPort, nil},
	}
	for _, c := range cases {
		d := validLdap()
		d.Host, d.Port = c.host, c.port
		wantLdap(t, ldapErrs(d, nil, true), c.want...)
	}
}

// LdapRulesTest.`StartTLS needs a host name`
func TestLdapStartTLSNeedsHostName(t *testing.T) {
	tls := validLdap()
	tls.Security = LdapSecurityStartTLS
	wantLdap(t, ldapErrs(tls, nil, true))
	tls.Host = "192.0.2.10"
	wantLdap(t, ldapErrs(tls, nil, true), LdapStartTLSNeedsHostName)
}

// LdapRulesTest.`what has to be filled in is asked for`
func TestLdapRequiredFields(t *testing.T) {
	cases := []struct {
		change func(*LdapDirectory)
		want   []LdapError
	}{
		{func(d *LdapDirectory) { d.BaseDn = "" }, []LdapError{LdapBaseDnEmpty}},
		{func(d *LdapDirectory) { d.NameAttributes = " " }, []LdapError{LdapNameAttributesEmpty}},
		{func(d *LdapDirectory) { d.NumberAttributes = " , " }, []LdapError{LdapNumberAttributesEmpty}},
		{func(d *LdapDirectory) { d.NumberAttributes = "ipPhone" }, nil},
		// A filter may be left to the attributes, but one that is written has to place the search.
		{func(d *LdapDirectory) { d.NameFilter = "(cn=*)" }, []LdapError{LdapNameFilterNoPlaceholder}},
		{func(d *LdapDirectory) { d.NumberFilter = "(mobile=*)" }, []LdapError{LdapNumberFilterNoPlaceholder}},
		{func(d *LdapDirectory) { d.NameFilter, d.NumberFilter = "(cn=%)", "(mobile=%)" }, nil},
		{func(d *LdapDirectory) { d.Password = "secret" }, []LdapError{LdapPasswordWithoutUser}},
		{func(d *LdapDirectory) { d.MaxResults = 0 }, []LdapError{LdapMaxResultsOutOfRange}},
		{func(d *LdapDirectory) { d.MaxResults = MaxResults + 1 }, []LdapError{LdapMaxResultsOutOfRange}},
	}
	for _, c := range cases {
		d := validLdap()
		c.change(&d)
		wantLdap(t, ldapErrs(d, nil, true), c.want...)
	}
}

// LdapRulesTest.`two directories cannot share a name`
func TestLdapNamesAreUnique(t *testing.T) {
	d := validLdap()
	d.Name = "总部"
	wantLdap(t, ldapErrs(d, []string{"分公司", "总部"}, true), LdapNameTaken)
	d.Name = "HQ"
	wantLdap(t, ldapErrs(d, []string{"hq"}, true), LdapNameTaken)
	// Without a name a directory goes by its server, and several may do so.
	wantLdap(t, ldapErrs(validLdap(), []string{"", "总部"}, true))
	d.Name = strings.Repeat("长", MaxDirectoryNameLength+1)
	wantLdap(t, ldapErrs(d, nil, true), LdapNameTooLong)
}

// LdapRulesTest.`there are fifty directories at most`
func TestLdapFiftyDirectoriesAtMost(t *testing.T) {
	full := make([]string, MaxDirectories)
	wantLdap(t, ldapErrs(validLdap(), full, true), LdapLimitReached)
	wantLdap(t, ldapErrs(validLdap(), full[1:], true))
	// Changing one of the fifty is not adding one.
	wantLdap(t, ldapErrs(validLdap(), full[1:], false))
	if MaxDirectories != 50 {
		t.Error(MaxDirectories)
	}
}

// LdapRulesTest.`the placeholder of a filter is the beginning of what is searched`
func TestLdapCoreFilter(t *testing.T) {
	cases := map[string]string{
		"(|(cn=%)(sn=%))":               "(|(cn=%s*)(sn=%s*))",
		"(telephoneNumber=*%)":          "(telephoneNumber=*%s*)",
		"(cn=%*)":                       "(cn=%s*)",
		"(&(objectClass=person)(cn=%))": "(&(objectClass=person)(cn=%s*))",
		"(cn=*%s*)":                     "(cn=*%s*)",
		"(uid=%s)":                      "(uid=%s)",
	}
	for in, want := range cases {
		if got := LdapCoreFilter(in); got != want {
			t.Errorf("%q: got %q", in, got)
		}
	}
}

// LdapRulesTest.`a filter of the SIP core becomes one with the placeholder`
func TestLdapFromCoreFilter(t *testing.T) {
	if got := LdapFromCoreFilter("(|(cn=*%s*)(mobile=*%s*))"); got != "(|(cn=*%)(mobile=*%))" {
		t.Error(got)
	}
	if got := LdapFromCoreFilter("(uid=%s)"); got != "(uid=%)" {
		t.Error(got)
	}
	if got := LdapCoreFilter(LdapFromCoreFilter("(|(cn=*%s*)(mobile=*%s*))")); got != "(|(cn=*%s*)(mobile=*%s*))" {
		t.Error(got)
	}
}

// LdapRulesTest.`attributes are told apart by blanks or commas`
func TestLdapAttributes(t *testing.T) {
	cases := map[string][]string{
		" cn sn,displayName ": {"cn", "sn", "displayName"},
		"sn+givenName, cn":    {"sn+givenName", "cn"},
		"  ":                  {},
	}
	for in, want := range cases {
		if got := LdapAttributes(in); !reflect.DeepEqual(got, want) {
			t.Errorf("%q: got %v", in, got)
		}
	}
}

// LdapRulesTest.`a filter that is not written is made from the attributes`
func TestLdapFilterFromAttributes(t *testing.T) {
	v := validLdap()
	with := func(change func(*LdapDirectory)) LdapDirectory {
		d := validLdap()
		change(&d)
		return d
	}
	cases := []struct{ got, want string }{
		{LdapNameFilter(v), "(|(cn=*%)(sn=*%))"},
		{LdapNumberFilter(v), "(|(telephoneNumber=*%)(mobile=*%))"},
		{LdapNameFilter(with(func(d *LdapDirectory) { d.NameAttributes = "cn" })), "(cn=*%)"},
		{LdapNumberFilter(with(func(d *LdapDirectory) { d.NumberAttributes = "telephoneNumber ipPhone homePhone" })),
			"(|(telephoneNumber=*%)(ipPhone=*%)(homePhone=*%))"},
		{LdapNameFilter(with(func(d *LdapDirectory) { d.NameAttributes = "sn+givenName cn sn" })), "(|(sn=*%)(givenName=*%)(cn=*%))"},
		{LdapNameFilter(with(func(d *LdapDirectory) { d.NameFilter = "(uid=%)" })), "(uid=%)"},
		{LdapNumberFilter(with(func(d *LdapDirectory) { d.NumberFilter = "(mobile=%)" })), "(mobile=%)"},
		{LdapCoreFilter(LdapNameFilter(v)), "(|(cn=*%s*)(sn=*%s*))"},
	}
	for i, c := range cases {
		if c.got != c.want {
			t.Errorf("%d: got %q, want %q", i, c.got, c.want)
		}
	}
}

// LdapRulesTest.`the address of the server is written for the SIP core`
func TestLdapServerURL(t *testing.T) {
	cases := []struct {
		host string
		port int
		want string
	}{
		{"ldap.example.com", 389, "ldap://ldap.example.com:389"},
		{"192.0.2.10", 1389, "ldap://192.0.2.10:1389"},
		{"2001:db8::1", 389, "ldap://[2001:db8::1]:389"},
		{"[2001:db8::1]", 389, "ldap://[2001:db8::1]:389"},
	}
	for _, c := range cases {
		d := validLdap()
		d.Host, d.Port = c.host, c.port
		if got := LdapServerURL(d); got != c.want {
			t.Errorf("got %q", got)
		}
	}
}

// LdapRulesTest.`the server is typed as a host with the port only when it is not the usual one`
func TestLdapServerText(t *testing.T) {
	if got := LdapServerText(validLdap()); got != "ldap.example.com" {
		t.Error(got)
	}
	v := validLdap()
	v.Port = 1389
	if got := LdapServerText(v); got != "ldap.example.com:1389" {
		t.Error(got)
	}
	// What is typed that way comes apart again.
	typed := DefaultLdapDirectory()
	typed.Host = LdapServerText(v)
	n := NormalizeLdap(typed)
	if n.Host != "ldap.example.com" || n.Port != 1389 {
		t.Errorf("got %q %d", n.Host, n.Port)
	}
}

// Not a Kotlin test: the error texts are the app's own.
func TestLdapErrorTexts(t *testing.T) {
	zh := func(e LdapError) string { return i18n.For(i18n.Source).Msg(e.Msg()) }
	if zh(LdapHostLdaps) != "服务器地址不支持 ldaps://，请用 StartTLS" ||
		zh(LdapMaxResultsOutOfRange) != "最大结果数应为 1–1000" ||
		zh(LdapLimitReached) != "最多 50 个通讯录" ||
		LdapStartTLSNeedsHostName.Name() != "STARTTLS_NEEDS_HOST_NAME" ||
		len(ldapErrors) != int(LdapManaged)+1 {
		t.Error("error texts differ")
	}
}
