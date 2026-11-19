package provision

import (
	"slices"
	"strings"
	"unicode/utf8"

	"siplink/internal/i18n"
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
	Problems           []i18n.Msg // found while assembling (e.g. a withdrawn LDAP grant)
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
func Build(in Input) (*Document, []i18n.Msg) {
	problems := append([]i18n.Msg(nil), in.Problems...)
	d := &Document{Schema: Schema, Platform: in.Platform, Client: in.Client, RefreshMinutes: in.RefreshMinutes,
		ConfigPasswordHash: in.ConfigPasswordHash, Settings: in.Settings, Codecs: in.Codecs,
		Accounts: []Account{}, CACertificates: []string{}, Directories: []Directory{}, Shortcuts: []Shortcut{}}
	if in.RefreshMinutes < 5 || in.RefreshMinutes > 1440 {
		problems = append(problems, RefreshRange)
	}
	var keys, names []string
	for _, a := range in.Accounts {
		draft := rules.NormalizeAccount(a.Draft)
		for _, e := range rules.ValidateAccount(draft, keys, "", names) {
			problems = append(problems, AccountProblem(draft.Name, e.Msg()))
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
			problems = append(problems, DirectoryProblem(dir.Name, e.Msg()))
		}
		dirNames = append(dirNames, dir.Name)
		d.Directories = append(d.Directories, Directory{Key: k.Key, Name: dir.Name, Enabled: dir.Enabled, Host: dir.Host, Port: dir.Port,
			Security: dir.Security, BindDn: dir.BindDn, Password: dir.Password, BaseDn: dir.BaseDn, Line: dir.Line,
			LookupIncoming: dir.LookupIncoming, LookupOutgoing: dir.LookupOutgoing, MaxResults: dir.MaxResults,
			NameAttributes: dir.NameAttributes, NumberAttributes: dir.NumberAttributes, NameFilter: dir.NameFilter,
			NumberFilter: dir.NumberFilter})
	}
	if len(in.Shortcuts) > rules.MaxShortcuts {
		problems = append(problems, rules.ShortcutLimitReached.Msg())
	}
	for _, s := range in.Shortcuts {
		s = rules.NormalizeShortcut(s)
		for _, e := range rules.ValidateShortcut(s) {
			problems = append(problems, i18n.M("provision.key_problem", "name", s.Label, "problem", e.Msg()))
		}
		d.Shortcuts = append(d.Shortcuts, Shortcut{Label: s.Label, Number: s.Number, Action: rules.TypeWord(s.Action),
			Category: s.Category, Line: s.Line, Icon: strings.ToLower(string(s.Icon))})
	}
	problems = append(problems, SettingsProblems(in.Settings)...)
	v, err := Version(d)
	if err != nil {
		problems = append(problems, i18n.M("provision.version_failed", "err", err.Error()))
	}
	d.Version = v
	return d, problems
}

// RefreshRange is the problem of a refresh interval outside 5–1440 minutes.
var RefreshRange = i18n.M("provision.refresh_range", "min", 5, "max", 1440)

// AccountProblem names the account a problem belongs to.
func AccountProblem(name string, problem i18n.Msg) i18n.Msg {
	return i18n.M("provision.account_problem", "name", name, "problem", problem)
}

// DirectoryProblem names the directory a problem belongs to.
func DirectoryProblem(name string, problem i18n.Msg) i18n.Msg {
	return i18n.M("provision.directory_problem", "name", name, "problem", problem)
}

// SettingsProblems checks the locked settings of a template.
func SettingsProblems(s Settings) []i18n.Msg {
	var out []i18n.Msg
	if !s.MediaSecurity.Valid() || !s.DndMode.Valid() || !s.DtmfMode.Valid() || !s.VideoSize.Valid() {
		out = append(out, i18n.M("provision.settings.invalid_option"))
	}
	if s.ForwardNoAnswerSeconds < rules.MinNoAnswerSeconds || s.ForwardNoAnswerSeconds > rules.MaxNoAnswerSeconds {
		out = append(out, i18n.M("provision.settings.no_answer_range", "min", rules.MinNoAnswerSeconds, "max", rules.MaxNoAnswerSeconds))
	}
	if !slices.Contains(rules.FrameRates, s.VideoFrameRate) {
		out = append(out, i18n.M("provision.settings.frame_rate_invalid"))
	}
	if !slices.Contains(rules.AutoAnswerDelays, s.AutoAnswerSeconds) {
		out = append(out, i18n.M("provision.settings.auto_answer_invalid"))
	}
	for _, v := range []string{s.ForwardAlways, s.ForwardBusy, s.ForwardNoAnswer} {
		if err := ForwardProblem(v); err != nil {
			out = append(out, i18n.MsgOf(err))
			break
		}
	}
	if err := UserAgentProblem(s.UserAgent); err != nil {
		out = append(out, i18n.MsgOf(err))
	}
	if n := s.RoomSize(); n < rules.MinRoomLimit || n > rules.RoomLimit {
		out = append(out, i18n.M("provision.settings.room_limit_range", "min", rules.MinRoomLimit, "max", rules.RoomLimit))
	}
	if !rules.IsValidStunServer(s.StunServer) {
		out = append(out, i18n.M("provision.settings.stun_invalid"))
	}
	return out
}

// MaxUserAgent is the longest SIP User-Agent a template may set.
const MaxUserAgent = 120

// UserAgentProblem checks the User-Agent: printable ASCII, no space at either end.
func UserAgentProblem(v string) error {
	if len(v) > MaxUserAgent {
		return i18n.E("provision.user_agent.too_long", "max", MaxUserAgent)
	}
	for i := 0; i < len(v); i++ {
		if v[i] < 0x20 || v[i] > 0x7e {
			return i18n.E("provision.user_agent.not_ascii")
		}
	}
	if strings.TrimSpace(v) != v {
		return i18n.E("provision.user_agent.spaces")
	}
	return nil
}

// ForwardProblem checks one forwarding target (template or per-client override).
func ForwardProblem(v string) error {
	if strings.ContainsAny(v, " \t\r\n") || utf8.RuneCountInString(v) > 128 {
		return i18n.E("provision.forward_invalid", "max", 128)
	}
	return nil
}
