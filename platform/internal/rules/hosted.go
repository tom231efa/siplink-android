package rules

import (
	"net"

	"siplink/internal/i18n"
)

// HostedNeedsName is shown where a hosted address book cannot be offered because the
// platform's LDAP host is a literal address while TLS is required.
var HostedNeedsName = i18n.M("rules.hosted_needs_name")

// HostedSecurity chooses the security of a hosted address book in a provisioning
// document. This is platform policy (decided 2026-11-16), kept in this one place:
//   - the platform's LDAP host is a name: START_TLS;
//   - it is a literal address and TLS is required: no hosted directory can be offered
//     (ok is false), because the app refuses StartTLS to an address
//     (LdapRules.Error.STARTTLS_NEEDS_HOST_NAME) and its LDAP library cannot verify a
//     certificate without a host name;
//   - it is a literal address and TLS is not required: NONE (unencrypted).
func HostedSecurity(publicHost string, requireTLS bool) (security LdapSecurity, ok bool) {
	// net.ParseIP also catches an IPv6 address written without brackets.
	if !IsAddress(publicHost) && net.ParseIP(publicHost) == nil {
		return LdapSecurityStartTLS, true
	}
	if requireTLS {
		return LdapSecurityNone, false
	}
	return LdapSecurityNone, true
}
