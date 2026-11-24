package ldapd

import (
	"bytes"
	"log/slog"
	"net"
	"strings"
	"sync"
	"testing"

	"github.com/go-ldap/ldap/v3"
)

type lockedBuffer struct {
	mu sync.Mutex
	b  bytes.Buffer
}

func (l *lockedBuffer) Write(p []byte) (int, error) {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.b.Write(p)
}

func (l *lockedBuffer) String() string {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.b.String()
}

// A panic while handling a request ends that connection only. The server has no store
// here, so the first search that reads the books panics on the nil store.
func TestPanicEndsOnlyItsConnection(t *testing.T) {
	logs := &lockedBuffer{}
	srv := New(Config{Suffix: testSuffix}, nil, slog.New(slog.NewTextHandler(logs, nil)))
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	go srv.Serve(ln, false)
	t.Cleanup(func() { ln.Close(); srv.Close() })
	dial := func() *ldap.Conn {
		c, err := ldap.DialURL("ldap://" + ln.Addr().String())
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() { c.Close() })
		return c
	}
	if _, err := search(dial(), "ou=books,"+testSuffix, ldap.ScopeWholeSubtree, 0, "(cn=secretname)"); err == nil {
		t.Fatal("the search on a nil store was answered")
	}
	r, err := search(dial(), "", ldap.ScopeBaseObject, 0, "(objectClass=*)")
	if err != nil || len(r.Entries) != 1 {
		t.Fatalf("the server stopped serving after a panic: %v", err)
	}
	out := logs.String()
	if strings.Count(out, "panic in ldap connection") != 1 || strings.Contains(out, "secretname") {
		t.Errorf("log: %q", out)
	}
}
