package web

import (
	"context"
	"net/http"
	"strings"
	"sync"
	"time"

	"siplink/internal/i18n"
	"siplink/internal/store"
)

// ---- the language of a request (design §5.5) ----

const langCookie = "siplink_lang"

type trKey struct{}

// tr is the translator of the request: the administrator's stored language, else the
// cookie, else Accept-Language (the language middleware and withAdmin set it).
func tr(r *http.Request) *i18n.Tr {
	if t, ok := r.Context().Value(trKey{}).(*i18n.Tr); ok {
		return t
	}
	return i18n.For(requestLang(r))
}

// requestLang is the language before sign-in: the cookie, else Accept-Language.
func requestLang(r *http.Request) *i18n.Lang {
	if l := cookieLang(r); l != nil {
		return l
	}
	return i18n.Match(r.Header.Get("Accept-Language"))
}

// cookieLang is the language the browser chose, nil when it chose none.
func cookieLang(r *http.Request) *i18n.Lang {
	if c, err := r.Cookie(langCookie); err == nil {
		return i18n.ByCode(c.Value)
	}
	return nil
}

func withLang(r *http.Request, l *i18n.Lang) *http.Request {
	return r.WithContext(context.WithValue(r.Context(), trKey{}, i18n.For(l)))
}

// withAdmin makes a the request's administrator and their stored language, if any,
// the request's language.
func withAdmin(r *http.Request, a *store.Admin) *http.Request {
	r = r.WithContext(context.WithValue(r.Context(), adminKey{}, a))
	if l := i18n.ByCode(a.Language); l != nil {
		r = withLang(r, l)
	}
	return r
}

// language gives every request the language of the browser; requireLogin replaces it
// with the administrator's stored one.
func (s *Server) language(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		next.ServeHTTP(w, withLang(r, requestLang(r)))
	})
}

func (s *Server) setLangCookie(w http.ResponseWriter, r *http.Request, l *i18n.Lang) {
	http.SetCookie(w, &http.Cookie{Name: langCookie, Value: l.Code, Path: "/", HttpOnly: true,
		Secure: s.cookieSecure(r), SameSite: http.SameSiteLaxMode, MaxAge: int((365 * 24 * time.Hour).Seconds())})
}

// setLanguage is the language choice of the login and second-step pages and of the
// user menu: the cookie, and for a complete session the administrator's stored choice.
func (s *Server) setLanguage(w http.ResponseWriter, r *http.Request) {
	if l := i18n.ByCode(r.FormValue("lang")); l != nil {
		s.setLangCookie(w, r, l)
		if a := s.fullSession(r); a != nil {
			if err := s.St.SetAdminLanguage(r.Context(), a.ID, l.Code); err != nil {
				s.internal(w, r, err)
				return
			}
		}
	}
	http.Redirect(w, r, safeNext(r.FormValue("next")), http.StatusSeeOther)
}

// syncLanguage runs when a sign-in completes: a stored language refreshes the cookie,
// and without one the cookie's language becomes the administrator's choice.
func (s *Server) syncLanguage(w http.ResponseWriter, r *http.Request, a *store.Admin) {
	if l := i18n.ByCode(a.Language); l != nil {
		s.setLangCookie(w, r, l)
		return
	}
	if l := cookieLang(r); l != nil {
		if err := s.St.SetAdminLanguage(r.Context(), a.ID, l.Code); err != nil {
			s.Log.Error("storing the language failed", "admin", a.ID, "err", err)
		}
	}
}

// ---- texts of the scripts ----

// jsTexts are the texts app.js and the other scripts read from the page (the keys of
// the catalogs that start with "js.", the date patterns and the language's code).
var jsTexts = sync.OnceValue(func() map[*i18n.Lang]map[string]string {
	out := map[*i18n.Lang]map[string]string{}
	for _, l := range i18n.Langs {
		t := i18n.For(l)
		m := map[string]string{"lang": l.Code, "dir": l.Dir(), "date": t.Raw("format.date"), "datetime": t.Raw("format.datetime")}
		for _, k := range i18n.Keys(l) {
			if name, ok := strings.CutPrefix(k, "js."); ok {
				m[name] = t.Raw(k)
			}
		}
		out[l] = m
	}
	return out
})

// errText is the text of err in the request's language, "" for nil.
func errText(r *http.Request, err error) string {
	if err == nil {
		return ""
	}
	return tr(r).Err(err)
}

// chineseColumns: CSV files written for a page in Chinese (either script) carry the
// Chinese column names, all others the English ones (§5.5).
func chineseColumns(r *http.Request) bool {
	code := tr(r).Lang.Code
	return code == "zh-Hans" || code == "zh-Hant"
}

// useNames puts what uses an object into words: "客户端模板 Standard".
func useNames(t *i18n.Tr, list []store.Use) []string {
	out := make([]string, len(list))
	for i, u := range list {
		out[i] = t.T("use.item", "kind", t.T("audit.object."+u.Kind), "name", u.Name)
	}
	return out
}

// adminTr translates for an administrator who is not necessarily the one at the
// browser (the recipient of a mail): their stored language, else the request's.
func adminTr(r *http.Request, a *store.Admin) *i18n.Tr {
	if l := i18n.ByCode(a.Language); l != nil {
		return i18n.For(l)
	}
	return tr(r)
}

// auditChanged is what an audit entry stores for a secret that changed (§6.6): a code
// the page translates, never the value.
const auditChanged = "changed"
