package store_test

import (
	"errors"
	"testing"

	"siplink/internal/store"
	"siplink/internal/testutil"
)

func TestTemplateStore(t *testing.T) {
	st := testutil.Store(t)
	ctx := t.Context()
	r := &store.Region{Name: "华东"}
	if err := st.CreateRegion(ctx, r); err != nil {
		t.Fatal(err)
	}
	c := &store.ConfigTemplate{Name: "标准", SettingsJSON: "{}", RefreshMinutes: 60, RegionID: &r.ID}
	if err := st.CreateConfigTemplate(ctx, c); err != nil {
		t.Fatal(err)
	}
	if err := st.CreateConfigTemplate(ctx, &store.ConfigTemplate{Name: "标准", SettingsJSON: "{}", RefreshMinutes: 60, RegionID: &r.ID}); !errors.Is(err, store.ErrConflict) {
		t.Errorf("same name in a region: %v", err)
	}
	if err := st.CreateConfigTemplate(ctx, &store.ConfigTemplate{Name: "标准", SettingsJSON: "{}", RefreshMinutes: 60}); err != nil {
		t.Errorf("same name, global: %v", err)
	}
	var ids []int64
	for _, name := range []string{"甲", "乙", "丙"} {
		p := &store.Profile{TemplateID: c.ID, Name: name, Domain: "pbx.example.com", Transport: "UDP", Expires: 3600, Enabled: true}
		if err := st.SaveProfile(ctx, p, 3); err != nil {
			t.Fatal(err)
		}
		ids = append(ids, p.ID)
	}
	if err := st.SaveProfile(ctx, &store.Profile{TemplateID: c.ID, Name: "丁", Domain: "x", Transport: "UDP"}, 3); !errors.Is(err, store.ErrLimit) {
		t.Errorf("limit: %v", err)
	}
	ps, _ := st.Profiles(ctx, c.ID)
	if !ps[0].IsDefault || ps[1].IsDefault {
		t.Error("first profile is not the default")
	}
	if err := st.Reorder(ctx, "account_profiles", c.ID, []int64{ids[0], ids[2], ids[1]}); err != nil {
		t.Fatal(err)
	}
	ps, _ = st.Profiles(ctx, c.ID)
	if ps[1].Name != "丙" || ps[2].Name != "乙" {
		t.Errorf("order after reorder: %s %s %s", ps[0].Name, ps[1].Name, ps[2].Name)
	}
	for _, bad := range [][]int64{{ids[0], ids[2]}, {ids[0], ids[2], ids[2]}, {ids[0], ids[1], ids[2] + 100}} {
		if err := st.Reorder(ctx, "account_profiles", c.ID, bad); !errors.Is(err, store.ErrOrder) {
			t.Errorf("Reorder(%v) = %v, want ErrOrder", bad, err)
		}
	}
	if err := st.Reorder(ctx, "admins", c.ID, ids); err == nil {
		t.Error("Reorder accepted a table outside its list")
	}
	if err := st.DeleteProfile(ctx, c.ID, ids[0]); err != nil {
		t.Fatal(err)
	}
	ps, _ = st.Profiles(ctx, c.ID)
	if len(ps) != 2 || !ps[0].IsDefault {
		t.Error("default not handed on after delete")
	}
	before, _ := st.ConfigTemplateByID(ctx, c.ID)
	if before.Revision < 5 {
		t.Errorf("revision not bumped: %d", before.Revision)
	}

	k := &store.KeyTemplate{Name: "键"}
	if err := st.CreateKeyTemplate(ctx, k); err != nil {
		t.Fatal(err)
	}
	if err := st.AddKeys(ctx, k.ID, []*store.TemplateKey{{Label: "a", Number: "1", Action: "speed"}, {Label: "b", Number: "2", Action: "speed"}}, false, 2); err != nil {
		t.Fatal(err)
	}
	if err := st.AddKeys(ctx, k.ID, []*store.TemplateKey{{Label: "c", Number: "3", Action: "speed"}}, false, 2); !errors.Is(err, store.ErrLimit) {
		t.Errorf("key limit: %v", err)
	}
	if err := st.AddKeys(ctx, k.ID, []*store.TemplateKey{{Label: "c", Number: "3", Action: "speed"}}, true, 2); err != nil {
		t.Errorf("replace: %v", err)
	}
	ct := &store.ClientTemplate{Name: "前台", ConfigTemplateID: c.ID, KeyTemplateID: &k.ID, RegionID: &r.ID}
	if err := st.CreateClientTemplate(ctx, ct); err != nil {
		t.Fatal(err)
	}
	if err := st.DeleteConfigTemplate(ctx, c.ID); !errors.Is(err, store.ErrInUse) {
		t.Errorf("delete used configuration template: %v", err)
	}
	if err := st.DeleteKeyTemplate(ctx, k.ID); !errors.Is(err, store.ErrInUse) {
		t.Errorf("delete used key template: %v", err)
	}
	if u, _ := st.RegionUsage(ctx, r.ID); u.Templates != 2 {
		t.Errorf("region usage: %+v", u)
	}
	if err := st.ReportCodecs(ctx, "0.2.0", []string{"opus/48000/2", "PCMA/8000/1"}, []string{"H264/90000/1"}); err != nil {
		t.Fatal(err)
	}
	st.ReportCodecs(ctx, "0.2.1", []string{"opus/48000/2"}, nil)
	if got, _ := st.ReportedCodecs(ctx); len(got["audio"]) != 2 || len(got["video"]) != 1 {
		t.Errorf("reported codecs: %v", got)
	}
}
