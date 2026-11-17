package provision_test

import (
	"context"
	"testing"

	"siplink/internal/secret"
	"siplink/internal/store"
	"siplink/internal/testutil"
)

// inlineDirectories makes the directories of the full example as schema 7 stored them:
// external servers typed into the template's rows, the template password sealed for
// the row.
func (w *world) inlineDirectories(cfg *store.ConfigTemplate, hq *store.Profile, book *store.Book) int64 {
	ctx := context.Background()
	insert := func(kind string, bookID, line any, name string, enabled bool, host string, security, bindDN, mode, baseDN string,
		incoming bool, maxResults int, nameAttrs, numberAttrs, nameFilter string) int64 {
		res, err := w.st.DB.ExecContext(ctx, `INSERT INTO template_directories(config_template_id, position, kind, book_id, name,
			enabled, host, port, security, bind_dn, password_mode, base_dn, line_profile_id, lookup_incoming, lookup_outgoing,
			max_results, name_attributes, number_attributes, name_filter, number_filter, created_at, updated_at)
			VALUES(?, (SELECT COALESCE(max(position), 0) + 1 FROM template_directories WHERE config_template_id = ?), ?, ?, ?, ?, ?,
			389, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, ?, '', ?, ?)`, cfg.ID, cfg.ID, kind, bookID, name, enabled, host, security, bindDN,
			mode, baseDN, line, incoming, maxResults, nameAttrs, numberAttrs, nameFilter, w.st.NowText(), w.st.NowText())
		w.must(err)
		id, _ := res.LastInsertId()
		return id
	}
	insert("hosted", book.ID, hq.ID, "", true, "", "START_TLS", "", "none", "", true, 50, "cn", "telephoneNumber mobile", "")
	company := insert("external", nil, nil, "公司目录", true, "ldap.example.com", "START_TLS", "cn=reader,dc=example,dc=com",
		"template", "ou=people,dc=example,dc=com", true, 100, "cn sn", "telephoneNumber mobile", "")
	enc, err := w.box.Seal([]byte("test-directory-password"), secret.AAD("template_directories", company, "password_enc"))
	w.must(err)
	_, err = w.st.DB.ExecContext(ctx, `UPDATE template_directories SET password_enc = ? WHERE id = ?`, enc, company)
	w.must(err)
	personal := insert("external", nil, nil, "个人目录", true, "ldap.example.com", "START_TLS", "uid=1001,ou=people,dc=example,dc=com",
		"client", "ou=people,dc=example,dc=com", false, 50, "displayName", "telephoneNumber", "(displayName=*%s*)")
	insert("external", nil, nil, "公共目录", false, "directory.example.com", "NONE", "", "none", "dc=example,dc=com", true, 20,
		"cn", "telephoneNumber", "")
	return personal
}

// TestGoldenAfterMigration: the full example stored at schema 7, with an external
// directory in each password mode and a per-client password, gives the same document
// after migrations 0008 and 0009 as the golden file.
func TestGoldenAfterMigration(t *testing.T) {
	ctx := context.Background()
	old, path := testutil.StoreAt(t, 7)
	w := newWorldOn(t, old, "platform.example.com", true)
	w.p.CAPEMs = []string{testCert(t, "SipLink CA 00000000")}
	c, tpl := full(t, w, w.inlineDirectories)
	before, err := old.ConfigTemplateByID(ctx, tpl.ConfigTemplateID)
	w.must(err)
	w.must(old.Close())

	st, err := store.Open(ctx, path, w.box)
	w.must(err)
	t.Cleanup(func() { st.Close() })
	st.Now, w.st = old.Now, st
	if v, _ := st.SchemaVersion(ctx); v < 9 {
		t.Fatalf("schema version %d", v)
	}
	if after, _ := st.ConfigTemplateByID(ctx, tpl.ConfigTemplateID); after.Revision != before.Revision {
		t.Errorf("the migration changed the template revision: %d → %d", before.Revision, after.Revision)
	}
	entries, err := st.ListExternalDirectories(ctx, store.BookScope{All: true})
	w.must(err)
	if len(entries) != 3 {
		t.Fatalf("entries: %d", len(entries))
	}
	c, err = st.ClientByID(ctx, c.ID)
	w.must(err)
	d, problems := w.document(c)
	if len(problems) > 0 {
		t.Fatal(problems)
	}
	golden(t, "testdata/full.json", d)
}
