package web

import (
	"errors"
	"fmt"
	"io"
	"net/http"
	"strconv"
	"strings"

	"siplink/internal/auth"
	"siplink/internal/catalog"
	"siplink/internal/rules"
	"siplink/internal/store"
)

func (s *Server) routesKeyTemplates() {
	s.page("GET /key-templates", s.keyList)
	s.page("GET /key-templates/new", s.keyNew)
	s.page("POST /key-templates", s.keyCreate)
	s.page("GET /key-templates/{id}", s.keyShow)
	s.page("POST /key-templates/{id}/meta", s.keyMeta)
	s.page("POST /key-templates/{id}/delete", s.keyDelete)
	s.page("POST /key-templates/{id}/keys", s.keySave)
	s.page("POST /key-templates/{id}/keys/{kid}", s.keySave)
	s.page("POST /key-templates/{id}/keys/order", s.keyOrder)
	s.page("POST /key-templates/{id}/keys/{kid}/delete", s.keyRemove)
	s.page("POST /key-templates/{id}/import", s.keyImport)
	s.page("GET /key-templates/{id}/export.csv", s.keyExport)
}

func (s *Server) keyList(w http.ResponseWriter, r *http.Request) {
	list, err := s.St.ListKeyTemplates(r.Context(), s.templateScope(r))
	if err != nil {
		s.internal(w, r, err)
		return
	}
	names, _ := s.allRegionNames(r)
	var rows []templateRow
	for _, t := range list {
		rows = append(rows, templateRow{ID: t.ID, Name: t.Name, Note: t.Note, Region: regionLabel(names, t.RegionID),
			Global: t.RegionID == nil, Extra: strconv.Itoa(t.KeyCount) + " 个功能键"})
	}
	s.ok(w, r, "templates", &Page{Title: "功能键模板", Nav: "keys", Data: templatesData{Rows: rows, Base: "/key-templates", Kind: "功能键模板"}})
}

func (s *Server) keyNew(w http.ResponseWriter, r *http.Request) {
	s.metaFormPage(w, r, http.StatusOK, metaForm{Base: "/key-templates", Kind: "功能键模板", Region: s.defaultRegion(r)})
}

func (s *Server) keyCreate(w http.ResponseWriter, r *http.Request) {
	t := &store.KeyTemplate{}
	f := metaForm{Base: "/key-templates", Kind: "功能键模板"}
	if f.Error = s.readMeta(r, &t.Name, &t.Note, &t.RegionID, true); f.Error == "" {
		if err := s.St.CreateKeyTemplate(r.Context(), t); errors.Is(err, store.ErrConflict) {
			f.Error = "同一区域已有同名模板。"
		} else if err != nil {
			s.internal(w, r, err)
			return
		}
	}
	if f.Error != "" {
		f.Name, f.Note, f.Region = t.Name, t.Note, t.RegionID
		s.metaFormPage(w, r, http.StatusBadRequest, f)
		return
	}
	s.audit(r, "key_template.create", "key_template", t.ID, t.Name, t.RegionID, nil)
	s.redirect(w, r, "/key-templates/"+itoa(t.ID), "ok", "功能键模板已创建。")
}

func (s *Server) loadKeyTemplate(w http.ResponseWriter, r *http.Request, edit bool) *store.KeyTemplate {
	id, ok := pathID(r, "id")
	t, err := s.St.KeyTemplateByID(r.Context(), id)
	if !ok || errors.Is(err, store.ErrNotFound) {
		s.notFound(w, r)
		return nil
	}
	if err != nil {
		s.internal(w, r, err)
		return nil
	}
	if s.checkTemplate(w, r, t.RegionID, edit, &store.ConfigTemplate{}) == nil {
		return nil
	}
	return t
}

// lineNames are the profile names of the configuration templates used together with a
// key template (through client templates); they are what a key's line may name.
func (s *Server) lineNames(r *http.Request, keyTemplateID int64) []string {
	cts, _ := s.St.ClientTemplatesUsingKeys(r.Context(), keyTemplateID)
	seen := map[string]bool{}
	var out []string
	for _, ct := range cts {
		profiles, _ := s.St.Profiles(r.Context(), ct.ConfigTemplateID)
		for _, p := range profiles {
			if k := strings.ToLower(p.Name); !seen[k] {
				seen[k] = true
				out = append(out, p.Name)
			}
		}
	}
	return out
}

type keyData struct {
	T       *store.KeyTemplate
	Region  string
	CanEdit bool
	Keys    []*store.TemplateKey
	Edit    *store.TemplateKey
	Lines   []string
	Actions []actionOption
	Icons   []string
	Users   []string
	Error   string
	Errors  []rules.CsvLineError
	Hint    string
	Max     int
	Regions []store.Region
	IsSuper bool
}

type actionOption struct{ Word, Label string }

func (s *Server) renderKeys(w http.ResponseWriter, r *http.Request, status int, t *store.KeyTemplate, d keyData) {
	d.T = t
	d.CanEdit = actor(r).Can(auth.TemplateEdit, t.RegionID)
	names, _ := s.allRegionNames(r)
	d.Region = regionLabel(names, t.RegionID)
	d.Keys, _ = s.St.Keys(r.Context(), t.ID)
	d.Lines = s.lineNames(r, t.ID)
	for _, a := range rules.ShortcutActions {
		d.Actions = append(d.Actions, actionOption{rules.TypeWord(a), a.Label()})
	}
	d.Icons = rules.IconWords()
	d.Users, _ = s.St.KeyTemplateUsers(r.Context(), t.ID)
	d.Hint, d.Max = rules.FormatHint, rules.MaxShortcuts
	a := current(r)
	d.IsSuper = a.Role == store.RoleSuper
	d.Regions, _ = s.St.ListRegions(r.Context(), a.RegionIDs, d.IsSuper)
	if d.Edit == nil {
		if kid, ok := parseInt64(r.URL.Query().Get("edit")); ok {
			for _, k := range d.Keys {
				if k.ID == kid {
					d.Edit = k
				}
			}
		}
	}
	s.render(w, r, status, "key_template", &Page{Title: t.Name, Nav: "keys", Data: d})
}

func (s *Server) keyShow(w http.ResponseWriter, r *http.Request) {
	if t := s.loadKeyTemplate(w, r, false); t != nil {
		s.renderKeys(w, r, http.StatusOK, t, keyData{})
	}
}

func (s *Server) keyMeta(w http.ResponseWriter, r *http.Request) {
	t := s.loadKeyTemplate(w, r, true)
	if t == nil {
		return
	}
	old := *t
	msg := s.readMeta(r, &t.Name, &t.Note, &t.RegionID, current(r).Role == store.RoleSuper)
	if msg == "" && !sameRegion(old.RegionID, t.RegionID) && t.RegionID != nil {
		cts, _ := s.St.ClientTemplatesUsingKeys(r.Context(), t.ID)
		for _, ct := range cts {
			if ct.RegionID == nil || *ct.RegionID != *t.RegionID {
				msg = "客户端模板「" + ct.Name + "」使用此模板，移动后将违反区域规则。"
			}
		}
	}
	if msg == "" {
		if err := s.St.UpdateKeyTemplate(r.Context(), t); errors.Is(err, store.ErrConflict) {
			msg = "同一区域已有同名模板。"
		} else if err != nil {
			s.internal(w, r, err)
			return
		}
	}
	if msg != "" {
		s.renderKeys(w, r, http.StatusBadRequest, &old, keyData{Error: msg})
		return
	}
	s.audit(r, "key_template.update", "key_template", t.ID, t.Name, t.RegionID, changes(
		field{"name", old.Name, t.Name}, field{"note", old.Note, t.Note}, field{"region_id", derefOr(old.RegionID), derefOr(t.RegionID)}))
	s.redirect(w, r, "/key-templates/"+itoa(t.ID), "ok", "已保存。")
}

func (s *Server) keyDelete(w http.ResponseWriter, r *http.Request) {
	t := s.loadKeyTemplate(w, r, true)
	if t == nil {
		return
	}
	if err := s.St.DeleteKeyTemplate(r.Context(), t.ID); errors.Is(err, store.ErrInUse) {
		s.redirect(w, r, "/key-templates/"+itoa(t.ID), "error", "模板仍被客户端模板使用，无法删除。")
		return
	} else if err != nil {
		s.internal(w, r, err)
		return
	}
	s.audit(r, "key_template.delete", "key_template", t.ID, t.Name, t.RegionID, nil)
	s.redirect(w, r, "/key-templates", "ok", "功能键模板「"+t.Name+"」已删除。")
}

func (s *Server) keySave(w http.ResponseWriter, r *http.Request) {
	t := s.loadKeyTemplate(w, r, true)
	if t == nil {
		return
	}
	k := &store.TemplateKey{Label: r.FormValue("label"), Number: r.FormValue("number"), Action: r.FormValue("action"),
		Category: r.FormValue("category"), Line: r.FormValue("line"), Icon: r.FormValue("icon")}
	if kid, ok := pathID(r, "kid"); ok {
		k.ID = kid
	}
	nk, problems := catalog.KeyProblems(k, s.lineNames(r, t.ID))
	if len(problems) == 0 {
		if err := s.St.SaveKey(r.Context(), t.ID, nk, rules.MaxShortcuts); errors.Is(err, store.ErrLimit) {
			problems = append(problems, rules.ShortcutLimitReached.Full())
		} else if errors.Is(err, store.ErrNotFound) {
			s.notFound(w, r)
			return
		} else if err != nil {
			s.internal(w, r, err)
			return
		}
	}
	if len(problems) > 0 {
		s.renderKeys(w, r, http.StatusBadRequest, t, keyData{Edit: k, Error: strings.Join(problems, "；")})
		return
	}
	s.audit(r, "key_template.update", "key_template", t.ID, t.Name, t.RegionID, map[string]any{"key": nk.Label})
	s.redirect(w, r, "/key-templates/"+itoa(t.ID), "ok", "功能键「"+nk.Label+"」已保存。")
}

func (s *Server) keyRemove(w http.ResponseWriter, r *http.Request) {
	t := s.loadKeyTemplate(w, r, true)
	if t == nil {
		return
	}
	kid, _ := pathID(r, "kid")
	if err := s.St.DeleteKey(r.Context(), t.ID, kid); errors.Is(err, store.ErrNotFound) {
		s.notFound(w, r)
		return
	} else if err != nil {
		s.internal(w, r, err)
		return
	}
	s.audit(r, "key_template.update", "key_template", t.ID, t.Name, t.RegionID, map[string]any{"op": "delete"})
	s.redirect(w, r, "/key-templates/"+itoa(t.ID), "ok", "已删除。")
}

// keyOrder saves the order of the keys set by dragging (sortable.js).
func (s *Server) keyOrder(w http.ResponseWriter, r *http.Request) {
	t := s.loadKeyTemplate(w, r, true)
	if t != nil && s.reorder(w, r, "template_keys", t.ID) {
		s.audit(r, "key_template.update", "key_template", t.ID, t.Name, t.RegionID, map[string]any{"op": "order"})
		w.WriteHeader(http.StatusNoContent)
	}
}

// keyImport reads keys in the app's CSV format (ShortcutRules.decode). Nothing is
// written when a line has an error.
func (s *Server) keyImport(w http.ResponseWriter, r *http.Request) {
	t := s.loadKeyTemplate(w, r, true)
	if t == nil {
		return
	}
	r.Body = http.MaxBytesReader(w, r.Body, 2<<20)
	if err := r.ParseMultipartForm(2 << 20); err != nil && !errors.Is(err, http.ErrNotMultipart) {
		s.renderKeys(w, r, http.StatusBadRequest, t, keyData{Error: "文件过大（最大 2 MB）"})
		return
	}
	text := r.FormValue("csv")
	if f, _, err := r.FormFile("file"); err == nil {
		b, _ := io.ReadAll(io.LimitReader(f, 2<<20))
		f.Close()
		text = string(b)
	}
	replace := r.FormValue("mode") == "replace"
	room := rules.MaxShortcuts
	if !replace {
		room -= t.KeyCount
	}
	parsed := rules.DecodeShortcuts(text, room, 1)
	lines := s.lineNames(r, t.ID)
	rowOf := acceptedRows(text, parsed.Errors)
	var keys []*store.TemplateKey
	for i, sc := range parsed.Shortcuts {
		if p := catalog.LineProblem(sc.Line, lines); p != "" {
			parsed.Errors = append(parsed.Errors, rules.CsvLineError{Line: rowOf[i], Reason: p})
			continue
		}
		keys = append(keys, catalog.StoredKey(sc))
	}
	if len(parsed.Errors) > 0 || parsed.SkippedForLimit > 0 || len(keys) == 0 {
		msg := "未导入任何功能键。"
		if parsed.SkippedForLimit > 0 {
			msg += fmt.Sprintf("超出 %d 个上限：%d 行。", rules.MaxShortcuts, parsed.SkippedForLimit)
		}
		s.renderKeys(w, r, http.StatusBadRequest, t, keyData{Error: msg, Errors: parsed.Errors})
		return
	}
	if err := s.St.AddKeys(r.Context(), t.ID, keys, replace, rules.MaxShortcuts); errors.Is(err, store.ErrLimit) {
		s.renderKeys(w, r, http.StatusBadRequest, t, keyData{Error: rules.ShortcutLimitReached.Full()})
		return
	} else if err != nil {
		s.internal(w, r, err)
		return
	}
	s.audit(r, "key_template.import", "key_template", t.ID, t.Name, t.RegionID, map[string]any{"count": len(keys), "replace": replace})
	s.redirect(w, r, "/key-templates/"+itoa(t.ID), "ok", fmt.Sprintf("已导入 %d 个功能键。", len(keys)))
}

// acceptedRows are the CSV line numbers of the keys DecodeShortcuts returned, in order:
// the rows that did not end up in errs.
func acceptedRows(text string, errs []rules.CsvLineError) []int {
	bad := map[int]bool{}
	for _, e := range errs {
		bad[e.Line] = true
	}
	var out []int
	for _, row := range rules.CsvRows(text) {
		if !bad[row.Line] {
			out = append(out, row.Line)
		}
	}
	return out
}

func (s *Server) keyExport(w http.ResponseWriter, r *http.Request) {
	t := s.loadKeyTemplate(w, r, false)
	if t == nil {
		return
	}
	keys, err := s.St.Keys(r.Context(), t.ID)
	if err != nil {
		s.internal(w, r, err)
		return
	}
	list := make([]rules.Shortcut, len(keys))
	for i, k := range keys {
		list[i] = catalog.KeyShortcut(k)
	}
	w.Header().Set("Content-Type", "text/csv; charset=utf-8")
	w.Header().Set("Content-Disposition", fmt.Sprintf(`attachment; filename="keys-%d.csv"`, t.ID))
	w.Write([]byte(rules.EncodeShortcuts(list)))
}
