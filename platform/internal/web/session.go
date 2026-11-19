package web

import (
	"context"
	"errors"
	"net/http"
	"net/url"
	"strings"

	"siplink/internal/auth"
	"siplink/internal/store"
)

// SessionCookie is the name of the session cookie (design §6.2).
const SessionCookie = "siplink_session"

type adminKey struct{}

// current returns the administrator of the request; nil on public pages.
func current(r *http.Request) *store.Admin {
	a, _ := r.Context().Value(adminKey{}).(*store.Admin)
	return a
}

func (s *Server) setSessionCookie(w http.ResponseWriter, r *http.Request, token string) {
	http.SetCookie(w, &http.Cookie{
		Name:     SessionCookie,
		Value:    token,
		Path:     "/",
		HttpOnly: true,
		Secure:   s.cookieSecure(r),
		SameSite: http.SameSiteLaxMode,
		MaxAge:   int(auth.AbsoluteTimeout.Seconds()),
	})
}

func (s *Server) clearSessionCookie(w http.ResponseWriter, r *http.Request) {
	http.SetCookie(w, &http.Cookie{Name: SessionCookie, Value: "", Path: "/", HttpOnly: true,
		Secure: s.cookieSecure(r), SameSite: http.SameSiteLaxMode, MaxAge: -1})
}

// cookieSecure: cookies are Secure unless plain HTTP is explicitly allowed and in use.
func (s *Server) cookieSecure(r *http.Request) bool {
	return s.isSecure(r) || !s.Cfg.AllowHTTPProvisioning
}

func sessionToken(r *http.Request) string {
	c, err := r.Cookie(SessionCookie)
	if err != nil {
		return ""
	}
	return c.Value
}

// requireLogin loads the session; without one it sends the browser to /login. A
// session waiting for the second step reaches only /logout here (§6.8). An
// administrator who must change their password, or (policy) switch a two-step method
// on, reaches only their own account page and /logout until they did.
func (s *Server) requireLogin(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		admin, se, err := s.Sessions.Check(r.Context(), sessionToken(r))
		if err != nil {
			if !errors.Is(err, auth.ErrNoSession) {
				s.Log.Error("session check failed", "err", err)
				s.fail(w, r, http.StatusInternalServerError, tr(r).T("error.internal_retry"))
				return
			}
			s.clearSessionCookie(w, r)
			if r.Method == http.MethodGet {
				http.Redirect(w, r, "/login?next="+url.QueryEscape(r.URL.RequestURI()), http.StatusSeeOther)
			} else {
				http.Redirect(w, r, "/login", http.StatusSeeOther)
			}
			return
		}
		if info := logInfoFrom(r.Context()); info != nil {
			info.adminID = admin.ID
		}
		path := r.URL.Path
		if se.Stage != "" && path != "/logout" {
			http.Redirect(w, r, "/login/step", http.StatusSeeOther)
			return
		}
		own := path == "/me" || strings.HasPrefix(path, "/me/") || path == "/logout"
		if admin.MustChangePassword && path != "/me" && path != "/logout" {
			http.Redirect(w, r, "/me", http.StatusSeeOther)
			return
		}
		if !own {
			switch must, err := s.mustSetUp(r.Context(), admin); {
			case err != nil:
				s.internal(w, r, err)
				return
			case must:
				http.Redirect(w, r, "/me", http.StatusSeeOther)
				return
			}
		}
		next(w, withAdmin(r, admin))
	}
}

// requirePending lets only a session that waits for the second step through (§6.8);
// a complete session goes to the dashboard, none to /login. The administrator is kept
// apart from current(r), so these pages render like the login page, without navigation.
func (s *Server) requirePending(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		admin, se, err := s.Sessions.Check(r.Context(), sessionToken(r))
		switch {
		case errors.Is(err, auth.ErrNoSession):
			s.clearSessionCookie(w, r)
			http.Redirect(w, r, "/login", http.StatusSeeOther)
			return
		case err != nil:
			s.internal(w, r, err)
			return
		case se.Stage != store.StageSecond:
			http.Redirect(w, r, "/", http.StatusSeeOther)
			return
		}
		if info := logInfoFrom(r.Context()); info != nil {
			info.adminID = admin.ID
		}
		next(w, r.WithContext(context.WithValue(r.Context(), pendingKey{}, admin)))
	}
}

type pendingKey struct{}

// pendingAdmin returns the administrator of a session waiting for the second step.
func pendingAdmin(r *http.Request) *store.Admin {
	a, _ := r.Context().Value(pendingKey{}).(*store.Admin)
	return a
}

// fullSession returns the administrator of a complete session of the request, or nil.
func (s *Server) fullSession(r *http.Request) *store.Admin {
	a, se, err := s.Sessions.Check(r.Context(), sessionToken(r))
	if err != nil || se.Stage != "" {
		return nil
	}
	return a
}

// requireSuper lets only super administrators through; others get 404.
func (s *Server) requireSuper(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if a := current(r); a == nil || a.Role != store.RoleSuper {
			s.notFound(w, r)
			return
		}
		next(w, r)
	}
}
