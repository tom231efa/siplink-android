package catalog

import (
	"strings"
	"unicode/utf8"

	"siplink/internal/rules"
	"siplink/internal/store"
)

// MaxClientName is the longest client name.
const MaxClientName = 64

// ClientNameProblem checks the name of a client.
func ClientNameProblem(name string) string {
	switch {
	case name == "":
		return "名称必填"
	case utf8.RuneCountInString(name) > MaxClientName:
		return "名称最多 64 个字符"
	case strings.ContainsAny(name, "\r\n\t"):
		return "名称不得包含换行符或制表符"
	}
	return ""
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
func CredentialProblems(p *store.Profile, in store.CredentialInput, taken []string) []string {
	var out []string
	for _, e := range rules.ValidateAccount(CredentialDraft(p, in), nil, "x@x", taken) {
		out = append(out, "账号「"+p.Name+"」："+e.Full())
	}
	return out
}
