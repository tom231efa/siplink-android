package store_test

import (
	"context"
	"testing"
	"time"

	"siplink/internal/secret"
	"siplink/internal/store"
	"siplink/internal/testutil"
)

// TestUnbindEndsLDAPPassword: an unbind bumps the LDAP generation, so the password derived
// for the old phone does not work again once a new phone enrolls (§4.3).
func TestUnbindEndsLDAPPassword(t *testing.T) {
	ctx := context.Background()
	st, box := testutil.Store(t), testutil.Box(t)
	must := func(err error) {
		t.Helper()
		if err != nil {
			t.Fatal(err)
		}
	}
	reg := &store.Region{Name: "华东"}
	must(st.CreateRegion(ctx, reg))
	cfg := &store.ConfigTemplate{Name: "标准", SettingsJSON: "{}", RefreshMinutes: 60}
	must(st.CreateConfigTemplate(ctx, cfg))
	tpl := &store.ClientTemplate{Name: "标准", ConfigTemplateID: cfg.ID}
	must(st.CreateClientTemplate(ctx, tpl))
	expires := time.Now().Add(time.Hour)
	c := &store.Client{Name: "前台", RegionID: reg.ID, ClientTemplateID: tpl.ID, EnrollExpiresAt: expires.UTC().Format(store.TimeFormat)}
	must(st.SaveClient(ctx, c, nil, nil, nil, box, "token-1"))
	_, err := st.Enroll(ctx, secret.HashHex("token-1"), secret.HashHex("phone-1"), "i1")
	must(err)
	old, _, ok, err := st.ClientBooks(ctx, c.PublicID)
	if !ok || err != nil {
		t.Fatalf("bound client: %v %v", ok, err)
	}

	must(st.Unbind(ctx, c, "token-2", expires, box))
	if _, _, ok, _ := st.ClientBooks(ctx, c.PublicID); ok {
		t.Error("an unbound client can bind")
	}
	_, err = st.Enroll(ctx, secret.HashHex("token-2"), secret.HashHex("phone-2"), "i2")
	must(err)
	gen, _, ok, err := st.ClientBooks(ctx, c.PublicID)
	if !ok || err != nil || gen == old || c.LDAPGeneration != gen {
		t.Errorf("generation after a new enrollment: %d (was %d, client says %d) %v %v", gen, old, c.LDAPGeneration, ok, err)
	}
}
