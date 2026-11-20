package web

import (
	"net/http"
	"regexp"
	"strings"
	"testing"

	"siplink/internal/i18n"
	"siplink/internal/qr"
	"siplink/internal/store"
)

var printCardHTML = regexp.MustCompile(`(?s)<div class="print-card">\s*<div class="qr">(<svg.*?</svg>)</div>\s*<strong dir="auto">([^<]*)</strong>`)

// TestPrintOrder: the print page has one card per printable client in the order of the
// ids, a client named twice twice; a bound client counts as skipped, unknown ids and
// clients of other regions are left out without a word; each card's code is the code of
// its client's enrollment link (§5.4).
func TestPrintOrder(t *testing.T) {
	h := newHarness(t)
	east, west := h.region("华东"), h.region("华西")
	f := h.fixture("标准", nil)
	root, _ := h.loggedIn("root", store.RoleSuper)
	ids, links := map[string]string{}, map[string]string{}
	var bound string
	for _, name := range []string{"A", "B", "C", "bound"} {
		id, token := newClient(t, root, f, name, east, "1001")
		ids[name], bound = itoa(id), token
		links[name] = enrollLink.FindString(root.get("/clients/" + itoa(id)).body)
	}
	if code, _, _ := h.deviceCall("/p/"+bound, "", ""); code != http.StatusOK {
		t.Fatalf("enroll: %d", code)
	}
	westID, _ := newClient(t, root, f, "west", west, "1002")
	local, _ := h.loggedIn("east", store.RoleRegion, east)
	path := "/clients/print?ids=" + strings.Join([]string{ids["C"], "999999", ids["A"], ids["bound"], ids["C"], itoa(westID), "x", ids["B"]}, ",")
	body := local.get(path).body
	cards := printCardHTML.FindAllStringSubmatch(body, -1)
	var names []string
	for _, c := range cards {
		names = append(names, c[2])
		want, err := qr.SVG(links[c[2]], i18n.For(i18n.Source).T("common.qr_code"))
		if err != nil || c[1] != want {
			t.Errorf("card %s: not the code of its enrollment link", c[2])
		}
	}
	if strings.Join(names, ",") != "C,A,C,B" {
		t.Errorf("cards: %v", names)
	}
	if !strings.Contains(body, zhText("clients.print.skipped", "n", 1)) {
		t.Error("the bound client is not counted as skipped")
	}
}
