package web

import (
	"net/http"
	"strings"
	"testing"

	"siplink/internal/backup"
	"siplink/internal/config"
	"siplink/internal/store"
)

func TestSystemPage(t *testing.T) {
	h := newHarness(t)
	root, _ := h.loggedIn("root", store.RoleSuper)
	r := root.get("/system")
	ca := h.srv.Ext.Certs.CA().CA()
	if r.code != 200 || !strings.Contains(r.body, ca.Subject.String()) || !strings.Contains(r.body, "下载 CA 证书") ||
		!strings.Contains(r.body, "platform.example.com") || !strings.Contains(r.body, "StartTLS") {
		t.Fatalf("system page: %d", r.code)
	}
	if r := root.get("/system/ca.pem"); r.code != 200 || r.body != string(h.srv.Ext.Certs.CA().CAPEM()) {
		t.Errorf("CA download: %d", r.code)
	}
	if r := root.post("/system/backup", nil); r.code != http.StatusSeeOther {
		t.Fatalf("backup: %d", r.code)
	}
	if list, _ := backup.List(backup.Dir(h.srv.Cfg.DataDir)); len(list) != 1 {
		t.Errorf("backups: %v", list)
	}
	before := h.srv.Ext.Certs.CA().Server().SerialNumber
	root.post("/system/reissue", nil)
	if h.srv.Ext.Certs.CA().Server().SerialNumber.Cmp(before) == 0 {
		t.Error("server certificate not reissued")
	}
	if r := root.get("/audit"); !strings.Contains(r.body, "重新签发服务器证书") || !strings.Contains(r.body, "备份") {
		t.Error("audit entries")
	}
	// Region administrators may download the CA but not see the page.
	east := h.region("华东")
	local, _ := h.loggedIn("east", store.RoleRegion, east)
	if r := local.get("/system"); r.code != http.StatusNotFound {
		t.Errorf("region admin: %d", r.code)
	}
	if r := local.post("/system/backup", nil); r.code != http.StatusNotFound {
		t.Errorf("region admin backup: %d", r.code)
	}
	if r := local.get("/system/ca.pem"); r.code != 200 {
		t.Errorf("region admin CA: %d", r.code)
	}

	strict := newHarness(t, func(c *config.Config) { c.LDAPPublicHost = "192.0.2.10" })
	root, _ = strict.loggedIn("root", store.RoleSuper)
	if r := root.get("/system"); !strings.Contains(r.body, "无法下发") || !strings.Contains(r.body, "SIPLINK_LDAP_REQUIRE_TLS=false") {
		t.Error("hosted rule not explained on the system page")
	}
}

// TestPagesRender opens every page of P6–P9 as a super and a region administrator, so a
// template error anywhere shows up as a failing status.
func TestPagesRender(t *testing.T) {
	h := newHarness(t)
	east := h.region("华东")
	f := h.fixture("标准", &east)
	keys := &store.KeyTemplate{Name: "键", RegionID: &east}
	if err := h.st.CreateKeyTemplate(t.Context(), keys); err != nil {
		t.Fatal(err)
	}
	root, _ := h.loggedIn("root", store.RoleSuper)
	local, _ := h.loggedIn("east", store.RoleRegion, east)
	id, _ := newClient(t, root, f, "前台", east, "1001")
	cfg, p, d := itoa(f.config.ID), itoa(f.profiles[0].ID), itoa(f.dir.ID)
	paths := []string{"/", "/clients", "/clients/new", "/clients/new?region=" + itoa(east) + "&template=" + itoa(f.tpl.ID),
		"/clients/" + itoa(id), "/clients/" + itoa(id) + "/edit", "/clients/" + itoa(id) + "/preview", "/clients/import",
		"/clients/print?ids=" + itoa(id), "/client-templates", "/client-templates/new", "/client-templates/" + itoa(f.tpl.ID),
		"/config-templates", "/config-templates/new", "/config-templates/" + cfg + "/profiles/new",
		"/config-templates/" + cfg + "/profiles/" + p, "/config-templates/" + cfg + "/directories/new",
		"/config-templates/" + cfg + "/directories/new?source=external:" + itoa(*f.dir.ExternalID),
		"/config-templates/" + cfg + "/directories/new?source=hosted:" + itoa(*f.hosted.BookID),
		"/config-templates/" + cfg + "/directories/" + d, "/config-templates/" + cfg + "/directories/" + itoa(f.hosted.ID),
		"/books", "/books?kind=external&q=ldap", "/books/new", "/books/new?kind=external", "/directories/" + itoa(*f.dir.ExternalID),
		"/books/" + itoa(*f.hosted.BookID), "/books/" + itoa(*f.hosted.BookID) + "/edit",
		"/key-templates", "/key-templates/new", "/key-templates/" + itoa(keys.ID), "/regions", "/audit"}
	for _, tab := range []string{"accounts", "calls", "features", "general", "media", "security", "directories", "advanced"} {
		paths = append(paths, "/config-templates/"+cfg+"?tab="+tab)
	}
	for _, c := range []*client{root, local} {
		for _, path := range paths {
			if r := c.get(path); r.code != 200 {
				t.Errorf("%s: %d", path, r.code)
			}
		}
	}
	if r := root.get("/system"); r.code != 200 {
		t.Errorf("/system: %d", r.code)
	}
}
