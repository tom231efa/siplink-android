package web

import (
	"fmt"
	"net/http"
	"net/url"
	"strings"
	"testing"

	"siplink/internal/config"
	"siplink/internal/rules"
	"siplink/internal/store"
)

func csvLines(head, format string, n int) []byte {
	var b strings.Builder
	b.WriteString(head)
	for i := range n {
		fmt.Fprintf(&b, format, i)
	}
	return []byte(b.String())
}

// TestBookImportLimits: a directory file with more lines of people than the entry limit
// is refused; the dry run lists the first 200 bad lines and counts the others.
func TestBookImportLimits(t *testing.T) {
	h := newHarness(t, func(c *config.Config) { c.MaxBookEntries = 300 })
	root, _ := h.loggedIn("root", store.RoleSuper)
	path := "/books/" + itoa(h.book("people", nil).ID) + "/import"
	r := root.upload(path, "append", csvLines("姓名\n", "人员%d\n", 301))
	if r.code != http.StatusBadRequest || !strings.Contains(r.body, zhText("dirs.import.too_many", "max", 300)) || importKey.MatchString(r.body) {
		t.Errorf("301 lines: %d", r.code)
	}
	r = root.upload(path, "append", csvLines("姓名,手机\n", "人员%d,bad\n", 250))
	if r.code != http.StatusOK || strings.Count(r.body, `<td class="num">`) != 200 || !strings.Contains(r.body, zhText("dashboard.attention.more", "n", 50)) {
		t.Errorf("250 bad lines: %d, %d listed", r.code, strings.Count(r.body, `<td class="num">`))
	}
}

// TestKeyImportLimits: a key file with more lines than a template holds is refused
// before it is read further; the first 200 bad lines are listed, the others counted.
func TestKeyImportLimits(t *testing.T) {
	h := newHarness(t)
	root, _ := h.loggedIn("root", store.RoleSuper)
	kid := created(t, root.post("/key-templates", url.Values{"name": {"前台"}, "region": {"0"}}), "/key-templates/")
	kb := "/key-templates/" + itoa(kid)
	r := root.post(kb+"/import", url.Values{"csv": {string(csvLines("", "键%d,8001\n", rules.MaxShortcuts+1))}, "mode": {"append"}})
	if r.code != http.StatusBadRequest || !strings.Contains(r.body, zhText("rules.shortcut.limit_reached", "max", rules.MaxShortcuts)) {
		t.Errorf("%d lines: %d", rules.MaxShortcuts+1, r.code)
	}
	if keys, _ := h.st.Keys(t.Context(), kid); len(keys) != 0 {
		t.Fatalf("a refused file wrote %d keys", len(keys))
	}
	r = root.post(kb+"/import", url.Values{"csv": {string(csvLines("", "键%d\n", 250))}, "mode": {"append"}})
	if r.code != http.StatusBadRequest || strings.Count(r.body, `<td class="num">`) != 200 || !strings.Contains(r.body, zhText("dashboard.attention.more", "n", 50)) {
		t.Errorf("250 bad lines: %d, %d listed", r.code, strings.Count(r.body, `<td class="num">`))
	}
}

// TestClientImportLimits: a client file with more than 2,000 lines is refused before any
// line is checked; the dry run lists the first 200 lines with problems and counts the
// others.
func TestClientImportLimits(t *testing.T) {
	h := newHarness(t)
	h.fixture("标准", nil)
	root, _ := h.loggedIn("root", store.RoleSuper)
	head := "名称,区域,客户端模板,总部用户名,总部密码\n"
	r := root.uploadCSV("/clients/import", csvLines(head, "甲%d,华东,标准,2001,pw\n", maxClientRows+1))
	if r.code != http.StatusBadRequest || !strings.Contains(r.body, zhText("dirs.import.too_many", "max", maxClientRows)) {
		t.Errorf("%d lines: %d", maxClientRows+1, r.code)
	}
	r = root.uploadCSV("/clients/import", csvLines(head, "甲%d,无此区域,标准,2001,pw\n", 250))
	if r.code != http.StatusOK || strings.Count(r.body, `<td class="num">`) != 200 ||
		!strings.Contains(r.body, zhText("dashboard.attention.more", "n", 50)) || !strings.Contains(r.body, zhText("clients.import.ok_count", "ok", 0, "total", 250)) {
		t.Errorf("250 bad lines: %d, %d listed", r.code, strings.Count(r.body, `<td class="num">`))
	}
}
