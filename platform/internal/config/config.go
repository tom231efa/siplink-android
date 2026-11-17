// Package config reads the platform's settings from environment variables and an
// optional KEY=value file (SIPLINK_CONFIG_FILE). Environment variables win over the file.
package config

import (
	"bufio"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

// Hard upper bounds that come from the app and must not be raised.
const (
	MaxBooksLimit       = 500
	MaxBookEntriesLimit = 10000
	MaxLDAPResultsLimit = 1000
)

// TLS modes of the HTTP listener (SIPLINK_TLS).
const (
	TLSAuto = "auto"
	TLSOff  = "off"
)

// Config holds every setting of the platform.
type Config struct {
	PublicURL             string
	PublicHost            string // host part of PublicURL, without port
	HTTPListen            string
	TLSMode               string
	TLSCertFile           string
	TLSKeyFile            string
	TLSExtraNames         []string
	TrustedProxies        []netip.Prefix
	AllowHTTPProvisioning bool
	DataDir               string
	KeyFile               string

	LDAPListen       string
	LDAPSListen      string
	LDAPPublicHost   string
	LDAPPublicPort   int
	LDAPSuffix       string
	LDAPTLSCertFile  string
	LDAPTLSKeyFile   string
	LDAPRequireTLS   bool
	LDAPMaxResults   int
	LDAPCacheEntries int

	MaxBooks       int
	MaxBookEntries int

	EnrollDays      int
	RevokedKeepDays int
	BackupTime      string
	BackupKeep      int
	LogLevel        string
}

// Lookup returns the value of a variable and whether it is set.
type Lookup func(key string) (string, bool)

// Load reads the configuration from the process environment.
func Load() (*Config, error) {
	return LoadFrom(os.LookupEnv)
}

// LoadFrom reads the configuration through env, merged with SIPLINK_CONFIG_FILE if set.
func LoadFrom(env Lookup) (*Config, error) {
	file := map[string]string{}
	if path, ok := env("SIPLINK_CONFIG_FILE"); ok && path != "" {
		var err error
		if file, err = readFile(path); err != nil {
			return nil, err
		}
	}
	r := &reader{env: env, file: file}
	c := &Config{
		PublicURL:             r.str("SIPLINK_PUBLIC_URL", ""),
		HTTPListen:            r.str("SIPLINK_HTTP_LISTEN", ":8080"),
		TLSMode:               strings.ToLower(r.str("SIPLINK_TLS", TLSAuto)),
		TLSCertFile:           r.str("SIPLINK_TLS_CERT_FILE", ""),
		TLSKeyFile:            r.str("SIPLINK_TLS_KEY_FILE", ""),
		TLSExtraNames:         r.list("SIPLINK_TLS_EXTRA_NAMES"),
		AllowHTTPProvisioning: r.boolean("SIPLINK_ALLOW_HTTP_PROVISIONING", false),
		DataDir:               r.str("SIPLINK_DATA_DIR", "/data"),
		LDAPListen:            r.str("SIPLINK_LDAP_LISTEN", ":1389"),
		LDAPSListen:           r.str("SIPLINK_LDAPS_LISTEN", ""),
		LDAPPublicHost:        r.str("SIPLINK_LDAP_PUBLIC_HOST", ""),
		LDAPPublicPort:        r.integer("SIPLINK_LDAP_PUBLIC_PORT", 389),
		LDAPSuffix:            r.str("SIPLINK_LDAP_SUFFIX", "dc=siplink"),
		LDAPTLSCertFile:       r.str("SIPLINK_LDAP_TLS_CERT_FILE", ""),
		LDAPTLSKeyFile:        r.str("SIPLINK_LDAP_TLS_KEY_FILE", ""),
		LDAPRequireTLS:        r.boolean("SIPLINK_LDAP_REQUIRE_TLS", true),
		LDAPMaxResults:        r.integer("SIPLINK_LDAP_MAX_RESULTS", MaxLDAPResultsLimit),
		LDAPCacheEntries:      r.integer("SIPLINK_LDAP_CACHE_ENTRIES", 1000000),
		MaxBooks:              r.integer("SIPLINK_MAX_BOOKS", MaxBooksLimit),
		MaxBookEntries:        r.integer("SIPLINK_MAX_BOOK_ENTRIES", MaxBookEntriesLimit),
		EnrollDays:            r.integer("SIPLINK_ENROLL_DAYS", 30),
		RevokedKeepDays:       r.integer("SIPLINK_REVOKED_KEEP_DAYS", 365),
		BackupTime:            r.str("SIPLINK_BACKUP_TIME", "03:30"),
		BackupKeep:            r.integer("SIPLINK_BACKUP_KEEP", 14),
		LogLevel:              strings.ToLower(r.str("SIPLINK_LOG_LEVEL", "info")),
	}
	for _, cidr := range r.list("SIPLINK_TRUSTED_PROXIES") {
		p, err := parsePrefix(cidr)
		if err != nil {
			r.errs = append(r.errs, fmt.Errorf("SIPLINK_TRUSTED_PROXIES: %q is not an address or CIDR", cidr))
			continue
		}
		c.TrustedProxies = append(c.TrustedProxies, p)
	}
	c.KeyFile = r.str("SIPLINK_KEY_FILE", "")
	if c.KeyFile == "" {
		if _, err := os.Stat("/run/secrets/siplink-key"); err == nil {
			c.KeyFile = "/run/secrets/siplink-key"
		} else {
			c.KeyFile = filepath.Join(c.DataDir, "key")
		}
	}
	if err := c.validate(); err != nil {
		r.errs = append(r.errs, err)
	}
	return c, errors.Join(r.errs...)
}

func (c *Config) validate() error {
	var errs []error
	bad := func(format string, a ...any) { errs = append(errs, fmt.Errorf(format, a...)) }
	if c.PublicURL == "" {
		bad("SIPLINK_PUBLIC_URL is required (for example https://platform.example.com)")
	} else if u, err := url.Parse(c.PublicURL); err != nil || u.Host == "" || (u.Scheme != "https" && u.Scheme != "http") || (u.Path != "" && u.Path != "/") || u.RawQuery != "" {
		bad("SIPLINK_PUBLIC_URL must be an http(s) URL without path, got %q", c.PublicURL)
	} else {
		c.PublicURL = strings.TrimSuffix(c.PublicURL, "/")
		c.PublicHost = u.Hostname()
	}
	if c.LDAPPublicHost == "" {
		c.LDAPPublicHost = c.PublicHost
	}
	if c.TLSMode != TLSAuto && c.TLSMode != TLSOff {
		bad("SIPLINK_TLS must be auto or off, got %q", c.TLSMode)
	}
	if (c.TLSCertFile == "") != (c.TLSKeyFile == "") {
		bad("SIPLINK_TLS_CERT_FILE and SIPLINK_TLS_KEY_FILE must be set together")
	}
	if (c.LDAPTLSCertFile == "") != (c.LDAPTLSKeyFile == "") {
		bad("SIPLINK_LDAP_TLS_CERT_FILE and SIPLINK_LDAP_TLS_KEY_FILE must be set together")
	}
	if c.LDAPPublicPort < 1 || c.LDAPPublicPort > 65535 {
		bad("SIPLINK_LDAP_PUBLIC_PORT must be 1-65535")
	}
	if c.LDAPMaxResults < 1 || c.LDAPMaxResults > MaxLDAPResultsLimit {
		bad("SIPLINK_LDAP_MAX_RESULTS must be 1-%d", MaxLDAPResultsLimit)
	}
	if c.LDAPCacheEntries < 1 {
		bad("SIPLINK_LDAP_CACHE_ENTRIES must be positive")
	}
	if c.MaxBooks < 1 || c.MaxBooks > MaxBooksLimit {
		bad("SIPLINK_MAX_BOOKS must be 1-%d", MaxBooksLimit)
	}
	if c.MaxBookEntries < 1 || c.MaxBookEntries > MaxBookEntriesLimit {
		bad("SIPLINK_MAX_BOOK_ENTRIES must be 1-%d", MaxBookEntriesLimit)
	}
	if c.EnrollDays < 1 || c.RevokedKeepDays < 1 || c.BackupKeep < 1 {
		bad("SIPLINK_ENROLL_DAYS, SIPLINK_REVOKED_KEEP_DAYS and SIPLINK_BACKUP_KEEP must be positive")
	}
	if _, err := time.Parse("15:04", c.BackupTime); err != nil {
		bad("SIPLINK_BACKUP_TIME must be HH:MM, got %q", c.BackupTime)
	}
	switch c.LogLevel {
	case "debug", "info", "warn", "error":
	default:
		bad("SIPLINK_LOG_LEVEL must be debug, info, warn or error")
	}
	if c.LDAPSuffix == "" || !strings.Contains(c.LDAPSuffix, "=") {
		bad("SIPLINK_LDAP_SUFFIX must be a DN such as dc=siplink")
	}
	return errors.Join(errs...)
}

// TLSFromFiles reports whether the HTTP listener uses operator-supplied certificate files.
func (c *Config) TLSFromFiles() bool { return c.TLSCertFile != "" }

// HTTPSListener reports whether the HTTP listener speaks TLS itself.
func (c *Config) HTTPSListener() bool { return c.TLSMode == TLSAuto || c.TLSFromFiles() }

// LDAPCertFiles returns the operator files for LDAP TLS, or empty strings for the built-in CA.
func (c *Config) LDAPCertFiles() (cert, key string) {
	if c.LDAPTLSCertFile != "" {
		return c.LDAPTLSCertFile, c.LDAPTLSKeyFile
	}
	return c.TLSCertFile, c.TLSKeyFile
}

// CertNames returns the names a certificate of the built-in CA must carry, without duplicates.
func (c *Config) CertNames() []string {
	names := []string{c.PublicHost, c.LDAPPublicHost}
	names = append(names, c.TLSExtraNames...)
	names = append(names, "localhost", "127.0.0.1", "::1")
	seen := map[string]bool{}
	var out []string
	for _, n := range names {
		n = strings.TrimSpace(n)
		key := strings.ToLower(n)
		if n == "" || seen[key] {
			continue
		}
		seen[key] = true
		out = append(out, n)
	}
	return out
}

// IsTrustedProxy reports whether addr is inside SIPLINK_TRUSTED_PROXIES.
func (c *Config) IsTrustedProxy(addr netip.Addr) bool {
	addr = addr.Unmap()
	for _, p := range c.TrustedProxies {
		if p.Contains(addr) {
			return true
		}
	}
	return false
}

func parsePrefix(s string) (netip.Prefix, error) {
	if strings.Contains(s, "/") {
		p, err := netip.ParsePrefix(s)
		return p.Masked(), err
	}
	a, err := netip.ParseAddr(s)
	if err != nil {
		return netip.Prefix{}, err
	}
	return netip.PrefixFrom(a.Unmap(), a.Unmap().BitLen()), nil
}

// IsAddress reports whether host is a literal IP address.
func IsAddress(host string) bool { return net.ParseIP(host) != nil }

type reader struct {
	env  Lookup
	file map[string]string
	errs []error
}

func (r *reader) raw(key string) (string, bool) {
	if v, ok := r.env(key); ok {
		return strings.TrimSpace(v), true
	}
	v, ok := r.file[key]
	return v, ok
}

func (r *reader) str(key, def string) string {
	if v, ok := r.raw(key); ok && v != "" {
		return v
	}
	return def
}

func (r *reader) list(key string) []string {
	var out []string
	for _, part := range strings.Split(r.str(key, ""), ",") {
		if part = strings.TrimSpace(part); part != "" {
			out = append(out, part)
		}
	}
	return out
}

func (r *reader) integer(key string, def int) int {
	v := r.str(key, "")
	if v == "" {
		return def
	}
	n, err := strconv.Atoi(v)
	if err != nil {
		r.errs = append(r.errs, fmt.Errorf("%s must be a whole number, got %q", key, v))
		return def
	}
	return n
}

func (r *reader) boolean(key string, def bool) bool {
	v := r.str(key, "")
	if v == "" {
		return def
	}
	b, err := strconv.ParseBool(v)
	if err != nil {
		r.errs = append(r.errs, fmt.Errorf("%s must be true or false, got %q", key, v))
		return def
	}
	return b
}

func readFile(path string) (map[string]string, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, fmt.Errorf("SIPLINK_CONFIG_FILE: %w", err)
	}
	defer f.Close()
	out := map[string]string{}
	sc := bufio.NewScanner(f)
	line := 0
	for sc.Scan() {
		line++
		text := strings.TrimSpace(sc.Text())
		if text == "" || strings.HasPrefix(text, "#") {
			continue
		}
		key, value, ok := strings.Cut(text, "=")
		if !ok {
			return nil, fmt.Errorf("SIPLINK_CONFIG_FILE line %d: expected KEY=value", line)
		}
		value = strings.TrimSpace(value)
		if len(value) >= 2 && (value[0] == '"' || value[0] == '\'') && value[len(value)-1] == value[0] {
			value = value[1 : len(value)-1]
		}
		out[strings.TrimSpace(key)] = value
	}
	return out, sc.Err()
}
