package provision_test

import (
	"bytes"
	"context"
	"crypto/ed25519"
	"crypto/sha256"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/json"
	"encoding/pem"
	"flag"
	"math/big"
	"os"
	"path/filepath"
	"reflect"
	"siplink/internal/i18n"
	"strings"
	"testing"
	"time"

	"siplink/internal/catalog"
	"siplink/internal/provision"
	"siplink/internal/rules"
	"siplink/internal/secret"
	"siplink/internal/store"
	"siplink/internal/testutil"
)

// go test ./internal/provision -update rewrites the golden files.
var update = flag.Bool("update", false, "rewrite golden files")

var fixedTime = time.Date(2026, 11, 16, 8, 0, 0, 0, time.UTC)

// testCert makes a CA certificate from a fixed seed: Ed25519 signatures are
// deterministic, so the PEM is the same on every run. Certificates are public.
func testCert(t *testing.T, name string) string {
	t.Helper()
	seed := sha256.Sum256([]byte(name))
	key := ed25519.NewKeyFromSeed(seed[:])
	tpl := &x509.Certificate{SerialNumber: big.NewInt(1), Subject: pkix.Name{CommonName: name},
		NotBefore: fixedTime, NotAfter: fixedTime.AddDate(10, 0, 0), IsCA: true, BasicConstraintsValid: true,
		KeyUsage: x509.KeyUsageCertSign}
	der, err := x509.CreateCertificate(nil, tpl, tpl, key.Public(), key)
	if err != nil {
		t.Fatal(err)
	}
	return string(pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der}))
}

type world struct {
	t      *testing.T
	st     *store.Store
	box    *secret.Box
	p      catalog.Platform
	region *store.Region
}

func newWorld(t *testing.T, ldapHost string, requireTLS bool) *world {
	return newWorldOn(t, testutil.Store(t), ldapHost, requireTLS)
}

// newWorldOn is newWorld on a given database.
func newWorldOn(t *testing.T, st *store.Store, ldapHost string, requireTLS bool) *world {
	st.Now = func() time.Time { return fixedTime }
	w := &world{t: t, st: st, box: testutil.Box(t), region: &store.Region{Name: "华东"}}
	w.must(st.CreateRegion(context.Background(), w.region))
	w.p = catalog.Platform{Name: "SipLink 管理平台", SyncURL: "https://platform.example.com/api/device/v1/sync",
		Hosted: catalog.Hosted{Host: ldapHost, Port: 389, Suffix: "dc=siplink", RequireTLS: requireTLS}, Box: w.box,
		LDAPKey: [32]byte{7}}
	return w
}

func (w *world) must(err error) {
	w.t.Helper()
	if err != nil {
		w.t.Fatal(err)
	}
}

// config makes a configuration template with settings changed by edit.
func (w *world) config(name string, edit func(*provision.Settings, *store.ConfigTemplate)) *store.ConfigTemplate {
	s := provision.DefaultSettings()
	c := &store.ConfigTemplate{Name: name, RefreshMinutes: 60}
	if edit != nil {
		edit(&s, c)
	}
	c.SettingsJSON = catalog.SettingsJSON(s)
	w.must(w.st.CreateConfigTemplate(context.Background(), c))
	return c
}

// external stores an external directory, with the password kept for every client when
// one is given.
func (w *world) external(e *store.ExternalDirectory, password string) *store.ExternalDirectory {
	w.t.Helper()
	var seal func(int64) (string, error)
	if password != "" {
		seal = func(id int64) (string, error) { return w.box.Seal([]byte(password), store.ExternalDirectoryAAD(id)) }
	}
	w.must(w.st.SaveExternalDirectory(context.Background(), e, seal))
	return e
}

func (w *world) profile(c *store.ConfigTemplate, name, domain, transport string, def bool) *store.Profile {
	p := &store.Profile{TemplateID: c.ID, Name: name, Domain: domain, Transport: transport, Expires: 3600, Enabled: true,
		IsDefault: def, SubscribeMWI: true}
	if v, err := w.st.SchemaVersion(context.Background()); err == nil && v < timeoutSchema {
		testutil.OldProfile(w.t, w.st, p)
		return p
	}
	w.must(w.st.SaveProfile(context.Background(), p, rules.MaxAccounts))
	return p
}

// client makes a client with a fixed public id and the given credentials.
func (w *world) client(name string, tpl *store.ClientTemplate, creds []store.CredentialInput, dirs map[int64]string, edit func(*store.Client)) *store.Client {
	ctx := context.Background()
	c := &store.Client{Name: name, RegionID: w.region.ID, ClientTemplateID: tpl.ID,
		EnrollExpiresAt: fixedTime.Add(time.Hour).Format(store.TimeFormat)}
	if edit != nil {
		edit(c)
	}
	old, err := w.st.SchemaVersion(ctx)
	w.must(err)
	if old < groupsSchema {
		testutil.OldClient(w.t, w.st, w.box, c, creds, dirs)
	} else {
		w.must(w.st.SaveClient(ctx, c, creds, dirs, nil, w.box, "token-"+name))
	}
	_, err = w.st.DB.ExecContext(ctx, `UPDATE clients SET public_id = ? WHERE id = ?`, "pub"+strings.Repeat("x", 7-len(name))+name, c.ID)
	w.must(err)
	if old < groupsSchema {
		return c // read again once the database is migrated
	}
	c, err = w.st.ClientByID(ctx, c.ID)
	w.must(err)
	return c
}

func (w *world) document(c *store.Client) (*provision.Document, []string) {
	in, err := catalog.Assemble(context.Background(), w.st, w.p, c)
	w.must(err)
	d, problems := provision.Build(in)
	d.GeneratedAt = fixedTime.Format(time.RFC3339)
	return d, i18n.For(i18n.Source).Msgs(problems)
}

// golden compares a document with a file, or writes it with -update.
func golden(t *testing.T, path string, d *provision.Document) {
	t.Helper()
	raw, err := provision.Marshal(d)
	if err != nil {
		t.Fatal(err)
	}
	var buf bytes.Buffer
	if err := json.Indent(&buf, raw, "", "  "); err != nil {
		t.Fatal(err)
	}
	buf.WriteByte('\n')
	if *update {
		if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(path, buf.Bytes(), 0o644); err != nil {
			t.Fatal(err)
		}
		return
	}
	want, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("%v (run with -update to create it)", err)
	}
	if !bytes.Equal(want, buf.Bytes()) {
		t.Errorf("%s differs from the built document:\n%s", path, buf.String())
	}
}

func TestGoldenMinimal(t *testing.T) {
	w := newWorld(t, "platform.example.com", true)
	cfg := w.config("最小", nil)
	p := w.profile(cfg, "总部", "pbx.example.com", "UDP", true)
	tpl := &store.ClientTemplate{Name: "最小", ConfigTemplateID: cfg.ID}
	w.must(w.st.CreateClientTemplate(context.Background(), tpl))
	c := w.client("min", tpl, []store.CredentialInput{{ProfileID: p.ID, Username: "1001", Password: "test-password-1001"}}, nil, nil)
	d, problems := w.document(c)
	if len(problems) > 0 {
		t.Fatal(problems)
	}
	golden(t, "testdata/minimal.json", d)
}

// directoryMaker adds the four directories of the full example to a template and
// returns the id of the use whose password each client sets.
type directoryMaker func(cfg *store.ConfigTemplate, hq *store.Profile, book *store.Book) (personal int64)

// full builds the example of docs/examples: two profiles, the three password modes of
// external directories, a hosted book, forwarding overrides, codecs, CAs and keys. The
// directories are made by w.directories unless another maker is given.
func full(t *testing.T, w *world, makers ...directoryMaker) (*store.Client, *store.ClientTemplate) {
	ctx := context.Background()
	cfg := w.config("标准", func(s *provision.Settings, c *store.ConfigTemplate) {
		s.ForwardBusy, s.Features.Park, s.VideoCalls = "8000", "*85", true
		s.UserAgent, s.AdaptiveRateControl, s.EchoCancellation = "SipLink-Example/1.0", true, false
		s.RecordByDefault = true
		s.AutoAnswer, s.AutoAnswerSeconds = true, 5
		c.RefreshMinutes = 30
		c.ConfigPasswordHash, _ = catalog.ConfigPasswordHash("example-config-password")
		c.CodecsJSON = `{"audio":[{"key":"opus/48000/2","enabled":true},{"key":"PCMA/8000/1","enabled":true},{"key":"PCMU/8000/1","enabled":false}],"video":[{"key":"H264/90000/1","enabled":true}]}`
		c.CAPEMs = testCert(t, "Example PBX CA")
	})
	hq := w.profile(cfg, "总部", "pbx.example.com", "TLS", false)
	branch := w.profile(cfg, "分部", "192.0.2.20:5070", "UDP", true)
	book := &store.Book{Slug: "staff", Name: "员工通讯录"}
	w.must(w.st.CreateBook(ctx, book, 500))
	makeDirs := w.directories
	if len(makers) > 0 {
		makeDirs = makers[0]
	}
	personal := makeDirs(cfg, hq, book)
	keys := &store.KeyTemplate{Name: "前台"}
	w.must(w.st.CreateKeyTemplate(ctx, keys))
	w.must(w.st.AddKeys(ctx, keys.ID, []*store.TemplateKey{
		{Label: "保安室", Number: "8001", Action: "speed", Category: "内部", Line: "总部", Icon: "star"},
		{Label: "转财务", Number: "8002", Action: "bxfer", Category: "内部"},
		{Label: "免打扰", Action: "dnd"},
	}, false, rules.MaxShortcuts))
	tpl := &store.ClientTemplate{Name: "前台", ConfigTemplateID: cfg.ID, KeyTemplateID: &keys.ID}
	w.must(w.st.CreateClientTemplate(ctx, tpl))
	name := "前台一号"
	c := w.client("desk1", tpl, []store.CredentialInput{
		{ProfileID: hq.ID, Username: "1001", Password: "test-password-1001", DisplayName: "前台", AccountName: &name},
		{ProfileID: branch.ID, Username: "2001", AuthUser: "auth2001", Password: "test-password-2001"},
	}, map[int64]string{personal: "test-personal-password"}, func(c *store.Client) {
		always := "13800000000"
		c.ForwardAlways = &always
	})
	return c, tpl
}

// directories makes the directories of the full example as they are stored now: three
// external directories and four uses.
func (w *world) directories(cfg *store.ConfigTemplate, hq *store.Profile, book *store.Book) int64 {
	ctx := context.Background()
	company := w.external(&store.ExternalDirectory{Name: "公司目录", Host: "ldap.example.com", Port: 389, Security: "START_TLS",
		BindDN: "cn=reader,dc=example,dc=com", PasswordMode: catalog.PasswordTemplate, BaseDN: "ou=people,dc=example,dc=com",
		MaxResults: 100, NameAttributes: "cn sn", NumberAttributes: "telephoneNumber mobile"}, "test-directory-password")
	personal := w.external(&store.ExternalDirectory{Name: "个人目录", Host: "ldap.example.com", Port: 389, Security: "START_TLS",
		BindDN: "uid=1001,ou=people,dc=example,dc=com", PasswordMode: catalog.PasswordClient, BaseDN: "ou=people,dc=example,dc=com",
		MaxResults: 50, NameAttributes: "displayName", NumberAttributes: "telephoneNumber", NameFilter: "(displayName=*%s*)"}, "")
	public := w.external(&store.ExternalDirectory{Name: "公共目录", Host: "directory.example.com", Port: 389, Security: "NONE",
		PasswordMode: catalog.PasswordNone, BaseDN: "dc=example,dc=com", MaxResults: 20, NameAttributes: "cn",
		NumberAttributes: "telephoneNumber"}, "")
	dirs := []*store.Directory{
		{Kind: store.KindHosted, BookID: &book.ID, Enabled: true, PasswordMode: catalog.PasswordNone, MaxResults: 50, LookupIncoming: true, LookupOutgoing: true, LineProfileID: &hq.ID},
		{Kind: store.KindExternal, ExternalID: &company.ID, Enabled: true, LookupIncoming: true, LookupOutgoing: true},
		{Kind: store.KindExternal, ExternalID: &personal.ID, Name: "个人目录", Enabled: true, LookupIncoming: false, LookupOutgoing: true},
		{Kind: store.KindExternal, ExternalID: &public.ID, Enabled: false, LookupIncoming: true, LookupOutgoing: true},
	}
	for _, d := range dirs {
		d.TemplateID = cfg.ID
		w.must(w.st.SaveDirectory(ctx, d, rules.MaxDirectories, nil))
	}
	return dirs[2].ID
}

func TestGoldenFull(t *testing.T) {
	w := newWorld(t, "platform.example.com", true)
	w.p.CAPEMs = []string{testCert(t, "SipLink CA 00000000")}
	c, _ := full(t, w)
	d, problems := w.document(c)
	if len(problems) > 0 {
		t.Fatal(problems)
	}
	golden(t, "testdata/full.json", d)
	// The example in docs/ is the enrollment answer: the same document plus deviceKey.
	d.DeviceKey = strings.Repeat("K", 43)
	golden(t, "../../docs/examples/provisioning-document.json", d)

	// Details the golden file shows, asserted so a careless -update cannot hide them.
	if d.Accounts[0].Name != "前台一号" || d.DefaultAccount != d.Accounts[1].Key || d.Settings.ForwardAlways != "13800000000" ||
		d.Settings.ForwardBusy != "8000" || d.Directories[0].Security != rules.LdapSecurityStartTLS ||
		d.Directories[0].Line != d.Accounts[0].Key || d.Directories[0].BaseDn != "ou=staff,ou=books,dc=siplink" ||
		d.Directories[1].Password != "test-directory-password" || d.Directories[2].Password != "test-personal-password" ||
		d.Directories[3].Password != "" || d.Shortcuts[0].Line != "前台一号" || len(d.CACertificates) != 2 ||
		!d.Settings.AutoAnswer || d.Settings.AutoAnswerSeconds != 5 ||
		!strings.Contains(d.CACertificates[0], "BEGIN CERTIFICATE") {
		t.Errorf("document details: %+v", d)
	}
}

func TestGoldenHostedWithoutTLS(t *testing.T) {
	w := newWorld(t, "192.0.2.10", false)
	c, _ := full(t, w)
	d, problems := w.document(c)
	if len(problems) > 0 {
		t.Fatal(problems)
	}
	if d.Directories[0].Security != rules.LdapSecurityNone || d.Directories[0].Host != "192.0.2.10" {
		t.Errorf("hosted directory: %+v", d.Directories[0])
	}
	golden(t, "testdata/hosted-plain.json", d)
}

func TestVersion(t *testing.T) {
	w := newWorld(t, "platform.example.com", true)
	c, tpl := full(t, w)
	a, _ := w.document(c)
	b, _ := w.document(c)
	b.GeneratedAt, b.DeviceKey = "2030-01-01T00:00:00Z", "something"
	vb, _ := provision.Version(b)
	if a.Version == "" || len(a.Version) != 64 || vb != a.Version {
		t.Fatalf("same input, different version: %s %s", a.Version, vb)
	}
	// Any change of a field gives a new version.
	cases := map[string]func(d *provision.Document){
		"setting":   func(d *provision.Document) { d.Settings.CallWaiting = !d.Settings.CallWaiting },
		"password":  func(d *provision.Document) { d.Accounts[0].Password += "x" },
		"key order": func(d *provision.Document) { d.Shortcuts[0], d.Shortcuts[1] = d.Shortcuts[1], d.Shortcuts[0] },
		"codecs":    func(d *provision.Document) { d.Codecs = nil },
	}
	for name, change := range cases {
		x, _ := w.document(c)
		change(x)
		if v, _ := provision.Version(x); v == a.Version {
			t.Errorf("%s: version unchanged", name)
		}
	}
	// A stored change shows up in the revision key and the version.
	key1, _ := w.st.RevisionKey(context.Background(), c.ID)
	w.must(w.st.ResetLDAP(context.Background(), c.ID))
	c, _ = w.st.ClientByID(context.Background(), c.ID)
	key2, _ := w.st.RevisionKey(context.Background(), c.ID)
	d2, _ := w.document(c)
	if key1 == key2 || d2.Version == a.Version {
		t.Error("LDAP reset did not change the revision key or the version")
	}
	_ = tpl
}

// TestNewSettingsDefaults: templates stored before the four SIP options, the two recording
// options and the two auto answer options existed read them as the defaults, a stored wait
// that cannot be chosen reads as the nearest one, and the User-Agent is checked.
func TestNewSettingsDefaults(t *testing.T) {
	old := catalog.ParseSettings(`{"keepAlive":true,"dndMode":"SOFTWARE"}`)
	if old.UserAgent != "" || old.AdaptiveRateControl || !old.EchoCancellation || !old.IPv6 || !old.CallRecording || old.RecordByDefault ||
		old.AutoAnswer || old.AutoAnswerSeconds != 3 {
		t.Errorf("absent fields: %+v", old)
	}
	for stored, want := range map[string]int{`{"autoAnswerSeconds":0}`: 0, `{"autoAnswerSeconds":30}`: 30,
		`{"autoAnswerSeconds":7}`: 5, `{"autoAnswerSeconds":-2}`: 0, `{"autoAnswerSeconds":600}`: 30} {
		if got := catalog.ParseSettings(stored).AutoAnswerSeconds; got != want {
			t.Errorf("%s: %d, want %d", stored, got, want)
		}
	}
	for v, ok := range map[string]bool{"": true, "Acme Phone/2.1 (x)": true, " lead": false, "trail ": false,
		"中文": false, "tab\t": false, strings.Repeat("a", 120): true, strings.Repeat("a", 121): false} {
		if got := provision.UserAgentProblem(v) == nil; got != ok {
			t.Errorf("UserAgentProblem(%q) ok=%v", v, got)
		}
	}
}

// TestHostedByAccount: a hosted book bound by hand gets the service account's DN and
// the template's password; a withdrawn grant or a reset password becomes a problem.
func TestHostedByAccount(t *testing.T) {
	ctx := context.Background()
	w := newWorld(t, "platform.example.com", true)
	cfg := w.config("手动", nil)
	p := w.profile(cfg, "总部", "pbx.example.com", "UDP", true)
	book := &store.Book{Slug: "staff", Name: "员工通讯录"}
	w.must(w.st.CreateBook(ctx, book, 500))
	pw := secret.RandomText(32) // made at test time, never printed
	acct := &store.LDAPAccount{Name: "desk-phones", PasswordHash: secret.HashHex(pw), BookIDs: []int64{book.ID}}
	w.must(w.st.CreateLDAPAccount(ctx, acct))
	d := &store.Directory{TemplateID: cfg.ID, Kind: store.KindHosted, BookID: &book.ID, Enabled: true,
		PasswordMode: catalog.PasswordTemplate, MaxResults: 50, LDAPAccountID: &acct.ID}
	w.must(w.st.SaveDirectory(ctx, d, rules.MaxDirectories, func(id int64) (string, error) {
		return w.box.Seal([]byte(pw), secret.AAD("template_directories", id, "password_enc"))
	}))
	tpl := &store.ClientTemplate{Name: "手动", ConfigTemplateID: cfg.ID}
	w.must(w.st.CreateClientTemplate(ctx, tpl))
	c := w.client("man", tpl, []store.CredentialInput{{ProfileID: p.ID, Username: "1001", Password: "test-password-1001"}}, nil, nil)
	doc, problems := w.document(c)
	if len(problems) > 0 || doc.Directories[0].BindDn != "cn=desk-phones,ou=accounts,dc=siplink" ||
		doc.Directories[0].Password != pw || doc.Directories[0].BaseDn != "ou=staff,ou=books,dc=siplink" {
		t.Fatalf("manual hosted directory: %v, bind DN %q", problems, doc.Directories[0].BindDn) // never print the password
	}
	key1, _ := w.st.RevisionKey(ctx, c.ID)
	w.st.Now = func() time.Time { return fixedTime.Add(time.Minute) }
	w.must(w.st.SetLDAPAccountPassword(ctx, acct.ID, secret.HashHex("another-test-value")))
	if _, problems = w.document(c); len(problems) != 1 || !strings.Contains(problems[0], "不符") {
		t.Errorf("reset password: %v", problems)
	}
	acct.BookIDs = nil
	w.must(w.st.UpdateLDAPAccount(ctx, acct))
	if _, problems = w.document(c); len(problems) != 1 || !strings.Contains(problems[0], "无权读取") {
		t.Errorf("withdrawn grant: %v", problems)
	}
	if key2, _ := w.st.RevisionKey(ctx, c.ID); key2 == key1 {
		t.Error("account change did not change the revision key")
	}
	if err := w.st.DeleteLDAPAccount(ctx, acct.ID); err != store.ErrInUse {
		t.Errorf("delete account in use: %v", err)
	}
}

func TestBuildRefusesInvalid(t *testing.T) {
	in := provision.Input{RefreshMinutes: 60, Settings: provision.DefaultSettings(),
		Accounts: []provision.KeyedAccount{
			{Key: "p1", Draft: rules.AccountDraft{Name: "a", Username: "1001", Domain: "pbx.example.com", Transport: "UDP", Expires: 3600, Enabled: true}},
			{Key: "p2", Draft: rules.AccountDraft{Name: "b", Username: "1001", Domain: "pbx.example.com", Transport: "UDP", Expires: 3600, Enabled: true}},
		}}
	zh := i18n.For(i18n.Source)
	_, problems := provision.Build(in)
	if len(problems) != 1 || !strings.Contains(zh.Msg(problems[0]), zh.Msg(rules.AccountDuplicate.Msg())) {
		t.Errorf("duplicate account: %v", problems)
	}
	in.Accounts = in.Accounts[:1]
	in.Settings.VideoFrameRate = 25
	if _, problems = provision.Build(in); len(problems) != 1 {
		t.Errorf("frame rate: %v", problems)
	}
	in.Settings.VideoFrameRate, in.Settings.AutoAnswerSeconds = 0, 4
	if _, problems = provision.Build(in); len(problems) != 1 || zh.Msg(problems[0]) != "自动接听时间无效" {
		t.Errorf("auto answer wait: %v", problems)
	}
}

// TestFieldNames pins the top-level and nested JSON names of §3.5.
func TestFieldNames(t *testing.T) {
	names := func(v any) []string {
		var out []string
		rt := reflect.TypeOf(v)
		for i := 0; i < rt.NumField(); i++ {
			out = append(out, strings.Split(rt.Field(i).Tag.Get("json"), ",")[0])
		}
		return out
	}
	want := map[string][]string{
		"document": {"schema", "version", "generatedAt", "deviceKey", "platform", "client", "refreshMinutes", "configPasswordHash",
			"settings", "accounts", "defaultAccount", "codecs", "caCertificates", "directories", "shortcuts"},
		"settings": {"keepAlive", "intercomAutoAnswer", "intercomSpeaker", "verifyTlsCertificate", "verifyTlsHostname", "mediaSecurity",
			"dndMode", "callWaiting", "rejectAnonymous", "dtmfMode", "forwardAlways", "forwardBusy", "forwardNoAnswer",
			"forwardNoAnswerSeconds", "features", "videoCalls", "videoSize", "videoFrameRate", "autoUpdateCheck",
			"userAgent", "adaptiveRateControl", "echoCancellation", "ipv6", "callRecording", "recordByDefault",
			"autoAnswer", "autoAnswerSeconds", "roomLimit", "autoNetwork", "stunServer"},
		"account": {"key", "name", "username", "password", "authUser", "displayName", "domain", "port", "server", "backupServer",
			"serverTimeoutMs", "dnsServer", "transport", "expires", "subscribeMwi", "enabled"},
		"directory": {"key", "name", "enabled", "host", "port", "security", "bindDn", "password", "baseDn", "line", "lookupIncoming",
			"lookupOutgoing", "maxResults", "nameAttributes", "numberAttributes", "nameFilter", "numberFilter"},
		"shortcut": {"label", "number", "action", "category", "line", "icon"},
	}
	got := map[string][]string{"document": names(provision.Document{}), "settings": names(provision.Settings{}),
		"account": names(provision.Account{}), "directory": names(provision.Directory{}), "shortcut": names(provision.Shortcut{})}
	for k := range want {
		if !reflect.DeepEqual(got[k], want[k]) {
			t.Errorf("%s: %v", k, got[k])
		}
	}
}
