package provision

import (
	"fmt"
	"slices"
	"strings"
	"unicode/utf8"

	"siplink/internal/rules"
)

// Input is everything a document is made of, already merged (§3.5.8 steps 1–4) and in
// the app's types. Build validates it (step 5) and turns it into a Document.
type Input struct {
	Platform           PlatformInfo
	Client             ClientInfo
	RefreshMinutes     int
	ConfigPasswordHash string
	Settings           Settings
	Accounts           []KeyedAccount
	Codecs             *Codecs
	CACertificates     []string // PEM, the platform's CA first; duplicates are dropped
	Directories        []KeyedDirectory
	Shortcuts          []rules.Shortcut
	Problems           []string // found while assembling (e.g. a withdrawn LDAP grant)
}

// KeyedAccount is one account with its stable key ("p" + profile id).
type KeyedAccount struct {
	Key     string
	Draft   rules.AccountDraft
	Default bool
}

// KeyedDirectory is one directory with its stable key ("d" + directory id); its Line
// is an account key or "".
type KeyedDirectory struct {
	Key string
	Dir rules.LdapDirectory
}

// Build validates the input with the app's rules and returns the document (Version set,
// GeneratedAt left to the caller). problems lists everything that does not validate;
// the document must not be sent then.
func Build(in Input) (*Document, []string) {
	problems := append([]string(nil), in.Problems...)
	d := &Document{Schema: Schema, Platform: in.Platform, Client: in.Client, RefreshMinutes: in.RefreshMinutes,
		ConfigPasswordHash: in.ConfigPasswordHash, Settings: in.Settings, Codecs: in.Codecs,
		Accounts: []Account{}, CACertificates: []string{}, Directories: []Directory{}, Shortcuts: []Shortcut{}}
	if in.RefreshMinutes < 5 || in.RefreshMinutes > 1440 {
		problems = append(problems, "刷新间隔应为 5–1440 分钟")
	}
	var keys, names []string
	for _, a := range in.Accounts {
		draft := rules.NormalizeAccount(a.Draft)
		for _, e := range rules.ValidateAccount(draft, keys, "", names) {
			problems = append(problems, fmt.Sprintf("账号「%s」：%s", draft.Name, e.Full()))
		}
		keys = append(keys, rules.IdentityKey(draft.Username, draft.Address()))
		names = append(names, draft.Name)
		d.Accounts = append(d.Accounts, Account{Key: a.Key, Name: draft.Name, Username: draft.Username, Password: draft.Password,
			AuthUser: draft.AuthUser, DisplayName: draft.DisplayName, Domain: draft.Domain, Port: draft.Port, Server: draft.Server,
			BackupServer: draft.BackupServer, DNSServer: draft.DnsServer, Transport: draft.Transport, Expires: draft.Expires,
			SubscribeMwi: draft.SubscribeMwi, Enabled: draft.Enabled})
		if a.Default && d.DefaultAccount == "" {
			d.DefaultAccount = a.Key
		}
	}
	if d.DefaultAccount == "" && len(d.Accounts) > 0 {
		d.DefaultAccount = d.Accounts[0].Key
	}
	seen := map[string]bool{}
	for _, pem := range in.CACertificates {
		if p := strings.TrimSpace(pem) + "\n"; !seen[p] {
			seen[p] = true
			d.CACertificates = append(d.CACertificates, p)
		}
	}
	var dirNames []string
	for _, k := range in.Directories {
		dir := rules.NormalizeLdap(k.Dir)
		for _, e := range rules.ValidateLdap(dir, dirNames, true) {
			problems = append(problems, fmt.Sprintf("通讯录「%s」：%s", dir.Name, e.Full()))
		}
		dirNames = append(dirNames, dir.Name)
		d.Directories = append(d.Directories, Directory{Key: k.Key, Name: dir.Name, Enabled: dir.Enabled, Host: dir.Host, Port: dir.Port,
			Security: dir.Security, BindDn: dir.BindDn, Password: dir.Password, BaseDn: dir.BaseDn, Line: dir.Line,
			LookupIncoming: dir.LookupIncoming, LookupOutgoing: dir.LookupOutgoing, MaxResults: dir.MaxResults,
			NameAttributes: dir.NameAttributes, NumberAttributes: dir.NumberAttributes, NameFilter: dir.NameFilter,
			NumberFilter: dir.NumberFilter})
	}
	if len(in.Shortcuts) > rules.MaxShortcuts {
		problems = append(problems, rules.ShortcutLimitReached.Full())
	}
	for _, s := range in.Shortcuts {
		s = rules.NormalizeShortcut(s)
		for _, e := range rules.ValidateShortcut(s) {
			problems = append(problems, fmt.Sprintf("功能键「%s」：%s", s.Label, e.Full()))
		}
		d.Shortcuts = append(d.Shortcuts, Shortcut{Label: s.Label, Number: s.Number, Action: rules.TypeWord(s.Action),
			Category: s.Category, Line: s.Line, Icon: strings.ToLower(string(s.Icon))})
	}
	problems = append(problems, SettingsProblems(in.Settings)...)
	v, err := Version(d)
	if err != nil {
		problems = append(problems, err.Error())
	}
	d.Version = v
	return d, problems
}

// SettingsProblems checks the locked settings of a template.
func SettingsProblems(s Settings) []string {
	var out []string
	if !s.MediaSecurity.Valid() || !s.DndMode.Valid() || !s.DtmfMode.Valid() || !s.VideoSize.Valid() {
		out = append(out, "选项的值无效")
	}
	if s.ForwardNoAnswerSeconds < rules.MinNoAnswerSeconds || s.ForwardNoAnswerSeconds > rules.MaxNoAnswerSeconds {
		out = append(out, fmt.Sprintf("无应答转移等待时间应为 %d–%d 秒", rules.MinNoAnswerSeconds, rules.MaxNoAnswerSeconds))
	}
	if !slices.Contains(rules.FrameRates, s.VideoFrameRate) {
		out = append(out, "视频帧率无效")
	}
	if !slices.Contains(rules.AutoAnswerDelays, s.AutoAnswerSeconds) {
		out = append(out, "自动接听时间无效")
	}
	for _, v := range []string{s.ForwardAlways, s.ForwardBusy, s.ForwardNoAnswer} {
		if p := ForwardProblem(v); p != "" {
			out = append(out, p)
			break
		}
	}
	if p := UserAgentProblem(s.UserAgent); p != "" {
		out = append(out, p)
	}
	return out
}

// MaxUserAgent is the longest SIP User-Agent a template may set.
const MaxUserAgent = 120

// UserAgentProblem checks the User-Agent: printable ASCII, no space at either end.
func UserAgentProblem(v string) string {
	if len(v) > MaxUserAgent {
		return fmt.Sprintf("客户端名称（User-Agent）最多 %d 个字符", MaxUserAgent)
	}
	for i := 0; i < len(v); i++ {
		if v[i] < 0x20 || v[i] > 0x7e {
			return "客户端名称（User-Agent）仅可使用可打印 ASCII 字符"
		}
	}
	if strings.TrimSpace(v) != v {
		return "客户端名称（User-Agent）首尾不得包含空格"
	}
	return ""
}

// ForwardProblem checks one forwarding target (template or per-client override).
func ForwardProblem(v string) string {
	if strings.ContainsAny(v, " \t\r\n") || utf8.RuneCountInString(v) > 128 {
		return "转移号码不得包含空格，最多 128 个字符"
	}
	return ""
}
