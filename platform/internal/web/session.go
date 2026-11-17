package web

import (
	"context"
	"errors"
	"net/http"
	"net/url"

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

// requireLogin loads the session; without one it sends the browser to /login.
// An administrator who must change their password only reaches /me and /logout.
func (s *Server) requireLogin(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		admin, _, err := s.Sessions.Check(r.Context(), sessionToken(r))
		if err != nil {
			if !errors.Is(err, auth.ErrNoSession) {
				s.Log.Error("session check failed", "err", err)
				s.fail(w, r, http.StatusInternalServerError, "内部错误，请稍后重试。")
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
		if admin.MustChangePassword && r.URL.Path != "/me" && r.URL.Path != "/logout" {
			http.Redirect(w, r, "/me", http.StatusSeeOther)
			return
		}
		next(w, r.WithContext(context.WithValue(r.Context(), adminKey{}, admin)))
	}
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
