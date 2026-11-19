package catalog

import (
	"slices"
	"strings"

	"siplink/internal/books"
	"siplink/internal/i18n"
	"siplink/internal/rules"
	"siplink/internal/store"
)

// Password modes of external directories (design §3.5.5).
const (
	PasswordNone     = "none"
	PasswordTemplate = "template"
	PasswordClient   = "client"
)

// Hosted describes the platform's LDAP service as phones reach it.
type Hosted struct {
	Host       string
	Port       int
	Suffix     string
	RequireTLS bool
}

// Security returns the security of hosted directories and whether they can be offered
// at all (rules.HostedSecurity).
func (h Hosted) Security() (rules.LdapSecurity, bool) {
	return rules.HostedSecurity(h.Host, h.RequireTLS)
}

// ExternalLdap turns an external directory into the app's LdapDirectory, with password;
// name, enabled and lookups are those of the directory on its own.
func ExternalLdap(e *store.ExternalDirectory, password string) rules.LdapDirectory {
	return rules.LdapDirectory{Name: e.Name, Enabled: true, Host: e.Host, Port: e.Port, Security: rules.LdapSecurity(e.Security),
		BindDn: e.BindDN, Password: password, BaseDn: e.BaseDN, LookupIncoming: true, LookupOutgoing: true, MaxResults: e.MaxResults,
		NameAttributes: e.NameAttributes, NumberAttributes: e.NumberAttributes, NameFilter: e.NameFilter, NumberFilter: e.NumberFilter}
}

// LdapOf is the directory a phone gets for the use of an external directory: the
// directory's connection with the use's name, switch and lookups.
func LdapOf(d *store.Directory, password string) rules.LdapDirectory {
	l := ExternalLdap(d.External, password)
	l.Name, l.Enabled, l.LookupIncoming, l.LookupOutgoing = d.DisplayName(), d.Enabled, d.LookupIncoming, d.LookupOutgoing
	return l
}

// HostedLdap is the directory a phone gets for a hosted book (§3.5.5, derived values).
func (h Hosted) HostedLdap(d *store.Directory, b *store.Book, bindDN, password string) rules.LdapDirectory {
	sec, _ := h.Security()
	name := d.Name
	if name == "" {
		name = b.Name
	}
	return rules.LdapDirectory{Name: name, Enabled: d.Enabled, Host: h.Host, Port: h.Port, Security: sec, BindDn: bindDN,
		Password: password, BaseDn: books.BookDN(b.Slug, h.Suffix), LookupIncoming: d.LookupIncoming,
		LookupOutgoing: d.LookupOutgoing, MaxResults: d.MaxResults, NameAttributes: "cn",
		NumberAttributes: "telephoneNumber mobile"}
}

// AccountBindDN is the DN a service account binds with (design §4.2).
func AccountBindDN(name, suffix string) string { return "cn=" + name + ",ou=accounts," + suffix }

// HostedAccountProblem checks a hosted book bound by hand with a service account: the
// account must exist for the template's region (global or the same), be enabled and
// be granted the book; the password comes from the template or from each client.
func HostedAccountProblem(d *store.Directory, book *store.Book, a *store.LDAPAccount, templateRegion *int64) error {
	switch {
	case a == nil:
		return i18n.E("catalog.hosted.pick_account")
	case a.RegionID != nil && (templateRegion == nil || *templateRegion != *a.RegionID):
		return i18n.E("catalog.hosted.account_other_region", "account", a.Name)
	case a.Disabled:
		return i18n.E("catalog.hosted.account_disabled", "account", a.Name)
	case book != nil && !slices.Contains(a.BookIDs, book.ID):
		return i18n.E("catalog.hosted.account_no_grant", "account", a.Name, "book", book.Name)
	case d.PasswordMode != PasswordTemplate && d.PasswordMode != PasswordClient:
		return i18n.E("catalog.hosted.password_mode")
	}
	return nil
}

// NormalizeExternal applies LdapRules.normalize to an external directory.
func NormalizeExternal(e *store.ExternalDirectory) {
	e.Note = strings.TrimSpace(e.Note)
	l := rules.NormalizeLdap(ExternalLdap(e, ""))
	e.Name, e.Host, e.Port, e.BindDN, e.BaseDN = l.Name, l.Host, l.Port, l.BindDn, l.BaseDn
	e.NameAttributes, e.NumberAttributes, e.NameFilter, e.NumberFilter = l.NameAttributes, l.NumberAttributes, l.NameFilter, l.NumberFilter
}

// ExternalProblems checks a normalized external directory with LdapRules.validate; its
// name is required here (it names the directory in lists and is the phone's default).
func ExternalProblems(e *store.ExternalDirectory) []i18n.Msg {
	var out []i18n.Msg
	if e.Name == "" {
		out = append(out, i18n.M("catalog.name_required_short"))
	}
	switch e.PasswordMode {
	case PasswordNone, PasswordTemplate, PasswordClient:
	default:
		out = append(out, i18n.M("catalog.password_mode_invalid"))
	}
	probe := ExternalLdap(e, "")
	if e.PasswordMode != PasswordNone {
		probe.Password = "x" // a password without a bind DN is refused (PASSWORD_WITHOUT_USER)
	}
	for _, err := range rules.ValidateLdap(probe, nil, false) {
		out = append(out, err.Msg())
	}
	return out
}

// DirectoryProblems checks a directory use with a trimmed name. others are the other
// uses of the template (for unique names on the phone); book is the hosted book (nil for
// external).
func DirectoryProblems(d *store.Directory, others []*store.Directory, book *store.Book, h Hosted, isNew bool) []i18n.Msg {
	var taken []string
	for _, o := range others {
		if o.ID != d.ID {
			taken = append(taken, o.DisplayName())
		}
	}
	var out []i18n.Msg
	switch d.Kind {
	case store.KindHosted:
		if d.MaxResults < 1 || d.MaxResults > rules.MaxResults {
			out = append(out, rules.LdapMaxResultsOutOfRange.Msg())
		}
		if book == nil {
			return append(out, i18n.M("catalog.pick_directory"))
		}
		if _, ok := h.Security(); !ok {
			return append(out, rules.HostedNeedsName)
		}
		for _, e := range rules.ValidateLdap(h.HostedLdap(d, book, "uid=x", "x"), taken, isNew) {
			out = append(out, e.Msg())
		}
	case store.KindExternal:
		if d.External == nil {
			return append(out, i18n.M("catalog.pick_directory"))
		}
		for _, e := range rules.ValidateLdap(LdapOf(d, ""), taken, isNew) {
			out = append(out, e.Msg())
		}
	default:
		out = append(out, i18n.M("catalog.kind_invalid"))
	}
	return out
}

// ---- shortcut keys ----

// KeyShortcut turns a stored key into the app's Shortcut.
func KeyShortcut(k *store.TemplateKey) rules.Shortcut {
	action, _ := rules.ParseAction(k.Action)
	icon, _ := rules.ParseIcon(k.Icon)
	return rules.Shortcut{Label: k.Label, Number: k.Number, Action: action, Category: k.Category, Line: k.Line, Icon: icon}
}

// StoredKey turns a normalized Shortcut into a stored key.
func StoredKey(s rules.Shortcut) *store.TemplateKey {
	return &store.TemplateKey{Label: s.Label, Number: s.Number, Action: rules.TypeWord(s.Action), Category: s.Category,
		Line: s.Line, Icon: strings.ToLower(string(s.Icon))}
}

// KeyProblems normalizes a key with ShortcutRules.normalize and checks it with validate;
// lines must name one of profileNames when that list is given.
func KeyProblems(k *store.TemplateKey, profileNames []string) (*store.TemplateKey, []i18n.Msg) {
	action, ok := rules.ParseAction(k.Action)
	if !ok {
		return k, []i18n.Msg{i18n.M("catalog.key.unknown_type")}
	}
	icon, ok := rules.ParseIcon(k.Icon)
	if !ok && k.Icon != "" {
		return k, []i18n.Msg{i18n.M("catalog.key.unknown_icon")}
	}
	s := rules.NormalizeShortcut(rules.Shortcut{Label: k.Label, Number: k.Number, Action: action, Category: k.Category, Line: k.Line, Icon: icon})
	var out []i18n.Msg
	for _, e := range rules.ValidateShortcut(s) {
		out = append(out, e.Msg())
	}
	if err := LineProblem(s.Line, profileNames); err != nil {
		out = append(out, i18n.MsgOf(err))
	}
	nk := StoredKey(s)
	nk.ID, nk.Position = k.ID, k.Position
	return nk, out
}

// LineProblem checks that a key's line names an account profile (§3.5.7). An empty
// list of names skips the check (the key template is not used yet).
func LineProblem(line string, profileNames []string) error {
	if line == "" || len(profileNames) == 0 {
		return nil
	}
	for _, n := range profileNames {
		if strings.EqualFold(n, line) {
			return nil
		}
	}
	return i18n.E("catalog.key.line_unknown", "line", line, "names", profileNames)
}
