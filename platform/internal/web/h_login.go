package web

import (
	"errors"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"
	"unicode"

	"siplink/internal/auth"
	"siplink/internal/store"
)

type loginData struct {
	Username string
	Next     string
	Error    string
}

// safeNext keeps a local path for the redirect after a sign-in, else "/". It refuses
// every control character and backslash (browsers drop tabs and newlines and read "\\"
// as "/", so "/\t/host" would become "//host"), anything that is not a path-only
// reference, and paths that start with "//" before or after decoding.
func safeNext(next string) string {
	if !strings.HasPrefix(next, "/") || strings.HasPrefix(next, "//") || strings.ContainsRune(next, '\\') {
		return "/"
	}
	for _, r := range next {
		if unicode.IsControl(r) {
			return "/"
		}
	}
	u, err := url.Parse(next)
	if err != nil || u.Scheme != "" || u.Host != "" || u.User != nil || u.Opaque != "" ||
		!strings.HasPrefix(u.Path, "/") || strings.HasPrefix(u.Path, "//") {
		return "/"
	}
	return next
}

func (s *Server) loginForm(w http.ResponseWriter, r *http.Request) {
	if a, se, err := s.Sessions.Check(r.Context(), sessionToken(r)); err == nil && a != nil {
		to := "/"
		if se.Stage != "" {
			to = "/login/step"
		}
		http.Redirect(w, r, to, http.StatusSeeOther)
		return
	}
	s.ok(w, r, "login", &Page{Title: "登录", Data: loginData{Next: safeNext(r.URL.Query().Get("next"))}})
}

func (s *Server) loginSubmit(w http.ResponseWriter, r *http.Request) {
	username := strings.TrimSpace(r.FormValue("username"))
	password := r.FormValue("password")
	next := safeNext(r.FormValue("next"))
	ip := s.clientIP(r)
	key := strings.ToLower(username)
	data := loginData{Username: username, Next: next}

	if !s.Logins.Attempt(key, ip) {
		data.Error = "尝试次数过多，请 15 分钟后重试。"
		s.render(w, r, http.StatusTooManyRequests, "login", &Page{Title: "登录", Data: data})
		return
	}
	admin, err := s.St.AdminByUsername(r.Context(), username)
	okPassword := false
	switch {
	case errors.Is(err, store.ErrNotFound) || username == "":
		auth.VerifyDummy(password)
	case err != nil:
		s.internal(w, r, err)
		return
	default:
		okPassword, err = auth.VerifyPassword(admin.PasswordHash, password)
		if err != nil {
			s.Log.Error("stored password hash unreadable", "admin", admin.ID)
		}
	}
	if !okPassword || admin.Disabled {
		e := &store.AuditEntry{Action: "login.failure", ObjectType: "admin", ObjectName: truncate(username, 64), IP: ip}
		if admin != nil {
			e.ObjectID = &admin.ID
		}
		s.addAudit(r, e)
		data.Error = "用户名或密码不正确。"
		s.render(w, r, http.StatusUnauthorized, "login", &Page{Title: "登录", Data: data})
		return
	}
	s.Logins.Succeeded(key, ip)
	// A new token at every login; an old cookie of this browser is ended first.
	_ = s.Sessions.Destroy(r.Context(), sessionToken(r))
	ts, err := s.St.TwoStepState(r.Context(), admin.ID)
	if err != nil {
		s.internal(w, r, err)
		return
	}
	if ts.Methods() > 0 {
		token, err := s.Sessions.CreatePending(r.Context(), admin.ID, ip, r.UserAgent())
		if err != nil {
			s.internal(w, r, err)
			return
		}
		s.setSessionCookie(w, r, token)
		http.Redirect(w, r, "/login/step?next="+url.QueryEscape(next), http.StatusSeeOther)
		return
	}
	to, err := s.signIn(w, r, admin, next, nil)
	if err != nil {
		s.internal(w, r, err)
		return
	}
	http.Redirect(w, r, to, http.StatusSeeOther)
}

// signIn starts a complete session after the password and, when due, the second step:
// a new token, the login audit entry. It returns where the browser goes next: the forced
// password change if any, else next.
func (s *Server) signIn(w http.ResponseWriter, r *http.Request, admin *store.Admin, next string, detail map[string]any) (string, error) {
	ip := s.clientIP(r)
	token, err := s.Sessions.Create(r.Context(), admin.ID, ip, r.UserAgent())
	if err != nil {
		return "", err
	}
	_ = s.St.TouchLogin(r.Context(), admin.ID)
	s.addAudit(r, &store.AuditEntry{AdminID: &admin.ID, AdminName: admin.Username, Action: "login.success",
		ObjectType: "admin", ObjectID: &admin.ID, ObjectName: admin.Username, IP: ip, Detail: detail})
	s.setSessionCookie(w, r, token)
	if admin.MustChangePassword {
		return "/me", nil
	}
	return next, nil
}

func (s *Server) logout(w http.ResponseWriter, r *http.Request) {
	a := current(r)
	_ = s.Sessions.Destroy(r.Context(), sessionToken(r))
	s.clearSessionCookie(w, r)
	s.audit(r, "logout", "admin", a.ID, a.Username, nil, nil)
	http.Redirect(w, r, "/login", http.StatusSeeOther)
}

// ---- region filter of the top bar ----

const filterCookie = "siplink_region"

func (s *Server) setFilter(w http.ResponseWriter, r *http.Request) {
	id := formID(r, "region")
	http.SetCookie(w, &http.Cookie{Name: filterCookie, Value: strconv.FormatInt(id, 10), Path: "/",
		HttpOnly: true, Secure: s.cookieSecure(r), SameSite: http.SameSiteLaxMode, MaxAge: int((30 * 24 * time.Hour).Seconds())})
	http.Redirect(w, r, safeNext(r.FormValue("next")), http.StatusSeeOther)
}

// regionFilter returns the filter for administrators who see more than one region.
func (s *Server) regionFilter(r *http.Request) *RegionFilter {
	a := current(r)
	if a == nil {
		return nil
	}
	regions, err := s.St.ListRegions(r.Context(), a.RegionIDs, a.Role == store.RoleSuper)
	if err != nil || len(regions) < 2 && a.Role != store.RoleSuper || len(regions) == 0 {
		return nil
	}
	f := &RegionFilter{Options: regions}
	if c, err := r.Cookie(filterCookie); err == nil {
		id, _ := strconv.ParseInt(c.Value, 10, 64)
		for _, reg := range regions {
			if reg.ID == id {
				f.Selected = id
			}
		}
	}
	return f
}

// selectedRegion is the region chosen in the top bar, or 0 for all.
func (s *Server) selectedRegion(r *http.Request) int64 {
	if f := s.regionFilter(r); f != nil {
		return f.Selected
	}
	return 0
}

// ---- audit ----

func (s *Server) addAudit(r *http.Request, e *store.AuditEntry) {
	if e.IP == "" {
		e.IP = s.clientIP(r)
	}
	if err := s.St.AddAudit(r.Context(), e); err != nil {
		s.Log.Error("audit write failed", "action", e.Action, "err", err)
	}
}

// audit writes an entry for an action of the current administrator.
func (s *Server) audit(r *http.Request, action, objectType string, objectID int64, objectName string, region *int64, detail map[string]any) {
	a := current(r)
	e := &store.AuditEntry{Action: action, ObjectType: objectType, ObjectName: objectName, RegionID: region, Detail: detail}
	if objectID != 0 {
		e.ObjectID = &objectID
	}
	if a != nil {
		e.AdminID, e.AdminName = &a.ID, a.Username
	}
	s.addAudit(r, e)
}

func truncate(s string, n int) string {
	if r := []rune(s); len(r) > n {
		return string(r[:n])
	}
	return s
}
