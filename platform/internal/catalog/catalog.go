// Package catalog checks templates and clients with the app's rules before they are
// stored. The web UI and the seed command use the same checks.
package catalog

import (
	"crypto/x509"
	"encoding/json"
	"encoding/pem"
	"regexp"
	"strings"
	"time"
	"unicode/utf8"

	"siplink/internal/i18n"
	"siplink/internal/pki"
	"siplink/internal/provision"
	"siplink/internal/rules"
	"siplink/internal/store"
)

// Limits of the platform's own fields.
const (
	MaxTemplateName   = 64
	MinRefreshMinutes = 5
	MaxRefreshMinutes = 1440
	MinConfigPassword = 8
	MaxCAs            = 50
)

// NameProblem checks the name of a template or client.
func NameProblem(name string) error {
	switch n := utf8.RuneCountInString(name); {
	case n == 0:
		return i18n.E("catalog.name_required")
	case n > MaxTemplateName:
		return i18n.E("catalog.name_too_long", "max", MaxTemplateName)
	case i18n.HasBidiControl(name):
		return i18n.E("names.bidi_control")
	}
	return nil
}

// ---- account profiles ----

// ProfileDraft turns a profile into the app's AccountDraft (server side only).
func ProfileDraft(p *store.Profile) rules.AccountDraft {
	return rules.AccountDraft{Name: p.Name, Domain: p.Domain, Port: p.Port, Server: p.Server, BackupServer: p.BackupServer,
		ServerTimeoutMs: p.ServerTimeoutMs, DnsServer: p.DNSServer, Transport: rules.SipTransport(p.Transport), Expires: p.Expires,
		SubscribeMwi: p.SubscribeMWI, Enabled: p.Enabled}
}

// NormalizeProfile applies AccountRules.normalize to the server side of a profile.
func NormalizeProfile(p *store.Profile) {
	d := rules.NormalizeAccount(ProfileDraft(p))
	p.Name, p.Domain, p.Port, p.Server, p.BackupServer, p.DNSServer = d.Name, d.Domain, d.Port, d.Server, d.BackupServer, d.DnsServer
}

// ProfileProblems checks a normalized profile; others are the other profiles of the template.
func ProfileProblems(p *store.Profile, others []*store.Profile) []i18n.Msg {
	var out []i18n.Msg
	if p.Name == "" {
		out = append(out, i18n.M("catalog.profile_name_required"))
	}
	if i18n.HasBidiControl(p.Name) {
		out = append(out, i18n.M("names.bidi_control"))
	}
	if !rules.SipTransport(p.Transport).Valid() {
		out = append(out, i18n.M("catalog.transport_invalid"))
	}
	if !rules.IsValidServerTimeoutMs(p.ServerTimeoutMs) {
		out = append(out, provision.ServerTimeoutRange)
	}
	var taken []string
	for _, o := range others {
		if o.ID != p.ID {
			taken = append(taken, o.Name)
		}
	}
	d := ProfileDraft(p)
	d.Username = "x" // the user name is the client's; only the server side is checked here
	for _, e := range rules.ValidateAccount(d, nil, "x@x", taken) {
		out = append(out, e.Msg())
	}
	return out
}

// ---- settings ----

// ParseSettings reads stored settings JSON over the app's defaults; an auto answer wait that
// cannot be chosen becomes the nearest one that can, a room size outside the app's range the
// nearest end of it.
func ParseSettings(raw string) provision.Settings {
	s := provision.DefaultSettings()
	if raw != "" {
		_ = json.Unmarshal([]byte(raw), &s)
	}
	s.AutoAnswerSeconds = rules.AutoAnswerDelay(s.AutoAnswerSeconds)
	// A room size the app would not take is read as the app reads it; the defaults of the
	// fields that are left out at their default are left out (provision.Settings).
	s.SetRoomLimit(rules.RoomLimitOf(s.RoomSize()))
	s.SetAutoNetwork(s.AutoNetworkOn())
	return s
}

// SettingsJSON stores settings.
func SettingsJSON(s provision.Settings) string {
	b, _ := json.Marshal(s)
	return string(b)
}

// RefreshProblem checks the refresh interval.
func RefreshProblem(minutes int) error {
	if minutes < MinRefreshMinutes || minutes > MaxRefreshMinutes {
		return i18n.ErrorOf(provision.RefreshRange)
	}
	return nil
}

// ConfigPasswordHash checks a new configuration password and returns the hash the app
// expects (ManagedConfig.isPassword).
func ConfigPasswordHash(password string) (string, error) {
	if utf8.RuneCountInString(password) < MinConfigPassword {
		return "", i18n.E("catalog.config_password_short", "min", MinConfigPassword)
	}
	return rules.PasswordHash(password), nil
}

// ---- codecs ----

var codecKey = regexp.MustCompile(`^[A-Za-z0-9._+-]+/[0-9]+/[0-9]+$`)

// CodecProblem checks one Codec.key ("mime/clockRate/channels").
func CodecProblem(key string) error {
	if !codecKey.MatchString(key) {
		return i18n.E("catalog.codec_invalid")
	}
	return nil
}

// ParseCodecs reads stored codecs; nil means the SDK's defaults.
func ParseCodecs(raw string) *provision.Codecs {
	if raw == "" {
		return nil
	}
	var c provision.Codecs
	if json.Unmarshal([]byte(raw), &c) != nil {
		return nil
	}
	if c.Audio == nil {
		c.Audio = []provision.Codec{}
	}
	if c.Video == nil {
		c.Video = []provision.Codec{}
	}
	return &c
}

// CodecsJSON stores codecs; nil = SDK defaults.
func CodecsJSON(c *provision.Codecs) string {
	if c == nil {
		return ""
	}
	b, _ := json.Marshal(c)
	return string(b)
}

// ---- CA certificates ----

// CAInfo describes one certificate of a template's CA list.
type CAInfo struct {
	Subject     string
	NotAfter    time.Time
	Fingerprint string // as the app shows it: upper-case hex pairs joined by ':'
	PEM         string
}

// SplitCAs parses a PEM bundle; it fails on anything that is not an X.509 certificate.
func SplitCAs(bundle string) ([]CAInfo, error) {
	var out []CAInfo
	rest := []byte(bundle)
	for {
		var block *pem.Block
		block, rest = pem.Decode(rest)
		if block == nil {
			break
		}
		if block.Type != "CERTIFICATE" {
			return nil, i18n.E("catalog.ca_not_certificate", "type", block.Type)
		}
		c, err := x509.ParseCertificate(block.Bytes)
		if err != nil {
			return nil, i18n.E("catalog.ca_unreadable", "err", err.Error())
		}
		out = append(out, CAInfo{Subject: c.Subject.String(), NotAfter: c.NotAfter, Fingerprint: pki.Fingerprint(c),
			PEM: string(pem.EncodeToMemory(block))})
	}
	if strings.TrimSpace(string(rest)) != "" && len(out) == 0 {
		return nil, i18n.E("catalog.ca_none")
	}
	return out, nil
}

// MergeCAs joins PEM bundles, dropping certificates seen before (same fingerprint).
func MergeCAs(bundles ...string) []string {
	seen := map[string]bool{}
	var out []string
	for _, b := range bundles {
		list, _ := SplitCAs(b)
		for _, c := range list {
			if !seen[c.Fingerprint] {
				seen[c.Fingerprint] = true
				out = append(out, c.PEM)
			}
		}
	}
	return out
}
