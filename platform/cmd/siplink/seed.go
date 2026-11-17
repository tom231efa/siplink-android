package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"strings"
	"time"

	"siplink/internal/books"
	"siplink/internal/catalog"
	"siplink/internal/rules"
	"siplink/internal/secret"
	"siplink/internal/store"
)

// seedSpec is the JSON file of "siplink seed" (docs/examples/seed.example.json). Every
// part is optional except region, configTemplate, clientTemplate and client. Objects
// are found by name and reused, so a second run changes them instead of failing.
// Passwords can be given as "passwordEnv": the name of an environment variable.
type seedSpec struct {
	Region         string `json:"region"`
	ConfigTemplate struct {
		Name           string `json:"name"`
		RefreshMinutes int    `json:"refreshMinutes"`
		Profile        struct {
			Name      string `json:"name"`
			Domain    string `json:"domain"`
			Port      string `json:"port"`
			Transport string `json:"transport"`
			Default   bool   `json:"default"`
		} `json:"profile"`
		CAPEMFile         string    `json:"caPemFile"`
		ExternalDirectory *seedDir  `json:"externalDirectory"`
		HostedBook        *seedBook `json:"hostedBook"`
	} `json:"configTemplate"`
	KeyTemplate *struct {
		Name    string `json:"name"`
		CSVFile string `json:"csvFile"` // the app's format: 名称,号码[,类型[,分类[,线路[,图标]]]]
	} `json:"keyTemplate"`
	ClientTemplate struct {
		Name string `json:"name"`
	} `json:"clientTemplate"`
	Client struct {
		Name        string `json:"name"`
		Username    string `json:"username"`
		Password    string `json:"password"`
		PasswordEnv string `json:"passwordEnv"`
		AuthUser    string `json:"authUser"`
		DisplayName string `json:"displayName"`
	} `json:"client"`
}

type seedDir struct {
	Name         string `json:"name"`
	Host         string `json:"host"`
	Port         int    `json:"port"`
	Security     string `json:"security"`
	BindDN       string `json:"bindDn"`
	BaseDN       string `json:"baseDn"`
	PasswordMode string `json:"passwordMode"` // none / template (one password for every client) / client
	Password     string `json:"password"`
	PasswordEnv  string `json:"passwordEnv"`
}

type seedBook struct {
	Slug    string `json:"slug"`
	Name    string `json:"name"`
	CSVFile string `json:"csvFile"` // the platform's book CSV (姓名,电话,手机,…)
}

func secretValue(plain, env string) string {
	if env != "" {
		return os.Getenv(env)
	}
	return plain
}

// seedCommand creates or updates the objects of a spec file through the same rules as
// the web UI and prints the client's enrollment URL.
func seedCommand(args []string, out io.Writer) error {
	if len(args) != 1 {
		return errors.New("用法: siplink seed <规格文件.json>")
	}
	raw, err := os.ReadFile(args[0])
	if err != nil {
		return err
	}
	var spec seedSpec
	dec := json.NewDecoder(strings.NewReader(string(raw)))
	dec.DisallowUnknownFields()
	if err := dec.Decode(&spec); err != nil {
		return fmt.Errorf("规格文件: %w", err)
	}
	ctx := context.Background()
	e, err := open(ctx)
	if err != nil {
		return err
	}
	defer e.st.Close()
	sd := &seeder{ctx: ctx, e: e}
	u, err := sd.run(&spec)
	if err != nil {
		return err
	}
	fmt.Fprintln(out, u)
	return nil
}

type seeder struct {
	ctx context.Context
	e   *env
}

func (sd *seeder) audit(action, objType string, id int64, name string, region *int64) {
	_ = sd.e.st.AddAudit(sd.ctx, &store.AuditEntry{AdminName: "CLI", Action: action, ObjectType: objType, ObjectID: &id,
		ObjectName: name, RegionID: region, Detail: map[string]any{"seed": true}})
}

func problems(list []string) error {
	if len(list) == 0 {
		return nil
	}
	return errors.New(strings.Join(list, "；"))
}

func (sd *seeder) run(spec *seedSpec) (string, error) {
	st, ctx := sd.e.st, sd.ctx
	region, err := sd.region(spec.Region)
	if err != nil {
		return "", err
	}
	rid := &region.ID
	cfg, err := sd.config(spec, rid)
	if err != nil {
		return "", fmt.Errorf("配置模板: %w", err)
	}
	var keyID *int64
	if spec.KeyTemplate != nil {
		k, err := sd.keys(spec.KeyTemplate.Name, spec.KeyTemplate.CSVFile, rid)
		if err != nil {
			return "", fmt.Errorf("功能键模板: %w", err)
		}
		keyID = &k.ID
	}
	tpl, err := sd.clientTemplate(spec.ClientTemplate.Name, rid, cfg.ID, keyID)
	if err != nil {
		return "", fmt.Errorf("客户端模板: %w", err)
	}
	profiles, err := st.Profiles(ctx, cfg.ID)
	if err != nil {
		return "", err
	}
	var prof *store.Profile
	for _, p := range profiles {
		if strings.EqualFold(p.Name, spec.ConfigTemplate.Profile.Name) {
			prof = p
		}
	}
	in := store.CredentialInput{ProfileID: prof.ID, Username: spec.Client.Username,
		Password: secretValue(spec.Client.Password, spec.Client.PasswordEnv), AuthUser: spec.Client.AuthUser,
		DisplayName: spec.Client.DisplayName}
	catalog.NormalizeCredential(prof, &in)
	if err := problems(catalog.CredentialProblems(prof, in, nil)); err != nil {
		return "", fmt.Errorf("客户端: %w", err)
	}
	if p := catalog.ClientNameProblem(spec.Client.Name); p != "" {
		return "", fmt.Errorf("客户端: %s", p)
	}
	list, _, err := st.ListClients(ctx, store.ClientFilter{All: true, Region: region.ID, Query: spec.Client.Name})
	if err != nil {
		return "", err
	}
	c := &store.Client{Name: spec.Client.Name, RegionID: region.ID}
	for _, x := range list {
		if strings.EqualFold(x.Name, spec.Client.Name) {
			c = x
		}
	}
	c.ClientTemplateID = tpl.ID
	expires := time.Now().Add(time.Duration(sd.e.cfg.EnrollDays) * 24 * time.Hour)
	c.EnrollExpiresAt = expires.UTC().Format(store.TimeFormat)
	isNew := c.ID == 0
	token := secret.Token(32)
	if err := st.SaveClient(ctx, c, []store.CredentialInput{in}, nil, sd.e.box, token); err != nil {
		return "", fmt.Errorf("客户端: %w", err)
	}
	if !isNew { // an existing client gets a fresh QR code (its phone, if any, is unbound)
		if err := st.Unbind(ctx, c, token, expires, sd.e.box); err != nil {
			return "", err
		}
	}
	if isNew {
		sd.audit("client.create", "client", c.ID, c.Name, rid)
	} else {
		sd.audit("client.unbind", "client", c.ID, c.Name, rid)
	}
	pin, err := sd.pin()
	if err != nil {
		return "", err
	}
	u := strings.TrimRight(sd.e.cfg.PublicURL, "/") + "/p/" + token
	if pin != "" {
		u += "?pin=" + pin
	}
	return u, nil
}

// pin is the pin of QR codes (§3.1), as the running platform puts it into them.
func (sd *seeder) pin() (string, error) {
	certs, err := openCerts(sd.ctx, sd.e, false)
	if err != nil {
		return "", err
	}
	return certs.Pin(), nil
}

func (sd *seeder) region(name string) (*store.Region, error) {
	regions, err := sd.e.st.ListRegions(sd.ctx, nil, true)
	if err != nil {
		return nil, err
	}
	for _, r := range regions {
		if r.Name == name {
			return &r, nil
		}
	}
	if strings.TrimSpace(name) == "" {
		return nil, errors.New("缺少区域名称")
	}
	r := &store.Region{Name: name}
	if err := sd.e.st.CreateRegion(sd.ctx, r); err != nil {
		return nil, err
	}
	sd.audit("region.create", "region", r.ID, r.Name, &r.ID)
	return r, nil
}

func (sd *seeder) config(spec *seedSpec, rid *int64) (*store.ConfigTemplate, error) {
	st, ctx, cs := sd.e.st, sd.ctx, &spec.ConfigTemplate
	if p := catalog.NameProblem(cs.Name); p != "" {
		return nil, errors.New(p)
	}
	all, err := st.ListConfigTemplates(ctx, store.BookScope{All: true, Region: *rid})
	if err != nil {
		return nil, err
	}
	var t *store.ConfigTemplate
	for _, x := range all {
		if x.RegionID != nil && strings.EqualFold(x.Name, cs.Name) {
			t = x
		}
	}
	if t == nil {
		t = &store.ConfigTemplate{Name: cs.Name, RegionID: rid, RefreshMinutes: 60, SettingsJSON: catalog.SettingsJSON(catalog.ParseSettings(""))}
		if err := st.CreateConfigTemplate(ctx, t); err != nil {
			return nil, err
		}
		sd.audit("config_template.create", "config_template", t.ID, t.Name, rid)
	}
	if cs.RefreshMinutes != 0 {
		if p := catalog.RefreshProblem(cs.RefreshMinutes); p != "" {
			return nil, errors.New(p)
		}
		t.RefreshMinutes = cs.RefreshMinutes
	}
	if cs.CAPEMFile != "" {
		pemText, err := os.ReadFile(cs.CAPEMFile)
		if err != nil {
			return nil, err
		}
		if list, err := catalog.SplitCAs(string(pemText)); err != nil || len(list) == 0 {
			return nil, fmt.Errorf("CA 文件 %s 中未找到证书", cs.CAPEMFile)
		}
		t.CAPEMs = strings.Join(catalog.MergeCAs(t.CAPEMs, string(pemText)), "")
	}
	if err := st.UpdateConfigTemplate(ctx, t); err != nil {
		return nil, err
	}
	// The one account profile.
	ps := cs.Profile
	others, err := st.Profiles(ctx, t.ID)
	if err != nil {
		return nil, err
	}
	p := &store.Profile{TemplateID: t.ID, Expires: 3600, Enabled: true}
	for _, o := range others {
		if strings.EqualFold(o.Name, ps.Name) {
			p = o
		}
	}
	p.Name, p.Domain, p.Port, p.Transport, p.IsDefault = ps.Name, ps.Domain, ps.Port, strings.ToUpper(ps.Transport), ps.Default || len(others) == 0
	catalog.NormalizeProfile(p)
	if err := problems(catalog.ProfileProblems(p, others)); err != nil {
		return nil, err
	}
	if err := st.SaveProfile(ctx, p, rules.MaxAccounts); err != nil {
		return nil, err
	}
	if cs.HostedBook != nil {
		if err := sd.hostedBook(t, cs.HostedBook, rid); err != nil {
			return nil, fmt.Errorf("托管通讯录: %w", err)
		}
	}
	if cs.ExternalDirectory != nil {
		if err := sd.external(t, cs.ExternalDirectory, rid); err != nil {
			return nil, fmt.Errorf("外部通讯录: %w", err)
		}
	}
	return t, nil
}

func (sd *seeder) hosted() catalog.Hosted {
	c := sd.e.cfg
	return catalog.Hosted{Host: c.LDAPPublicHost, Port: c.LDAPPublicPort, Suffix: c.LDAPSuffix, RequireTLS: c.LDAPRequireTLS}
}

// directory finds a directory of the template by name (or by book) for reuse.
func (sd *seeder) directory(t *store.ConfigTemplate, match func(*store.Directory) bool) (*store.Directory, []*store.Directory, error) {
	dirs, err := sd.e.st.Directories(sd.ctx, t.ID)
	if err != nil {
		return nil, nil, err
	}
	for _, d := range dirs {
		if match(d) {
			return d, dirs, nil
		}
	}
	return nil, dirs, nil
}

func (sd *seeder) hostedBook(t *store.ConfigTemplate, sb *seedBook, rid *int64) error {
	st, ctx := sd.e.st, sd.ctx
	b, err := st.BookBySlug(ctx, sb.Slug)
	if errors.Is(err, store.ErrNotFound) {
		if p := books.SlugProblem(sb.Slug); p != "" {
			return errors.New(p)
		}
		b = &store.Book{Slug: sb.Slug, Name: sb.Name, RegionID: rid}
		if err = st.CreateBook(ctx, b, sd.e.cfg.MaxBooks); err != nil {
			return err
		}
		sd.audit("book.create", "book", b.ID, b.Name, rid)
	} else if err != nil {
		return err
	}
	if sb.CSVFile != "" {
		data, err := os.ReadFile(sb.CSVFile)
		if err != nil {
			return err
		}
		parsed, err := books.ParseCSV(data)
		if err != nil {
			return err
		}
		if len(parsed.Errors) > 0 {
			return fmt.Errorf("CSV 第 %d 行: %s", parsed.Errors[0].Line, parsed.Errors[0].Message)
		}
		if err := st.AddEntries(ctx, b.ID, parsed.Entries, true, sd.e.cfg.MaxBookEntries); err != nil {
			return err
		}
		sd.audit("book.import", "book", b.ID, b.Name, rid)
	}
	d, others, err := sd.directory(t, func(d *store.Directory) bool { return d.BookID != nil && *d.BookID == b.ID })
	if err != nil || d != nil {
		return err // already there
	}
	d = &store.Directory{TemplateID: t.ID, Kind: store.KindHosted, BookID: &b.ID, Source: b.Name, Enabled: true,
		PasswordMode: catalog.PasswordNone, LookupIncoming: true, LookupOutgoing: true, MaxResults: 50}
	if err := problems(catalog.DirectoryProblems(d, others, b, sd.hosted(), true)); err != nil {
		return err
	}
	return st.SaveDirectory(ctx, d, rules.MaxDirectories, nil)
}

// external creates or updates the external directory of the region by its name and
// makes the template use it.
func (sd *seeder) external(t *store.ConfigTemplate, sx *seedDir, rid *int64) error {
	st, ctx := sd.e.st, sd.ctx
	list, err := st.ListExternalDirectories(ctx, store.BookScope{All: true, Region: *rid})
	if err != nil {
		return err
	}
	e := &store.ExternalDirectory{RegionID: rid, MaxResults: 50, NameAttributes: rules.LdapDefaultNameAttributes,
		NumberAttributes: rules.LdapDefaultNumberAttributes}
	for _, x := range list {
		if strings.EqualFold(x.Name, sx.Name) {
			e = x
		}
	}
	isNew := e.ID == 0
	e.Name, e.Host, e.Port, e.Security, e.BindDN, e.BaseDN = sx.Name, sx.Host, sx.Port, strings.ToUpper(sx.Security), sx.BindDN, sx.BaseDN
	e.PasswordMode = sx.PasswordMode
	if e.PasswordMode == "" {
		e.PasswordMode = catalog.PasswordNone
	}
	if e.Port == 0 {
		e.Port = 389
	}
	catalog.NormalizeExternal(e)
	if err := problems(catalog.ExternalProblems(e)); err != nil {
		return err
	}
	var seal func(int64) (string, error)
	if pw := secretValue(sx.Password, sx.PasswordEnv); e.PasswordMode == catalog.PasswordTemplate && pw != "" {
		seal = func(id int64) (string, error) { return sd.e.box.Seal([]byte(pw), store.ExternalDirectoryAAD(id)) }
	}
	if err := st.SaveExternalDirectory(ctx, e, seal); err != nil {
		return err
	}
	if isNew {
		sd.audit("directory.create", "directory", e.ID, e.Name, rid)
	}
	d, others, err := sd.directory(t, func(d *store.Directory) bool { return d.ExternalID != nil && *d.ExternalID == e.ID })
	if err != nil || d != nil {
		return err // already used by the template
	}
	d = &store.Directory{TemplateID: t.ID, Kind: store.KindExternal, ExternalID: &e.ID, External: e, Source: e.Name, Enabled: true,
		PasswordMode: catalog.PasswordNone, LookupIncoming: true, LookupOutgoing: true, MaxResults: 50}
	if err := problems(catalog.DirectoryProblems(d, others, nil, sd.hosted(), true)); err != nil {
		return err
	}
	return st.SaveDirectory(ctx, d, rules.MaxDirectories, nil)
}

func (sd *seeder) keys(name, csvFile string, rid *int64) (*store.KeyTemplate, error) {
	st, ctx := sd.e.st, sd.ctx
	if p := catalog.NameProblem(name); p != "" {
		return nil, errors.New(p)
	}
	all, err := st.ListKeyTemplates(ctx, store.BookScope{All: true, Region: *rid})
	if err != nil {
		return nil, err
	}
	var k *store.KeyTemplate
	for _, x := range all {
		if x.RegionID != nil && strings.EqualFold(x.Name, name) {
			k = x
		}
	}
	if k == nil {
		k = &store.KeyTemplate{Name: name, RegionID: rid}
		if err := st.CreateKeyTemplate(ctx, k); err != nil {
			return nil, err
		}
		sd.audit("key_template.create", "key_template", k.ID, k.Name, rid)
	}
	if csvFile == "" {
		return k, nil
	}
	data, err := os.ReadFile(csvFile)
	if err != nil {
		return nil, err
	}
	text, _, err := books.Decode(data)
	if err != nil {
		return nil, err
	}
	parsed := rules.DecodeShortcuts(text, rules.MaxShortcuts, 1)
	if len(parsed.Errors) > 0 {
		return nil, fmt.Errorf("CSV 第 %d 行: %s", parsed.Errors[0].Line, parsed.Errors[0].Reason)
	}
	var keys []*store.TemplateKey
	for _, s := range parsed.Shortcuts {
		keys = append(keys, catalog.StoredKey(s))
	}
	return k, st.AddKeys(ctx, k.ID, keys, true, rules.MaxShortcuts)
}

func (sd *seeder) clientTemplate(name string, rid *int64, configID int64, keyID *int64) (*store.ClientTemplate, error) {
	st, ctx := sd.e.st, sd.ctx
	if p := catalog.NameProblem(name); p != "" {
		return nil, errors.New(p)
	}
	all, err := st.ListClientTemplates(ctx, store.BookScope{All: true, Region: *rid})
	if err != nil {
		return nil, err
	}
	t := &store.ClientTemplate{Name: name, RegionID: rid}
	for _, x := range all {
		if x.RegionID != nil && strings.EqualFold(x.Name, name) {
			t = x
		}
	}
	t.ConfigTemplateID, t.KeyTemplateID = configID, keyID
	// Key lines must name a profile of the configuration template (§3.5.7).
	if keyID != nil {
		profiles, _ := st.Profiles(ctx, configID)
		var names []string
		for _, p := range profiles {
			names = append(names, p.Name)
		}
		keys, _ := st.Keys(ctx, *keyID)
		for _, k := range keys {
			if p := catalog.LineProblem(k.Line, names); p != "" {
				return nil, fmt.Errorf("功能键「%s」的%s", k.Label, p)
			}
		}
	}
	if t.ID == 0 {
		if err := st.CreateClientTemplate(ctx, t); err != nil {
			return nil, err
		}
		sd.audit("client_template.create", "client_template", t.ID, t.Name, rid)
		return t, nil
	}
	return t, st.UpdateClientTemplate(ctx, t)
}
