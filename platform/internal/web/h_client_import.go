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

// Client CSV (design §5.2): 名称,区域,客户端模板[,备注], then per account profile of the
// template {profile}用户名, {profile}密码, {profile}认证用户, {profile}显示名, and per
// directory with per-client passwords {directory}LDAP密码. Extra columns are ignored.
const (
	colName, colRegion, colTemplate, colNote   = "名称", "区域", "客户端模板", "备注"
	sufUser, sufPass, sufAuth, sufDisp, sufDir = "用户名", "密码", "认证用户", "显示名", "LDAP密码"
	maxClientCSV                               = 4 << 20
)

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
	Rows      []*importedClient
	Error     string
	Key       string
	OK        int
}

func (s *Server) clientImportForm(w http.ResponseWriter, r *http.Request) {
	d := clientImportData{Templates: s.usableClientTemplates(r, 0)}
	s.ok(w, r, "client_import", &Page{Title: "批量导入客户端", Nav: "clients", Data: d})
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
	head := []string{colName, colRegion, colTemplate, colNote}
	for _, p := range profiles {
		head = append(head, p.Name+sufUser, p.Name+sufPass, p.Name+sufAuth, p.Name+sufDisp)
	}
	for _, d := range dirs {
		head = append(head, d.Name+sufDir)
	}
	w.Header().Set("Content-Type", "text/csv; charset=utf-8")
	w.Header().Set("Content-Disposition", fmt.Sprintf(`attachment; filename="clients-%d.csv"`, t.ID))
	w.Write([]byte("\xef\xbb\xbf" + rules.CsvJoin(head) + "\r\n"))
}

func (s *Server) clientImportDryRun(w http.ResponseWriter, r *http.Request) {
	d := clientImportData{Templates: s.usableClientTemplates(r, 0)}
	fail := func(msg string) {
		d.Error = msg
		s.render(w, r, http.StatusBadRequest, "client_import", &Page{Title: "批量导入客户端", Nav: "clients", Data: d})
	}
	r.Body = http.MaxBytesReader(w, r.Body, maxClientCSV+64<<10)
	if err := r.ParseMultipartForm(1 << 20); err != nil && !errors.Is(err, http.ErrNotMultipart) {
		fail("文件无法读取（最大 4 MB）。")
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
		fail(err.Error())
		return
	}
	rows := rules.CsvRows(text)
	if len(rows) < 2 || rows[0].Fields == nil {
		fail("首行须为表头，且至少包含一行客户端数据。")
		return
	}
	col := map[string]int{}
	for i, h := range rows[0].Fields {
		col[strings.TrimSpace(h)] = i
	}
	for _, need := range []string{colName, colRegion, colTemplate} {
		if _, ok := col[need]; !ok {
			fail("表头缺少「" + need + "」列。")
			return
		}
	}
	seen := map[string]bool{}
	for _, row := range rows[1:] {
		ic := s.importRow(r, row, col)
		if ic.C != nil && len(ic.Problems) == 0 {
			key := itoa(ic.C.RegionID) + "\x00" + strings.ToLower(ic.C.Name)
			if seen[key] {
				ic.Problems = append(ic.Problems, "文件中有同名客户端")
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
	s.ok(w, r, "client_import", &Page{Title: "批量导入客户端：校验结果", Nav: "clients", Data: d})
}

// importRow reads and checks one line; nothing is written.
func (s *Server) importRow(r *http.Request, row rules.CsvRow, col map[string]int) *importedClient {
	ic := &importedClient{Line: row.Line}
	if row.Fields == nil {
		ic.Problems = []string{"引号未闭合"}
		return ic
	}
	get := func(name string) string {
		if i, ok := col[name]; ok && i < len(row.Fields) {
			return strings.TrimSpace(row.Fields[i])
		}
		return ""
	}
	c := &store.Client{Name: get(colName), Note: truncate(get(colNote), 200)}
	ic.C, ic.Region, ic.Template = c, get(colRegion), get(colTemplate)
	if p := catalog.ClientNameProblem(c.Name); p != "" {
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
		ic.Problems = append(ic.Problems, "区域「"+ic.Region+"」不存在或不可管理")
		return ic
	}
	// A template of the client's own region wins over a global one of the same name.
	for _, t := range s.usableClientTemplates(r, c.RegionID) {
		if strings.EqualFold(t.Name, ic.Template) && (c.ClientTemplateID == 0 || t.RegionID != nil) {
			c.ClientTemplateID = t.ID
		}
	}
	if c.ClientTemplateID == 0 {
		ic.Problems = append(ic.Problems, "客户端模板「"+ic.Template+"」不存在或不适用于该区域")
		return ic
	}
	_, profiles, dirs, err := s.clientParts(r, c.ClientTemplateID)
	if err != nil {
		ic.Problems = append(ic.Problems, "内部错误")
		return ic
	}
	var taken []string
	for _, p := range profiles {
		in := store.CredentialInput{ProfileID: p.ID, Username: get(p.Name + sufUser), Password: get(p.Name + sufPass),
			AuthUser: get(p.Name + sufAuth), DisplayName: get(p.Name + sufDisp)}
		catalog.NormalizeCredential(p, &in)
		ic.creds = append(ic.creds, in)
		if in.Username != "" {
			ic.Problems = append(ic.Problems, catalog.CredentialProblems(p, in, taken)...)
			taken = append(taken, catalog.CredentialDraft(p, in).Name)
		}
	}
	ic.dirs = map[int64]string{}
	for _, d := range dirs {
		ic.dirs[d.ID] = get(d.Name + sufDir)
		if d.LDAPAccountID != nil && ic.dirs[d.ID] != "" {
			a, err := s.St.LDAPAccountByID(r.Context(), *d.LDAPAccountID)
			if p := s.accountPasswordProblem(a, ic.dirs[d.ID]); err == nil && p != "" {
				ic.Problems = append(ic.Problems, "通讯录「"+d.Name+"」："+p)
			}
		}
	}
	if n, _ := s.clientNameTaken(r, c.RegionID, c.Name); n {
		ic.Problems = append(ic.Problems, "该区域已有同名客户端")
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
		s.redirect(w, r, "/clients/import", "error", "校验结果已失效，请重新上传文件。")
		return
	}
	expires := s.Now().Add(time.Duration(s.Cfg.EnrollDays) * 24 * time.Hour).UTC().Format(store.TimeFormat)
	var ids []string
	for _, ic := range p.clients {
		ic.C.EnrollExpiresAt = expires
		err := s.St.SaveClient(r.Context(), ic.C, ic.creds, ic.dirs, s.Ext.Box, secret.Token(32))
		if errors.Is(err, store.ErrConflict) {
			s.redirect(w, r, "/clients", "error", fmt.Sprintf("第 %d 行「%s」与现有客户端重名，此前的 %d 行已导入。", ic.Line, ic.C.Name, len(ids)))
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
	s.redirect(w, r, "/clients/print?ids="+strings.Join(ids, ","), "ok", fmt.Sprintf("已导入 %d 个客户端。", len(ids)))
}
