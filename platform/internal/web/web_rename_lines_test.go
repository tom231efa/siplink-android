package web

import (
	"net/url"
	"testing"

	"siplink/internal/store"
)

// TestRenameLinesNeedsEdit: a profile rename is followed only in the key templates the
// administrator may edit, each with an audit entry; a global key template a region
// administrator paired with their configuration template stays as it is (§3.5.7).
func TestRenameLinesNeedsEdit(t *testing.T) {
	h := newHarness(t)
	east := h.region("华东")
	root, _ := h.loggedIn("root", store.RoleSuper)
	local, _ := h.loggedIn("east", store.RoleRegion, east)

	key := url.Values{"label": {"前台"}, "number": {"8001"}, "action": {"speed"}, "line": {"总部"}}
	global := created(t, root.post("/key-templates", url.Values{"name": {"全局"}, "region": {"0"}}), "/key-templates/")
	created(t, root.post("/key-templates/"+itoa(global)+"/keys", key), "/key-templates/")
	own := created(t, local.post("/key-templates", url.Values{"name": {"华东键"}, "region": {itoa(east)}}), "/key-templates/")
	created(t, local.post("/key-templates/"+itoa(own)+"/keys", key), "/key-templates/")
	cid := created(t, local.post("/config-templates", url.Values{"name": {"华东"}, "region": {itoa(east)}}), "/config-templates/")
	created(t, local.post("/config-templates/"+itoa(cid)+"/profiles", profileValues("总部", true)), "/config-templates/")
	for name, kid := range map[string]int64{"甲": global, "乙": own} {
		created(t, local.post("/client-templates", url.Values{"name": {name}, "region": {itoa(east)}, "config": {itoa(cid)}, "keys": {itoa(kid)}}), "/client-templates/")
	}
	line := func(kid int64) string {
		keys, _ := h.st.Keys(t.Context(), kid)
		return keys[0].Line
	}
	revision := func(kid int64) int64 {
		kt, _ := h.st.KeyTemplateByID(t.Context(), kid)
		return kt.Revision
	}
	audited := func(kid int64) int {
		entries, _ := h.st.ListAudit(t.Context(), store.AuditFilter{AllRegions: true, Action: "key_template.update"})
		n := 0
		for _, e := range entries {
			if e.ObjectID != nil && *e.ObjectID == kid && e.Detail["profile"] != nil {
				n++
			}
		}
		return n
	}
	profiles, _ := h.st.Profiles(t.Context(), cid)
	profile := "/config-templates/" + itoa(cid) + "/profiles/" + itoa(profiles[0].ID)

	before := revision(global)
	created(t, local.post(profile, profileValues("总部B", true)), "/config-templates/")
	if line(global) != "总部" || revision(global) != before || audited(global) != 0 {
		t.Errorf("global key template after a region administrator's rename: %q, revision %d of %d, %d entries",
			line(global), revision(global), before, audited(global))
	}
	if line(own) != "总部B" || audited(own) != 1 {
		t.Errorf("own key template: %q, %d entries", line(own), audited(own))
	}

	// A super administrator's rename follows in both.
	keys, _ := h.st.Keys(t.Context(), global)
	keys[0].Line = "总部B"
	if err := h.st.SaveKey(t.Context(), global, keys[0], 100); err != nil {
		t.Fatal(err)
	}
	before = revision(global)
	created(t, root.post(profile, profileValues("总部C", true)), "/config-templates/")
	if line(global) != "总部C" || revision(global) == before || audited(global) != 1 || line(own) != "总部C" || audited(own) != 2 {
		t.Errorf("after the super administrator's rename: %q %q, revision %d of %d, entries %d %d",
			line(global), line(own), revision(global), before, audited(global), audited(own))
	}
}
