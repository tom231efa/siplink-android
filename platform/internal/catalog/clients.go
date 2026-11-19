package catalog

import (
	"strings"
	"unicode/utf8"

	"siplink/internal/i18n"
	"siplink/internal/provision"
	"siplink/internal/rules"
	"siplink/internal/store"
)

// MaxClientName is the longest client name.
const MaxClientName = 64

// ClientNameProblem checks the name of a client.
func ClientNameProblem(name string) error {
	switch {
	case name == "":
		return i18n.E("catalog.name_required_short")
	case utf8.RuneCountInString(name) > MaxClientName:
		return i18n.E("catalog.client.name_too_long", "max", MaxClientName)
	case strings.ContainsAny(name, "\r\n\t"):
		return i18n.E("catalog.client.name_control")
	}
	return nil
}

// CredentialDraft is the account a profile and a client's credential make together.
func CredentialDraft(p *store.Profile, in store.CredentialInput) rules.AccountDraft {
	d := ProfileDraft(p)
	d.Username, d.AuthUser, d.DisplayName, d.Password = in.Username, in.AuthUser, in.DisplayName, in.Password
	if in.AccountName != nil && *in.AccountName != "" {
		d.Name = *in.AccountName
	}
	return d
}

// NormalizeCredential applies AccountRules.normalize to the client's part: a user name
// typed as sip:user@domain keeps only the user, as on the phone.
func NormalizeCredential(p *store.Profile, in *store.CredentialInput) {
	in.Username = strings.TrimSpace(in.Username)
	if in.Username == "" {
		return
	}
	d := rules.NormalizeAccount(CredentialDraft(p, *in))
	in.Username, in.AuthUser, in.DisplayName = d.Username, d.AuthUser, d.DisplayName
	if in.AccountName != nil {
		name := strings.TrimSpace(*in.AccountName)
		if name == "" {
			in.AccountName = nil
		} else {
			in.AccountName = &name
		}
	}
}

// CredentialProblems checks the client's account on one profile with the app's rules;
// taken are the account names already used by the client's other accounts.
func CredentialProblems(p *store.Profile, in store.CredentialInput, taken []string) []i18n.Msg {
	var out []i18n.Msg
	for _, e := range rules.ValidateAccount(CredentialDraft(p, in), nil, "x@x", taken) {
		out = append(out, provision.AccountProblem(p.Name, e.Msg()))
	}
	return out
}
