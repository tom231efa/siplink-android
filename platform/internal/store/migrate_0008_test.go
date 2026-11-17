package store_test

import (
	"context"
	"database/sql"
	"strings"
	"testing"
	"unicode/utf16"

	"siplink/internal/rules"
	"siplink/internal/secret"
	"siplink/internal/store"
	"siplink/internal/testutil"
)

// oldWorld is a database at schema 7 with templates that type external servers inline.
type oldWorld struct {
	t   *testing.T
	st  *store.Store
	box *secret.Box
}

func (w *oldWorld) must(err error) {
	w.t.Helper()
	if err != nil {
		w.t.Fatal(err)
	}
}

func (w *oldWorld) template(name string, region *int64) *store.ConfigTemplate {
	t := &store.ConfigTemplate{Name: name, RegionID: region, RefreshMinutes: 60, SettingsJSON: "{}"}
	w.must(w.st.CreateConfigTemplate(context.Background(), t))
	return t
}

// inline adds an external directory the schema 7 way; password is sealed for the row
// when the mode is template.
func (w *oldWorld) inline(t *store.ConfigTemplate, name, host, mode, password string) int64 {
	ctx := context.Background()
	res, err := w.st.DB.ExecContext(ctx, `INSERT INTO template_directories(config_template_id, position, kind, name, enabled, host,
		port, security, bind_dn, password_mode, base_dn, max_results, created_at, updated_at)
		VALUES(?, 1, 'external', ?, 1, ?, 389, 'START_TLS', 'cn=reader,dc=example,dc=com', ?, 'dc=example,dc=com', 50, ?, ?)`,
		t.ID, name, host, mode, w.st.NowText(), w.st.NowText())
	w.must(err)
	id, _ := res.LastInsertId()
	if password != "" {
		enc, err := w.box.Seal([]byte(password), secret.AAD("template_directories", id, "password_enc"))
		w.must(err)
		_, err = w.st.DB.ExecContext(ctx, `UPDATE template_directories SET password_enc = ? WHERE id = ?`, enc, id)
		w.must(err)
	}
	return id
}

type migratedRow struct {
	external int64
	mode     string
	enc      sql.NullString
}

func migrated(t *testing.T, st *store.Store, id int64) migratedRow {
	t.Helper()
	var m migratedRow
	if err := st.DB.QueryRow(`SELECT external_id, password_mode, password_enc FROM template_directories WHERE id = ?`, id).
		Scan(&m.external, &m.mode, &m.enc); err != nil {
		t.Fatal(err)
	}
	return m
}

// TestMigrateExternalDirectories: inline external directories become entries of their
// template's region; identical ones share one, names are made unique within a region,
// passwords are sealed again for the entry, per-client passwords keep working, and the
// old columns go.
func TestMigrateExternalDirectories(t *testing.T) {
	ctx := context.Background()
	old, path := testutil.StoreAt(t, 7)
	w := &oldWorld{t: t, st: old, box: testutil.Box(t)}
	east := &store.Region{Name: "华东"}
	w.must(old.CreateRegion(ctx, east))
	t1, t2, global := w.template("一", &east.ID), w.template("二", &east.ID), w.template("全局", nil)
	long := strings.Repeat("目", rules.MaxDirectoryNameLength)

	shared1 := w.inline(t1, "公司", "ldap.example.com", "template", "secret-a")
	shared2 := w.inline(t2, "公司", "ldap.example.com", "template", "secret-a")
	otherPass := w.inline(t2, "公司", "ldap.example.com", "template", "secret-b")
	globalRow := w.inline(global, "公司", "ldap.example.com", "template", "secret-a")
	personal := w.inline(t1, "个人", "ldap.example.com", "client", "")
	anonymous := w.inline(t1, "公共", "dir.example.com", "none", "")
	long1 := w.inline(t1, long, "a.example.com", "none", "")
	long2 := w.inline(t2, long, "b.example.com", "none", "")

	tpl := &store.ClientTemplate{Name: "一", RegionID: &east.ID, ConfigTemplateID: t1.ID}
	w.must(old.CreateClientTemplate(ctx, tpl))
	c := &store.Client{Name: "前台", RegionID: east.ID, ClientTemplateID: tpl.ID, EnrollExpiresAt: "2099-01-01T00:00:00Z"}
	w.must(old.SaveClient(ctx, c, nil, map[int64]string{personal: "personal-secret"}, w.box, "token"))
	w.must(old.Close())

	st, err := store.Open(ctx, path, w.box)
	w.must(err)
	defer st.Close()

	rows := map[string]migratedRow{}
	for name, id := range map[string]int64{"shared1": shared1, "shared2": shared2, "otherPass": otherPass, "global": globalRow,
		"personal": personal, "anonymous": anonymous, "long1": long1, "long2": long2} {
		rows[name] = migrated(t, st, id)
		if rows[name].mode != "none" || rows[name].enc.Valid {
			t.Errorf("%s keeps a password of its own: %+v", name, rows[name])
		}
	}
	if rows["shared1"].external != rows["shared2"].external {
		t.Error("identical directories of one region do not share an entry")
	}
	if rows["otherPass"].external == rows["shared1"].external || rows["global"].external == rows["shared1"].external {
		t.Error("a directory with another password or of another region shares an entry")
	}
	entry := func(name string) *store.ExternalDirectory {
		e, err := st.ExternalDirectoryByID(ctx, rows[name].external)
		w.must(err)
		return e
	}
	if e := entry("shared1"); e.Name != "公司" || e.RegionID == nil || *e.RegionID != east.ID || e.PasswordMode != "template" {
		t.Errorf("shared entry: %+v", e)
	}
	if e := entry("otherPass"); e.Name != "公司（2）" {
		t.Errorf("taken name: %q", e.Name)
	}
	if e := entry("global"); e.Name != "公司" || e.RegionID != nil {
		t.Errorf("global entry: %+v", e)
	}
	if e := entry("long2"); !strings.HasSuffix(e.Name, "（2）") || len(utf16.Encode([]rune(e.Name))) != rules.MaxDirectoryNameLength {
		t.Errorf("long taken name: %q", e.Name)
	}
	for name, want := range map[string]string{"shared1": "secret-a", "otherPass": "secret-b", "global": "secret-a"} {
		e := entry(name)
		plain, err := w.box.Open(e.PasswordEnc, store.ExternalDirectoryAAD(e.ID))
		if err != nil || string(plain) != want {
			t.Errorf("%s: password does not open for the entry", name)
		}
	}
	if e := entry("personal"); e.PasswordMode != "client" || e.PasswordEnc != "" {
		t.Errorf("per-client entry: %+v", e)
	}

	// The template's use reads the entry; the client's own password is untouched.
	d, err := st.DirectoryByID(ctx, t1.ID, personal)
	w.must(err)
	if mode, _, _ := d.Password(); mode != "client" || d.DisplayName() != "个人" || d.External.Host != "ldap.example.com" {
		t.Errorf("use after migration: %+v", d)
	}
	pass, err := st.DirectoryPasswords(ctx, c.ID)
	w.must(err)
	if plain, err := w.box.Open(pass[personal], store.DirectoryPasswordAAD(c.ID, personal)); err != nil || string(plain) != "personal-secret" {
		t.Error("per-client password lost")
	}
	if _, err := st.DB.Exec(`SELECT host FROM template_directories`); err == nil {
		t.Error("0009 left the connection columns")
	}
	if _, err := st.DB.Exec(`INSERT INTO template_directories(config_template_id, position, kind, name, enabled, password_mode,
		max_results, created_at, updated_at) VALUES(?, 9, 'external', 'x', 1, 'none', 50, '', '')`, t1.ID); err == nil {
		t.Error("an external use without its directory was stored")
	}
}

// TestMigrateExternalNeedsKey: stored passwords cannot be sealed again without the key
// file; the migration then changes nothing.
func TestMigrateExternalNeedsKey(t *testing.T) {
	ctx := context.Background()
	old, path := testutil.StoreAt(t, 7)
	w := &oldWorld{t: t, st: old, box: testutil.Box(t)}
	w.inline(w.template("一", nil), "公司", "ldap.example.com", "template", "secret-a")
	w.must(old.Close())
	if st, err := store.Open(ctx, path, nil); err == nil {
		st.Close()
		t.Fatal("migrated without the key")
	}
	wrong, err := secret.NewBox([32]byte{1})
	w.must(err)
	if st, err := store.Open(ctx, path, wrong); err == nil {
		st.Close()
		t.Fatal("migrated with another key")
	}
	st, err := store.Open(ctx, path, w.box)
	w.must(err)
	defer st.Close()
	if n, _ := st.ListExternalDirectories(ctx, store.BookScope{All: true}); len(n) != 1 {
		t.Errorf("entries after the retry: %d", len(n))
	}
}
