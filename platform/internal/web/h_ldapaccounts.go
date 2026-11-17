package web

import (
	"errors"
	"net/http"
	"regexp"
	"slices"
	"strings"

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
	s.ok(w, r, "ldap_accounts", &Page{Title: "LDAP 账号", Nav: "ldap", Data: ldapAccountsData{
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
	title := "编辑 LDAP 账号"
	if f.New {
		title = "新建 LDAP 账号"
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
		return "您无权在所选区域管理 LDAP 账号。"
	}
	if a.RegionID != nil {
		if _, err := s.St.RegionByID(r.Context(), *a.RegionID); err != nil {
			return "所选区域不存在。"
		}
	}
	a.BookIDs = formIDs(r, "books")
	slices.Sort(a.BookIDs)
	a.BookIDs = slices.Compact(a.BookIDs)
	for _, id := range a.BookIDs {
		b, err := s.St.BookByID(r.Context(), id)
		if err != nil || !act.Can(auth.BookUse, b.RegionID) {
			return "所选通讯录不存在或不可用。"
		}
		if b.RegionID != nil && (a.RegionID == nil || *a.RegionID != *b.RegionID) {
			return "通讯录「" + b.Name + "」属于其他区域，账号仅可使用全局及本区域的通讯录。"
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
		msg = "账号名仅可使用 2-32 个小写字母、数字或 -。"
	}
	if msg != "" {
		s.ldapAccountFormPage(w, r, http.StatusBadRequest, ldapAccountForm{New: true, Account: a, Error: msg})
		return
	}
	password := secret.RandomText(ldapSecretLength)
	a.PasswordHash = secret.HashHex(password)
	if err := s.St.CreateLDAPAccount(r.Context(), a); errors.Is(err, store.ErrConflict) {
		s.ldapAccountFormPage(w, r, http.StatusBadRequest, ldapAccountForm{New: true, Account: a, Error: "账号名已存在。"})
		return
	} else if err != nil {
		s.internal(w, r, err)
		return
	}
	s.audit(r, "ldap_account.create", "ldap_account", a.ID, a.Name, a.RegionID, map[string]any{"books": a.BookIDs})
	s.ok(w, r, "ldap_account_secret", &Page{Title: "LDAP 账号已创建", Nav: "ldap",
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
	s.redirect(w, r, "/ldap-accounts", "ok", "LDAP 账号「"+a.Name+"」已保存。")
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
	s.audit(r, "ldap_account.reset", "ldap_account", a.ID, a.Name, a.RegionID, map[string]any{"password": "已修改"})
	s.ok(w, r, "ldap_account_secret", &Page{Title: "LDAP 账号密码已重置", Nav: "ldap",
		Data: ldapAccountForm{Account: a, BindDN: s.bindDN(a.Name), Password: password}})
}

func (s *Server) ldapAccountDelete(w http.ResponseWriter, r *http.Request) {
	a := s.loadLDAPAccount(w, r)
	if a == nil {
		return
	}
	if err := s.St.DeleteLDAPAccount(r.Context(), a.ID); errors.Is(err, store.ErrInUse) {
		users, _ := s.St.LDAPAccountUsers(r.Context(), a.ID)
		s.redirect(w, r, "/ldap-accounts", "error", "LDAP 账号「"+a.Name+"」仍被配置模板「"+strings.Join(users, "、")+"」的托管通讯录使用，无法删除。")
		return
	} else if err != nil {
		s.internal(w, r, err)
		return
	}
	s.audit(r, "ldap_account.delete", "ldap_account", a.ID, a.Name, a.RegionID, nil)
	s.redirect(w, r, "/ldap-accounts", "ok", "LDAP 账号「"+a.Name+"」已删除。")
}

// bookMoveProblem says why a book cannot move to another region, or "": an LDAP
// account of another region that uses the book would break the ownership rule (§2.3).
func (s *Server) bookMoveProblem(r *http.Request, b *store.Book, to *int64) string {
	if to == nil {
		return "" // a global book can be used from every region
	}
	regions, err := s.St.AccountRegionsOfBook(r.Context(), b.ID)
	if err != nil {
		return "内部错误，请稍后重试。"
	}
	for _, reg := range regions {
		if reg == nil || *reg != *to {
			return "其他区域或全局的 LDAP 账号正在使用此通讯录，无法移至该区域。请先撤销相关授权。"
		}
	}
	return ""
}
