package web

import (
	"bytes"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"

	"siplink/internal/books"
	"siplink/internal/catalog"
	"siplink/internal/rules"
	"siplink/internal/secret"
	"siplink/internal/store"
)

// Client CSV (design §5.2, §5.5): 名称,区域,客户端模板[,备注], then per account profile of
// the template {profile}用户名, {profile}密码, {profile}认证用户, {profile}显示名, and per
// directory with per-client passwords {directory}LDAP密码. The English names are aliases
// (name, region, client_template, note, "{profile} username", …), their English part in
// any case. Extra columns are ignored.
const maxClientCSV = 4 << 20

// csvColumn is a column of the client CSV by its Chinese and its English name; the
// per-account and per-directory ones follow the name of the profile or directory.
type csvColumn struct{ zh, en string }

var (
	colName     = csvColumn{"名称", "name"}
	colRegion   = csvColumn{"区域", "region"}
	colTemplate = csvColumn{"客户端模板", "client_template"}
	colNote     = csvColumn{"备注", "note"}
	sufUser     = csvColumn{"用户名", " username"}
	sufPass     = csvColumn{"密码", " password"}
	sufAuth     = csvColumn{"认证用户", " auth user"}
	sufDisp     = csvColumn{"显示名", " display name"}
	sufDir      = csvColumn{"LDAP密码", " LDAP password"}
)

// name is the column's name after prefix, the Chinese or the English one.
func (c csvColumn) name(chinese bool, prefix string) string {
	if chinese {
		return prefix + c.zh
	}
	return prefix + c.en
}

// csvHeader finds the columns of a client CSV by either name.
type csvHeader struct {
	fields []string // trimmed
	found  map[string]int
}

func newCSVHeader(fields []string) *csvHeader {
	h := &csvHeader{found: map[string]int{}}
	for _, f := range fields {
		h.fields = append(h.fields, strings.TrimSpace(f))
	}
	return h
}

// index is the column of c after prefix, -1 when there is none; of two columns with
// the name, the last one counts.
func (h *csvHeader) index(prefix string, c csvColumn) int {
	key := prefix + "\x00" + c.zh
	if i, ok := h.found[key]; ok {
		return i
	}
	i := -1
	for j, f := range h.fields {
		if rest, ok := strings.CutPrefix(f, prefix); ok && (rest == c.zh || strings.EqualFold(rest, c.en)) {
			i = j
		}
	}
	h.found[key] = i
	return i
}

// clientCSVFormat is the format help of the import page: the columns by the names the
// header download writes in the page's language, on a Chinese page also the English ones.
func clientCSVFormat(r *http.Request) []string {
	t, chinese := tr(r), chineseColumns(r)
	account, directory := t.T("clients.import.account_word"), t.T("clients.import.directory_word")
	lines := []string{t.T("clients.import.format_columns", "name", colName.name(chinese, ""),
		"region", colRegion.name(chinese, ""), "client_template", colTemplate.name(chinese, ""), "note", colNote.name(chinese, ""),
		"username", sufUser.name(chinese, account), "password", sufPass.name(chinese, account),
		"auth_user", sufAuth.name(chinese, account), "display_name", sufDisp.name(chinese, account),
		"ldap_password", sufDir.name(chinese, directory))}
	if chinese {
		lines = append(lines, t.T("clients.import.format_english", "columns", []string{colName.en, colRegion.en, colTemplate.en,
			colNote.en, account + sufUser.en, account + sufPass.en, account + sufAuth.en, account + sufDisp.en, directory + sufDir.en}))
	}
	return append(lines, t.T("clients.import.format_size", "mb", maxClientCSV>>20))
}

func (s *Server) routesClientImport() {
	s.page("GET /clients/import", s.clientImportForm)
	s.page("POST /clients/import", s.clientImportDryRun)
	s.page("POST /clients/import/confirm", s.clientImportConfirm)
	s.page("GET /clients/import/template.csv", s.clientImportTemplate)
}

// importedClient is one checked line of a client CSV.
type importedClient struct {
	Line     int
	C        *store.Client
	Region   string
	Template string
	creds    []store.CredentialInput
	dirs     map[int64]string
	Problems []string
}

type clientImportData struct {
	Templates []*store.ClientTemplate
	Format    []string // the lines of the format help
	Rows      []*importedClient
	Error     string
	Key       string
	OK        int
}

func (s *Server) newClientImportData(r *http.Request) clientImportData {
	return clientImportData{Templates: s.usableClientTemplates(r, 0), Format: clientCSVFormat(r)}
}

func (s *Server) clientImportForm(w http.ResponseWriter, r *http.Request) {
	s.ok(w, r, "client_import", &Page{Title: tr(r).T("clients.import.title"), Nav: "clients", Data: s.newClientImportData(r)})
}

// clientImportTemplate returns an empty CSV with the columns of one client template.
func (s *Server) clientImportTemplate(w http.ResponseWriter, r *http.Request) {
	id, _ := parseInt64(r.URL.Query().Get("template"))
	ok := false
	for _, t := range s.usableClientTemplates(r, 0) {
		ok = ok || t.ID == id
	}
	if !ok {
		s.notFound(w, r)
		return
	}
	t, profiles, dirs, err := s.clientParts(r, id)
	if err != nil {
		s.internal(w, r, err)
		return
	}
	chinese := chineseColumns(r)
	head := []string{colName.name(chinese, ""), colRegion.name(chinese, ""), colTemplate.name(chinese, ""), colNote.name(chinese, "")}
	for _, p := range profiles {
		head = append(head, sufUser.name(chinese, p.Name), sufPass.name(chinese, p.Name), sufAuth.name(chinese, p.Name), sufDisp.name(chinese, p.Name))
	}
	for _, d := range dirs {
		head = append(head, sufDir.name(chinese, d.DisplayName()))
	}
	w.Header().Set("Content-Type", "text/csv; charset=utf-8")
	w.Header().Set("Content-Disposition", fmt.Sprintf(`attachment; filename="clients-%d.csv"`, t.ID))
	w.Write([]byte("\xef\xbb\xbf" + rules.CsvJoin(head) + "\r\n"))
}

func (s *Server) clientImportDryRun(w http.ResponseWriter, r *http.Request) {
	d := s.newClientImportData(r)
	fail := func(msg string) {
		d.Error = msg
		s.render(w, r, http.StatusBadRequest, "client_import", &Page{Title: tr(r).T("clients.import.title"), Nav: "clients", Data: d})
	}
	r.Body = http.MaxBytesReader(w, r.Body, maxClientCSV+64<<10)
	if err := r.ParseMultipartForm(1 << 20); err != nil && !errors.Is(err, http.ErrNotMultipart) {
		fail(tr(r).T("clients.import.unreadable", "mb", maxClientCSV>>20))
		return
	}
	var data []byte
	if f, _, err := r.FormFile("file"); err == nil {
		var buf bytes.Buffer
		io.Copy(&buf, io.LimitReader(f, maxClientCSV))
		f.Close()
		data = buf.Bytes()
	} else {
		data = []byte(r.FormValue("csv"))
	}
	text, _, err := books.Decode(data)
	if err != nil {
		fail(errText(r, err))
		return
	}
	rows := rules.CsvRows(text)
	if len(rows) < 2 || rows[0].Fields == nil {
		fail(tr(r).T("clients.import.need_header"))
		return
	}
	col := newCSVHeader(rows[0].Fields)
	for _, need := range []csvColumn{colName, colRegion, colTemplate} {
		if col.index("", need) < 0 {
			fail(tr(r).T("clients.import.missing_column", "column", need.name(chineseColumns(r), "")))
			return
		}
	}
	seen := map[string]bool{}
	for _, row := range rows[1:] {
		ic := s.importRow(r, row, col)
		if ic.C != nil && len(ic.Problems) == 0 {
			key := itoa(ic.C.RegionID) + "\x00" + strings.ToLower(ic.C.Name)
			if seen[key] {
				ic.Problems = append(ic.Problems, tr(r).T("clients.import.duplicate_in_file"))
			}
			seen[key] = true
		}
		if len(ic.Problems) == 0 {
			d.OK++
		}
		d.Rows = append(d.Rows, ic)
	}
	if d.OK == len(d.Rows) {
		d.Key = s.imports.put(s.Now(), &pendingImport{adminID: current(r).ID, clients: d.Rows})
	}
	s.ok(w, r, "client_import", &Page{Title: tr(r).T("clients.import.title_results"), Nav: "clients", Data: d})
}

// importRow reads and checks one line; nothing is written.
func (s *Server) importRow(r *http.Request, row rules.CsvRow, col *csvHeader) *importedClient {
	ic := &importedClient{Line: row.Line}
	if row.Fields == nil {
		ic.Problems = []string{tr(r).T("rules.csv.unclosed_quote")}
		return ic
	}
	get := func(prefix string, c csvColumn) string {
		if i := col.index(prefix, c); i >= 0 && i < len(row.Fields) {
			return strings.TrimSpace(row.Fields[i])
		}
		return ""
	}
	c := &store.Client{Name: get("", colName), Note: truncate(get("", colNote), 200)}
	ic.C, ic.Region, ic.Template = c, get("", colRegion), get("", colTemplate)
	if p := errText(r, catalog.ClientNameProblem(c.Name)); p != "" {
		ic.Problems = append(ic.Problems, p)
	}
	a := current(r)
	regions, _ := s.St.ListRegions(r.Context(), a.RegionIDs, a.Role == store.RoleSuper)
	for _, reg := range regions {
		if strings.EqualFold(reg.Name, ic.Region) {
			c.RegionID = reg.ID
		}
	}
	if c.RegionID == 0 {
		ic.Problems = append(ic.Problems, tr(r).T("clients.import.region_unknown", "name", ic.Region))
		return ic
	}
	// A template of the client's own region wins over a global one of the same name.
	for _, t := range s.usableClientTemplates(r, c.RegionID) {
		if strings.EqualFold(t.Name, ic.Template) && (c.ClientTemplateID == 0 || t.RegionID != nil) {
			c.ClientTemplateID = t.ID
		}
	}
	if c.ClientTemplateID == 0 {
		ic.Problems = append(ic.Problems, tr(r).T("clients.import.template_unknown", "name", ic.Template))
		return ic
	}
	_, profiles, dirs, err := s.clientParts(r, c.ClientTemplateID)
	if err != nil {
		ic.Problems = append(ic.Problems, tr(r).T("error.internal"))
		return ic
	}
	var taken []string
	for _, p := range profiles {
		in := store.CredentialInput{ProfileID: p.ID, Username: get(p.Name, sufUser), Password: get(p.Name, sufPass),
			AuthUser: get(p.Name, sufAuth), DisplayName: get(p.Name, sufDisp)}
		catalog.NormalizeCredential(p, &in)
		ic.creds = append(ic.creds, in)
		if in.Username != "" {
			ic.Problems = append(ic.Problems, tr(r).Msgs(catalog.CredentialProblems(p, in, taken))...)
			taken = append(taken, catalog.CredentialDraft(p, in).Name)
		}
	}
	ic.dirs = map[int64]string{}
	for _, d := range dirs {
		ic.dirs[d.ID] = get(d.DisplayName(), sufDir)
		if d.LDAPAccountID != nil && ic.dirs[d.ID] != "" {
			a, err := s.St.LDAPAccountByID(r.Context(), *d.LDAPAccountID)
			if p := s.accountPasswordProblem(r, a, ic.dirs[d.ID]); err == nil && p != "" {
				ic.Problems = append(ic.Problems, tr(r).T("provision.directory_problem", "name", d.DisplayName(), "problem", p))
			}
		}
	}
	if n, _ := s.clientNameTaken(r, c.RegionID, c.Name); n {
		ic.Problems = append(ic.Problems, tr(r).T("clients.import.name_taken"))
	}
	return ic
}

func (s *Server) clientNameTaken(r *http.Request, region int64, name string) (bool, error) {
	list, _, err := s.St.ListClients(r.Context(), store.ClientFilter{All: true, Region: region, Query: name})
	for _, c := range list {
		if strings.EqualFold(c.Name, name) {
			return true, err
		}
	}
	return false, err
}

func (s *Server) clientImportConfirm(w http.ResponseWriter, r *http.Request) {
	p := s.imports.take(s.Now(), r.FormValue("key"), current(r).ID, 0)
	if p == nil || len(p.clients) == 0 {
		s.redirect(w, r, "/clients/import", "error", tr(r).T("clients.import.expired"))
		return
	}
	expires := s.Now().Add(time.Duration(s.Cfg.EnrollDays) * 24 * time.Hour).UTC().Format(store.TimeFormat)
	var ids []string
	for _, ic := range p.clients {
		ic.C.EnrollExpiresAt = expires
		err := s.St.SaveClient(r.Context(), ic.C, ic.creds, ic.dirs, s.Ext.Box, secret.Token(32))
		if errors.Is(err, store.ErrConflict) {
			s.redirect(w, r, "/clients", "error", tr(r).T("clients.import.conflict", "line", ic.Line, "name", ic.C.Name, "n", len(ids)))
			return
		}
		if err != nil {
			s.internal(w, r, err)
			return
		}
		ids = append(ids, itoa(ic.C.ID))
		s.audit(r, "client.create", "client", ic.C.ID, ic.C.Name, &ic.C.RegionID, map[string]any{"import": true})
	}
	s.audit(r, "client.import", "client", 0, "", nil, map[string]any{"count": len(ids)})
	s.redirect(w, r, "/clients/print?ids="+strings.Join(ids, ","), "ok", tr(r).T("clients.import.done", "n", len(ids)))
}
