package web

import (
	"net/http"
	"slices"
	"strings"
	"testing"

	"siplink/internal/catalog"
	"siplink/internal/store"
)

// TestCodecReportKeyLength: a phone's codec key of more than catalog.MaxCodecKey bytes is
// not stored; one of exactly that length is.
func TestCodecReportKeyLength(t *testing.T) {
	h := newHarness(t)
	east := h.region("华东")
	f := h.fixture("标准", nil)
	root, _ := h.loggedIn("root", store.RoleSuper)
	_, token := newClient(t, root, f, "前台", east, "1001")
	code, body, _ := h.deviceCall("/p/"+token, "", "")
	if code != 200 {
		t.Fatalf("enroll: %d", code)
	}
	key := decode(t, body).DeviceKey
	fits := strings.Repeat("a", catalog.MaxCodecKey-len("/8000/1")) + "/8000/1"
	long := "b" + fits
	req, _ := http.NewRequest("POST", h.ts.URL+"/api/device/v1/sync",
		strings.NewReader(`{"device":{"appVersion":"0.3.0","codecs":{"audio":["`+fits+`","`+long+`"]}}}`))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer "+key)
	r, err := h.ts.Client().Do(req)
	if err != nil {
		t.Fatal(err)
	}
	r.Body.Close()
	got, _ := h.st.ReportedCodecs(t.Context())
	if !slices.Contains(got["audio"], fits) || slices.Contains(got["audio"], long) {
		t.Errorf("sync %d stored %v", r.StatusCode, got["audio"])
	}
}
