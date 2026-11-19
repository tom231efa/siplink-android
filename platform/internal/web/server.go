// Package web is the administrators' web UI (design §5), server-rendered with
// html/template, plus the middleware every route shares.
package web

import (
	"context"
	"log/slog"
	"net"
	"net/http"
	"net/netip"
	"strings"
	"time"

	"siplink/internal/auth"
	"siplink/internal/config"
	"siplink/internal/mail"
	"siplink/internal/pki"
	"siplink/internal/secret"
	"siplink/internal/store"

	"github.com/go-webauthn/webauthn/webauthn"
)

// Version is set by main.
var Version = "dev"

// Server holds what the handlers need.
type Server struct {
	Cfg      *config.Config
	St       *store.Store
	Log      *slog.Logger
	Sessions *auth.Sessions
	Logins   *auth.LoginLimiter
	Now      func() time.Time
	Ext      Extensions
	// Mailer sends the e-mail codes and test mails (§6.8).
	Mailer *mail.Sender

	pages   *pages
	imports importCache
	reveals revealGrants
	pending holding[*pki.Own] // uploads that wait for a confirmation
	limits  deviceLimits
	csrf    *http.CrossOriginProtection
	mux     *http.ServeMux

	// Two-step sign-in (§6.8).
	stepFails  *auth.Window // failed second steps and password re-entries, per administrator
	mailMinute *auth.Window // e-mail codes per administrator
	mailHour   *auth.Window
	passkeys   *webauthn.WebAuthn // nil when browsers refuse WebAuthn at the public URL
	ceremonies holding[*webauthn.SessionData]
	shown      holding[[]string] // recovery codes to be shown once
}

// Extensions are the parts wired in by main.
type Extensions struct {
	// Box seals stored passwords (SIP, external LDAP).
	Box *secret.Box
	// LDAPKey derives the LDAP passwords of clients (design §4.3).
	LDAPKey [32]byte
	// CodeKey keys the stored e-mail and recovery codes (§6.8).
	CodeKey [32]byte
	// Certs chooses the certificate of every listener: the built-in CA's, the
	// operator's uploaded one or files (§6.7). It gives the pin of QR codes and the
	// platform's CAs of documents.
	Certs *pki.Certs
}

// New builds the server and its routes.
func New(cfg *config.Config, st *store.Store, log *slog.Logger, now func() time.Time) (*Server, error) {
	if now == nil {
		now = time.Now
	}
	s := &Server{
		Cfg:      cfg,
		St:       st,
		Log:      log,
		Now:      now,
		Sessions: &auth.Sessions{St: st, Now: now},
		Logins:   auth.NewLoginLimiter(now),
		csrf:     http.NewCrossOriginProtection(),
		mux:      http.NewServeMux(),
		limits:   newDeviceLimits(now),
		Mailer:   &mail.Sender{},

		pending:    holding[*pki.Own]{ttl: 10 * time.Minute},
		stepFails:  auth.NewWindow(5, 15*time.Minute, now),
		mailMinute: auth.NewWindow(1, time.Minute, now),
		mailHour:   auth.NewWindow(5, time.Hour, now),
		ceremonies: holding[*webauthn.SessionData]{ttl: 5 * time.Minute},
		shown:      holding[[]string]{ttl: 5 * time.Minute},
	}
	var err error
	if s.passkeys, err = newPasskeys(cfg); err != nil {
		return nil, err
	}
	if s.pages, err = loadPages(); err != nil {
		return nil, err
	}
	s.csrf.SetDenyHandler(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		s.fail(w, r, http.StatusForbidden, tr(r).T("error.cross_site"))
	}))
	s.routes()
	return s, nil
}

// Handler returns the root handler with all middleware.
func (s *Server) Handler() http.Handler {
	return s.logRequests(s.language(s.recoverPanics(s.headers(s.mux))))
}

// handle registers a route. Every route records its pattern for the log.
func (s *Server) handle(pattern string, h http.HandlerFunc) {
	s.mux.Handle(pattern, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if info := logInfoFrom(r.Context()); info != nil {
			info.pattern = r.Pattern
		}
		h(w, r)
	}))
}

// page registers an admin route: HTTPS required, CSRF protection on unsafe methods,
// a valid session, and the forced password change.
func (s *Server) page(pattern string, h http.HandlerFunc) {
	s.handle(pattern, s.requireSecure(s.csrfCheck(s.requireLogin(h))))
}

// publicPage registers a route without a session (login).
func (s *Server) publicPage(pattern string, h http.HandlerFunc) {
	s.handle(pattern, s.requireSecure(s.csrfCheck(h)))
}

func (s *Server) routes() {
	s.handle("GET /static/", s.static)
	s.handle("GET /healthz", s.healthz)

	s.publicPage("GET /login", s.loginForm)
	s.publicPage("POST /login", s.loginSubmit)
	s.page("POST /logout", s.logout)
	s.page("GET /{$}", s.dashboard)
	s.page("POST /filter", s.setFilter)
	s.page("POST /theme", s.setTheme)
	s.publicPage("POST /language", s.setLanguage)
	s.handle("GET /", s.requireSecure(s.unknownPath))
	s.routesP2()
	s.routesBooks()
	s.routesLDAPAccounts()
	s.routesTemplates()
	s.routesClients()
	s.routesDevice()
	s.routesSystem()
	s.routesAccount()
	s.routesTwoStep()
	s.routesMail()
}

// ---- middleware ----

type logInfo struct {
	pattern string
	adminID int64
}

type logKey struct{}

func logInfoFrom(ctx context.Context) *logInfo {
	v, _ := ctx.Value(logKey{}).(*logInfo)
	return v
}

type statusWriter struct {
	http.ResponseWriter
	status int
	bytes  int
}

func (w *statusWriter) WriteHeader(code int) {
	if w.status == 0 {
		w.status = code
	}
	w.ResponseWriter.WriteHeader(code)
}

func (w *statusWriter) Write(b []byte) (int, error) {
	if w.status == 0 {
		w.status = http.StatusOK
	}
	n, err := w.ResponseWriter.Write(b)
	w.bytes += n
	return n, err
}

func (w *statusWriter) Unwrap() http.ResponseWriter { return w.ResponseWriter }

// logRequests logs method, route pattern (never the real path), status, duration,
// client address and administrator id (design §6.6).
func (s *Server) logRequests(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		info := &logInfo{}
		sw := &statusWriter{ResponseWriter: w}
		next.ServeHTTP(sw, r.WithContext(context.WithValue(r.Context(), logKey{}, info)))
		if sw.status == 0 {
			sw.status = http.StatusOK
		}
		pattern := info.pattern
		if pattern == "" {
			pattern = "(unmatched)"
		}
		attrs := []any{"method", r.Method, "route", pattern, "status", sw.status,
			"duration_ms", time.Since(start).Milliseconds(), "ip", s.clientIP(r)}
		if info.adminID != 0 {
			attrs = append(attrs, "admin", info.adminID)
		}
		s.Log.Info("http", attrs...)
	})
}

func (s *Server) recoverPanics(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		defer func() {
			if v := recover(); v != nil {
				if v == http.ErrAbortHandler {
					panic(v)
				}
				// No request data in the log: only the fact and the value type.
				s.Log.Error("panic in handler", "route", logPattern(r))
				http.Error(w, tr(r).T("error.internal"), http.StatusInternalServerError)
			}
		}()
		next.ServeHTTP(w, r)
	})
}

func logPattern(r *http.Request) string {
	if info := logInfoFrom(r.Context()); info != nil {
		return info.pattern
	}
	return ""
}

func (s *Server) headers(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		h := w.Header()
		h.Set("X-Content-Type-Options", "nosniff")
		h.Set("X-Frame-Options", "DENY")
		h.Set("Referrer-Policy", "same-origin")
		h.Set("Content-Security-Policy", "default-src 'self'; img-src 'self' data:; style-src 'self'; script-src 'self'; frame-ancestors 'none'; form-action 'self'; base-uri 'none'")
		if s.isSecure(r) {
			h.Set("Strict-Transport-Security", "max-age=31536000")
		}
		next.ServeHTTP(w, r)
	})
}

// isSecure reports whether the request reached the platform over HTTPS, directly
// or through a trusted proxy that says so.
func (s *Server) isSecure(r *http.Request) bool {
	if r.TLS != nil {
		return true
	}
	return s.fromTrustedProxy(r) && strings.EqualFold(r.Header.Get("X-Forwarded-Proto"), "https")
}

func (s *Server) fromTrustedProxy(r *http.Request) bool {
	peer, ok := peerAddr(r)
	return ok && s.Cfg.IsTrustedProxy(peer)
}

func peerAddr(r *http.Request) (netip.Addr, bool) {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		host = r.RemoteAddr
	}
	a, err := netip.ParseAddr(host)
	if err != nil {
		return netip.Addr{}, false
	}
	return a.Unmap(), true
}

// clientIP is the remote address, or the last untrusted hop of X-Forwarded-For when
// the peer is a trusted proxy (design §6.5).
func (s *Server) clientIP(r *http.Request) string {
	peer, ok := peerAddr(r)
	if !ok {
		return ""
	}
	if !s.Cfg.IsTrustedProxy(peer) {
		return peer.String()
	}
	hops := strings.Split(r.Header.Get("X-Forwarded-For"), ",")
	for i := len(hops) - 1; i >= 0; i-- {
		a, err := netip.ParseAddr(strings.TrimSpace(hops[i]))
		if err != nil {
			break
		}
		a = a.Unmap()
		if !s.Cfg.IsTrustedProxy(a) {
			return a.String()
		}
		peer = a
	}
	return peer.String()
}

// requireSecure refuses plain HTTP unless SIPLINK_ALLOW_HTTP_PROVISIONING is set.
func (s *Server) requireSecure(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if !s.isSecure(r) && !s.Cfg.AllowHTTPProvisioning {
			s.fail(w, r, http.StatusForbidden, tr(r).T("error.https_only"))
			return
		}
		next(w, r)
	}
}

func (s *Server) csrfCheck(next http.HandlerFunc) http.HandlerFunc {
	h := s.csrf.Handler(next)
	return h.ServeHTTP
}
