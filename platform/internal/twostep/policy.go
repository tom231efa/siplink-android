package twostep

import (
	"net"
	"net/url"
	"strings"
)

// MustSetUp reports whether an administrator has to switch a method on before using
// the platform: a region administrator without any method while the policy
// "区域管理员必须启用两步验证" is on. Super administrators are never bound.
func MustSetUp(regionAdmin, required bool, methods int) bool {
	return regionAdmin && required && methods == 0
}

// MayRemove reports whether a method may be switched off (or a passkey deleted) when
// methodsAfter would remain: under the policy a region administrator keeps at least one.
func MayRemove(regionAdmin, required bool, methodsAfter int) bool {
	return !MustSetUp(regionAdmin, required, methodsAfter)
}

// PasskeysUsable reports whether browsers allow WebAuthn at the public URL: it must be
// a secure context (https, or http to localhost or *.localhost) and its host a domain
// name, since a relying party ID cannot be an IP address.
func PasskeysUsable(publicURL string) bool {
	u, err := url.Parse(publicURL)
	if err != nil {
		return false
	}
	host := strings.ToLower(u.Hostname())
	if host == "" || net.ParseIP(host) != nil {
		return false
	}
	return u.Scheme == "https" || u.Scheme == "http" && (host == "localhost" || strings.HasSuffix(host, ".localhost"))
}

// Origin is the browser's origin of the public URL: scheme and host in lower case, with
// the port only when it is not the scheme's default.
func Origin(publicURL string) string {
	u, err := url.Parse(publicURL)
	if err != nil {
		return publicURL
	}
	host := strings.ToLower(u.Host)
	if p := u.Port(); u.Scheme == "https" && p == "443" || u.Scheme == "http" && p == "80" {
		host = strings.ToLower(u.Hostname())
		if strings.Contains(host, ":") {
			host = "[" + host + "]"
		}
	}
	return strings.ToLower(u.Scheme) + "://" + host
}
