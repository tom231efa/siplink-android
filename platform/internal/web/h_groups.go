package web

import (
	"context"
	"errors"
	"net/http"
	"slices"

	"siplink/internal/auth"
	"siplink/internal/catalog"
	"siplink/internal/i18n"
	"siplink/internal/store"
)

// Groups and tags of clients (design §2.6): the page 分组与标签 with its two tabs, and
// what the client pages need of them.

func (s *Server) routesGroups() {
	s.page("GET /groups", s.groupsPage)
	s.page("POST /groups", s.groupSave)
	s.page("POST /groups/{id}", s.groupSave)
	s.page("POST /groups/{id}/delete", s.groupDelete)
	s.page("GET /tags", s.tagsPage)
	s.page("POST /tags", s.tagSave)
	s.page("POST /tags/{id}", s.tagSave)
	s.page("POST /tags/{id}/delete", s.tagDelete)
}

type groupRow struct {
	*store.Group
	Region  string
	Clients int
}

type tagRow struct {
	*store.Tag
	Clients int
	Locked  bool // a region administrator may not change it: clients outside their regions carry it
}

// dialogForm is what the server shows in the dialog it opens itself: without JavaScript,
// and with a refusal after a POST.
type dialogForm struct {
	ID     int64
	Name   string
	Region int64
	Color  string
}

type groupsData struct {
	Tab     string // "groups" or "tags"
	Groups  []groupRow
	Tags    []tagRow
	Regions []store.Region // where the administrator may make a group
	Colors  []string
	Dialog  string // the dialog the server opens: "new", "edit" or ""
	Form    dialogForm
	Error   string
}

// tagDialog is what the dialog of a tag shows (template "tag-dialog").
type tagDialog struct {
	Kind   string // "new" or "edit"
	Open   bool   // the server opens it
	Form   dialogForm
	Error  string
	Colors []string
}

// TagDialog is the dialog of kind: with the values the server shows when it is the one it
// opens, else empty with the default colour.
func (d groupsData) TagDialog(kind string) tagDialog {
	td := tagDialog{Kind: kind, Open: d.Dialog == kind, Colors: d.Colors, Form: dialogForm{Color: catalog.TagColors[0]}}
	if td.Open {
		td.Form, td.Error = d.Form, d.Error
	}
	return td
}

// allTags is the shared tag list, colours as the palette has them.
func (s *Server) allTags(ctx context.Context) ([]*store.Tag, error) {
	tags, err := s.St.ListTags(ctx)
	for _, t := range tags {
		t.Color = catalog.TagColor(t.Color)
	}
	return tags, err
}

// tagsByID indexes tags by id.
func tagsByID(tags []*store.Tag) map[int64]*store.Tag {
	m := make(map[int64]*store.Tag, len(tags))
	for _, t := range tags {
		m[t.ID] = t
	}
	return m
}

// tagNames are the names of tag ids, for the audit log.
func tagNames(byID map[int64]*store.Tag, ids []int64) []string {
	out := []string{}
	for _, id := range ids {
		if t := byID[id]; t != nil {
			out = append(out, t.Name)
		}
	}
	return out
}

// groupsOf are the groups an administrator may see and use, narrowed by the top bar's
// region filter.
func (s *Server) groupsOf(r *http.Request) ([]*store.Group, error) {
	return s.St.ListGroups(r.Context(), s.bookScope(r))
}

// loadGroup returns the group of the path when the administrator manages its region.
func (s *Server) loadGroup(w http.ResponseWriter, r *http.Request) *store.Group {
	id, ok := pathID(r, "id")
	g, err := s.St.GroupByID(r.Context(), id)
	if !ok || errors.Is(err, store.ErrNotFound) || err == nil && !actor(r).Can(auth.GroupManage, &g.RegionID) {
		s.notFound(w, r) // 404, not 403: groups of other regions do not exist for this administrator
		return nil
	}
	if err != nil {
		s.internal(w, r, err)
		return nil
	}
	return g
}

func (s *Server) groupsPage(w http.ResponseWriter, r *http.Request) {
	d := groupsData{Tab: "groups"}
	q := r.URL.Query()
	if q.Get("new") != "" {
		d.Dialog = "new"
		if def := s.defaultRegion(r); def != nil {
			d.Form.Region = *def
		}
	} else if id, ok := parseInt64(q.Get("edit")); ok {
		if g, err := s.St.GroupByID(r.Context(), id); err == nil && actor(r).Can(auth.GroupManage, &g.RegionID) {
			d.Dialog, d.Form = "edit", dialogForm{ID: g.ID, Name: g.Name, Region: g.RegionID}
		}
	}
	s.renderGroups(w, r, http.StatusOK, d)
}

func (s *Server) renderGroups(w http.ResponseWriter, r *http.Request, status int, d groupsData) {
	ctx := r.Context()
	a := current(r)
	regions, err := s.St.ListRegions(ctx, a.RegionIDs, a.Role == store.RoleSuper)
	if err != nil {
		s.internal(w, r, err)
		return
	}
	d.Regions, d.Colors = regions, catalog.TagColors
	if d.Tab == "groups" {
		groups, err := s.groupsOf(r)
		if err != nil {
			s.internal(w, r, err)
			return
		}
		counts, _ := s.St.GroupClients(ctx)
		names := regionNames(regions)
		for _, g := range groups {
			d.Groups = append(d.Groups, groupRow{Group: g, Region: names[g.RegionID], Clients: counts[g.ID]})
		}
	} else if d.Tags, err = s.tagRows(r); err != nil {
		s.internal(w, r, err)
		return
	}
	s.render(w, r, status, "groups", &Page{Title: tr(r).T("nav.groups"), Nav: "groups", Data: d})
}

// tagRows are the tags with the clients that carry them in the administrator's regions
// (narrowed by the top bar's filter) and whether the administrator may change them.
func (s *Server) tagRows(r *http.Request) ([]tagRow, error) {
	ctx := r.Context()
	act := actor(r)
	tags, err := s.allTags(ctx)
	if err != nil {
		return nil, err
	}
	use, err := s.St.TagClients(ctx, act.Super, act.RegionIDs())
	if err != nil {
		return nil, err
	}
	shown := use
	if sel := s.selectedRegion(r); sel != 0 {
		if shown, err = s.St.TagClients(ctx, false, []int64{sel}); err != nil {
			return nil, err
		}
	}
	var out []tagRow
	for _, t := range tags {
		out = append(out, tagRow{Tag: t, Clients: shown[t.ID].In, Locked: !act.CanChangeTag(use[t.ID].Out > 0)})
	}
	return out, nil
}

// groupSave creates a group (POST /groups) or renames one (POST /groups/{id}).
func (s *Server) groupSave(w http.ResponseWriter, r *http.Request) {
	g := &store.Group{}
	dialog := "new"
	if r.PathValue("id") != "" {
		if g = s.loadGroup(w, r); g == nil {
			return
		}
		dialog = "edit"
	} else {
		g.RegionID = formID(r, "region")
	}
	old := g.Name
	g.Name = trimmed(r, "name")
	refuse := func(msg string) {
		s.renderGroups(w, r, http.StatusBadRequest, groupsData{Tab: "groups", Dialog: dialog, Error: msg,
			Form: dialogForm{ID: g.ID, Name: g.Name, Region: g.RegionID}})
	}
	if msg := errText(r, catalog.GroupNameProblem(g.Name)); msg != "" {
		refuse(msg)
		return
	}
	if g.ID == 0 {
		if _, err := s.St.RegionByID(r.Context(), g.RegionID); err != nil || !actor(r).Can(auth.GroupManage, &g.RegionID) {
			refuse(tr(r).T("clients.form.region_required"))
			return
		}
	}
	switch err := s.St.SaveGroup(r.Context(), g, catalog.MaxGroupsPerRegion); {
	case errors.Is(err, store.ErrConflict):
		refuse(tr(r).T("groups.name_taken"))
		return
	case errors.Is(err, store.ErrLimit):
		refuse(tr(r).T("groups.limit", "max", catalog.MaxGroupsPerRegion))
		return
	case err != nil:
		s.internal(w, r, err)
		return
	}
	if dialog == "new" {
		s.audit(r, "group.create", "group", g.ID, g.Name, &g.RegionID, map[string]any{"name": g.Name})
		s.redirect(w, r, "/groups", "ok", tr(r).T("groups.created", "name", g.Name))
		return
	}
	s.audit(r, "group.update", "group", g.ID, g.Name, &g.RegionID, changes(field{"name", old, g.Name}))
	s.redirect(w, r, "/groups", "ok", tr(r).T("groups.saved", "name", g.Name))
}

func (s *Server) groupDelete(w http.ResponseWriter, r *http.Request) {
	g := s.loadGroup(w, r)
	if g == nil {
		return
	}
	n, err := s.St.DeleteGroup(r.Context(), g.ID)
	if err != nil && !errors.Is(err, store.ErrNotFound) {
		s.internal(w, r, err)
		return
	}
	s.audit(r, "group.delete", "group", g.ID, g.Name, &g.RegionID, map[string]any{"clients": n})
	s.redirect(w, r, "/groups", "ok", tr(r).T("groups.deleted", "name", g.Name))
}

func (s *Server) tagsPage(w http.ResponseWriter, r *http.Request) {
	d := groupsData{Tab: "tags", Form: dialogForm{Color: catalog.TagColors[0]}}
	q := r.URL.Query()
	if q.Get("new") != "" {
		d.Dialog = "new"
	} else if id, ok := parseInt64(q.Get("edit")); ok {
		if t, err := s.St.TagByID(r.Context(), id); err == nil {
			d.Dialog, d.Form = "edit", dialogForm{ID: t.ID, Name: t.Name, Color: catalog.TagColor(t.Color)}
		}
	}
	s.renderGroups(w, r, http.StatusOK, d)
}

// tagSave creates a tag (POST /tags) or renames and recolours one (POST /tags/{id}).
func (s *Server) tagSave(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	t := &store.Tag{}
	dialog := "new"
	if r.PathValue("id") != "" {
		id, ok := pathID(r, "id")
		var err error
		if t, err = s.St.TagByID(ctx, id); !ok || errors.Is(err, store.ErrNotFound) {
			s.notFound(w, r)
			return
		} else if err != nil {
			s.internal(w, r, err)
			return
		}
		dialog = "edit"
	}
	old := *t
	t.Name, t.Color = trimmed(r, "name"), catalog.TagColor(r.FormValue("color"))
	refuse := func(msg string) {
		s.renderGroups(w, r, http.StatusBadRequest, groupsData{Tab: "tags", Dialog: dialog, Error: msg,
			Form: dialogForm{ID: t.ID, Name: t.Name, Color: t.Color}})
	}
	if msg := errText(r, catalog.TagNameProblem(t.Name)); msg != "" {
		refuse(msg)
		return
	}
	act := actor(r)
	var err error
	if dialog == "new" {
		err = s.St.CreateTag(ctx, t, catalog.MaxTags)
	} else {
		err = s.St.UpdateTag(ctx, t, act.Super, act.RegionIDs())
	}
	switch {
	case errors.Is(err, store.ErrInUse):
		s.redirect(w, r, "/tags", "error", tr(r).T("tags.locked", "name", old.Name))
		return
	case errors.Is(err, store.ErrConflict):
		refuse(tr(r).T("tags.name_taken"))
		return
	case errors.Is(err, store.ErrLimit):
		refuse(tr(r).T("tags.limit", "max", catalog.MaxTags))
		return
	case err != nil:
		s.internal(w, r, err)
		return
	}
	if dialog == "new" {
		s.audit(r, "tag.create", "tag", t.ID, t.Name, nil, map[string]any{"name": t.Name, "color": t.Color})
		s.redirect(w, r, "/tags", "ok", tr(r).T("tags.created", "name", t.Name))
		return
	}
	s.audit(r, "tag.update", "tag", t.ID, t.Name, nil, changes(field{"name", old.Name, t.Name}, field{"color", old.Color, t.Color}))
	s.redirect(w, r, "/tags", "ok", tr(r).T("tags.saved", "name", t.Name))
}

func (s *Server) tagDelete(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	id, ok := pathID(r, "id")
	t, err := s.St.TagByID(ctx, id)
	if !ok || errors.Is(err, store.ErrNotFound) {
		s.notFound(w, r)
		return
	}
	if err != nil {
		s.internal(w, r, err)
		return
	}
	act := actor(r)
	n, err := s.St.DeleteTag(ctx, t.ID, act.Super, act.RegionIDs())
	switch {
	case errors.Is(err, store.ErrInUse):
		s.redirect(w, r, "/tags", "error", tr(r).T("tags.locked", "name", t.Name))
		return
	case err != nil && !errors.Is(err, store.ErrNotFound):
		s.internal(w, r, err)
		return
	}
	s.audit(r, "tag.delete", "tag", t.ID, t.Name, nil, map[string]any{"clients": n})
	s.redirect(w, r, "/tags", "ok", tr(r).T("tags.deleted", "name", t.Name))
}

// ---- what the client pages use ----

// groupSet is the groups of one region, for the <optgroup>s of a group choice.
type groupSet struct {
	Region string
	Groups []*store.Group
}

// groupSets puts groups by region, in the order of the regions' names.
func groupSets(groups []*store.Group, regions []store.Region) []groupSet {
	var out []groupSet
	for _, reg := range regions {
		set := groupSet{Region: reg.Name}
		for _, g := range groups {
			if g.RegionID == reg.ID {
				set.Groups = append(set.Groups, g)
			}
		}
		if len(set.Groups) > 0 {
			out = append(out, set)
		}
	}
	return out
}

// tagPicker is a choice of tags (template "tag-picker"): the tags of the field name
// that are chosen, at most Max of them (0: no limit); Filter makes it the compact one of
// a filter row.
type tagPicker struct {
	Name   string
	Label  string
	Tags   []*store.Tag
	Chosen []int64
	Max    int
	Filter bool
}

// ChosenTags are the chosen tags in the list's order.
func (p tagPicker) ChosenTags() []*store.Tag {
	var out []*store.Tag
	for _, t := range p.Tags {
		if slices.Contains(p.Chosen, t.ID) {
			out = append(out, t)
		}
	}
	return out
}

// readTags reads the tag ids of a form or query field: each a known tag, once.
func readTags(values []string, byID map[int64]*store.Tag) []int64 {
	out := []int64{}
	for _, v := range values {
		if id, ok := parseInt64(v); ok && byID[id] != nil && !slices.Contains(out, id) {
			out = append(out, id)
		}
	}
	return out
}

// tooManyTags is the refusal of more tags on one client than allowed, "" when they fit.
func tooManyTags(t *i18n.Tr, n int) string {
	if n > catalog.MaxTagsPerClient {
		return t.T("clients.tags.too_many", "max", catalog.MaxTagsPerClient)
	}
	return ""
}
