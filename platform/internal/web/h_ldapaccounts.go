package web

import (
	"errors"
	"net/http"
	"regexp"
	"slices"

	"siplink/internal/auth"
	"siplink/internal/books"
	"siplink/internal/secret"
	"siplink/internal/store"
)

func (s *Server) routesLDAPAccounts() {
	s.page("GET /ldap-accounts", s.ldapAccountList)
	s.page("GET /ldap-accounts/new", s.ldapAccountNew)
	s.page("POST /ldap-accounts", s.ldapAccountCreate)
	s.page("GET /ldap-accounts/{id}", s.ldapAccountEdit)
	s.page("POST /ldap-accounts/{id}", s.ldapAccountUpdate)
	s.page("POST /ldap-accounts/{id}/reset", s.ldapAccountReset)
	s.page("POST /ldap-accounts/{id}/delete", s.ldapAccountDelete)
}

var accountNamePattern = regexp.MustCompile(`^[a-z0-9-]{2,32}$`)

// ldapSecretLength is the length of a service account password (design §4.3).
const ldapSecretLength = 32

type ldapAccountsData struct {
	Accounts []*store.LDAPAccount
	Regions  map[int64]string
	Books    map[int64]string
	Suffix   string
}

func (s *Server) ldapAccountList(w http.ResponseWriter, r *http.Request) {
	list, err := s.St.ListLDAPAccounts(r.Context(), s.bookScope(r))
	if err != nil {
		s.internal(w, r, err)
		return
	}
	names, err := s.allRegionNames(r)
	if err != nil {
		s.internal(w, r, err)
		return
	}
	all, err := s.St.ListBooks(r.Context(), store.BookScope{All: true})
	if err != nil {
		s.internal(w, r, err)
		return
	}
	bookNames := map[int64]string{}
	for _, b := range all {
		bookNames[b.ID] = b.Name
	}
	s.ok(w, r, "ldap_accounts", &Page{Title: tr(r).T("nav.ldap_accounts"), Nav: "ldap", Data: ldapAccountsData{
		Accounts: list, Regions: names, Books: bookNames, Suffix: s.Cfg.LDAPSuffix}})
}

type ldapAccountForm struct {
	Account  *store.LDAPAccount
	New      bool
	Regions  []store.Region
	Global   bool
	Books    []*store.Book
	RegionOf map[int64]string
	Error    string
	BindDN   string
	Password string // shown once after create / reset
}

func (s *Server) ldapAccountFormPage(w http.ResponseWriter, r *http.Request, status int, f ldapAccountForm) {
	a := current(r)
	regions, err := s.St.ListRegions(r.Context(), a.RegionIDs, a.Role == store.RoleSuper)
	if err != nil {
		s.internal(w, r, err)
		return
	}
	f.Regions, f.Global = regions, a.Role == store.RoleSuper
	// Offer the books the administrator may use (own + global, all for super).
	if f.Books, err = s.St.ListBooks(r.Context(), store.BookScope{All: a.Role == store.RoleSuper, Regions: a.RegionIDs}); err != nil {
		s.internal(w, r, err)
		return
	}
	if f.RegionOf, err = s.allRegionNames(r); err != nil {
		s.internal(w, r, err)
		return
	}
	title := tr(r).T("dirs.ldap.edit_title")
	if f.New {
		title = tr(r).T("dirs.ldap.new")
	}
	s.render(w, r, status, "ldap_account_edit", &Page{Title: title, Nav: "ldap", Data: f})
}

func (s *Server) bindDN(name string) string { return "cn=" + name + ",ou=accounts," + s.Cfg.LDAPSuffix }

func (s *Server) loadLDAPAccount(w http.ResponseWriter, r *http.Request) *store.LDAPAccount {
	id, ok := pathID(r, "id")
	a, err := s.St.LDAPAccountByID(r.Context(), id)
	if !ok || errors.Is(err, store.ErrNotFound) || err == nil && !actor(r).Can(auth.LDAPAccountManage, a.RegionID) {
		s.notFound(w, r)
		return nil
	}
	if err != nil {
		s.internal(w, r, err)
		return nil
	}
	return a
}

// readAccountForm reads note, disabled, region (create, or super) and books, and
// checks the grant rules: books must be usable by the administrator and be global
// or of the account's own region (§2.3, §2.4).
func (s *Server) readAccountForm(r *http.Request, a *store.LDAPAccount, regionEditable bool) string {
	a.Note = truncate(trimmed(r, "note"), books.MaxNote)
	a.Disabled = r.FormValue("disabled") == "1"
	if regionEditable {
		a.RegionID = formRegion(r)
	}
	act := actor(r)
	if !act.Can(auth.LDAPAccountManage, a.RegionID) {
		return tr(r).T("dirs.ldap.region_forbidden")
	}
	if a.RegionID != nil {
		if _, err := s.St.RegionByID(r.Context(), *a.RegionID); err != nil {
			return tr(r).T("dirs.region_missing")
		}
	}
	a.BookIDs = formIDs(r, "books")
	slices.Sort(a.BookIDs)
	a.BookIDs = slices.Compact(a.BookIDs)
	for _, id := range a.BookIDs {
		b, err := s.St.BookByID(r.Context(), id)
		if err != nil || !act.Can(auth.BookUse, b.RegionID) {
			return tr(r).T("dirs.ldap.book_unavailable")
		}
		if b.RegionID != nil && (a.RegionID == nil || *a.RegionID != *b.RegionID) {
			return tr(r).T("dirs.ldap.book_other_region", "name", b.Name)
		}
	}
	return ""
}

func (s *Server) ldapAccountNew(w http.ResponseWriter, r *http.Request) {
	a := &store.LDAPAccount{}
	if u := current(r); u.Role != store.RoleSuper && len(u.RegionIDs) > 0 {
		a.RegionID = &u.RegionIDs[0]
	}
	s.ldapAccountFormPage(w, r, http.StatusOK, ldapAccountForm{New: true, Account: a})
}

func (s *Server) ldapAccountCreate(w http.ResponseWriter, r *http.Request) {
	a := &store.LDAPAccount{Name: trimmed(r, "name")}
	msg := s.readAccountForm(r, a, true)
	if !accountNamePattern.MatchString(a.Name) {
		msg = tr(r).T("dirs.ldap.name_invalid")
	}
	if msg != "" {
		s.ldapAccountFormPage(w, r, http.StatusBadRequest, ldapAccountForm{New: true, Account: a, Error: msg})
		return
	}
	password := secret.RandomText(ldapSecretLength)
	a.PasswordHash = secret.HashHex(password)
	if err := s.St.CreateLDAPAccount(r.Context(), a); errors.Is(err, store.ErrConflict) {
		s.ldapAccountFormPage(w, r, http.StatusBadRequest, ldapAccountForm{New: true, Account: a, Error: tr(r).T("dirs.ldap.name_taken")})
		return
	} else if err != nil {
		s.internal(w, r, err)
		return
	}
	s.audit(r, "ldap_account.create", "ldap_account", a.ID, a.Name, a.RegionID, map[string]any{"books": a.BookIDs})
	s.ok(w, r, "ldap_account_secret", &Page{Title: tr(r).T("dirs.ldap.created_title"), Nav: "ldap",
		Data: ldapAccountForm{Account: a, BindDN: s.bindDN(a.Name), Password: password}})
}

func (s *Server) ldapAccountEdit(w http.ResponseWriter, r *http.Request) {
	if a := s.loadLDAPAccount(w, r); a != nil {
		s.ldapAccountFormPage(w, r, http.StatusOK, ldapAccountForm{Account: a, BindDN: s.bindDN(a.Name)})
	}
}

func (s *Server) ldapAccountUpdate(w http.ResponseWriter, r *http.Request) {
	old := s.loadLDAPAccount(w, r)
	if old == nil {
		return
	}
	a := *old
	if msg := s.readAccountForm(r, &a, current(r).Role == store.RoleSuper); msg != "" {
		s.ldapAccountFormPage(w, r, http.StatusBadRequest, ldapAccountForm{Account: &a, BindDN: s.bindDN(a.Name), Error: msg})
		return
	}
	if err := s.St.UpdateLDAPAccount(r.Context(), &a); err != nil {
		s.internal(w, r, err)
		return
	}
	s.audit(r, "ldap_account.update", "ldap_account", a.ID, a.Name, a.RegionID, changes(
		field{"note", old.Note, a.Note}, field{"disabled", old.Disabled, a.Disabled},
		field{"region_id", derefOr(old.RegionID), derefOr(a.RegionID)}, field{"books", old.BookIDs, a.BookIDs}))
	s.redirect(w, r, "/ldap-accounts", "ok", tr(r).T("dirs.ldap.saved", "name", a.Name))
}

func (s *Server) ldapAccountReset(w http.ResponseWriter, r *http.Request) {
	a := s.loadLDAPAccount(w, r)
	if a == nil {
		return
	}
	password := secret.RandomText(ldapSecretLength)
	if err := s.St.SetLDAPAccountPassword(r.Context(), a.ID, secret.HashHex(password)); err != nil {
		s.internal(w, r, err)
		return
	}
	s.audit(r, "ldap_account.reset", "ldap_account", a.ID, a.Name, a.RegionID, map[string]any{"password": auditChanged})
	s.ok(w, r, "ldap_account_secret", &Page{Title: tr(r).T("dirs.ldap.reset_title"), Nav: "ldap",
		Data: ldapAccountForm{Account: a, BindDN: s.bindDN(a.Name), Password: password}})
}

func (s *Server) ldapAccountDelete(w http.ResponseWriter, r *http.Request) {
	a := s.loadLDAPAccount(w, r)
	if a == nil {
		return
	}
	if err := s.St.DeleteLDAPAccount(r.Context(), a.ID); errors.Is(err, store.ErrInUse) {
		users, _ := s.St.LDAPAccountUsers(r.Context(), a.ID)
		s.redirect(w, r, "/ldap-accounts", "error", tr(r).T("dirs.ldap.in_use", "name", a.Name, "templates", users))
		return
	} else if err != nil {
		s.internal(w, r, err)
		return
	}
	s.audit(r, "ldap_account.delete", "ldap_account", a.ID, a.Name, a.RegionID, nil)
	s.redirect(w, r, "/ldap-accounts", "ok", tr(r).T("dirs.ldap.deleted", "name", a.Name))
}

// bookMoveProblem says why a book cannot move to another region, or "": an LDAP
// account or a configuration template of another region that uses the book would break
// the ownership rule (§2.3).
func (s *Server) bookMoveProblem(r *http.Request, b *store.Book, to *int64) string {
	if to == nil {
		return "" // a global book can be used from every region
	}
	if p := s.directoryMoveProblem(r, store.KindHosted, b.ID, to); p != "" {
		return p
	}
	regions, err := s.St.AccountRegionsOfBook(r.Context(), b.ID)
	if err != nil {
		return tr(r).T("error.internal_retry")
	}
	for _, reg := range regions {
		if reg == nil || *reg != *to {
			return tr(r).T("dirs.move_used_by_accounts")
		}
	}
	return ""
}
