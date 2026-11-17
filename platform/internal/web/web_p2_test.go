package web

import (
	"net/http"
	"net/url"
	"regexp"
	"strings"
	"testing"

	"siplink/internal/store"
)

func TestRegionsAndAdminsSuperOnly(t *testing.T) {
	h := newHarness(t)
	east := h.region("华东")
	west := h.region("华西")
	root, _ := h.loggedIn("root", store.RoleSuper)
	local, _ := h.loggedIn("east", store.RoleRegion, east)

	// Region administrators see only their own regions and no admin pages.
	r := local.get("/regions")
	if r.code != 200 || !strings.Contains(r.body, "华东") || strings.Contains(r.body, "华西") {
		t.Fatalf("region admin region list: %d", r.code)
	}
	for _, p := range []string{"/admins/new", "/admins/1"} {
		if r := local.get(p); r.code != http.StatusNotFound {
			t.Errorf("GET %s as region admin: %d", p, r.code)
		}
	}
	if r := local.get("/admins"); r.code != 200 || strings.Contains(r.body, "root") {
		t.Errorf("region admin sees other admins: %d", r.code)
	}
	for _, p := range []string{"/regions", "/regions/1", "/regions/1/delete", "/admins", "/admins/1"} {
		if r := local.post(p, url.Values{"name": {"x"}, "role": {"super"}}); r.code != http.StatusNotFound {
			t.Errorf("POST %s as region admin: %d", p, r.code)
		}
	}

	// Super administrators manage regions.
	if r := root.post("/regions", url.Values{"name": {"华南"}}); r.code != http.StatusSeeOther {
		t.Fatalf("create region: %d %s", r.code, r.body)
	}
	if r := root.post("/regions", url.Values{"name": {"华南"}}); r.code != http.StatusBadRequest {
		t.Errorf("duplicate region: %d", r.code)
	}
	if r := root.post("/regions/"+itoa(west), url.Values{"name": {"西部"}}); r.code != http.StatusSeeOther {
		t.Errorf("rename: %d", r.code)
	}
	root.post("/regions/"+itoa(east)+"/delete", nil)
	if _, err := h.st.RegionByID(t.Context(), east); err != nil {
		t.Error("region with an administrator was deleted")
	}
	root.post("/regions/"+itoa(west)+"/delete", nil)
	if _, err := h.st.RegionByID(t.Context(), west); err != store.ErrNotFound {
		t.Error("empty region not deleted")
	}
}

var oncePassword = regexp.MustCompile(`id="once">([^<]+)<`)

func TestAdminLifecycle(t *testing.T) {
	h := newHarness(t)
	east := h.region("华东")
	root, rootAdmin := h.loggedIn("root", store.RoleSuper)

	r := root.post("/admins", url.Values{"username": {"zhang"}, "role": {"region"}})
	if r.code != http.StatusBadRequest || !strings.Contains(r.body, "至少指定一个区域") {
		t.Fatalf("region admin without region: %d", r.code)
	}
	r = root.post("/admins", url.Values{"username": {"zhang"}, "role": {"region"}, "regions": {itoa(east)}, "display_name": {"张三"}})
	m := oncePassword.FindStringSubmatch(r.body)
	if r.code != 200 || m == nil {
		t.Fatalf("create admin: %d", r.code)
	}
	zhang := h.client()
	r = zhang.post("/login", url.Values{"username": {"zhang"}, "password": {m[1]}})
	if r.location != "/me" {
		t.Fatalf("new admin must change password, went to %q", r.location)
	}
	zhang.post("/me", url.Values{"current": {m[1]}, "password": {"zhang-new-password"}, "password2": {"zhang-new-password"}})
	if r := zhang.get("/"); r.code != 200 {
		t.Fatalf("after change: %d", r.code)
	}
	z, _ := h.st.AdminByUsername(t.Context(), "zhang")

	// Changing the regions ends the sessions.
	west := h.region("华西")
	root.post("/admins/"+itoa(z.ID), url.Values{"role": {"region"}, "regions": {itoa(east), itoa(west)}, "display_name": {"张三"}})
	if r := zhang.get("/"); r.code != http.StatusSeeOther {
		t.Error("session survived a region change")
	}

	// Own role cannot change; the last super cannot be demoted or disabled.
	r = root.post("/admins/"+itoa(rootAdmin.ID), url.Values{"role": {"region"}, "regions": {itoa(east)}})
	if r.code != http.StatusBadRequest {
		t.Errorf("own role change: %d", r.code)
	}
	other, _ := h.loggedIn("root2", store.RoleSuper)
	root.post("/admins/"+itoa(z.ID), url.Values{"role": {"super"}})
	other.post("/admins/"+itoa(z.ID), url.Values{"role": {"region"}, "regions": {itoa(east)}}) // back
	r = other.post("/admins/"+itoa(rootAdmin.ID), url.Values{"role": {"super"}, "disabled": {"1"}})
	if r.code != http.StatusSeeOther {
		t.Fatalf("disable root: %d", r.code)
	}
	if r := root.get("/"); r.code != http.StatusSeeOther {
		t.Error("disabled admin still logged in")
	}
	root2, _ := h.st.AdminByUsername(t.Context(), "root2")
	r = other.post("/admins/"+itoa(root2.ID), url.Values{"role": {"super"}, "disabled": {"1"}})
	if r.code != http.StatusBadRequest {
		t.Errorf("disable self: %d", r.code)
	}

	// Reset password: one-time password, sessions end, must change.
	r = other.post("/admins/"+itoa(z.ID)+"/reset-password", nil)
	if m = oncePassword.FindStringSubmatch(r.body); m == nil {
		t.Fatal("no one-time password after reset")
	}
	z, _ = h.st.AdminByID(t.Context(), z.ID)
	if !z.MustChangePassword {
		t.Error("reset does not force a change")
	}
	entries, _ := h.st.ListAudit(t.Context(), store.AuditFilter{AllRegions: true, Limit: 100})
	for _, e := range entries {
		if strings.Contains(e.DetailJSON, m[1]) || strings.Contains(e.DetailJSON, "zhang-new-password") {
			t.Error("password in the audit log")
		}
	}
}

func TestAuditVisibility(t *testing.T) {
	h := newHarness(t)
	east := h.region("华东")
	west := h.region("华西")
	root, _ := h.loggedIn("root", store.RoleSuper)
	local, _ := h.loggedIn("east", store.RoleRegion, east)
	root.post("/regions/"+itoa(east), url.Values{"name": {"华东区"}})
	root.post("/regions/"+itoa(west), url.Values{"name": {"华西区"}})
	r := local.get("/audit")
	if r.code != 200 || !strings.Contains(r.body, "华东区") || strings.Contains(r.body, "华西区") {
		t.Errorf("region admin audit view: %d", r.code)
	}
	r = root.get("/audit?object=region&page=1")
	if !strings.Contains(r.body, "华东区") || !strings.Contains(r.body, "华西区") {
		t.Error("super admin audit view incomplete")
	}
}
