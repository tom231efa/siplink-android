package web

import (
	"net/http"
	"regexp"
	"strings"
	"testing"
	"time"

	"siplink/internal/store"
)

var offlineTile = regexp.MustCompile(`card-label">离线</span></div>\s*<div class="card-value">(\d+)</div>`)

// TestOffline: a bound client not seen for more than 3 refresh intervals is "离线" on the
// dashboard, in the client list (status and filter) and on its page, by one rule.
func TestOffline(t *testing.T) {
	h := newHarness(t)
	east := h.region("华东")
	f := h.fixture("标准", nil) // refresh every 60 minutes
	root, _ := h.loggedIn("root", store.RoleSuper)
	quiet, tokenA := newClient(t, root, f, "安静", east, "1001")
	_, tokenB := newClient(t, root, f, "活跃", east, "1002")
	newClient(t, root, f, "未上线", east, "1003")
	if code, _, _ := h.deviceCall("/p/"+tokenA, "", ""); code != http.StatusOK {
		t.Fatalf("enroll A: %d", code)
	}
	code, body, _ := h.deviceCall("/p/"+tokenB, "", "")
	if code != http.StatusOK {
		t.Fatalf("enroll B: %d", code)
	}
	keyB := decode(t, body).DeviceKey

	h.clock.Advance(2 * time.Hour)
	h.deviceCall("/api/device/v1/sync", keyB, "")
	root.login("root") // the session has expired meanwhile
	if m := offlineTile.FindStringSubmatch(root.get("/").body); m == nil || m[1] != "0" {
		t.Fatalf("offline tile before 3 intervals: %v", m)
	}
	h.clock.Advance(time.Hour + time.Minute) // A: 3 h 1 min, B: 1 h 1 min
	root.login("root")

	if m := offlineTile.FindStringSubmatch(root.get("/").body); m == nil || m[1] != "1" {
		t.Errorf("offline tile: %v", m)
	}
	list := root.get("/clients?status=offline").body
	if !strings.Contains(list, ">安静</a>") || strings.Contains(list, ">活跃</a>") || strings.Contains(list, ">未上线</a>") ||
		!strings.Contains(list, `<span class="status warn">离线</span>`) {
		t.Error("offline filter of the client list")
	}
	all := root.get("/clients?status=active").body
	if !strings.Contains(all, ">安静</a>") || !strings.Contains(all, ">活跃</a>") || strings.Count(all, `<span class="status warn">离线</span>`) != 1 {
		t.Error("bound clients: both listed, only the quiet one offline")
	}
	if !strings.Contains(root.get("/clients/"+itoa(quiet)).body, `<h1 class="fade">安静</h1><span class="status warn">离线</span>`) {
		t.Error("client page does not say offline")
	}
}
