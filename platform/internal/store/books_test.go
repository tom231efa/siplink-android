package store_test

import (
	"context"
	"fmt"
	"testing"

	"siplink/internal/store"
	"siplink/internal/testutil"
)

func TestBookLimits(t *testing.T) {
	ctx := context.Background()
	st := testutil.Store(t)
	for i := 0; i < 2; i++ {
		if err := st.CreateBook(ctx, &store.Book{Slug: fmt.Sprintf("b%d", i), Name: "x"}, 2); err != nil {
			t.Fatal(err)
		}
	}
	if err := st.CreateBook(ctx, &store.Book{Slug: "b9", Name: "x"}, 2); err != store.ErrLimit {
		t.Errorf("third book with limit 2: %v", err)
	}
	if err := st.CreateBook(ctx, &store.Book{Slug: "b0", Name: "x"}, 500); err != store.ErrConflict {
		t.Errorf("duplicate slug: %v", err)
	}
	// The trigger is the second line: 500 books in total even when Go is bypassed.
	for i := 2; i < 500; i++ {
		if _, err := st.DB.Exec(`INSERT INTO address_books(slug, name, created_at, updated_at) VALUES(?, 'x', '', '')`, fmt.Sprintf("b%d", i)); err != nil {
			t.Fatal(err)
		}
	}
	if _, err := st.DB.Exec(`INSERT INTO address_books(slug, name, created_at, updated_at) VALUES('b500', 'x', '', '')`); err == nil {
		t.Error("trigger let a 501st book in")
	}
}

func TestEntryLimitsAndReplace(t *testing.T) {
	ctx := context.Background()
	st := testutil.Store(t)
	b := &store.Book{Slug: "staff", Name: "员工"}
	if err := st.CreateBook(ctx, b, 500); err != nil {
		t.Fatal(err)
	}
	mk := func(n int, prefix string) []*store.Entry {
		out := make([]*store.Entry, n)
		for i := range out {
			out[i] = &store.Entry{Name: fmt.Sprintf("%s%05d", prefix, i), Phones: []string{"1001", "1002"}}
		}
		return out
	}
	if err := st.AddEntries(ctx, b.ID, mk(10000, "a"), false, 10000); err != nil {
		t.Fatal(err)
	}
	if err := st.AddEntries(ctx, b.ID, mk(1, "z"), false, 10000); err != store.ErrLimit {
		t.Errorf("10,001st entry: %v", err)
	}
	// Replace with too many: nothing changes (one transaction).
	if err := st.AddEntries(ctx, b.ID, mk(10001, "r"), true, 10000); err != store.ErrLimit {
		t.Errorf("replace with 10,001: %v", err)
	}
	got, _ := st.BookByID(ctx, b.ID)
	if got.EntryCount != 10000 {
		t.Errorf("entry_count %d after a refused replace", got.EntryCount)
	}
	// The trigger refuses even when Go is bypassed.
	if _, err := st.DB.Exec(`INSERT INTO book_entries(book_id, name, created_at, updated_at) VALUES(?, 'x', '', '')`, b.ID); err == nil {
		t.Error("trigger let a 10,001st entry in")
	}
	rev := got.Revision
	if err := st.AddEntries(ctx, b.ID, mk(3, "n"), true, 10000); err != nil {
		t.Fatal(err)
	}
	entries, rev2, err := st.AllEntries(ctx, b.ID)
	if err != nil || len(entries) != 3 || rev2 <= rev || entries[0].Name != "n00000" || len(entries[0].Phones) != 2 {
		t.Fatalf("after replace: %d entries rev %d->%d %v", len(entries), rev, rev2, err)
	}
	page, total, _ := st.SearchEntries(ctx, b.ID, "n0000", 2, 0)
	if total != 3 || len(page) != 2 {
		t.Errorf("search: total %d page %d", total, len(page))
	}
	if _, _, err := st.AllEntries(ctx, 999); err != store.ErrNotFound {
		t.Errorf("missing book: %v", err)
	}
	empty := &store.Book{Slug: "empty", Name: "空"}
	st.CreateBook(ctx, empty, 500)
	if e, _, err := st.AllEntries(ctx, empty.ID); err != nil || len(e) != 0 {
		t.Errorf("empty book: %v %d", err, len(e))
	}
}
