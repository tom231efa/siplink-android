package auth

import (
	"strings"
	"testing"
)

// TestLoginLimiterInvalidName: a name no administrator can have counts against the
// address and leaves nothing per name.
func TestLoginLimiterInvalidName(t *testing.T) {
	l := NewLoginLimiter(nil)
	for _, name := range []string{strings.Repeat("a", 4<<20), "名字", "a b", ""} {
		if !l.Attempt(name, "192.0.2.1") {
			t.Fatalf("%.10q refused", name)
		}
		l.Succeeded(name, "192.0.2.1")
	}
	if n := len(l.failures.keys); n != 0 {
		t.Errorf("failures kept for %d invalid names", n)
	}
	if s := l.perIP.keys["192.0.2.1"]; s == nil || s.count != 4 {
		t.Errorf("per-address count: %+v", s)
	}
}
