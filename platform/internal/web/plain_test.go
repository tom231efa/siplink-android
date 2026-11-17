package web

import (
	"net/http/httptest"
	"net/netip"
	"testing"
)

func newPlainServer(t *testing.T, h *harness) string {
	ts := httptest.NewServer(h.srv.Handler())
	t.Cleanup(ts.Close)
	return ts.URL
}

func mustPrefix(s string) netip.Prefix { return netip.MustParsePrefix(s) }
