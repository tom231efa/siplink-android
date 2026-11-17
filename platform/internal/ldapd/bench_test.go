package ldapd

import (
	"context"
	"fmt"
	"os"
	"slices"
	"sync"
	"testing"
	"time"

	"github.com/go-ldap/ldap/v3"

	"siplink/internal/store"
)

var surnames = []rune("王李张刘陈杨黄赵吴周徐孙马朱胡郭何高林罗")
var givens = []rune("伟芳娜敏静丽强磊军洋勇艳杰娟涛明超秀霞平刚桂")

// fullBook adds a book of 10,000 people with Chinese names and numbers.
func fullBook(t testing.TB, st *store.Store, slug string) *store.Book {
	ctx := context.Background()
	b := &store.Book{Slug: slug, Name: slug, AllowAnonymous: true}
	if err := st.CreateBook(ctx, b, 500); err != nil {
		t.Fatal(err)
	}
	entries := make([]*store.Entry, 10000)
	for i := range entries {
		name := string(surnames[i%len(surnames)]) + string(givens[(i/len(surnames))%len(givens)]) + string(givens[i%7])
		entries[i] = &store.Entry{Name: name, Surname: string(surnames[i%len(surnames)]),
			Phones: []string{fmt.Sprintf("%d", 10000+i)}, Mobiles: []string{fmt.Sprintf("138 %04d %04d", i/100, i)},
			Organization: "示例公司", Department: fmt.Sprintf("部门%d", i%40)}
	}
	if err := st.AddEntries(ctx, b.ID, entries, false, 10000); err != nil {
		t.Fatal(err)
	}
	b, _ = st.BookByID(ctx, b.ID)
	return b
}

// TestLoadFullBook is the P4 benchmark (design §4.6, §8.3): cold load of a full book,
// then 50 concurrent connections searching it by name and by number. It runs only
// with SIPLINK_LDAP_BENCH=1 and prints p50/p99.
func TestLoadFullBook(t *testing.T) {
	if os.Getenv("SIPLINK_LDAP_BENCH") != "1" {
		t.Skip("set SIPLINK_LDAP_BENCH=1")
	}
	e := newEnv(t, func(c *Config) { c.RequireTLS = false })
	b := fullBook(t, e.st, "big")
	base := bookBase(b.Slug)

	// Cold load: the first search builds the snapshot from SQLite.
	c := e.dial()
	start := time.Now()
	r, err := search(c, base, ldap.ScopeWholeSubtree, 51, sdk(sdkName, "张伟"))
	cold := time.Since(start)
	if err != nil && code(err) != ldap.LDAPResultSizeLimitExceeded {
		t.Fatal(err)
	}
	t.Logf("cold search (load 10,000 entries + scan): %v, %d entries", cold, len(r.Entries))
	start = time.Now()
	snap, _ := e.srv.cache.get(context.Background(), b)
	t.Logf("cached snapshot lookup: %v (%d nodes)", time.Since(start), len(snap.nodes))

	// In-process scan of the snapshot with the compiled filter (no network).
	f := mustFilter(t, sdk(sdkName, "芳"))
	scans := make([]time.Duration, 200)
	for i := range scans {
		s := time.Now()
		n := 0
		for _, node := range snap.nodes {
			if f.eval(node) == tTrue {
				n++
			}
		}
		scans[i] = time.Since(s)
	}
	p50, p99 := pct(scans)
	t.Logf("in-memory scan of 10,000 entries (full scan, no limit): p50 %v p99 %v", p50, p99)

	// One connection, one search at a time: the latency a single phone sees.
	seq := make([]time.Duration, 0, 400)
	for j := 0; j < 400; j++ {
		filter := sdk(sdkName, string(givens[j%len(givens)]))
		if j%2 == 1 {
			filter = sdk(sdkNumber, fmt.Sprintf("%04d", j*7%10000))
		}
		s := time.Now()
		if _, err := search(c, base, ldap.ScopeWholeSubtree, 51, filter); err != nil && code(err) != ldap.LDAPResultSizeLimitExceeded {
			t.Fatal(err)
		}
		seq = append(seq, time.Since(s))
	}
	p50, p99 = pct(seq)
	t.Logf("1 connection x 400 searches (name/number, size limit 51) over TCP: p50 %v p99 %v", p50, p99)

	const conns, perConn = 50, 100
	var mu sync.Mutex
	var lat []time.Duration
	var wg sync.WaitGroup
	errs := make(chan error, conns)
	for i := 0; i < conns; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			c, err := ldap.DialURL("ldap://" + e.addr)
			if err != nil {
				errs <- err
				return
			}
			defer c.Close()
			local := make([]time.Duration, 0, perConn)
			for j := 0; j < perConn; j++ {
				filter := sdk(sdkName, string(givens[(i+j)%len(givens)]))
				if j%2 == 1 {
					filter = sdk(sdkNumber, fmt.Sprintf("%04d", (i*perConn+j)%10000))
				}
				s := time.Now()
				_, err := search(c, base, ldap.ScopeWholeSubtree, 51, filter)
				if err != nil && code(err) != ldap.LDAPResultSizeLimitExceeded {
					errs <- err
					return
				}
				local = append(local, time.Since(s))
			}
			mu.Lock()
			lat = append(lat, local...)
			mu.Unlock()
		}(i)
	}
	wg.Wait()
	close(errs)
	for err := range errs {
		t.Fatal(err)
	}
	p50, p99 = pct(lat)
	t.Logf("%d connections x %d searches (name/number, size limit 51) over TCP: p50 %v p99 %v", conns, perConn, p50, p99)
}

func mustFilter(t testing.TB, s string) *filter {
	p, err := ldap.CompileFilter(s)
	if err != nil {
		t.Fatal(err)
	}
	f, err := compileFilter(p)
	if err != nil {
		t.Fatal(err)
	}
	return f
}

func pct(d []time.Duration) (p50, p99 time.Duration) {
	s := slices.Clone(d)
	slices.Sort(s)
	return s[len(s)/2], s[len(s)*99/100]
}
