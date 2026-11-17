package web

import (
	"errors"
	"net/http"
	"strconv"

	"siplink/internal/auth"
	"siplink/internal/catalog"
	"siplink/internal/store"
)

func (s *Server) routesClientTemplates() {
	s.page("GET /client-templates", s.clientTplList)
	s.page("GET /client-templates/new", s.clientTplForm)
	s.page("POST /client-templates", s.clientTplSave)
	s.page("GET /client-templates/{id}", s.clientTplForm)
	s.page("POST /client-templates/{id}", s.clientTplSave)
	s.page("POST /client-templates/{id}/delete", s.clientTplDelete)
}

func (s *Server) clientTplList(w http.ResponseWriter, r *http.Request) {
	list, err := s.St.ListClientTemplates(r.Context(), s.templateScope(r))
	if err != nil {
		s.internal(w, r, err)
		return
	}
	names, _ := s.allRegionNames(r)
	var rows []templateRow
	for _, t := range list {
		n, _ := s.St.ClientCountOfTemplate(r.Context(), t.ID)
		extra := ""
		if c, err := s.St.ConfigTemplateByID(r.Context(), t.ConfigTemplateID); err == nil {
			extra = "配置：" + c.Name
		}
		if t.KeyTemplateID != nil {
			if k, err := s.St.KeyTemplateByID(r.Context(), *t.KeyTemplateID); err == nil {
				extra += "，功能键：" + k.Name
			}
		}
		rows = append(rows, templateRow{ID: t.ID, Name: t.Name, Note: t.Note, Region: regionLabel(names, t.RegionID),
			Global: t.RegionID == nil, Clients: n, Extra: extra})
	}
	s.ok(w, r, "templates", &Page{Title: "客户端模板", Nav: "clienttpl", Data: templatesData{Rows: rows, Base: "/client-templates", Kind: "客户端模板"}})
}

type clientTplForm struct {
	T       *store.ClientTemplate
	New     bool
	CanEdit bool
	Configs []*store.ConfigTemplate
	Keys    []*store.KeyTemplate
	Regions []store.Region
	Global  bool
	Clients int
	Error   string
}

func (s *Server) clientTplFormPage(w http.ResponseWriter, r *http.Request, status int, f clientTplForm) {
	ctx := r.Context()
	a := current(r)
	act := actor(r)
	f.Regions, _ = s.St.ListRegions(ctx, a.RegionIDs, a.Role == store.RoleSuper)
	f.Global = a.Role == store.RoleSuper
	all := store.BookScope{All: true}
	configs, _ := s.St.ListConfigTemplates(ctx, all)
	for _, c := range configs {
		if act.Can(auth.TemplateUse, c.RegionID) {
			f.Configs = append(f.Configs, c)
		}
	}
	keys, _ := s.St.ListKeyTemplates(ctx, all)
	for _, k := range keys {
		if act.Can(auth.TemplateUse, k.RegionID) {
			f.Keys = append(f.Keys, k)
		}
	}
	if !f.New {
		f.Clients, _ = s.St.ClientCountOfTemplate(ctx, f.T.ID)
	}
	title := "客户端模板：" + f.T.Name
	if f.New {
		title = "新建客户端模板"
	}
	s.render(w, r, status, "client_template", &Page{Title: title, Nav: "clienttpl", Data: f})
}

func (s *Server) loadClientTpl(w http.ResponseWriter, r *http.Request, edit bool) *store.ClientTemplate {
	id, ok := pathID(r, "id")
	t, err := s.St.ClientTemplateByID(r.Context(), id)
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

func (s *Server) clientTplForm(w http.ResponseWriter, r *http.Request) {
	if r.PathValue("id") == "" {
		s.clientTplFormPage(w, r, http.StatusOK, clientTplForm{New: true, CanEdit: true, T: &store.ClientTemplate{RegionID: s.defaultRegion(r)}})
		return
	}
	if t := s.loadClientTpl(w, r, false); t != nil {
		s.clientTplFormPage(w, r, http.StatusOK, clientTplForm{T: t, CanEdit: actor(r).Can(auth.TemplateEdit, t.RegionID)})
	}
}

// clientTplProblem checks the ownership rule (§2.3) and that every key's line names a
// profile of the configuration template (§3.5.7).
func (s *Server) clientTplProblem(r *http.Request, t *store.ClientTemplate) string {
	ctx := r.Context()
	act := actor(r)
	c, err := s.St.ConfigTemplateByID(ctx, t.ConfigTemplateID)
	if err != nil || !act.Can(auth.TemplateUse, c.RegionID) {
		return "请选择配置模板。"
	}
	if !compatible(t.RegionID, c.RegionID) {
		return "配置模板必须是全局的或属于同一区域。"
	}
	if t.KeyTemplateID == nil {
		return ""
	}
	k, err := s.St.KeyTemplateByID(ctx, *t.KeyTemplateID)
	if err != nil || !act.Can(auth.TemplateUse, k.RegionID) {
		return "所选功能键模板不存在或不可用。"
	}
	if !compatible(t.RegionID, k.RegionID) {
		return "功能键模板必须是全局的或属于同一区域。"
	}
	profiles, _ := s.St.Profiles(ctx, c.ID)
	var names []string
	for _, p := range profiles {
		names = append(names, p.Name)
	}
	if len(names) == 0 {
		names = []string{""} // no profile: any line is wrong
	}
	keys, _ := s.St.Keys(ctx, k.ID)
	for _, key := range keys {
		if p := catalog.LineProblem(key.Line, names); p != "" {
			return "功能键「" + key.Label + "」的" + p
		}
	}
	return ""
}

// compatible: an object of region may use objects that are global or of the same region.
func compatible(region, used *int64) bool {
	return used == nil || region != nil && *region == *used
}

func (s *Server) clientTplSave(w http.ResponseWriter, r *http.Request) {
	isNew := r.PathValue("id") == ""
	t := &store.ClientTemplate{}
	if !isNew {
		if t = s.loadClientTpl(w, r, true); t == nil {
			return
		}
	}
	old := *t
	msg := s.readMeta(r, &t.Name, &t.Note, &t.RegionID, isNew || current(r).Role == store.RoleSuper)
	t.ConfigTemplateID = formID(r, "config")
	t.KeyTemplateID = nil
	if id := formID(r, "keys"); id > 0 {
		t.KeyTemplateID = &id
	}
	if msg == "" {
		msg = s.clientTplProblem(r, t)
	}
	if msg == "" && !isNew && !sameRegion(old.RegionID, t.RegionID) {
		if n, _ := s.St.ClientCountOfTemplate(r.Context(), t.ID); n > 0 && t.RegionID != nil {
			msg = "此模板已被客户端使用，无法移至区域，仅可设为全局。"
		}
	}
	var err error
	if msg == "" {
		if isNew {
			err = s.St.CreateClientTemplate(r.Context(), t)
		} else {
			err = s.St.UpdateClientTemplate(r.Context(), t)
		}
		if errors.Is(err, store.ErrConflict) {
			msg = "同一区域已有同名模板。"
		} else if err != nil {
			s.internal(w, r, err)
			return
		}
	}
	if msg != "" {
		s.clientTplFormPage(w, r, http.StatusBadRequest, clientTplForm{T: t, New: isNew, CanEdit: true, Error: msg})
		return
	}
	action := "client_template.update"
	if isNew {
		action = "client_template.create"
	}
	s.audit(r, action, "client_template", t.ID, t.Name, t.RegionID, changes(
		field{"name", old.Name, t.Name}, field{"config_template_id", old.ConfigTemplateID, t.ConfigTemplateID},
		field{"key_template_id", derefOr(old.KeyTemplateID), derefOr(t.KeyTemplateID)},
		field{"region_id", derefOr(old.RegionID), derefOr(t.RegionID)}))
	s.redirect(w, r, "/client-templates/"+strconv.FormatInt(t.ID, 10), "ok", "客户端模板已保存。")
}

func (s *Server) clientTplDelete(w http.ResponseWriter, r *http.Request) {
	t := s.loadClientTpl(w, r, true)
	if t == nil {
		return
	}
	if err := s.St.DeleteClientTemplate(r.Context(), t.ID); errors.Is(err, store.ErrInUse) {
		s.redirect(w, r, "/client-templates/"+itoa(t.ID), "error", "此模板仍被客户端使用，无法删除。")
		return
	} else if err != nil {
		s.internal(w, r, err)
		return
	}
	s.audit(r, "client_template.delete", "client_template", t.ID, t.Name, t.RegionID, nil)
	s.redirect(w, r, "/client-templates", "ok", "客户端模板「"+t.Name+"」已删除。")
}
