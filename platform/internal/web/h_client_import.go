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
	"siplink/internal/i18n"
	"siplink/internal/rules"
	"siplink/internal/secret"
	"siplink/internal/store"
)

// Client CSV (design §5.2, §5.5, §2.6): 名称,区域,客户端模板[,备注][,分组][,标签], then per
// account profile of the template {profile}用户名, {profile}密码, {profile}认证用户,
// {profile}显示名, and per directory with per-client passwords {directory}LDAP密码. The
// English names are an alternative (name, region, client_template, note, group, tags,
// "{profile} username", …, in any case); one file uses one language. Extra columns are
// ignored. Several tags in one cell are separated by ";" or "；". maxClientUpload is the
// body limit of the dry run: the file, or the pasted text, and the form around it.
const (
	maxClientCSV    = 4 << 20
	maxClientUpload = maxClientCSV + 64<<10
)

// csvColumn is a column of the client CSV by its Chinese and its English name; the
// per-account and per-directory ones follow the name of the profile or directory.
type csvColumn struct{ zh, en string }

var (
	colName     = csvColumn{"名称", "name"}
	colRegion   = csvColumn{"区域", "region"}
	colTemplate = csvColumn{"客户端模板", "client_template"}
	colNote     = csvColumn{"备注", "note"}
	colGroup    = csvColumn{"分组", "group"}
	colTags     = csvColumn{"标签", "tags"}
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

// sameColumn tells whether a header cell is the column name: the Chinese names exactly,
// the English ones in any case.
func sameColumn(chinese bool, cell, name string) bool {
	if chinese {
		return cell == name
	}
	return strings.EqualFold(cell, name)
}

// csvField is a field a client CSV can fill: a column after the name of a profile or
// directory, or after nothing (name, region, client template, note).
type csvField struct {
	prefix string
	col    csvColumn
}

var fixedFields = []csvField{{"", colName}, {"", colRegion}, {"", colTemplate}, {"", colNote}, {"", colGroup}, {"", colTags}}

// templateFields are the fields of a client template's profiles and directories.
func templateFields(profiles []*store.Profile, dirs []*store.Directory) []csvField {
	var out []csvField
	for _, p := range profiles {
		out = append(out, csvField{p.Name, sufUser}, csvField{p.Name, sufPass}, csvField{p.Name, sufAuth}, csvField{p.Name, sufDisp})
	}
	for _, d := range dirs {
		out = append(out, csvField{d.DisplayName(), sufDir})
	}
	return out
}

// csvHeader is the header of a client CSV in its one language: a file with a 名称 column
// is a Chinese one, where only the Chinese names count and English names are extra
// columns (as before the console had other languages); a file with a name column but no
// 名称 counts only the English names; with neither, the page's language names what is
// missing.
type csvHeader struct {
	cells   []string // trimmed
	chinese bool
	fixed   map[csvField]int
	bound   map[int64]*csvBinding // by client template
}

type csvBinding struct {
	at  map[csvField]int
	err error
}

func newCSVHeader(cells []string, pageChinese bool) (*csvHeader, error) {
	h := &csvHeader{bound: map[int64]*csvBinding{}}
	hasName := false
	for _, c := range cells {
		c = strings.TrimSpace(c)
		h.cells = append(h.cells, c)
		h.chinese = h.chinese || c == colName.zh
		hasName = hasName || strings.EqualFold(c, colName.en)
	}
	h.chinese = h.chinese || !hasName && pageChinese
	var err error
	h.fixed, err = h.bind(fixedFields)
	return h, err
}

// bind finds the column of each field (-1 when there is none). A field with two columns
// or a column that fits two fields (a profile "X LDAP" and a directory "X" both give "X
// LDAP password") is refused: the value would go to the wrong place without a word.
func (h *csvHeader) bind(fields []csvField) (map[csvField]int, error) {
	at := make(map[csvField]int, len(fields))
	taken := make([]bool, len(h.cells))
	for _, f := range fields {
		name := f.col.name(h.chinese, f.prefix)
		at[f] = -1
		for i, cell := range h.cells {
			switch {
			case !sameColumn(h.chinese, cell, name):
			case at[f] >= 0:
				return nil, i18n.E("clients.import.duplicate_column", "column", cell)
			case taken[i]:
				return nil, i18n.E("clients.import.ambiguous_column", "column", cell)
			default:
				at[f], taken[i] = i, true
			}
		}
	}
	return at, nil
}

// forTemplate binds the fields of one client template, once per template.
func (h *csvHeader) forTemplate(id int64, profiles []*store.Profile, dirs []*store.Directory) (map[csvField]int, error) {
	if b, ok := h.bound[id]; ok {
		return b.at, b.err
	}
	at, err := h.bind(append(append([]csvField(nil), fixedFields...), templateFields(profiles, dirs)...))
	h.bound[id] = &csvBinding{at, err}
	return at, err
}

// columnClash is a column name that the header of a client template would hold twice,
// "" when there is none.
func columnClash(head []string, chinese bool) string {
	for i, a := range head {
		for _, b := range head[i+1:] {
			if sameColumn(chinese, a, b) {
				return b
			}
		}
	}
	return ""
}

// clientCSVFormat is the format help of the import page: the columns by the names the
// header download writes in the page's language, on a Chinese page also the English ones.
func clientCSVFormat(r *http.Request) []string {
	t, chinese := tr(r), chineseColumns(r)
	account, directory := t.T("clients.import.account_word"), t.T("clients.import.directory_word")
	lines := []string{t.T("clients.import.format_columns", "name", colName.name(chinese, ""),
		"region", colRegion.name(chinese, ""), "client_template", colTemplate.name(chinese, ""), "note", colNote.name(chinese, ""),
		"group", colGroup.name(chinese, ""), "tags", colTags.name(chinese, ""),
		"username", sufUser.name(chinese, account), "password", sufPass.name(chinese, account),
		"auth_user", sufAuth.name(chinese, account), "display_name", sufDisp.name(chinese, account),
		"ldap_password", sufDir.name(chinese, directory))}
	if chinese {
		lines = append(lines, t.T("clients.import.format_english", "columns", []string{colName.en, colRegion.en, colTemplate.en,
			colNote.en, colGroup.en, colTags.en, account + sufUser.en, account + sufPass.en, account + sufAuth.en, account + sufDisp.en, directory + sufDir.en}))
	}
	return append(lines, t.T("clients.import.format_size", "mb", maxClientCSV>>20))
}

func (s *Server) routesClientImport() {
	s.page("GET /clients/import", s.clientImportForm)
	s.upload("POST /clients/import", maxClientUpload, s.clientImportDryRun)
	s.page("POST /clients/import/confirm", s.clientImportConfirm)
	s.page("GET /clients/import/template.csv", s.clientImportTemplate)
}

// importedClient is one checked line of a client CSV.
type importedClient struct {
	Line     int
	C        *store.Client
	Region   string
	Template string
	Group    string
	Tags     []string // as the file names them; made at the confirmation when new
	creds    []store.CredentialInput
	dirs     map[int64]string
	Problems []string
}

// importGroups looks up the groups of a region by folded name, once per region and file.
type importGroups map[int64]map[string]*store.Group

func (m importGroups) find(r *http.Request, s *Server, region int64, name string) *store.Group {
	if m[region] == nil {
		m[region] = map[string]*store.Group{}
		groups, _ := s.St.ListGroups(r.Context(), store.BookScope{All: true, Region: region})
		for _, g := range groups {
			m[region][store.NameKey(g.Name)] = g
		}
	}
	return m[region][store.NameKey(name)]
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
	var head []string
	for _, f := range append(append([]csvField(nil), fixedFields...), templateFields(profiles, dirs)...) {
		head = append(head, f.col.name(chinese, f.prefix))
	}
	if c := columnClash(head, chinese); c != "" {
		s.redirect(w, r, "/clients/import", "error", tr(r).T("clients.import.header_clash", "column", c))
		return
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
	if err := r.ParseMultipartForm(maxClientUpload); err != nil && !errors.Is(err, http.ErrNotMultipart) {
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
	col, err := newCSVHeader(rows[0].Fields, chineseColumns(r))
	if err != nil {
		fail(errText(r, err))
		return
	}
	for _, need := range fixedFields[:3] {
		if col.fixed[need] < 0 {
			fail(tr(r).T("clients.import.missing_column", "column", need.col.name(col.chinese, "")))
			return
		}
	}
	seen, groups, newTags := map[string]bool{}, importGroups{}, map[string]bool{}
	known, err := s.allTags(r.Context())
	if err != nil {
		s.internal(w, r, err)
		return
	}
	knownKeys := map[string]bool{}
	for _, t := range known {
		knownKeys[store.NameKey(t.Name)] = true
	}
	for _, row := range rows[1:] {
		ic, err := s.importRow(r, row, col, groups)
		if err != nil {
			fail(errText(r, err))
			return
		}
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
		for _, name := range ic.Tags {
			if key := store.NameKey(name); !knownKeys[key] {
				newTags[key] = true
			}
		}
		d.Rows = append(d.Rows, ic)
	}
	if len(known)+len(newTags) > catalog.MaxTags {
		fail(tr(r).T("tags.limit", "max", catalog.MaxTags))
		return
	}
	if d.OK == len(d.Rows) {
		d.Key = s.imports.put(s.Now(), &pendingImport{adminID: current(r).ID, clients: d.Rows})
	}
	s.ok(w, r, "client_import", &Page{Title: tr(r).T("clients.import.title_results"), Nav: "clients", Data: d})
}

// importRow reads and checks one line; nothing is written. The error is a header that
// does not fit the line's client template, which refuses the whole file.
func (s *Server) importRow(r *http.Request, row rules.CsvRow, col *csvHeader, groups importGroups) (*importedClient, error) {
	ic := &importedClient{Line: row.Line}
	if row.Fields == nil {
		ic.Problems = []string{tr(r).T("rules.csv.unclosed_quote")}
		return ic, nil
	}
	at := col.fixed
	get := func(prefix string, c csvColumn) string {
		if i, ok := at[csvField{prefix, c}]; ok && i >= 0 && i < len(row.Fields) {
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
		return ic, nil
	}
	ic.Group, ic.Tags = get("", colGroup), catalog.SplitTags(get("", colTags))
	if ic.Group != "" {
		if g := groups.find(r, s, c.RegionID, ic.Group); g != nil {
			c.GroupID, ic.Group = &g.ID, g.Name
		} else {
			ic.Problems = append(ic.Problems, tr(r).T("clients.import.group_unknown", "name", ic.Group))
		}
	}
	for _, name := range ic.Tags {
		if p := errText(r, catalog.TagNameProblem(name)); p != "" {
			ic.Problems = append(ic.Problems, tr(r).T("clients.import.tag_invalid", "name", name, "problem", p))
		}
	}
	if p := tooManyTags(tr(r), len(ic.Tags)); p != "" {
		ic.Problems = append(ic.Problems, p)
	}
	// A template of the client's own region wins over a global one of the same name.
	for _, t := range s.usableClientTemplates(r, c.RegionID) {
		if strings.EqualFold(t.Name, ic.Template) && (c.ClientTemplateID == 0 || t.RegionID != nil) {
			c.ClientTemplateID = t.ID
		}
	}
	if c.ClientTemplateID == 0 {
		ic.Problems = append(ic.Problems, tr(r).T("clients.import.template_unknown", "name", ic.Template))
		return ic, nil
	}
	_, profiles, dirs, err := s.clientParts(r, c.ClientTemplateID)
	if err != nil {
		ic.Problems = append(ic.Problems, tr(r).T("error.internal"))
		return ic, nil
	}
	if at, err = col.forTemplate(c.ClientTemplateID, profiles, dirs); err != nil {
		return nil, err
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
	if taken, _ := s.St.ClientNameTaken(r.Context(), c.RegionID, c.Name); taken {
		ic.Problems = append(ic.Problems, tr(r).T("clients.import.name_taken"))
	}
	return ic, nil
}

func (s *Server) clientImportConfirm(w http.ResponseWriter, r *http.Request) {
	p := s.imports.take(s.Now(), r.FormValue("key"), current(r).ID, 0)
	if p == nil || len(p.clients) == 0 {
		s.redirect(w, r, "/clients/import", "error", tr(r).T("clients.import.expired"))
		return
	}
	expires := s.Now().Add(time.Duration(s.Cfg.EnrollDays) * 24 * time.Hour).UTC().Format(store.TimeFormat)
	var names []string
	for _, ic := range p.clients {
		names = append(names, ic.Tags...)
		// A group deleted since the dry run voids it, before anything is written.
		if ic.C.GroupID != nil {
			if g, err := s.St.GroupByID(r.Context(), *ic.C.GroupID); err != nil || g.RegionID != ic.C.RegionID {
				s.redirect(w, r, "/clients/import", "error", tr(r).T("clients.import.expired"))
				return
			}
		}
	}
	tags, err := s.St.EnsureTags(r.Context(), names, catalog.TagColors[0], catalog.MaxTags)
	if errors.Is(err, store.ErrLimit) {
		s.redirect(w, r, "/clients/import", "error", tr(r).T("tags.limit", "max", catalog.MaxTags))
		return
	}
	if err != nil {
		s.internal(w, r, err)
		return
	}
	var ids []string
	for _, ic := range p.clients {
		ic.C.EnrollExpiresAt = expires
		tagIDs, named := []int64{}, []string{}
		for _, name := range ic.Tags {
			t := tags[store.NameKey(name)]
			tagIDs, named = append(tagIDs, t.ID), append(named, t.Name)
		}
		err := s.St.SaveClient(r.Context(), ic.C, ic.creds, ic.dirs, tagIDs, s.Ext.Box, secret.Token(32))
		if errors.Is(err, store.ErrConflict) {
			s.redirect(w, r, "/clients", "error", tr(r).T("clients.import.conflict", "line", ic.Line, "name", ic.C.Name, "n", len(ids)))
			return
		}
		if err != nil {
			s.internal(w, r, err)
			return
		}
		ids = append(ids, itoa(ic.C.ID))
		detail := map[string]any{"import": true}
		if ic.Group != "" {
			detail["group"] = ic.Group
		}
		if len(named) > 0 {
			detail["tags"] = named
		}
		s.audit(r, "client.create", "client", ic.C.ID, ic.C.Name, &ic.C.RegionID, detail)
	}
	s.audit(r, "client.import", "client", 0, "", nil, map[string]any{"count": len(ids)})
	to := "/clients/print?ids=" + strings.Join(ids, ",")
	if len(ids) > maxPrint { // more than one print page takes: the list instead
		to = "/clients"
	}
	s.redirect(w, r, to, "ok", tr(r).T("clients.import.done", "n", len(ids)))
}
