package web

import (
	"strings"
	"testing"

	"siplink/internal/store"
)

// TestImportNameTaken: the dry run of a client CSV refuses a name its region already has,
// letters A to Z in any case, as the unique index compares names; a part of a name, a
// name of another region and other letters in another case are free (§5.2).
func TestImportNameTaken(t *testing.T) {
	h := newHarness(t)
	east, west := h.region("华东"), h.region("华西")
	f := h.fixture("标准", nil)
	root, _ := h.loggedIn("root", store.RoleSuper)
	for _, name := range []string{"Front Desk", "前台A", "Ärzte"} {
		newClient(t, root, f, name, east, "1001")
	}
	newClient(t, root, f, "Lobby", west, "1002")
	lines := []struct {
		name, region string
		taken        bool
	}{
		{"front DESK", "华东", true},
		{"Front Desk", "华东", true},
		{"前台a", "华东", true},
		{"前台", "华东", false},         // a part of 前台A
		{"Front", "华东", false},      // a part of Front Desk
		{"ärzte", "华东", false},      // Ä and ä: not A to Z
		{"Front Desk", "华西", false}, // another region
		{"lobby", "华西", true},
	}
	var csv strings.Builder
	csv.WriteString("名称,区域,客户端模板\n")
	for _, l := range lines {
		csv.WriteString(l.name + "," + l.region + ",标准\n")
	}
	r := uploadCSV(root, "zh-CN", csv.String())
	rows := strings.Split(r.body, `<tr><td class="num">`)
	if len(rows) != len(lines)+1 {
		t.Fatalf("dry run: %d rows for %d lines (%d)", len(rows)-1, len(lines), r.code)
	}
	taken := zhText("clients.import.name_taken")
	for i, l := range lines {
		if got := strings.Contains(rows[i+1], taken); got != l.taken {
			t.Errorf("%s in %s: taken %v, want %v", l.name, l.region, got, l.taken)
		}
	}
}
