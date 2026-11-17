package web

import (
	"sync"
	"time"

	"siplink/internal/secret"
)

// holding keeps values of one administrator in memory for a short time under a random
// token: certificate uploads that wait for a confirmation, WebAuthn ceremonies,
// recovery codes to be shown once. A new value drops the administrator's older ones.
type holding[T any] struct {
	ttl   time.Duration
	mu    sync.Mutex
	items map[string]held[T]
}

type held[T any] struct {
	v       T
	adminID int64
	expires time.Time
}

func (h *holding[T]) put(now time.Time, adminID int64, v T) string {
	h.mu.Lock()
	defer h.mu.Unlock()
	if h.items == nil {
		h.items = map[string]held[T]{}
	}
	for k, it := range h.items {
		if now.After(it.expires) || it.adminID == adminID {
			delete(h.items, k)
		}
	}
	token := secret.Token(18)
	h.items[token] = held[T]{v: v, adminID: adminID, expires: now.Add(h.ttl)}
	return token
}

// take returns and forgets the value of token if it belongs to adminID and is fresh.
func (h *holding[T]) take(now time.Time, token string, adminID int64) (T, bool) {
	return h.get(now, token, adminID, true)
}

// peek returns the value of token like take but keeps it until it expires.
func (h *holding[T]) peek(now time.Time, token string, adminID int64) (T, bool) {
	return h.get(now, token, adminID, false)
}

func (h *holding[T]) get(now time.Time, token string, adminID int64, forget bool) (T, bool) {
	h.mu.Lock()
	defer h.mu.Unlock()
	it, ok := h.items[token]
	if !ok || it.adminID != adminID || now.After(it.expires) {
		var zero T
		return zero, false
	}
	if forget {
		delete(h.items, token)
	}
	return it.v, true
}
