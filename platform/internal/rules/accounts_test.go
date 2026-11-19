package rules

import (
	"fmt"
	"reflect"
	"siplink/internal/i18n"
	"strings"
	"testing"
)

// Tests ported from android/app/src/test/java/com/sky/siplink/core/logic/AccountRulesTest.kt and,
// for the parts of AccountRoute the rules use, AccountRouteTest.kt.

func validDraft() AccountDraft {
	d := DefaultAccountDraft()
	d.Username, d.Password, d.Domain = "1001", "x", "pbx.example.com"
	return d
}

func noAccountErrors(t *testing.T, got []AccountError, what string) {
	t.Helper()
	if len(got) != 0 {
		t.Errorf("%s: want no errors, got %v", what, got)
	}
}

func wantAccountErrors(t *testing.T, got []AccountError, want []AccountError, what string) {
	t.Helper()
	if !reflect.DeepEqual(got, want) {
		t.Errorf("%s: want %v, got %v", what, want, got)
	}
}

func hasAccountError(errors []AccountError, e AccountError) bool { return contains(errors, e) }

// AccountRulesTest.`valid draft has no errors`
func TestAccountValidDraftHasNoErrors(t *testing.T) {
	noAccountErrors(t, ValidateAccount(validDraft(), nil, "", nil), "valid")
}

// AccountRulesTest.`limit is exactly one hundred accounts`
func TestAccountLimitIsExactlyOneHundred(t *testing.T) {
	if MaxAccounts != 100 {
		t.Fatal(MaxAccounts)
	}
	var keys []string
	for i := 1; i <= 99; i++ {
		keys = append(keys, IdentityKey(fmt.Sprintf("u%d", i), "pbx.example.com"))
	}
	if !CanAddAccount(len(keys)) {
		t.Error("99: cannot add")
	}
	noAccountErrors(t, ValidateAccount(validDraft(), keys, "", nil), "99")
	keys = append(keys, IdentityKey("u100", "pbx.example.com"))
	if CanAddAccount(len(keys)) {
		t.Error("100: can add")
	}
	if !hasAccountError(ValidateAccount(validDraft(), keys, "", nil), AccountLimitReached) {
		t.Error("100: no LIMIT_REACHED")
	}
}

// AccountRulesTest.`editing an existing account is allowed when the list is full`
func TestAccountEditingAllowedWhenFull(t *testing.T) {
	var keys []string
	for i := 1; i <= 100; i++ {
		keys = append(keys, IdentityKey(fmt.Sprintf("u%d", i), "pbx.example.com"))
	}
	editing := DefaultAccountDraft()
	editing.Username, editing.Domain = "u7", "pbx.example.com"
	noAccountErrors(t, ValidateAccount(editing, keys, keys[6], nil), "editing")
}

// AccountRulesTest.`duplicate identity is rejected and host comparison ignores case`
func TestAccountDuplicateIdentity(t *testing.T) {
	existing := []string{IdentityKey("1001", "PBX.Example.com")}
	if !hasAccountError(ValidateAccount(validDraft(), existing, "", nil), AccountDuplicate) {
		t.Error("no DUPLICATE")
	}
	// The user part stays case-sensitive.
	other := validDraft()
	other.Username = "Alice"
	if hasAccountError(ValidateAccount(other, []string{IdentityKey("alice", "pbx.example.com")}, "", nil), AccountDuplicate) {
		t.Error("DUPLICATE for another case of the user")
	}
}

// AccountRulesTest.`renaming an account onto another existing identity is rejected`
func TestAccountRenamingOntoAnotherIdentity(t *testing.T) {
	keys := []string{IdentityKey("1001", "pbx.example.com"), IdentityKey("1002", "pbx.example.com")}
	renamed := validDraft()
	renamed.Username = "1002"
	if !hasAccountError(ValidateAccount(renamed, keys, keys[0], nil), AccountDuplicate) {
		t.Error("no DUPLICATE")
	}
}

// AccountRulesTest.`missing and malformed fields are reported`
func TestAccountMissingAndMalformedFields(t *testing.T) {
	empty := DefaultAccountDraft()
	empty.Expires = 5
	errors := ValidateAccount(empty, nil, "", nil)
	for _, e := range []AccountError{AccountUsernameEmpty, AccountDomainEmpty, AccountExpiresOutOfRange} {
		if !hasAccountError(errors, e) {
			t.Errorf("missing %v in %v", e, errors)
		}
	}
	cases := []struct {
		change func(*AccountDraft)
		want   AccountError
	}{
		{func(d *AccountDraft) { d.Username = "a b" }, AccountUsernameInvalid},
		{func(d *AccountDraft) { d.Domain = "bad_host!" }, AccountDomainInvalid},
		{func(d *AccountDraft) { d.Server = "host:99999" }, AccountServerInvalid},
	}
	for _, c := range cases {
		d := validDraft()
		c.change(&d)
		if !hasAccountError(ValidateAccount(d, nil, "", nil), c.want) {
			t.Errorf("missing %v", c.want)
		}
	}
}

// AccountRulesTest.`a backup server is a second host that differs from the first`
func TestAccountBackupServer(t *testing.T) {
	errs := func(server, backup string) []AccountError {
		d := validDraft()
		d.Server, d.BackupServer = server, backup
		return ValidateAccount(NormalizeAccount(d), nil, "", nil)
	}
	noAccountErrors(t, errs("", " pbx2.example.com:5080 "), "backup with port")
	wantAccountErrors(t, errs("", "pbx2 example"), []AccountError{AccountBackupInvalid}, "blank inside")
	// Without a server of its own the account is on its domain.
	wantAccountErrors(t, errs("", "PBX.example.com"), []AccountError{AccountBackupSame}, "same as domain")
	wantAccountErrors(t, errs("sbc.example.com", "sbc.example.com"), []AccountError{AccountBackupSame}, "same as server")
	noAccountErrors(t, errs("sbc.example.com", "pbx.example.com"), "domain as backup")
}

// AccountRulesTest.`a dns server is given by its address`
func TestAccountDNSServer(t *testing.T) {
	errs := func(dns string) []AccountError {
		d := validDraft()
		d.DnsServer = dns
		return ValidateAccount(NormalizeAccount(d), nil, "", nil)
	}
	noAccountErrors(t, errs(" 192.0.2.53 "), "address")
	noAccountErrors(t, errs("192.0.2.53:5300"), "address and port")
	wantAccountErrors(t, errs("dns.example.com"), []AccountError{AccountDNSInvalid}, "name")
	wantAccountErrors(t, errs("192.0.2.53:99999"), []AccountError{AccountDNSInvalid}, "bad port")
}

// AccountRulesTest.`two accounts cannot have the same name`
func TestAccountNamesAreUnique(t *testing.T) {
	named := validDraft()
	named.Name = "前台"
	noAccountErrors(t, ValidateAccount(named, nil, "", []string{"销售", ""}), "free name")
	wantAccountErrors(t, ValidateAccount(named, nil, "", []string{"销售", "前台"}), []AccountError{AccountNameTaken}, "taken")
	front := validDraft()
	front.Name = "Front"
	wantAccountErrors(t, ValidateAccount(front, nil, "", []string{"front"}), []AccountError{AccountNameTaken}, "case")
	// Any number of accounts may go without a name.
	noAccountErrors(t, ValidateAccount(validDraft(), nil, "", []string{"", ""}), "no name")
}

// AccountRulesTest.`an account name is trimmed and limited in length`
func TestAccountNameTrimmedAndLimited(t *testing.T) {
	d := validDraft()
	d.Name = "  总机 "
	if got := NormalizeAccount(d).Name; got != "总机" {
		t.Errorf("got %q", got)
	}
	d.Name = strings.Repeat("x", MaxAccountNameLength)
	noAccountErrors(t, ValidateAccount(d, nil, "", nil), "30")
	d.Name = strings.Repeat("x", MaxAccountNameLength+1)
	if !hasAccountError(ValidateAccount(d, nil, "", nil), AccountNameTooLong) {
		t.Error("31: no NAME_TOO_LONG")
	}
}

// AccountRulesTest.`normalize trims strips scheme and splits user at domain`
func TestAccountNormalizeSplitsUserAtDomain(t *testing.T) {
	d := DefaultAccountDraft()
	d.Username, d.Domain = "  sip:alice@example.org ", " "
	n := NormalizeAccount(d)
	if n.Username != "alice" || n.Domain != "example.org" {
		t.Errorf("got %q %q", n.Username, n.Domain)
	}
	// An explicit domain wins over the one typed into the user field.
	d.Username, d.Domain = "bob@ignored.org", "pbx.local"
	n = NormalizeAccount(d)
	if n.Username != "bob" || n.Domain != "pbx.local" {
		t.Errorf("got %q %q", n.Username, n.Domain)
	}
}

// AccountRulesTest.`a port typed behind the domain goes to the port field`
func TestAccountPortTypedBehindDomain(t *testing.T) {
	cases := []struct{ domain, port, wantDomain, wantPort string }{
		{" Pbx.Example.com:5090 ", "", "Pbx.Example.com", "5090"},
		// It is the port that is meant, whatever the port field shows; a Chinese keyboard types a wide colon.
		{"pbx.example.com\uFF1A5090", "5060", "pbx.example.com", "5090"},
		{"[2001:db8::1]:5061", "", "[2001:db8::1]", "5061"},
		// Without a port in the domain the port field stands; a bare IPv6 address has no port in it.
		{"[2001:db8::1]", " 5070 ", "[2001:db8::1]", "5070"},
	}
	for _, c := range cases {
		d := DefaultAccountDraft()
		d.Username, d.Domain, d.Port = "1001", c.domain, c.port
		n := NormalizeAccount(d)
		if n.Domain != c.wantDomain || n.Port != c.wantPort {
			t.Errorf("%q: got %q %q", c.domain, n.Domain, n.Port)
		}
	}
	d := DefaultAccountDraft()
	d.Username, d.Domain = "1001", " Pbx.Example.com:5090 "
	if got := NormalizeAccount(d).Address(); got != "Pbx.Example.com:5090" {
		t.Errorf("address %q", got)
	}
	if got := NormalizeAccount(validDraft()).Port; got != "" {
		t.Errorf("port %q", got)
	}
}

// AccountRulesTest.`a port is a number from 1 to 65535`
func TestAccountPortRange(t *testing.T) {
	for _, port := range []string{"1", "5060", "65535"} {
		d := validDraft()
		d.Port = port
		noAccountErrors(t, ValidateAccount(d, nil, "", nil), port)
	}
	for _, port := range []string{"0", "65536", "123456", "50a", "+5060", "-1", "5 060"} {
		d := validDraft()
		d.Port = port
		wantAccountErrors(t, ValidateAccount(d, nil, "", nil), []AccountError{AccountPortInvalid}, port)
	}
	// What is typed behind the domain is checked as a port too.
	d := validDraft()
	d.Domain = "pbx.example.com:0"
	wantAccountErrors(t, ValidateAccount(NormalizeAccount(d), nil, "", nil), []AccountError{AccountPortInvalid}, ":0")
}

// AccountRulesTest.`the port is part of the address of the account`
func TestAccountPortIsPartOfAddress(t *testing.T) {
	account := validDraft()
	account.Port = "5090"
	if got := IdentityURI(account); got != "sip:1001@pbx.example.com:5090" {
		t.Error(got)
	}
	if got := ServerURI(account); got != "sip:pbx.example.com:5090;transport=udp" {
		t.Error(got)
	}
	// A proxy of its own is where the account registers, on the port given with it.
	proxied := account
	proxied.Server = "edge.example.com"
	if got := ServerURI(proxied); got != "sip:edge.example.com;transport=udp" {
		t.Error(got)
	}
	// The same user on another port of the host is another account; on the same port it is the same.
	noAccountErrors(t, ValidateAccount(account, []string{IdentityKey("1001", "pbx.example.com")}, "", nil), "other port")
	wantAccountErrors(t, ValidateAccount(account, []string{IdentityKey("1001", "PBX.example.com:5090")}, "", nil),
		[]AccountError{AccountDuplicate}, "same port")
	// The backup server must differ from the server the account is on, port included.
	backup := account
	backup.BackupServer = "pbx.example.com:5090"
	if !hasAccountError(ValidateAccount(backup, nil, "", nil), AccountBackupSame) {
		t.Error("no BACKUP_SAME")
	}
	backup.BackupServer = "pbx.example.com"
	if hasAccountError(ValidateAccount(backup, nil, "", nil), AccountBackupSame) {
		t.Error("BACKUP_SAME on another port")
	}
}

// AccountRulesTest.`host and port validation`
func TestAccountHostAndPortValidation(t *testing.T) {
	for _, v := range []string{"pbx.example.com", "pbx", "10.0.0.5", "10.0.0.5:5061", "pbx.example.com:5060", "[::1]", "[2001:db8::1]:5061"} {
		if !IsValidHostPort(v) {
			t.Errorf("%q rejected", v)
		}
	}
	for _, v := range []string{"", "pbx example", "pbx:", "pbx:0", "pbx:70000", "pbx:abc", "-pbx.com", "[::1", "[::1]5060", "a..b"} {
		if IsValidHostPort(v) {
			t.Errorf("%q accepted", v)
		}
	}
}

// AccountRulesTest.`uris carry identity proxy and transport`
func TestAccountURIs(t *testing.T) {
	if got := IdentityURI(validDraft()); got != "sip:1001@pbx.example.com" {
		t.Error(got)
	}
	if got := ServerURI(validDraft()); got != "sip:pbx.example.com;transport=udp" {
		t.Error(got)
	}
	tls := validDraft()
	tls.Server, tls.Transport = "edge.example.com:5061", SipTransportTLS
	if got := ServerURI(tls); got != "sip:edge.example.com:5061;transport=tls" {
		t.Error(got)
	}
}

// AccountRulesTest.`transport names are parsed case-insensitively`
func TestAccountParseTransport(t *testing.T) {
	cases := []struct {
		in   string
		want SipTransport
		ok   bool
	}{{"", SipTransportUDP, true}, {" TCP ", SipTransportTCP, true}, {"tls", SipTransportTLS, true}, {"sctp", "", false}}
	for _, c := range cases {
		if got, ok := ParseTransport(c.in); got != c.want || ok != c.ok {
			t.Errorf("%q: got %q %v", c.in, got, ok)
		}
	}
}

// AccountRouteTest.`host and port are told apart`
func TestRouteSplit(t *testing.T) {
	cases := []struct {
		in, host string
		port     int
		ok       bool
	}{
		{"pbx.example.com", "pbx.example.com", 0, false},
		{"pbx.example.com:5080", "pbx.example.com", 5080, true},
		{"[2001:db8::1]", "[2001:db8::1]", 0, false},
		{"[2001:db8::1]:5061", "[2001:db8::1]", 5061, true},
	}
	for _, c := range cases {
		host, port, ok := SplitHostPort(c.in)
		if host != c.host || port != c.port || ok != c.ok {
			t.Errorf("%q: got %q %d %v", c.in, host, port, ok)
		}
	}
}

// AccountRouteTest.`an address needs no lookup`
func TestRouteIsAddress(t *testing.T) {
	for _, v := range []string{"192.0.2.10", "[2001:db8::1]"} {
		if !IsAddress(v) {
			t.Errorf("%q", v)
		}
	}
	for _, v := range []string{"pbx.example.com", "192.0.2", "192.0.2.256", "1.2.3.example"} {
		if IsAddress(v) {
			t.Errorf("%q", v)
		}
	}
}

// AccountRouteTest.`a dns server is an ipv4 address with or without a port`
func TestRouteIsValidDNSServer(t *testing.T) {
	for _, v := range []string{"192.0.2.53", "192.0.2.53:5300"} {
		if !IsValidDNSServer(v) {
			t.Errorf("%q", v)
		}
	}
	for _, v := range []string{"dns.example.com", "[2001:db8::53]", "192.0.2.53:0", "192.0.2.53:port", ""} {
		if IsValidDNSServer(v) {
			t.Errorf("%q", v)
		}
	}
}

// Not a Kotlin test: the error texts are the app's own.
func TestAccountErrorTexts(t *testing.T) {
	cases := map[AccountError]string{
		AccountLimitReached:      "最多 100 个账号",
		AccountNameTooLong:       "账号名称最多 30 个字符",
		AccountExpiresOutOfRange: "注册有效期应为 60–86400 秒",
		AccountDNSInvalid:        "DNS 服务器应为 IP[:端口]",
		AccountManaged:           "配置由远程下发，不能在本机修改",
	}
	for e, want := range cases {
		if got := i18n.For(i18n.Source).Msg(e.Msg()); got != want {
			t.Errorf("%v: %q", e, got)
		}
	}
	if len(accountErrors) != int(AccountManaged)+1 || AccountBackupSame.Name() != "BACKUP_SAME" {
		t.Error("error table out of step")
	}
}
