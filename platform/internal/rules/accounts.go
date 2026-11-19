package rules

import (
	"regexp"
	"siplink/internal/i18n"
	"strconv"
	"strings"
)

// SipTransport is the transport of a SIP account; its values are the Kotlin enum names.
// Port of enum SipTransport (app/src/main/java/com/sky/siplink/core/model/Models.kt).
type SipTransport string

// The values of SipTransport, in the order of the Kotlin enum.
const (
	SipTransportUDP SipTransport = "UDP"
	SipTransportTCP SipTransport = "TCP"
	SipTransportTLS SipTransport = "TLS"
)

// SipTransports lists every SipTransport in the order of the Kotlin enum.
var SipTransports = []SipTransport{SipTransportUDP, SipTransportTCP, SipTransportTLS}

// Valid reports whether t is one of SipTransports.
func (t SipTransport) Valid() bool { return contains(SipTransports, t) }

// AccountDraft is the editable form of a SIP account.
// Port of data class AccountDraft (app/src/main/java/com/sky/siplink/core/model/Models.kt);
// DefaultAccountDraft gives its defaults.
type AccountDraft struct {
	Name         string       `json:"name"`
	Username     string       `json:"username"`
	Password     string       `json:"password"`
	Domain       string       `json:"domain"`
	Port         string       `json:"port"`
	DisplayName  string       `json:"displayName"`
	AuthUser     string       `json:"authUser"`
	Server       string       `json:"server"`
	BackupServer string       `json:"backupServer"`
	DnsServer    string       `json:"dnsServer"`
	Transport    SipTransport `json:"transport"`
	Expires      int          `json:"expires"`
	SubscribeMwi bool         `json:"subscribeMwi"`
	Enabled      bool         `json:"enabled"`
}

// DefaultAccountDraft returns AccountDraft() with the Kotlin default values.
func DefaultAccountDraft() AccountDraft {
	return AccountDraft{Transport: SipTransportUDP, Expires: 3600, Enabled: true}
}

// Address is the domain with its port, where one is given: `host[:port]`.
// Port of AccountDraft.address (app/src/main/java/com/sky/siplink/core/model/Models.kt).
func (d AccountDraft) Address() string {
	if d.Port == "" {
		return d.Domain
	}
	return d.Domain + ":" + d.Port
}

// Limits of AccountRules (app/src/main/java/com/sky/siplink/core/logic/AccountRules.kt):
// MAX_ACCOUNTS, MIN_EXPIRES, MAX_EXPIRES, MAX_NAME_LENGTH.
const (
	MaxAccounts          = 100
	MinExpires           = 60
	MaxExpires           = 86400
	MaxAccountNameLength = 30
)

// AccountError is what is wrong with an account.
// Port of enum AccountRules.Error (app/src/main/java/com/sky/siplink/core/logic/AccountRules.kt).
type AccountError int

// The values of AccountError, in the order of the Kotlin enum; Name gives the Kotlin name.
const (
	AccountLimitReached AccountError = iota
	AccountNameTooLong
	AccountNameTaken
	AccountUsernameEmpty
	AccountUsernameInvalid
	AccountDomainEmpty
	AccountDomainInvalid
	AccountPortInvalid
	AccountServerInvalid
	AccountBackupInvalid
	AccountBackupSame
	AccountDNSInvalid
	AccountExpiresOutOfRange
	AccountDuplicate
	AccountPasswordRequired
	AccountAddressRejected
	AccountNotFound
	AccountManaged
)

var accountErrors = []errorText{
	{"LIMIT_REACHED", i18n.M("rules.account.limit_reached", "max", MaxAccounts)},
	{"NAME_TOO_LONG", i18n.M("rules.account.name_too_long", "max", MaxAccountNameLength)},
	{"NAME_TAKEN", i18n.M("rules.account.name_taken")},
	{"USERNAME_EMPTY", i18n.M("rules.account.username_empty")},
	{"USERNAME_INVALID", i18n.M("rules.account.username_invalid")},
	{"DOMAIN_EMPTY", i18n.M("rules.account.domain_empty")},
	{"DOMAIN_INVALID", i18n.M("rules.account.domain_invalid")},
	{"PORT_INVALID", i18n.M("rules.account.port_invalid")},
	{"SERVER_INVALID", i18n.M("rules.account.server_invalid")},
	{"BACKUP_INVALID", i18n.M("rules.account.backup_invalid")},
	{"BACKUP_SAME", i18n.M("rules.account.backup_same")},
	{"DNS_INVALID", i18n.M("rules.account.dns_invalid")},
	{"EXPIRES_OUT_OF_RANGE", i18n.M("rules.account.expires_out_of_range", "min", MinExpires, "max", MaxExpires)},
	{"DUPLICATE", i18n.M("rules.account.duplicate")},
	{"PASSWORD_REQUIRED", i18n.M("rules.account.password_required")},
	{"ADDRESS_REJECTED", i18n.M("rules.account.address_rejected")},
	{"NOT_FOUND", i18n.M("rules.account.not_found")},
	{"MANAGED", ManagedNotice},
}

// Name is the name of the Kotlin enum value, e.g. "LIMIT_REACHED".
func (e AccountError) Name() string { return accountErrors[e].name }

// Msg is the error's text where no field stands next to it, translated where it is
// shown. Port of AccountRules.Error.full.
func (e AccountError) Msg() i18n.Msg { return accountErrors[e].msg }

// Error is the English text, so that an AccountError is an error.
func (e AccountError) Error() string { return i18n.For(i18n.English).Msg(e.Msg()) }

// String returns Name.
func (e AccountError) String() string { return e.Name() }

var (
	userRegex = regexp.MustCompile(`^[A-Za-z0-9\-_.!~*'()&=+$,?/%]+$`)
	hostLabel = `[A-Za-z0-9]([A-Za-z0-9\-]*[A-Za-z0-9])?`
	hostRegex = regexp.MustCompile(`^` + hostLabel + `(\.` + hostLabel + `)*\.?$`)
	ipv6Regex = regexp.MustCompile(`^\[[0-9A-Fa-f:.]+\]$`)
)

const wideColon = "\uFF1A"

// CanAddAccount reports whether one more account fits.
// Port of AccountRules.canAdd (app/src/main/java/com/sky/siplink/core/logic/AccountRules.kt).
func CanAddAccount(currentCount int) bool { return currentCount < MaxAccounts }

// IdentityKey is the key used to detect duplicates: the user part is case-sensitive, the
// host part is not.
// Port of AccountRules.identityKey (app/src/main/java/com/sky/siplink/core/logic/AccountRules.kt).
func IdentityKey(username, domain string) string { return username + "@" + lowercase(domain) }

func removeSipScheme(s string) string {
	return strings.TrimPrefix(strings.TrimPrefix(s, "sips:"), "sip:")
}

// NormalizeAccount trims every field, drops a leading `sip:` / `sips:` scheme, splits
// `user@domain` typed into the username field when the domain was left empty, and takes a
// port typed behind the domain to the port field.
// Port of AccountRules.normalize (app/src/main/java/com/sky/siplink/core/logic/AccountRules.kt).
func NormalizeAccount(draft AccountDraft) AccountDraft {
	username := removeSipScheme(trim(draft.Username))
	// A Chinese keyboard types the colon in front of a port in its wide form.
	domain := removeSipScheme(strings.ReplaceAll(trim(draft.Domain), wideColon, ":"))
	if at := strings.LastIndexByte(username, '@'); at > 0 {
		if domain == "" {
			domain = username[at+1:]
		}
		username = username[:at]
	}
	port := trim(draft.Port)
	if host, typedPort, ok := SplitHostPort(domain); ok {
		// The port written out with the domain is the one that is meant, whatever the port field holds.
		domain = host
		port = strconv.Itoa(typedPort)
	}
	out := draft
	out.Name = trim(draft.Name)
	out.Username = username
	out.Domain = domain
	out.Port = port
	out.DisplayName = trim(draft.DisplayName)
	out.AuthUser = trim(draft.AuthUser)
	out.Server = removeSipScheme(trim(draft.Server))
	out.BackupServer = removeSipScheme(trim(draft.BackupServer))
	out.DnsServer = trim(draft.DnsServer)
	return out
}

// ValidateAccount checks an already normalized draft.
//
// existingKeys are the identity keys of the accounts already configured; editingKey is the
// identity key of the account being edited, "" when adding a new one (Kotlin: null; an
// identity key always holds an '@', so "" never stands for an account); takenNames are the
// names of the other accounts.
// Port of AccountRules.validate (app/src/main/java/com/sky/siplink/core/logic/AccountRules.kt).
func ValidateAccount(draft AccountDraft, existingKeys []string, editingKey string, takenNames []string) []AccountError {
	errors := []AccountError{}
	if editingKey == "" && !CanAddAccount(len(existingKeys)) {
		errors = append(errors, AccountLimitReached)
	}
	if utf16Len(draft.Name) > MaxAccountNameLength {
		errors = append(errors, AccountNameTooLong)
	}
	if IsAccountNameTaken(draft.Name, takenNames) {
		errors = append(errors, AccountNameTaken)
	}
	switch {
	case draft.Username == "":
		errors = append(errors, AccountUsernameEmpty)
	case !userRegex.MatchString(draft.Username):
		errors = append(errors, AccountUsernameInvalid)
	}
	switch {
	case draft.Domain == "":
		errors = append(errors, AccountDomainEmpty)
	case !IsValidHostPort(draft.Domain):
		errors = append(errors, AccountDomainInvalid)
	}
	if draft.Port != "" && !IsValidPort(draft.Port) {
		errors = append(errors, AccountPortInvalid)
	}
	if draft.Server != "" && !IsValidHostPort(draft.Server) {
		errors = append(errors, AccountServerInvalid)
	}
	primary := draft.Server
	if primary == "" {
		primary = draft.Address()
	}
	switch {
	case draft.BackupServer == "":
	case !IsValidHostPort(draft.BackupServer):
		errors = append(errors, AccountBackupInvalid)
	case equalsIgnoreCase(draft.BackupServer, primary):
		errors = append(errors, AccountBackupSame)
	}
	if draft.DnsServer != "" && !IsValidDNSServer(draft.DnsServer) {
		errors = append(errors, AccountDNSInvalid)
	}
	if draft.Expires < MinExpires || draft.Expires > MaxExpires {
		errors = append(errors, AccountExpiresOutOfRange)
	}
	key := IdentityKey(draft.Username, draft.Address())
	if key != editingKey && contains(existingKeys, key) {
		errors = append(errors, AccountDuplicate)
	}
	return errors
}

// IsAccountNameTaken reports whether another account is already called name; upper and
// lower case do not make two names.
// Port of AccountRules.isNameTaken (app/src/main/java/com/sky/siplink/core/logic/AccountRules.kt).
func IsAccountNameTaken(name string, takenNames []string) bool {
	if name == "" {
		return false
	}
	for _, taken := range takenNames {
		if equalsIgnoreCase(taken, name) {
			return true
		}
	}
	return false
}

// IsValidHostPort accepts `host`, `host:port`, `1.2.3.4:5061`, `[::1]` and `[::1]:5061`.
// Port of AccountRules.isValidHostPort (app/src/main/java/com/sky/siplink/core/logic/AccountRules.kt).
func IsValidHostPort(value string) bool {
	if value == "" || hasWhitespace(value) {
		return false
	}
	var host, port string
	hasPort := false
	if strings.HasPrefix(value, "[") {
		end := strings.IndexByte(value, ']')
		if end < 0 {
			return false
		}
		host = value[:end+1]
		rest := value[end+1:]
		switch {
		case rest == "":
		case strings.HasPrefix(rest, ":"):
			port, hasPort = rest[1:], true
		default:
			return false
		}
		if !ipv6Regex.MatchString(host) {
			return false
		}
	} else {
		host = value
		if colon := strings.LastIndexByte(value, ':'); colon >= 0 {
			host, port, hasPort = value[:colon], value[colon+1:], true
		}
		if utf16Len(host) > 253 || !hostRegex.MatchString(host) {
			return false
		}
	}
	return !hasPort || IsValidPort(port)
}

// IsValidPort reports whether port is 1 to 5 ASCII digits making 1..65535.
// Port of AccountRules.isValidPort (app/src/main/java/com/sky/siplink/core/logic/AccountRules.kt).
func IsValidPort(port string) bool {
	if len(port) < 1 || len(port) > 5 {
		return false
	}
	for i := 0; i < len(port); i++ {
		if port[i] < '0' || port[i] > '9' {
			return false
		}
	}
	n, _ := strconv.Atoi(port)
	return n >= 1 && n <= 65535
}

// IdentityURI is the SIP address of the account.
// Port of AccountRules.identityUri (app/src/main/java/com/sky/siplink/core/logic/AccountRules.kt).
func IdentityURI(draft AccountDraft) string { return "sip:" + draft.Username + "@" + draft.Address() }

// ServerURI is the URI of the registrar / proxy the account starts on, the transport carried
// as a URI parameter.
// Port of AccountRules.serverUri and AccountRoute.uri without a pinned address
// (app/src/main/java/com/sky/siplink/core/logic/AccountRules.kt, AccountRoute.kt).
func ServerURI(draft AccountDraft) string {
	server := draft.Server
	if server == "" {
		server = draft.Address()
	}
	return "sip:" + server + ";transport=" + TransportParam(draft.Transport)
}

// TransportParam is the URI parameter value of a transport.
// Port of AccountRules.transportParam (app/src/main/java/com/sky/siplink/core/logic/AccountRules.kt).
func TransportParam(transport SipTransport) string {
	switch transport {
	case SipTransportTCP:
		return "tcp"
	case SipTransportTLS:
		return "tls"
	default:
		return "udp"
	}
}

// ParseTransport reads a transport name case-insensitively; empty is UDP. ok is false for
// anything else (Kotlin: null).
// Port of AccountRules.parseTransport (app/src/main/java/com/sky/siplink/core/logic/AccountRules.kt).
func ParseTransport(value string) (SipTransport, bool) {
	switch lowercase(trim(value)) {
	case "", "udp":
		return SipTransportUDP, true
	case "tcp":
		return SipTransportTCP, true
	case "tls":
		return SipTransportTLS, true
	}
	return "", false
}

// errorText holds the Kotlin name of an error enum value and its text (the app's
// Error.full: subject and message in one).
type errorText struct {
	name string
	msg  i18n.Msg
}

func contains[T comparable](list []T, value T) bool {
	for _, item := range list {
		if item == value {
			return true
		}
	}
	return false
}
