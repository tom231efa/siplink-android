package web

import (
	"fmt"
	"regexp"
	"strings"
	"testing"
	"time"

	"siplink/internal/secret"
	"siplink/internal/store"
)

var attentionSection = regexp.MustCompile(`(?s)<section class="card attention".*?</section>`)

// TestDashboardAttentionCap: the attention list shows the first attentionRows clients of
// each kind by name, the tile, the badge and the bar count all of them, and the offline
// clients left out are a link to the offline filter of the client list (§5.1).
func TestDashboardAttentionCap(t *testing.T) {
	h := newHarness(t)
	east, west := h.region("华东"), h.region("华西")
	f := h.fixture("标准", nil) // refresh every 60 minutes: offline after 3 hours
	root, _ := h.loggedIn("root", store.RoleSuper)
	ctx := t.Context()
	seen := func(ago time.Duration) string { return h.clock.Now().Add(-ago).UTC().Format(store.TimeFormat) }
	bound := func(name string, rep store.Report) {
		t.Helper()
		c := &store.Client{RegionID: east, ClientTemplateID: f.tpl.ID, Name: name, EnrollExpiresAt: seen(-time.Hour)}
		if err := h.st.SaveClient(ctx, c, nil, nil, nil, h.srv.Ext.Box, secret.Token(32)); err != nil {
			t.Fatal(err)
		}
		if _, err := h.st.DB.ExecContext(ctx, `UPDATE clients SET status = 'active' WHERE id = ?`, c.ID); err != nil {
			t.Fatal(err)
		}
		rep.ClientID = c.ID
		if err := h.st.SaveReport(ctx, &rep); err != nil {
			t.Fatal(err)
		}
	}
	failed := `[{"account":"p1","state":"FAILED","error":"403"}]`
	for i := 1; i <= 13; i++ {
		bound(fmt.Sprintf("off-%02d", i), store.Report{LastSeenAt: seen(4 * time.Hour), RegistrationsJSON: "[]"})
	}
	for i := 1; i <= 12; i++ {
		bound(fmt.Sprintf("reg-%02d", i), store.Report{LastSeenAt: seen(time.Minute), RegistrationsJSON: failed})
	}
	bound("both", store.Report{LastSeenAt: seen(5 * time.Hour), BuildError: "x", RegistrationsJSON: failed})
	bound("ok-1", store.Report{LastSeenAt: seen(time.Minute), RegistrationsJSON: "[]"})
	bound("ok-2", store.Report{LastSeenAt: seen(3 * time.Hour), RegistrationsJSON: "[]"}) // exactly 3 intervals: not yet
	newClient(t, root, f, "pending", east, "1001")

	page := root.get("/").body
	if m := offlineTile.FindStringSubmatch(page); m == nil || m[1] != "14" {
		t.Errorf("offline tile: %v", m)
	}
	list := attentionSection.FindString(page)
	if !strings.Contains(list, `<span class="badge warn">27</span>`) {
		t.Error("badge: not 13 in trouble + 14 offline")
	}
	var names []string
	for _, m := range regexp.MustCompile(`href="/clients/\d+">([^<]+)</a>`).FindAllStringSubmatch(list, -1) {
		names = append(names, m[1])
	}
	want := "both reg-01 reg-02 reg-03 reg-04 reg-05 reg-06 reg-07 reg-08 reg-09 both off-01 off-02 off-03 off-04 off-05 off-06 off-07 off-08 off-09"
	if strings.Join(names, " ") != want {
		t.Errorf("attention list:\n%s\nwant\n%s", strings.Join(names, " "), want)
	}
	if !strings.Contains(list, `<span class="sub">`+zhText("dashboard.attention.invalid_config")+`</span>`) ||
		strings.Count(list, zhText("dashboard.attention.reg_failed", "summary", zhText("reg.state.failed")+" 1")) != 9 {
		t.Error("reasons of the trouble rows")
	}
	if !strings.Contains(list, `<span class="sub">`+zhText("dashboard.attention.more", "n", 3)+`</span>`) {
		t.Error("trouble rows left out: no count")
	}
	if !strings.Contains(list, `<a href="/clients?status=offline">`+zhText("dashboard.attention.more", "n", 4)+`</a>`) {
		t.Error("offline rows left out: no link to the offline filter")
	}
	if !strings.Contains(page, `<li><span class="dot ok"></span>`+zhText("dashboard.bar.healthy")+`<strong>2</strong></li>`) ||
		!strings.Contains(page, `<li><span class="dot warn"></span>`+zhText("dashboard.bar.attention")+`<strong>26</strong></li>`) ||
		!strings.Contains(page, `<li><span class="dot idle"></span>`+zhText("dashboard.bar.pending")+`<strong>1</strong></li>`) {
		t.Errorf("legend of the client bar: %s", regexp.MustCompile(`(?s)<ul class="legend">.*?</ul>`).FindString(page))
	}
	if r := root.get("/clients?status=offline"); strings.Count(r.body, `<span class="status warn">离线</span>`) != 14 {
		t.Error("the offline filter lists another set than the tile counts")
	}

	// Another region's administrator sees none of them.
	other, _ := h.loggedIn("west", store.RoleRegion, west)
	page = other.get("/").body
	if m := offlineTile.FindStringSubmatch(page); m == nil || m[1] != "0" || !strings.Contains(page, zhText("dashboard.attention.empty")) {
		t.Errorf("another region: offline tile %v", m)
	}
}
