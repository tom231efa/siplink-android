package web

import (
	"net/http"
	"net/url"
	"slices"
	"strings"
	"testing"

	"siplink/internal/store"
)

func idValues(ids ...int64) url.Values {
	v := url.Values{}
	for _, id := range ids {
		v.Add("id", itoa(id))
	}
	return v
}

// TestReorder: the drag handles post the whole new order; only someone who may edit
// the template can, only with exactly the list's ids, and it is audited.
func TestReorder(t *testing.T) {
	h := newHarness(t)
	east := h.region("华东")
	root, _ := h.loggedIn("root", store.RoleSuper)
	local, _ := h.loggedIn("east", store.RoleRegion, east)
	kid := created(t, root.post("/key-templates", url.Values{"name": {"前台"}, "region": {"0"}}), "/key-templates/")
	kb := "/key-templates/" + itoa(kid)
	created(t, root.post(kb+"/import", url.Values{"csv": {"甲,1\n乙,2\n丙,3\n"}, "mode": {"append"}}), kb)
	keyIDs := func() []int64 {
		keys, _ := h.st.Keys(t.Context(), kid)
		var out []int64
		for _, k := range keys {
			out = append(out, k.ID)
		}
		return out
	}
	ids := keyIDs()

	if r := root.get(kb); !strings.Contains(r.body, `data-sort-url="`+kb+`/keys/order"`) || strings.Contains(r.body, "/up\"") {
		t.Error("key template page has no drag handles, or still has up/down buttons")
	}
	page := root.get(kb).body
	for _, want := range []string{`data-list-filter="key-rows"`, `data-resize="keys"`, `aria-describedby="key-format"`, `role="tooltip" id="key-format"`, `data-filter="甲 1 速拨  "`} {
		if !strings.Contains(page, want) {
			t.Errorf("key template page lacks %s", want)
		}
	}
	if r := local.get(kb); strings.Contains(r.body, "data-sortable") {
		t.Error("read-only template offers drag handles")
	}

	want := []int64{ids[2], ids[0], ids[1]}
	if r := root.post(kb+"/keys/order", idValues(want...)); r.code != http.StatusNoContent {
		t.Fatalf("order: %d %s", r.code, r.body)
	}
	if got := keyIDs(); !slices.Equal(got, want) {
		t.Errorf("order after save: %v, want %v", got, want)
	}
	entries, _ := h.st.ListAudit(t.Context(), store.AuditFilter{AllRegions: true, Action: "key_template.update", Limit: 5})
	if len(entries) == 0 || entries[0].Detail["op"] != "order" {
		t.Errorf("order not audited: %+v", entries)
	}

	for name, bad := range map[string][]int64{"missing": want[:2], "twice": {want[0], want[0], want[1]}, "foreign": {want[0], want[1], want[2] + 99}} {
		if r := root.post(kb+"/keys/order", idValues(bad...)); r.code != http.StatusConflict {
			t.Errorf("%s ids: %d", name, r.code)
		}
	}
	if r := local.post(kb+"/keys/order", idValues(ids...)); r.code != http.StatusForbidden {
		t.Errorf("region admin orders a global template: %d", r.code)
	}
	if r := root.post(kb+"/keys/order", idValues(ids...), "Sec-Fetch-Site", "cross-site"); r.code != http.StatusForbidden {
		t.Errorf("cross-site order: %d", r.code)
	}
	if got := keyIDs(); !slices.Equal(got, want) {
		t.Errorf("refused requests changed the order: %v", got)
	}

	// Accounts and directories of a configuration template.
	cid := created(t, root.post("/config-templates", url.Values{"name": {"标准"}, "region": {"0"}}), "/config-templates/")
	cb := "/config-templates/" + itoa(cid)
	created(t, root.post(cb+"/profiles", profileValues("总部", true)), cb)
	created(t, root.post(cb+"/profiles", profileValues("分部", false)), cb)
	ps, _ := h.st.Profiles(t.Context(), cid)
	before, _ := h.st.ConfigTemplateByID(t.Context(), cid)
	if r := root.post(cb+"/profiles/order", idValues(ps[1].ID, ps[0].ID)); r.code != http.StatusNoContent {
		t.Fatalf("profile order: %d %s", r.code, r.body)
	}
	if ps, _ = h.st.Profiles(t.Context(), cid); ps[0].Name != "分部" {
		t.Errorf("profiles after order: %s %s", ps[0].Name, ps[1].Name)
	}
	if after, _ := h.st.ConfigTemplateByID(t.Context(), cid); after.Revision <= before.Revision {
		t.Error("order did not change the revision the phones see")
	}
	if r := root.post(cb+"/directories/order", nil); r.code != http.StatusNoContent {
		t.Errorf("empty directory order: %d", r.code)
	}
	if r := root.post(cb+"/directories/order", idValues(ps[0].ID)); r.code != http.StatusConflict {
		t.Errorf("profile id as directory order: %d", r.code)
	}
}
