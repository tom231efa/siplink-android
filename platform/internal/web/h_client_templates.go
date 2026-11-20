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
	ctx := r.Context()
	names, _ := s.allRegionNames(r)
	tl := tr(r)
	clients, _ := s.St.ClientCountsByTemplate(ctx)
	all := store.BookScope{All: true}
	configs, keys := map[int64]string{}, map[int64]string{}
	if cs, err := s.St.ListConfigTemplates(ctx, all); err == nil {
		for _, c := range cs {
			configs[c.ID] = c.Name
		}
	}
	if ks, err := s.St.ListKeyTemplates(ctx, all); err == nil {
		for _, k := range ks {
			keys[k.ID] = k.Name
		}
	}
	var rows []templateRow
	for _, t := range list {
		config, extra := "", ""
		if name, ok := configs[t.ConfigTemplateID]; ok {
			config, extra = name, tl.T("clienttpl.list.content", "config", name)
		}
		if t.KeyTemplateID != nil {
			if name, ok := keys[*t.KeyTemplateID]; ok {
				extra = tl.T("clienttpl.list.content_keys", "config", config, "keys", name)
			}
		}
		rows = append(rows, templateRow{ID: t.ID, Name: t.Name, Note: t.Note, Region: regionLabel(names, t.RegionID),
			Global: t.RegionID == nil, Clients: clients[t.ID], Extra: extra})
	}
	s.ok(w, r, "templates", &Page{Title: tl.T("nav.client_templates"), Nav: "clienttpl", Data: templatesData{Rows: rows,
		Base: "/client-templates", New: tl.T("templates.new.clienttpl"), Empty: tl.T("templates.empty.clienttpl")}})
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
	title := tr(r).T("clienttpl.form.title", "name", f.T.Name)
	if f.New {
		title = tr(r).T("templates.new.clienttpl")
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
		return tr(r).T("clienttpl.error.choose_config")
	}
	if !compatible(t.RegionID, c.RegionID) {
		return tr(r).T("clienttpl.error.config_region")
	}
	if t.KeyTemplateID == nil {
		return ""
	}
	k, err := s.St.KeyTemplateByID(ctx, *t.KeyTemplateID)
	if err != nil || !act.Can(auth.TemplateUse, k.RegionID) {
		return tr(r).T("clienttpl.error.keys_unavailable")
	}
	if !compatible(t.RegionID, k.RegionID) {
		return tr(r).T("clienttpl.error.keys_region")
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
		if err := catalog.LineProblem(key.Line, names); err != nil {
			return tr(r).T("clienttpl.error.key_line", "key", key.Label, "problem", err)
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
			msg = tr(r).T("clienttpl.error.move_in_use")
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
			msg = tr(r).T("templates.error.name_taken")
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
	s.redirect(w, r, "/client-templates/"+strconv.FormatInt(t.ID, 10), "ok", tr(r).T("clienttpl.saved"))
}

func (s *Server) clientTplDelete(w http.ResponseWriter, r *http.Request) {
	t := s.loadClientTpl(w, r, true)
	if t == nil {
		return
	}
	if err := s.St.DeleteClientTemplate(r.Context(), t.ID); errors.Is(err, store.ErrInUse) {
		s.redirect(w, r, "/client-templates/"+itoa(t.ID), "error", tr(r).T("clienttpl.error.delete_in_use"))
		return
	} else if err != nil {
		s.internal(w, r, err)
		return
	}
	s.audit(r, "client_template.delete", "client_template", t.ID, t.Name, t.RegionID, nil)
	s.redirect(w, r, "/client-templates", "ok", tr(r).T("clienttpl.deleted", "name", t.Name))
}
