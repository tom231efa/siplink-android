package web

import (
	"errors"
	"net/http"
	"reflect"
	"strconv"
	"unicode/utf8"

	"siplink/internal/auth"
	"siplink/internal/i18n"
	"siplink/internal/store"
)

func actor(r *http.Request) auth.Actor { return auth.ActorOf(current(r)) }

func (s *Server) routesP2() {
	s.page("GET /regions", s.regionList)
	s.page("POST /regions", s.requireSuper(s.regionCreate))
	s.page("POST /regions/{id}", s.requireSuper(s.regionUpdate))
	s.page("POST /regions/{id}/delete", s.requireSuper(s.regionDelete))

	s.page("GET /admins", s.adminList)
	s.page("GET /admins/new", s.requireSuper(s.adminNew))
	s.page("POST /admins", s.requireSuper(s.adminCreate))
	s.page("GET /admins/{id}", s.requireSuper(s.adminEdit))
	s.page("POST /admins/{id}", s.requireSuper(s.adminUpdate))
	s.page("POST /admins/{id}/reset-password", s.requireSuper(s.adminResetPassword))

	s.page("GET /audit", s.auditList)
}

type regionRow struct {
	store.Region
	Usage store.RegionUsage
}

type regionsData struct {
	Rows  []regionRow
	Error string
	Name  string
}

func (s *Server) regionList(w http.ResponseWriter, r *http.Request) {
	s.renderRegions(w, r, http.StatusOK, regionsData{})
}

func (s *Server) renderRegions(w http.ResponseWriter, r *http.Request, status int, d regionsData) {
	a := current(r)
	regions, err := s.St.ListRegions(r.Context(), a.RegionIDs, a.Role == store.RoleSuper)
	if err != nil {
		s.internal(w, r, err)
		return
	}
	for _, reg := range regions {
		u, err := s.St.RegionUsage(r.Context(), reg.ID)
		if err != nil {
			s.internal(w, r, err)
			return
		}
		d.Rows = append(d.Rows, regionRow{Region: reg, Usage: u})
	}
	s.render(w, r, status, "regions", &Page{Title: tr(r).T("nav.regions"), Nav: "regions", Data: d})
}

// maxRegionName is the longest region name, in characters.
const maxRegionName = 64

func regionNameProblem(t *i18n.Tr, name string) string {
	switch n := utf8.RuneCountInString(name); {
	case n == 0:
		return t.T("regions.name_missing")
	case n > maxRegionName:
		return t.T("regions.name_too_long", "max", maxRegionName)
	case i18n.HasBidiControl(name):
		return t.T("names.bidi_control")
	}
	return ""
}

func (s *Server) regionCreate(w http.ResponseWriter, r *http.Request) {
	reg := &store.Region{Name: trimmed(r, "name"), Note: truncate(trimmed(r, "note"), 200)}
	if msg := regionNameProblem(tr(r), reg.Name); msg != "" {
		s.renderRegions(w, r, http.StatusBadRequest, regionsData{Error: msg, Name: reg.Name})
		return
	}
	err := s.St.CreateRegion(r.Context(), reg)
	if errors.Is(err, store.ErrConflict) {
		s.renderRegions(w, r, http.StatusBadRequest, regionsData{Error: tr(r).T("regions.name_taken"), Name: reg.Name})
		return
	}
	if err != nil {
		s.internal(w, r, err)
		return
	}
	s.audit(r, "region.create", "region", reg.ID, reg.Name, &reg.ID, map[string]any{"name": reg.Name})
	s.redirect(w, r, "/regions", "ok", tr(r).T("regions.created", "name", reg.Name))
}

func (s *Server) regionUpdate(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(r, "id")
	old, err := s.St.RegionByID(r.Context(), id)
	if !ok || errors.Is(err, store.ErrNotFound) {
		s.notFound(w, r)
		return
	}
	if err != nil {
		s.internal(w, r, err)
		return
	}
	reg := &store.Region{ID: id, Name: trimmed(r, "name"), Note: truncate(trimmed(r, "note"), 200)}
	if msg := regionNameProblem(tr(r), reg.Name); msg != "" {
		s.redirect(w, r, "/regions", "error", msg)
		return
	}
	if err := s.St.UpdateRegion(r.Context(), reg); errors.Is(err, store.ErrConflict) {
		s.redirect(w, r, "/regions", "error", tr(r).T("regions.name_taken"))
		return
	} else if err != nil {
		s.internal(w, r, err)
		return
	}
	s.audit(r, "region.update", "region", id, reg.Name, &id, changes(
		field{"name", old.Name, reg.Name}, field{"note", old.Note, reg.Note}))
	s.redirect(w, r, "/regions", "ok", tr(r).T("regions.saved"))
}

func (s *Server) regionDelete(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(r, "id")
	reg, err := s.St.RegionByID(r.Context(), id)
	if !ok || errors.Is(err, store.ErrNotFound) {
		s.notFound(w, r)
		return
	}
	if err != nil {
		s.internal(w, r, err)
		return
	}
	err = s.St.DeleteRegion(r.Context(), id)
	if errors.Is(err, store.ErrInUse) {
		s.redirect(w, r, "/regions", "error", tr(r).T("regions.delete_not_empty", "name", reg.Name))
		return
	}
	if err != nil {
		s.internal(w, r, err)
		return
	}
	s.audit(r, "region.delete", "region", id, reg.Name, &id, nil)
	s.redirect(w, r, "/regions", "ok", tr(r).T("regions.deleted", "name", reg.Name))
}

// field is one changed value for an audit entry.
type field struct {
	name     string
	old, new any
}

// changes lists changed fields with old and new values. Secret fields must be
// passed as auditChanged by the caller, never with their values.
func changes(fields ...field) map[string]any {
	out := map[string]any{}
	for _, f := range fields {
		if !reflect.DeepEqual(f.old, f.new) {
			out[f.name] = map[string]any{"old": f.old, "new": f.new}
		}
	}
	return out
}

func itoa(n int64) string { return strconv.FormatInt(n, 10) }
