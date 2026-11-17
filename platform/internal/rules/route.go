package rules

import "strings"

// SplitHostPort returns host and port of `host[:port]`; an IPv6 address stays in its
// brackets. hasPort is false where Kotlin returns a null port: no colon after the address,
// or what follows the colon is no number (String.toIntOrNull).
// Port of AccountRoute.split (app/src/main/java/com/sky/siplink/core/logic/AccountRoute.kt).
func SplitHostPort(hostPort string) (host string, port int, hasPort bool) {
	colon := strings.LastIndexByte(hostPort, ':')
	// The colons inside the brackets of an IPv6 address are not the one in front of a port.
	if colon < 0 || colon < strings.LastIndexByte(hostPort, ']') {
		return hostPort, 0, false
	}
	port, hasPort = toIntOrNull(hostPort[colon+1:])
	return hostPort[:colon], port, hasPort
}

// IsAddress reports whether host is an address already (an IPv4 address or a bracketed
// IPv6 address), so that there is nothing to look up.
// Port of AccountRoute.isAddress (app/src/main/java/com/sky/siplink/core/logic/AccountRoute.kt).
func IsAddress(host string) bool {
	if strings.HasPrefix(host, "[") {
		return true
	}
	parts := strings.Split(host, ".")
	if len(parts) != 4 {
		return false
	}
	for _, part := range parts {
		n := utf16Len(part)
		if n < 1 || n > 3 {
			return false
		}
		value := 0
		for _, r := range part {
			// Char.isDigit, then String.toInt (Integer.parseInt), which takes digits of any script.
			if !isDigit(r) {
				return false
			}
			value = value*10 + digitValue(r)
		}
		if value > 255 {
			return false
		}
	}
	return true
}

// IsValidDNSServer reports whether value names a DNS server: an IPv4 address, with a port
// when it is not the usual one.
// Port of AccountRoute.isValidDnsServer (app/src/main/java/com/sky/siplink/core/logic/AccountRoute.kt).
func IsValidDNSServer(value string) bool {
	host, port, hasPort := SplitHostPort(value)
	return IsValidHostPort(value) && !strings.HasPrefix(host, "[") && IsAddress(host) &&
		(!hasPort || (port >= 1 && port <= 65535))
}
