package ldapd

import (
	"context"
	"slices"
	"testing"
	"time"

	"github.com/go-ldap/ldap/v3"

	"siplink/internal/store"
)

// A book that gets the id of a deleted book and reaches the same revision answers with
// its own people, not the deleted book's cached ones.
func TestReusedBookIDNotServedFromCache(t *testing.T) {
	for _, tc := range []struct {
		name, slugB string
		later       time.Duration // between the creation of the two books
	}{
		{"same slug a second later", "alpha", time.Second},
		{"other slug in the same second", "beta", 0},
	} {
		t.Run(tc.name, func(t *testing.T) {
			e := newEnv(t)
			ctx := context.Background()
			now := time.Now()
			e.st.Now = func() time.Time { return now }
			mkBook := func(slug, person string) *store.Book {
				b := &store.Book{Slug: slug, Name: slug}
				must(t, e.st.CreateBook(ctx, b, 500))
				must(t, e.st.AddEntries(ctx, b.ID, []*store.Entry{{Name: person, Phones: []string{"3001"}}}, false, 10000))
				b, err := e.st.BookByID(ctx, b.ID)
				must(t, err)
				return b
			}
			grant := func(books ...int64) {
				a, err := e.st.LDAPAccountByName(ctx, "phone1")
				must(t, err)
				a.BookIDs = books
				must(t, e.st.UpdateLDAPAccount(ctx, a))
			}
			a := mkBook("alpha", "旧书的人")
			grant(a.ID)
			c := e.login("phone1")
			r, err := search(c, bookBase("alpha"), ldap.ScopeWholeSubtree, 0, "(cn=*)")
			if err != nil || !slices.Equal(names(r), []string{"旧书的人"}) {
				t.Fatalf("book A: %v %v", err, names(r))
			}
			grant()
			must(t, e.st.DeleteBook(ctx, a.ID))
			now = now.Add(tc.later)
			b := mkBook(tc.slugB, "新书的人")
			if b.ID != a.ID || b.Revision != a.Revision {
				t.Fatalf("book B has id %d revision %d, A had %d %d: the test proves nothing", b.ID, b.Revision, a.ID, a.Revision)
			}
			grant(b.ID)
			r, err = search(c, bookBase(tc.slugB), ldap.ScopeWholeSubtree, 0, "(cn=*)")
			if err != nil || !slices.Equal(names(r), []string{"新书的人"}) {
				t.Errorf("book B: %v %v", err, names(r))
			}
		})
	}
}
