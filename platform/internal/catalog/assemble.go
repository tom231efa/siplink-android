package catalog

import (
	"context"
	"fmt"
	"strings"

	"siplink/internal/provision"
	"siplink/internal/rules"
	"siplink/internal/secret"
	"siplink/internal/store"
)

// Platform is what documents take from the server's configuration.
type Platform struct {
	Name    string
	SyncURL string
	Hosted  Hosted
	CAPEMs  []string // the platform's own CAs, first in caCertificates (§3.5.2)
	Box     *secret.Box
	LDAPKey [32]byte
}

// ServerKey is the part of a revision key that comes from the server's configuration.
func (p Platform) ServerKey() string {
	return secret.HashHex(strings.Join([]string{p.Name, p.SyncURL, p.Hosted.Host, fmt.Sprint(p.Hosted.Port),
		p.Hosted.Suffix, fmt.Sprint(p.Hosted.RequireTLS), strings.Join(p.CAPEMs, "")}, "\x00"))[:16]
}

// Assemble gathers everything a client's document is made of (§3.5.8 steps 1–4):
// the configuration template, the client's own values, derived hosted directories and
// the key template. Stored passwords are opened here.
func Assemble(ctx context.Context, st *store.Store, p Platform, c *store.Client) (provision.Input, error) {
	var in provision.Input
	region, err := st.RegionByID(ctx, c.RegionID)
	if err != nil {
		return in, err
	}
	tpl, err := st.ClientTemplateByID(ctx, c.ClientTemplateID)
	if err != nil {
		return in, err
	}
	cfg, err := st.ConfigTemplateByID(ctx, tpl.ConfigTemplateID)
	if err != nil {
		return in, err
	}
	in.Platform = provision.PlatformInfo{Name: p.Name, SyncURL: p.SyncURL}
	in.Client = provision.ClientInfo{Name: c.Name, Region: region.Name}
	in.RefreshMinutes, in.ConfigPasswordHash = cfg.RefreshMinutes, cfg.ConfigPasswordHash
	in.Settings = ParseSettings(cfg.SettingsJSON)
	for _, o := range []struct{ v, dst *string }{{c.ForwardAlways, &in.Settings.ForwardAlways},
		{c.ForwardBusy, &in.Settings.ForwardBusy}, {c.ForwardNoAnswer, &in.Settings.ForwardNoAnswer}} {
		if o.v != nil {
			*o.dst = *o.v
		}
	}
	in.Codecs = ParseCodecs(cfg.CodecsJSON)
	in.CACertificates = append(in.CACertificates, p.CAPEMs...)
	in.CACertificates = append(in.CACertificates, MergeCAs(cfg.CAPEMs)...)

	// Accounts: one per profile the client has credentials for.
	profiles, err := st.Profiles(ctx, cfg.ID)
	if err != nil {
		return in, err
	}
	creds, err := st.Credentials(ctx, c.ID)
	if err != nil {
		return in, err
	}
	accountKey := map[int64]string{}   // profile id → account key
	accountName := map[string]string{} // profile name (lower case) → the client's account name
	for _, prof := range profiles {
		k := creds[prof.ID]
		if k == nil {
			continue
		}
		password := ""
		if k.PasswordEnc != "" {
			b, err := p.Box.Open(k.PasswordEnc, store.CredentialAAD(c.ID, prof.ID))
			if err != nil {
				return in, fmt.Errorf("credential %d/%d: %w", c.ID, prof.ID, err)
			}
			password = string(b)
		}
		draft := CredentialDraft(prof, store.CredentialInput{Username: k.Username, AuthUser: k.AuthUser, Password: password,
			DisplayName: k.DisplayName, AccountName: k.AccountName})
		key := fmt.Sprintf("p%d", prof.ID)
		accountKey[prof.ID], accountName[strings.ToLower(prof.Name)] = key, draft.Name
		in.Accounts = append(in.Accounts, provision.KeyedAccount{Key: key, Draft: draft, Default: prof.IsDefault})
	}

	// Directories in template order.
	dirs, err := st.Directories(ctx, cfg.ID)
	if err != nil {
		return in, err
	}
	clientPass, err := st.DirectoryPasswords(ctx, c.ID)
	if err != nil {
		return in, err
	}
	for _, d := range dirs {
		var l rules.LdapDirectory
		if d.Kind == "hosted" {
			if d.BookID == nil {
				continue
			}
			b, err := st.BookByID(ctx, *d.BookID)
			if err != nil {
				return in, err
			}
			if d.LDAPAccountID == nil {
				bindDN := "uid=" + c.PublicID + ",ou=clients," + p.Hosted.Suffix
				l = p.Hosted.HostedLdap(d, b, bindDN, secret.LDAPPassword(p.LDAPKey, c.PublicID, c.LDAPGeneration))
			} else if l, err = hostedByAccount(ctx, st, p, c, cfg, d, b, clientPass, &in); err != nil {
				return in, err
			}
		} else {
			password, err := directoryPassword(p.Box, c.ID, d, clientPass)
			if err != nil {
				return in, err
			}
			l = LdapOf(d, password)
		}
		if d.LineProfileID != nil {
			l.Line = accountKey[*d.LineProfileID]
		}
		in.Directories = append(in.Directories, provision.KeyedDirectory{Key: fmt.Sprintf("d%d", d.ID), Dir: l})
	}

	// Keys: lines name profiles in the template and become the client's account names.
	if tpl.KeyTemplateID != nil {
		keys, err := st.Keys(ctx, *tpl.KeyTemplateID)
		if err != nil {
			return in, err
		}
		for _, k := range keys {
			s := KeyShortcut(k)
			if s.Line != "" {
				s.Line = accountName[strings.ToLower(s.Line)] // "" (default account) when the client lacks it
			}
			in.Shortcuts = append(in.Shortcuts, s)
		}
	}
	return in, nil
}

// hostedByAccount is a hosted book bound by hand with a service account. What no
// longer holds since the template was saved (account disabled, grant withdrawn,
// password reset) becomes a problem of the document instead of a silent failure.
func hostedByAccount(ctx context.Context, st *store.Store, p Platform, c *store.Client, cfg *store.ConfigTemplate,
	d *store.Directory, b *store.Book, clientPass map[int64]string, in *provision.Input) (rules.LdapDirectory, error) {
	a, err := st.LDAPAccountByID(ctx, *d.LDAPAccountID)
	if err != nil {
		return rules.LdapDirectory{}, err
	}
	password, err := directoryPassword(p.Box, c.ID, d, clientPass)
	if err != nil {
		return rules.LdapDirectory{}, err
	}
	name := d.Name
	if name == "" {
		name = b.Name
	}
	if problem := HostedAccountProblem(d, b, a, cfg.RegionID); problem != "" {
		in.Problems = append(in.Problems, "通讯录「"+name+"」："+problem)
	} else if password != "" && secret.HashHex(password) != a.PasswordHash {
		in.Problems = append(in.Problems, "通讯录「"+name+"」：密码与 LDAP 账号「"+a.Name+"」不符（账号的密码可能已重置）")
	}
	return p.Hosted.HostedLdap(d, b, AccountBindDN(a.Name, p.Hosted.Suffix), password), nil
}

func directoryPassword(box *secret.Box, clientID int64, d *store.Directory, clientPass map[int64]string) (string, error) {
	var enc string
	var aad []byte
	switch d.PasswordMode {
	case PasswordTemplate:
		enc, aad = d.PasswordEnc, secret.AAD("template_directories", d.ID, "password_enc")
	case PasswordClient:
		enc, aad = clientPass[d.ID], store.DirectoryPasswordAAD(clientID, d.ID)
	}
	if enc == "" {
		return "", nil
	}
	b, err := box.Open(enc, aad)
	if err != nil {
		return "", fmt.Errorf("directory %d password: %w", d.ID, err)
	}
	return string(b), nil
}
