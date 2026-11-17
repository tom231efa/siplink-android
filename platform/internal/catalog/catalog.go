// Package catalog checks templates and clients with the app's rules before they are
// stored. The web UI and the seed command use the same checks.
package catalog

import (
	"crypto/x509"
	"encoding/json"
	"encoding/pem"
	"fmt"
	"regexp"
	"strings"
	"time"
	"unicode/utf8"

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
func NameProblem(name string) string {
	switch n := utf8.RuneCountInString(name); {
	case n == 0:
		return "请填写名称。"
	case n > MaxTemplateName:
		return fmt.Sprintf("名称不得超过 %d 个字符。", MaxTemplateName)
	}
	return ""
}

// ---- account profiles ----

// ProfileDraft turns a profile into the app's AccountDraft (server side only).
func ProfileDraft(p *store.Profile) rules.AccountDraft {
	return rules.AccountDraft{Name: p.Name, Domain: p.Domain, Port: p.Port, Server: p.Server, BackupServer: p.BackupServer,
		DnsServer: p.DNSServer, Transport: rules.SipTransport(p.Transport), Expires: p.Expires, SubscribeMwi: p.SubscribeMWI,
		Enabled: p.Enabled}
}

// NormalizeProfile applies AccountRules.normalize to the server side of a profile.
func NormalizeProfile(p *store.Profile) {
	d := rules.NormalizeAccount(ProfileDraft(p))
	p.Name, p.Domain, p.Port, p.Server, p.BackupServer, p.DNSServer = d.Name, d.Domain, d.Port, d.Server, d.BackupServer, d.DnsServer
}

// ProfileProblems checks a normalized profile; others are the other profiles of the template.
func ProfileProblems(p *store.Profile, others []*store.Profile) []string {
	var out []string
	if p.Name == "" {
		out = append(out, "账号名称必填")
	}
	if !rules.SipTransport(p.Transport).Valid() {
		out = append(out, "传输方式应为 UDP、TCP 或 TLS")
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
		out = append(out, e.Full())
	}
	return out
}

// ---- settings ----

// ParseSettings reads stored settings JSON over the app's defaults; an auto answer wait that
// cannot be chosen becomes the nearest one that can.
func ParseSettings(raw string) provision.Settings {
	s := provision.DefaultSettings()
	if raw != "" {
		_ = json.Unmarshal([]byte(raw), &s)
	}
	s.AutoAnswerSeconds = rules.AutoAnswerDelay(s.AutoAnswerSeconds)
	return s
}

// SettingsJSON stores settings.
func SettingsJSON(s provision.Settings) string {
	b, _ := json.Marshal(s)
	return string(b)
}

// RefreshProblem checks the refresh interval.
func RefreshProblem(minutes int) string {
	if minutes < MinRefreshMinutes || minutes > MaxRefreshMinutes {
		return fmt.Sprintf("刷新间隔应为 %d–%d 分钟", MinRefreshMinutes, MaxRefreshMinutes)
	}
	return ""
}

// ConfigPasswordHash checks a new configuration password and returns the hash the app
// expects (ManagedConfig.isPassword).
func ConfigPasswordHash(password string) (string, string) {
	if utf8.RuneCountInString(password) < MinConfigPassword {
		return "", fmt.Sprintf("配置密码至少为 %d 个字符", MinConfigPassword)
	}
	return rules.PasswordHash(password), ""
}

// ---- codecs ----

var codecKey = regexp.MustCompile(`^[A-Za-z0-9._+-]+/[0-9]+/[0-9]+$`)

// CodecProblem checks one Codec.key ("mime/clockRate/channels").
func CodecProblem(key string) string {
	if !codecKey.MatchString(key) {
		return "编解码器应写作 名称/采样率/声道，例如 opus/48000/2"
	}
	return ""
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
			return nil, fmt.Errorf("仅可上传证书（PEM 中包含 %s）", block.Type)
		}
		c, err := x509.ParseCertificate(block.Bytes)
		if err != nil {
			return nil, fmt.Errorf("证书无法解析：%v", err)
		}
		out = append(out, CAInfo{Subject: c.Subject.String(), NotAfter: c.NotAfter, Fingerprint: pki.Fingerprint(c),
			PEM: string(pem.EncodeToMemory(block))})
	}
	if strings.TrimSpace(string(rest)) != "" && len(out) == 0 {
		return nil, fmt.Errorf("未找到 PEM 格式的证书")
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
