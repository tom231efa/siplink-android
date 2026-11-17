package web

import (
	"bytes"
	"fmt"
	"mime/multipart"
	"net/http"
	"net/url"
	"regexp"
	"strings"
	"testing"

	"golang.org/x/text/encoding/simplifiedchinese"

	"siplink/internal/store"
)

func (c *client) upload(path, mode string, data []byte) resp {
	var body bytes.Buffer
	mw := multipart.NewWriter(&body)
	mw.WriteField("mode", mode)
	fw, _ := mw.CreateFormFile("file", "people.csv")
	fw.Write(data)
	mw.Close()
	return c.postBody(path, mw.FormDataContentType(), body.Bytes())
}

var importKey = regexp.MustCompile(`name="key" value="([^"]+)"`)

func (h *harness) book(slug string, region *int64) *store.Book {
	h.t.Helper()
	b := &store.Book{Slug: slug, Name: slug, RegionID: region}
	if err := h.st.CreateBook(h.t.Context(), b, 500); err != nil {
		h.t.Fatal(err)
	}
	return b
}

func TestBookPermissions(t *testing.T) {
	h := newHarness(t)
	east, west := h.region("华东"), h.region("华西")
	eastBook, westBook, global := h.book("east", &east), h.book("west", &west), h.book("global", nil)
	local, _ := h.loggedIn("east", store.RoleRegion, east)

	r := local.get("/books")
	if !strings.Contains(r.body, "/books/"+itoa(eastBook.ID)) || !strings.Contains(r.body, "/books/"+itoa(global.ID)) ||
		strings.Contains(r.body, "/books/"+itoa(westBook.ID)+"\"") {
		t.Error("region admin book list wrong")
	}
	wb := "/books/" + itoa(westBook.ID)
	for _, p := range []string{wb, wb + "/edit", wb + "/export.csv", wb + "/import", wb + "/entries/new"} {
		if r := local.get(p); r.code != http.StatusNotFound {
			t.Errorf("GET %s of another region: %d", p, r.code)
		}
	}
	if r := local.post(wb+"/delete", nil); r.code != http.StatusNotFound {
		t.Errorf("delete book of another region: %d", r.code)
	}
	gb := "/books/" + itoa(global.ID)
	if r := local.get(gb); r.code != 200 {
		t.Errorf("view global book: %d", r.code)
	}
	if r := local.get(gb + "/export.csv"); r.code != 200 {
		t.Errorf("export global book: %d", r.code)
	}
	if r := local.post(gb+"/entries", url.Values{"name": {"x"}}); r.code != http.StatusForbidden {
		t.Errorf("edit global book as region admin: %d", r.code)
	}
	// Region admins create only in their own regions.
	if r := local.post("/books", url.Values{"slug": {"g2"}, "name": {"g2"}, "region": {"0"}}); r.code != http.StatusBadRequest {
		t.Errorf("region admin creates global book: %d", r.code)
	}
	if r := local.post("/books", url.Values{"slug": {"w2"}, "name": {"w2"}, "region": {itoa(west)}}); r.code != http.StatusBadRequest {
		t.Errorf("region admin creates in other region: %d", r.code)
	}
	if r := local.post("/books", url.Values{"slug": {"e2"}, "name": {"东区二"}, "region": {itoa(east)}}); r.code != http.StatusSeeOther {
		t.Errorf("region admin creates in own region: %d %s", r.code, r.body)
	}
	// Region admins cannot move a book (the region field is ignored for them).
	local.post("/books/"+itoa(eastBook.ID), url.Values{"name": {"east"}, "region": {itoa(west)}})
	if b, _ := h.st.BookByID(t.Context(), eastBook.ID); b.RegionID == nil || *b.RegionID != east {
		t.Error("region admin moved a book")
	}
}

func TestEntriesAndImport(t *testing.T) {
	h := newHarness(t)
	root, _ := h.loggedIn("root", store.RoleSuper)
	r := root.post("/books", url.Values{"slug": {"staff"}, "name": {"员工"}, "region": {"0"}})
	if r.code != http.StatusSeeOther {
		t.Fatalf("create book: %d %s", r.code, r.body)
	}
	b, _ := h.st.BookBySlug(t.Context(), "staff")
	base := "/books/" + itoa(b.ID)

	r = root.post(base+"/entries", url.Values{"name": {"张伟"}, "phones": {"1001; 021-5555 0001"}, "mobiles": {"138 0000 1001"}})
	if r.code != http.StatusSeeOther {
		t.Fatalf("add entry: %d %s", r.code, r.body)
	}
	r = root.post(base+"/entries", url.Values{"name": {"坏号码"}, "phones": {"abc"}})
	if r.code != http.StatusBadRequest || !strings.Contains(r.body, "仅限数字") {
		t.Errorf("bad number: %d", r.code)
	}
	if r := root.get(base + "?q=1001"); !strings.Contains(r.body, "张伟") {
		t.Error("search by number")
	}

	// Dry run writes nothing; confirm imports.
	csvText := "姓名,办公电话,手机\n李娜,1002,\n王芳,,13900001002\n,1003,\n"
	gb, _ := simplifiedchinese.GB18030.NewEncoder().Bytes([]byte(csvText))
	r = root.upload(base+"/import", "append", gb)
	m := importKey.FindStringSubmatch(r.body)
	if r.code != 200 || m == nil || !strings.Contains(r.body, "GB18030") || !strings.Contains(r.body, "姓名为必填项") {
		t.Fatalf("dry run: %d", r.code)
	}
	if b2, _ := h.st.BookByID(t.Context(), b.ID); b2.EntryCount != 1 {
		t.Fatal("dry run wrote entries")
	}
	other, _ := h.loggedIn("root2", store.RoleSuper)
	if r := other.post(base+"/import/confirm", url.Values{"key": {m[1]}}); !strings.Contains(r.location, "/import") {
		t.Error("another administrator used the pending import")
	}
	if r := root.post(base+"/import/confirm", url.Values{"key": {m[1]}}); r.code != http.StatusSeeOther || r.location != base {
		t.Fatalf("confirm: %d %s", r.code, r.location)
	}
	if b2, _ := h.st.BookByID(t.Context(), b.ID); b2.EntryCount != 3 {
		t.Fatalf("after import: %d entries", b2.EntryCount)
	}
	if r := root.post(base+"/import/confirm", url.Values{"key": {m[1]}}); !strings.Contains(r.location, "/import") {
		t.Error("pending import used twice")
	}

	// Replace mode.
	r = root.upload(base+"/import", "replace", []byte("name\n赵六\n"))
	m = importKey.FindStringSubmatch(r.body)
	root.post(base+"/import/confirm", url.Values{"key": {m[1]}})
	entries, _, _ := h.st.AllEntries(t.Context(), b.ID)
	if len(entries) != 1 || entries[0].Name != "赵六" {
		t.Fatalf("replace: %v", entries)
	}

	// Exceeding 10,000 is reported in the dry run and offers no confirmation.
	var big strings.Builder
	big.WriteString("姓名\n")
	for i := 0; i < 10000; i++ {
		fmt.Fprintf(&big, "人员%05d\n", i)
	}
	r = root.upload(base+"/import", "append", []byte(big.String()))
	if !strings.Contains(r.body, "超出上限") || importKey.MatchString(r.body) {
		t.Error("10,001 entries offered for import")
	}
	r = root.upload(base+"/import", "replace", []byte(big.String()))
	m = importKey.FindStringSubmatch(r.body)
	if m == nil {
		t.Fatal("10,000 in replace mode refused")
	}
	root.post(base+"/import/confirm", url.Values{"key": {m[1]}})
	if b2, _ := h.st.BookByID(t.Context(), b.ID); b2.EntryCount != 10000 {
		t.Fatalf("full book: %d", b2.EntryCount)
	}
	if r := root.post(base+"/entries", url.Values{"name": {"多一个"}}); r.code != http.StatusBadRequest {
		t.Errorf("10,001st entry by form: %d", r.code)
	}

	// Export.
	r = root.get(base + "/export.csv")
	if r.code != 200 || !strings.HasPrefix(r.body, "\xef\xbb\xbf姓名") || strings.Count(r.body, "\n") != 10001 {
		t.Errorf("CSV export: %d, %d lines", r.code, strings.Count(r.body, "\n"))
	}
	r = root.get(base + "/export.ldif")
	if r.code != 200 || !strings.Contains(r.body, "dn: ou=staff,ou=books,dc=siplink") {
		t.Errorf("LDIF export: %d", r.code)
	}
	// Delete the book.
	if r := root.post(base+"/delete", nil); r.code != http.StatusSeeOther {
		t.Errorf("delete: %d", r.code)
	}
	if _, err := h.st.BookByID(t.Context(), b.ID); err != store.ErrNotFound {
		t.Error("book still there")
	}
}
