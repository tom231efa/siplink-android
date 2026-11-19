package rules

import (
	"regexp"
	"siplink/internal/i18n"
	"strconv"
	"strings"
)

// LdapSecurity is how the connection to an LDAP server is protected; its values are the
// Kotlin enum names.
// Port of enum LdapSecurity (android/app/src/main/java/com/sky/siplink/core/model/Models.kt).
type LdapSecurity string

// The values of LdapSecurity, in the order of the Kotlin enum.
const (
	LdapSecurityNone     LdapSecurity = "NONE"
	LdapSecurityStartTLS LdapSecurity = "START_TLS"
)

// LdapSecurities lists every LdapSecurity in the order of the Kotlin enum.
var LdapSecurities = []LdapSecurity{LdapSecurityNone, LdapSecurityStartTLS}

// Valid reports whether s is one of LdapSecurities.
func (s LdapSecurity) Valid() bool { return contains(LdapSecurities, s) }

// Defaults of LdapDirectory (companion object of LdapDirectory in
// android/app/src/main/java/com/sky/siplink/core/model/Models.kt): DEFAULT_PORT,
// DEFAULT_NAME_ATTRIBUTES, DEFAULT_NUMBER_ATTRIBUTES.
const (
	LdapDefaultPort             = 389
	LdapDefaultNameAttributes   = "cn sn"
	LdapDefaultNumberAttributes = "telephoneNumber mobile"
)

// LdapDirectory is one LDAP directory.
// Port of data class LdapDirectory (android/app/src/main/java/com/sky/siplink/core/model/Models.kt);
// DefaultLdapDirectory gives its defaults.
type LdapDirectory struct {
	ID               string       `json:"id"`
	Name             string       `json:"name"`
	Enabled          bool         `json:"enabled"`
	Host             string       `json:"host"`
	Port             int          `json:"port"`
	Security         LdapSecurity `json:"security"`
	BindDn           string       `json:"bindDn"`
	Password         string       `json:"password"`
	BaseDn           string       `json:"baseDn"`
	Line             string       `json:"line"`
	LookupIncoming   bool         `json:"lookupIncoming"`
	LookupOutgoing   bool         `json:"lookupOutgoing"`
	MaxResults       int          `json:"maxResults"`
	NameAttributes   string       `json:"nameAttributes"`
	NumberAttributes string       `json:"numberAttributes"`
	NameFilter       string       `json:"nameFilter"`
	NumberFilter     string       `json:"numberFilter"`
}

// DefaultLdapDirectory returns LdapDirectory() with the Kotlin default values.
func DefaultLdapDirectory() LdapDirectory {
	return LdapDirectory{
		Enabled:          true,
		Port:             LdapDefaultPort,
		Security:         LdapSecurityNone,
		LookupIncoming:   true,
		LookupOutgoing:   true,
		MaxResults:       50,
		NameAttributes:   LdapDefaultNameAttributes,
		NumberAttributes: LdapDefaultNumberAttributes,
	}
}

// Limits of LdapRules (android/app/src/main/java/com/sky/siplink/core/logic/LdapRules.kt):
// MAX_DIRECTORIES, MAX_NAME_LENGTH, MAX_RESULTS, PLACEHOLDER.
const (
	MaxDirectories         = 50
	MaxDirectoryNameLength = 40
	MaxResults             = 1000
	Placeholder            = '%'
)

// LdapError is what is wrong with a directory.
// Port of enum LdapRules.Error (android/app/src/main/java/com/sky/siplink/core/logic/LdapRules.kt).
type LdapError int

// The values of LdapError, in the order of the Kotlin enum; Name gives the Kotlin name.
const (
	LdapLimitReached LdapError = iota
	LdapNameTaken
	LdapNameTooLong
	LdapHostEmpty
	LdapHostInvalid
	LdapHostLdaps
	LdapStartTLSNeedsHostName
	LdapPortOutOfRange
	LdapPasswordWithoutUser
	LdapBaseDnEmpty
	LdapMaxResultsOutOfRange
	LdapNameAttributesEmpty
	LdapNumberAttributesEmpty
	LdapNameFilterNoPlaceholder
	LdapNumberFilterNoPlaceholder
	LdapManaged
)

var ldapErrors = []errorText{
	{"LIMIT_REACHED", i18n.M("rules.ldap.limit_reached", "max", MaxDirectories)},
	{"NAME_TAKEN", i18n.M("rules.ldap.name_taken")},
	{"NAME_TOO_LONG", i18n.M("rules.ldap.name_too_long", "max", MaxDirectoryNameLength)},
	{"HOST_EMPTY", i18n.M("rules.ldap.host_empty")},
	{"HOST_INVALID", i18n.M("rules.ldap.host_invalid")},
	{"HOST_LDAPS", i18n.M("rules.ldap.host_ldaps")},
	{"STARTTLS_NEEDS_HOST_NAME", i18n.M("rules.ldap.starttls_needs_host_name")},
	{"PORT_OUT_OF_RANGE", i18n.M("rules.ldap.port_out_of_range")},
	{"PASSWORD_WITHOUT_USER", i18n.M("rules.ldap.password_without_user")},
	{"BASE_DN_EMPTY", i18n.M("rules.ldap.base_dn_empty")},
	{"MAX_RESULTS_OUT_OF_RANGE", i18n.M("rules.ldap.max_results_out_of_range", "max", MaxResults)},
	{"NAME_ATTRIBUTES_EMPTY", i18n.M("rules.ldap.name_attributes_empty")},
	{"NUMBER_ATTRIBUTES_EMPTY", i18n.M("rules.ldap.number_attributes_empty")},
	{"NAME_FILTER_NO_PLACEHOLDER", i18n.M("rules.ldap.name_filter_no_placeholder")},
	{"NUMBER_FILTER_NO_PLACEHOLDER", i18n.M("rules.ldap.number_filter_no_placeholder")},
	{"MANAGED", ManagedNotice},
}

// Name is the name of the Kotlin enum value, e.g. "HOST_LDAPS".
func (e LdapError) Name() string { return ldapErrors[e].name }

// Msg is the error's text where no field stands next to it, translated where it is
// shown. Port of LdapRules.Error.full.
func (e LdapError) Msg() i18n.Msg { return ldapErrors[e].msg }

// Error is the English text, so that an LdapError is an error.
func (e LdapError) Error() string { return i18n.For(i18n.English).Msg(e.Msg()) }

// String returns Name.
func (e LdapError) String() string { return e.Name() }

var ldapSchemes = []string{"ldap://", "ldaps://"}

// Java's \s is [ \t\n\x0B\f\r]; Go's \s lacks \x0B, so the class is written out.
var ldapSeparators = regexp.MustCompile(`[ \t\n\x0B\f\r,]+`)

// ldapScheme returns the index in ldapSchemes of the scheme host starts with (ignoring
// case), the byte length it covers, or -1.
func ldapScheme(host string) (int, int) {
	for i, scheme := range ldapSchemes {
		if n, ok := prefixIgnoreCase(host, scheme); ok {
			return i, n
		}
	}
	return -1, 0
}

// NormalizeLdap trims every field. A server typed as an address (`ldap://host:389`) is
// taken apart into host and port; `ldaps://` stays, for ValidateLdap to refuse.
// Port of LdapRules.normalize (android/app/src/main/java/com/sky/siplink/core/logic/LdapRules.kt).
func NormalizeLdap(directory LdapDirectory) LdapDirectory {
	host := strings.TrimRight(trim(directory.Host), "/")
	port := directory.Port
	scheme, length := ldapScheme(host)
	if scheme == 0 {
		host = host[length:]
	}
	if scheme != 1 {
		if name, typedPort, ok := SplitHostPort(host); ok && name != "" {
			host = name
			port = typedPort
		}
	}
	out := directory
	out.Name = trim(directory.Name)
	out.Host = host
	out.Port = port
	out.BindDn = trim(directory.BindDn)
	out.BaseDn = trim(directory.BaseDn)
	out.NameAttributes = strings.Join(LdapAttributes(directory.NameAttributes), " ")
	out.NumberAttributes = strings.Join(distinct(LdapAttributes(directory.NumberAttributes)), " ")
	out.NameFilter = trim(directory.NameFilter)
	out.NumberFilter = trim(directory.NumberFilter)
	return out
}

// ValidateLdap checks an already normalized directory. otherNames are the names of the
// other directories; isNew says it is about to be added rather than replacing one.
// Port of LdapRules.validate (android/app/src/main/java/com/sky/siplink/core/logic/LdapRules.kt).
func ValidateLdap(directory LdapDirectory, otherNames []string, isNew bool) []LdapError {
	errors := []LdapError{}
	if isNew && len(otherNames) >= MaxDirectories {
		errors = append(errors, LdapLimitReached)
	}
	switch {
	case utf16Len(directory.Name) > MaxDirectoryNameLength:
		errors = append(errors, LdapNameTooLong)
	case IsLdapNameTaken(directory.Name, otherNames):
		errors = append(errors, LdapNameTaken)
	}
	host := directory.Host
	_, _, hostHasPort := SplitHostPort(host)
	_, isLdaps := prefixIgnoreCase(host, ldapSchemes[1])
	switch {
	case host == "":
		errors = append(errors, LdapHostEmpty)
	case isLdaps:
		errors = append(errors, LdapHostLdaps)
	case !IsValidHostPort(host) || hostHasPort:
		errors = append(errors, LdapHostInvalid)
	// The LDAP library of the SIP stack refuses the TLS handshake without a host name to
	// check the certificate against.
	case directory.Security == LdapSecurityStartTLS && IsAddress(host):
		errors = append(errors, LdapStartTLSNeedsHostName)
	}
	if directory.Port < 1 || directory.Port > 65535 {
		errors = append(errors, LdapPortOutOfRange)
	}
	if directory.BindDn == "" && directory.Password != "" {
		errors = append(errors, LdapPasswordWithoutUser)
	}
	if directory.BaseDn == "" {
		errors = append(errors, LdapBaseDnEmpty)
	}
	if directory.MaxResults < 1 || directory.MaxResults > MaxResults {
		errors = append(errors, LdapMaxResultsOutOfRange)
	}
	if directory.NameAttributes == "" {
		errors = append(errors, LdapNameAttributesEmpty)
	}
	if directory.NumberAttributes == "" {
		errors = append(errors, LdapNumberAttributesEmpty)
	}
	// A filter left empty is made from the attributes; one that is written has to say where the search goes.
	if directory.NameFilter != "" && !strings.ContainsRune(directory.NameFilter, Placeholder) {
		errors = append(errors, LdapNameFilterNoPlaceholder)
	}
	if directory.NumberFilter != "" && !strings.ContainsRune(directory.NumberFilter, Placeholder) {
		errors = append(errors, LdapNumberFilterNoPlaceholder)
	}
	return errors
}

// IsLdapNameTaken reports whether another directory goes by name, ignoring case; without a
// name directories go by their server, and several may do so.
// Port of LdapRules.isNameTaken (android/app/src/main/java/com/sky/siplink/core/logic/LdapRules.kt).
func IsLdapNameTaken(name string, otherNames []string) bool {
	return IsAccountNameTaken(name, otherNames)
}

// LdapServerText is the server as it is typed: the host, with the port behind it when that
// is not the usual one.
// Port of LdapRules.serverText (android/app/src/main/java/com/sky/siplink/core/logic/LdapRules.kt).
func LdapServerText(directory LdapDirectory) string {
	if directory.Port == LdapDefaultPort {
		return directory.Host
	}
	return directory.Host + ":" + strconv.Itoa(directory.Port)
}

// LdapServerURL is the server as the SIP core wants it.
// Port of LdapRules.serverUrl (android/app/src/main/java/com/sky/siplink/core/logic/LdapRules.kt).
func LdapServerURL(directory LdapDirectory) string {
	host := directory.Host
	if strings.Contains(host, ":") && !strings.HasPrefix(host, "[") {
		host = "[" + host + "]"
	}
	return "ldap://" + host + ":" + strconv.Itoa(directory.Port)
}

// LdapCoreFilter turns a filter with the placeholder into one for the SIP core:
// `(cn=%)` becomes `(cn=%s*)`. A filter already written for the core (`%s`) is kept.
// Port of LdapRules.coreFilter (android/app/src/main/java/com/sky/siplink/core/logic/LdapRules.kt).
func LdapCoreFilter(filter string) string {
	if strings.Contains(filter, "%s") {
		return filter
	}
	var out strings.Builder
	for i := 0; i < len(filter); i++ {
		c := filter[i]
		if c != Placeholder {
			out.WriteByte(c)
			continue
		}
		out.WriteString("%s")
		// "%*" already asks for "begins with".
		if i+1 >= len(filter) || filter[i+1] != '*' {
			out.WriteByte('*')
		}
	}
	return out.String()
}

// LdapFromCoreFilter is what LdapCoreFilter undoes.
// Port of LdapRules.fromCoreFilter (android/app/src/main/java/com/sky/siplink/core/logic/LdapRules.kt).
func LdapFromCoreFilter(filter string) string {
	return strings.ReplaceAll(strings.ReplaceAll(filter, "%s*", "%"), "%s", "%")
}

// LdapAttributes returns the attribute names of a list typed with blanks or commas.
// Port of LdapRules.attributes (android/app/src/main/java/com/sky/siplink/core/logic/LdapRules.kt).
func LdapAttributes(list string) []string {
	out := []string{}
	for _, part := range ldapSeparators.Split(list, -1) {
		if part != "" {
			out = append(out, part)
		}
	}
	return out
}

// LdapNameFilter is the filter a name is searched with.
// Port of LdapRules.nameFilter (android/app/src/main/java/com/sky/siplink/core/logic/LdapRules.kt).
func LdapNameFilter(directory LdapDirectory) string {
	if directory.NameFilter != "" {
		return directory.NameFilter
	}
	return LdapFilterOver(LdapAttributes(directory.NameAttributes))
}

// LdapNumberFilter is the filter a number is searched with.
// Port of LdapRules.numberFilter (android/app/src/main/java/com/sky/siplink/core/logic/LdapRules.kt).
func LdapNumberFilter(directory LdapDirectory) string {
	if directory.NumberFilter != "" {
		return directory.NumberFilter
	}
	return LdapFilterOver(LdapAttributes(directory.NumberAttributes))
}

// LdapFilterOver is a filter for entries of which one of attributes contains what is
// searched; a name put together from several attributes (`sn+givenName`) is searched in each.
// Port of LdapRules.filterOver (android/app/src/main/java/com/sky/siplink/core/logic/LdapRules.kt).
func LdapFilterOver(attributes []string) string {
	var names []string
	for _, attribute := range attributes {
		for _, part := range strings.Split(attribute, "+") {
			if part != "" {
				names = append(names, part)
			}
		}
	}
	terms := []string{}
	for _, name := range distinct(names) {
		terms = append(terms, "("+name+"=*"+string(Placeholder)+")")
	}
	if len(terms) == 1 {
		return terms[0]
	}
	return "(|" + strings.Join(terms, "") + ")"
}

func distinct(list []string) []string {
	out := []string{}
	seen := map[string]bool{}
	for _, item := range list {
		if !seen[item] {
			seen[item] = true
			out = append(out, item)
		}
	}
	return out
}
