package auth

import (
	"sync"
	"time"
)

// Window counts events per key in a fixed window and is safe for concurrent use.
// It is kept in memory per process (design §6.5).
type Window struct {
	mu     sync.Mutex
	limit  int
	period time.Duration
	now    func() time.Time
	keys   map[string]*windowState
	sweep  time.Time
}

type windowState struct {
	start time.Time
	count int
}

// NewWindow allows limit events per key in each period.
func NewWindow(limit int, period time.Duration, now func() time.Time) *Window {
	if now == nil {
		now = time.Now
	}
	return &Window{limit: limit, period: period, now: now, keys: map[string]*windowState{}}
}

func (w *Window) state(key string, now time.Time) *windowState {
	if now.Sub(w.sweep) > w.period {
		for k, s := range w.keys {
			if now.Sub(s.start) >= w.period {
				delete(w.keys, k)
			}
		}
		w.sweep = now
	}
	s := w.keys[key]
	if s == nil || now.Sub(s.start) >= w.period {
		s = &windowState{start: now}
		w.keys[key] = s
	}
	return s
}

// Allow records one event and reports whether it is within the limit.
func (w *Window) Allow(key string) bool {
	w.mu.Lock()
	defer w.mu.Unlock()
	s := w.state(key, w.now())
	s.count++
	return s.count <= w.limit
}

// Add records one event without asking.
func (w *Window) Add(key string) {
	w.mu.Lock()
	defer w.mu.Unlock()
	w.state(key, w.now()).count++
}

// AddHold records one event; once the limit is reached the window restarts at this
// event, so the key stays over the limit for a full period after the last event.
func (w *Window) AddHold(key string) {
	w.mu.Lock()
	defer w.mu.Unlock()
	now := w.now()
	s := w.state(key, now)
	s.count++
	if s.count >= w.limit {
		s.start = now
	}
}

// Take records one attempt and answers whether it is within the limit, under one lock,
// so that parallel attempts cannot all pass a check made before they are counted. A
// refused attempt is not recorded. The attempt that reaches the limit restarts the
// window, so the key stays refused for a full period after it. An attempt that turns
// out to be good gives its slot back with Release (or clears the key with Reset).
func (w *Window) Take(key string) bool {
	w.mu.Lock()
	defer w.mu.Unlock()
	now := w.now()
	s := w.state(key, now)
	if s.count >= w.limit {
		return false
	}
	s.count++
	if s.count >= w.limit {
		s.start = now
	}
	return true
}

// Release gives back the slot of one attempt taken with Take.
func (w *Window) Release(key string) {
	w.mu.Lock()
	defer w.mu.Unlock()
	if s := w.keys[key]; s != nil && s.count > 0 {
		s.count--
	}
}

// Exceeded reports whether the key has reached the limit, without recording.
func (w *Window) Exceeded(key string) bool {
	w.mu.Lock()
	defer w.mu.Unlock()
	return w.state(key, w.now()).count >= w.limit
}

// Reset forgets a key.
func (w *Window) Reset(key string) {
	w.mu.Lock()
	defer w.mu.Unlock()
	delete(w.keys, key)
}

// LoginLimiter applies the login rules of §6.5: 5 failures for one user name from
// one address lock that name for that address for 15 minutes; 30 attempts per
// address per minute overall.
type LoginLimiter struct {
	failures *Window
	perIP    *Window
}

// NewLoginLimiter makes the limiter; now may be nil.
func NewLoginLimiter(now func() time.Time) *LoginLimiter {
	return &LoginLimiter{
		failures: NewWindow(5, 15*time.Minute, now),
		perIP:    NewWindow(30, time.Minute, now),
	}
}

func loginKey(username, ip string) string { return ip + "\x00" + username }

// Attempt reports whether a login attempt may be checked at all. It counts as a failure
// of that name and address until Succeeded clears them, so parallel guesses cannot pass
// the lock before their failures are recorded.
func (l *LoginLimiter) Attempt(username, ip string) bool {
	return l.perIP.Allow(ip) && l.failures.Take(loginKey(username, ip))
}

// Succeeded clears the failures of that name and address.
func (l *LoginLimiter) Succeeded(username, ip string) { l.failures.Reset(loginKey(username, ip)) }
