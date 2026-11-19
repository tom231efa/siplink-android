package pki

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"encoding/pem"
	"errors"
	"fmt"
	"log/slog"
	"strconv"
	"sync"
	"time"

	"siplink/internal/config"
	"siplink/internal/i18n"
	"siplink/internal/secret"
	"siplink/internal/store"
)

// Source is where a listener's certificate comes from (§6.7).
type Source string

// Sources in the order of precedence: files from the environment, the uploaded
// certificate, the built-in CA. Off: the listener does not speak TLS itself.
const (
	SourceFiles   Source = "files"
	SourceOwn     Source = "own"
	SourceBuiltin Source = "builtin"
	SourceOff     Source = "off"
)

const nameOwn = "own"

// ErrPaused is returned by Issue while no listener uses the built-in certificate.
var ErrPaused = i18n.E("pki.paused")

// Options are the parts of the configuration that decide which certificate a listener uses.
type Options struct {
	HTTPSHost string // host of SIPLINK_PUBLIC_URL
	LDAPHost  string // SIPLINK_LDAP_PUBLIC_HOST
	HTTPSOff  bool   // SIPLINK_TLS=off without files: a reverse proxy speaks HTTPS
	LDAPOff   bool   // neither LDAP listener is configured
	// Files from the environment; LDAP ones already fall back to the HTTPS ones.
	HTTPSCertFile, HTTPSKeyFile string
	LDAPCertFile, LDAPKeyFile   string
	Names                       []string // names of the built-in server certificate
	// Roots is the system root store, used to tell a public chain from a private one;
	// nil when the machine has none.
	Roots *x509.CertPool
}

// OptionsFrom derives the options from the configuration and the system root store.
func OptionsFrom(cfg *config.Config) Options {
	o := Options{HTTPSHost: cfg.PublicHost, LDAPHost: cfg.LDAPPublicHost, HTTPSOff: !cfg.HTTPSListener(),
		LDAPOff: cfg.LDAPListen == "" && cfg.LDAPSListen == "", Names: cfg.CertNames()}
	if cfg.TLSFromFiles() {
		o.HTTPSCertFile, o.HTTPSKeyFile = cfg.TLSCertFile, cfg.TLSKeyFile
	}
	o.LDAPCertFile, o.LDAPKeyFile = cfg.LDAPCertFiles()
	if roots, err := x509.SystemCertPool(); err == nil {
		o.Roots = roots
	}
	return o
}

// Certs chooses the certificate of every listener: files from the environment, else the
// operator's uploaded certificate when it names the listener's public host, else the
// built-in CA's. It pauses the built-in CA's issuing while no listener needs it.
type Certs struct {
	ca  *Authority
	opt Options

	httpsFiles, ldapFiles *Own // fixed for the life of the process

	mu      sync.RWMutex
	own     *Own   // uploaded, or nil
	ownText string // stored PEM of own, to notice a change made by the CLI
}

// OpenCerts loads the files of the environment and the uploaded certificate.
func OpenCerts(ctx context.Context, ca *Authority, opt Options) (*Certs, error) {
	c := &Certs{ca: ca, opt: opt}
	var err error
	if opt.HTTPSCertFile != "" {
		if c.httpsFiles, err = ownFromPair(opt.HTTPSCertFile, opt.HTTPSKeyFile); err != nil {
			return nil, err
		}
		c.httpsFiles.classify(opt.Roots, ca.now())
	}
	if opt.LDAPCertFile != "" {
		if opt.LDAPCertFile == opt.HTTPSCertFile && c.httpsFiles != nil {
			c.ldapFiles = c.httpsFiles
		} else if c.ldapFiles, err = ownFromPair(opt.LDAPCertFile, opt.LDAPKeyFile); err != nil {
			return nil, err
		} else {
			c.ldapFiles.classify(opt.Roots, ca.now())
		}
	}
	if err := c.loadOwn(ctx); err != nil {
		return nil, err
	}
	return c, nil
}

// CA is the built-in certificate authority.
func (c *Certs) CA() *Authority { return c.ca }

// Options returns the options the certificates were opened with.
func (c *Certs) Options() Options { return c.opt }

// Uploaded returns the stored certificate of the operator's, or nil.
func (c *Certs) Uploaded() *Own {
	c.mu.RLock()
	defer c.mu.RUnlock()
	return c.own
}

// Files returns the certificates from the environment's files (nil where none).
func (c *Certs) Files() (https, ldap *Own) { return c.httpsFiles, c.ldapFiles }

func (c *Certs) loadOwn(ctx context.Context) error {
	m, err := c.ca.st.TLSMaterial(ctx, nameOwn)
	if errors.Is(err, store.ErrNotFound) {
		c.setOwn(nil, "")
		return nil
	}
	if err != nil {
		return err
	}
	c.mu.RLock()
	same := c.own != nil && c.ownText == m.CertPEM
	c.mu.RUnlock()
	if same {
		return nil
	}
	o, err := c.decodeOwn(m)
	if err != nil {
		return fmt.Errorf("pki: stored certificate of the operator: %w", err)
	}
	c.setOwn(o, m.CertPEM)
	return nil
}

func (c *Certs) setOwn(o *Own, text string) {
	c.mu.Lock()
	c.own, c.ownText = o, text
	c.mu.Unlock()
}

func (c *Certs) decodeOwn(m *store.TLSMaterial) (*Own, error) {
	pkcs8, err := c.ca.box.Open(m.KeyEnc, secret.AAD("tls_material", m.Name, "key_enc"))
	if err != nil {
		return nil, err
	}
	o, err := ParseOwn([]byte(m.CertPEM), pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: pkcs8}))
	if err != nil {
		return nil, err
	}
	o.classify(c.opt.Roots, c.ca.now())
	return o, nil
}

// sources decides both listeners for a given uploaded certificate (nil = none).
func (c *Certs) sources(own *Own) (https, ldap Source) {
	https, ldap = SourceBuiltin, SourceBuiltin
	switch {
	case c.httpsFiles != nil:
		https = SourceFiles
	case c.opt.HTTPSOff:
		https = SourceOff
	case own != nil && own.Covers(c.opt.HTTPSHost):
		https = SourceOwn
	}
	switch {
	case c.opt.LDAPOff:
		ldap = SourceOff
	case c.ldapFiles != nil:
		ldap = SourceFiles
	case own != nil && own.Covers(c.opt.LDAPHost):
		ldap = SourceOwn
	}
	return https, ldap
}

// Sources reports where the HTTPS and the LDAP listener take their certificate from now.
func (c *Certs) Sources() (https, ldap Source) {
	return c.sources(c.Uploaded())
}

// Mode is the certificate mode of the system page, in words: one source for every
// listener that speaks TLS, or a mix.
func (c *Certs) Mode() i18n.Msg {
	https, ldap := c.Sources()
	used := map[Source]bool{}
	for _, s := range []Source{https, ldap} {
		if s != SourceOff {
			used[s] = true
		}
	}
	switch {
	case len(used) == 0:
		return i18n.M("pki.mode.none")
	case len(used) == 2 && c.Paused():
		return i18n.M("pki.mode.own_and_files")
	case len(used) == 2:
		return i18n.M("pki.mode.mixed")
	case used[SourceOwn]:
		return i18n.M("pki.mode.own")
	case used[SourceFiles]:
		return i18n.M("pki.mode.files")
	}
	return i18n.M("pki.mode.builtin")
}

// SourceLabel names a listener's source in words; off depends on the listener.
func SourceLabel(s Source, offText i18n.Msg) i18n.Msg {
	switch s {
	case SourceFiles:
		return i18n.M("pki.source.files")
	case SourceOwn:
		return i18n.M("pki.source.own")
	case SourceBuiltin:
		return i18n.M("pki.source.builtin")
	}
	return offText
}

// Paused reports whether the built-in CA's issuing is paused: no listener uses its certificate.
func (c *Certs) Paused() bool {
	https, ldap := c.Sources()
	return https != SourceBuiltin && ldap != SourceBuiltin
}

// Ensure issues the built-in server certificate when it is needed and not paused.
func (c *Certs) Ensure(ctx context.Context) (string, error) {
	if c.Paused() {
		return "", nil
	}
	return c.ca.Ensure(ctx, c.opt.Names)
}

// Issue issues a new built-in server certificate; ErrPaused while issuing is paused.
func (c *Certs) Issue(ctx context.Context) error {
	if c.Paused() {
		return ErrPaused
	}
	return c.ca.Issue(ctx, c.opt.Names)
}

func (c *Certs) served(src Source, files *Own) (*tls.Certificate, error) {
	switch src {
	case SourceFiles:
		return files.cert, nil
	case SourceOwn:
		if o := c.Uploaded(); o != nil {
			return o.cert, nil
		}
	}
	return c.ca.GetCertificate(nil)
}

// HTTPSConfig is the TLS configuration of the HTTP listener, nil with SIPLINK_TLS=off.
func (c *Certs) HTTPSConfig() *tls.Config {
	if https, _ := c.Sources(); https == SourceOff {
		return nil
	}
	return &tls.Config{MinVersion: tls.VersionTLS12, GetCertificate: func(*tls.ClientHelloInfo) (*tls.Certificate, error) {
		https, _ := c.Sources()
		return c.served(https, c.httpsFiles)
	}}
}

// LDAPConfig is the TLS configuration of StartTLS and LDAPS.
func (c *Certs) LDAPConfig() *tls.Config {
	return &tls.Config{MinVersion: tls.VersionTLS12, GetCertificate: func(*tls.ClientHelloInfo) (*tls.Certificate, error) {
		_, ldap := c.Sources()
		return c.served(ldap, c.ldapFiles)
	}}
}

// anchor is what the phones trust a listener's chain by: the certificate they pin
// (nil when none) or public = the system CAs.
func (c *Certs) anchor(src Source, files, own *Own) (cert *x509.Certificate, public bool) {
	switch src {
	case SourceBuiltin:
		return c.ca.CA(), false
	case SourceFiles:
		return files.Private(), files.Public
	case SourceOwn:
		return own.Private(), own.Public
	}
	return nil, false
}

// Pin is the pin of QR codes (§3.1): the built-in CA's, or the private root of the
// operator's chain, when the HTTPS listener presents it; "" otherwise.
func (c *Certs) Pin() string {
	own := c.Uploaded()
	https, _ := c.sources(own)
	if a, _ := c.anchor(https, c.httpsFiles, own); a != nil {
		return Pin(a)
	}
	return ""
}

// DocumentCAs are the platform's CAs that documents start their caCertificates with
// (§3.5.2): the anchor of the HTTPS listener's chain, then the LDAP listener's, each
// when it is private.
func (c *Certs) DocumentCAs() []string {
	own := c.Uploaded()
	https, ldap := c.sources(own)
	var out []string
	seen := map[string]bool{}
	for _, l := range []struct {
		src   Source
		files *Own
	}{{https, c.httpsFiles}, {ldap, c.ldapFiles}} {
		a, _ := c.anchor(l.src, l.files, own)
		if a == nil || seen[string(a.Raw)] {
			continue
		}
		seen[string(a.Raw)] = true
		out = append(out, string(pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: a.Raw})))
	}
	return out
}

// Reload picks up what another process (the CLI) stored: the uploaded certificate and the
// built-in server certificate. After a change of the uploaded certificate the issue check
// runs at once, so that returning to the built-in CA needs no wait.
func (c *Certs) Reload(ctx context.Context) error {
	before := c.Uploaded()
	if err := c.loadOwn(ctx); err != nil {
		return err
	}
	if err := c.ca.Reload(ctx); err != nil {
		return err
	}
	if c.Uploaded() != before {
		_, err := c.Ensure(ctx)
		return err
	}
	return nil
}

// Import stores an assessed certificate and uses it at once.
func (c *Certs) Import(ctx context.Context, o *Own) error {
	pkcs8, err := x509.MarshalPKCS8PrivateKey(o.Key)
	if err != nil {
		return err
	}
	sealed, err := c.ca.box.Seal(pkcs8, secret.AAD("tls_material", nameOwn, "key_enc"))
	if err != nil {
		return err
	}
	text := string(o.ChainPEM())
	if err := c.ca.st.PutTLSMaterial(ctx, &store.TLSMaterial{Name: nameOwn, CertPEM: text, KeyEnc: sealed}); err != nil {
		return err
	}
	c.setOwn(o, text)
	return nil
}

// Remove drops the uploaded certificate and returns to the built-in CA: the issue check
// runs at once. It returns why a server certificate was issued, or "".
func (c *Certs) Remove(ctx context.Context) (string, error) {
	if err := c.ca.st.DeleteTLSMaterial(ctx, nameOwn); err != nil {
		return "", err
	}
	c.setOwn(nil, "")
	return c.Ensure(ctx)
}

// Reminder days: how long before the expiry of an operator's certificate the platform
// reminds (meta key cert_reminder_days).
const (
	metaReminderDays    = "cert_reminder_days"
	DefaultReminderDays = 30
	MaxReminderDays     = 365
)

// ReminderDays reads the setting, DefaultReminderDays when it is not set.
func (c *Certs) ReminderDays(ctx context.Context) int {
	v, err := c.ca.st.Meta(ctx, metaReminderDays)
	if err != nil {
		return DefaultReminderDays
	}
	n, err := strconv.Atoi(v)
	if err != nil || n < 1 || n > MaxReminderDays {
		return DefaultReminderDays
	}
	return n
}

// SetReminderDays stores the setting (1–365).
func (c *Certs) SetReminderDays(ctx context.Context, days int) error {
	if days < 1 || days > MaxReminderDays {
		return refuse("pki.refuse.reminder_days", "max", MaxReminderDays)
	}
	return c.ca.st.SetMeta(ctx, metaReminderDays, strconv.Itoa(days))
}

// Run does the daily check (Daily) and looks once a minute for a certificate stored by
// the CLI, until ctx ends.
func (c *Certs) Run(ctx context.Context, every time.Duration, log *slog.Logger) {
	daily := time.NewTicker(every)
	defer daily.Stop()
	minute := time.NewTicker(time.Minute)
	defer minute.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-minute.C:
			if err := c.Reload(ctx); err != nil {
				log.Error("certificate reload failed", "err", err)
			}
		case <-daily.C:
			c.Daily(ctx, log)
		}
	}
}

// Daily is the check of the start and of every day: the built-in server certificate is
// issued when needed (unless issuing is paused), and the expiry of the operator's
// certificates is logged inside the reminder window.
func (c *Certs) Daily(ctx context.Context, log *slog.Logger) {
	if err := c.Reload(ctx); err != nil {
		log.Error("certificate reload failed", "err", err)
	}
	if reason, err := c.Ensure(ctx); err != nil {
		log.Error("server certificate check failed", "err", err)
	} else if reason != "" {
		log.Info("server certificate issued", "reason", reason, "names", c.opt.Names)
	}
	c.Remind(ctx, log)
}

// Remind logs the notices of the operator's certificates in use.
func (c *Certs) Remind(ctx context.Context, log *slog.Logger) {
	for _, n := range c.Notices(c.ReminderDays(ctx)) {
		attrs := []any{"subject", n.Cert.Leaf().Subject.String(), "not_after", n.Cert.Leaf().NotAfter.UTC().Format(time.RFC3339)}
		if n.Level == LevelError {
			log.Error("certificate expired; phones cannot sync", attrs...)
		} else {
			log.Warn("certificate expires soon", append(attrs, "days_left", n.DaysLeft)...)
		}
	}
}
