package web

import (
	"context"
	"errors"
	"net/http"
	"slices"
	"strconv"
	"strings"

	"siplink/internal/auth"
	"siplink/internal/catalog"
	"siplink/internal/store"
)

// clientBulk applies one action of the bulk bar to the selected clients. Every selected
// client must be one the administrator manages, and every value of the request one the
// action can use: anything else refuses the whole request, nothing is applied in part.
func (s *Server) clientBulk(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	act := actor(r)
	ids := formIDs(r, "ids")
	if len(ids) == 0 {
		s.redirect(w, r, "/clients", "error", tr(r).T("clients.bulk.none_selected"))
		return
	}
	if len(ids) != len(r.Form["ids"]) {
		s.redirect(w, r, "/clients", "error", tr(r).T("clients.bulk.forbidden"))
		return
	}
	slices.Sort(ids)
	var list []*store.Client
	for _, id := range slices.Compact(ids) {
		c, err := s.St.ClientByID(ctx, id)
		if err != nil && !errors.Is(err, store.ErrNotFound) {
			s.internal(w, r, err)
			return
		}
		if err != nil || !act.Can(auth.ClientManage, &c.RegionID) {
			s.redirect(w, r, "/clients", "error", tr(r).T("clients.bulk.forbidden"))
			return
		}
		list = append(list, c)
	}
	switch action := r.FormValue("action"); action {
	case "print":
		parts := make([]string, len(list))
		for i, c := range list {
			parts[i] = itoa(c.ID)
		}
		http.Redirect(w, r, "/clients/print?ids="+strings.Join(parts, ","), http.StatusSeeOther)
	case "template":
		s.bulkTemplate(w, r, list)
	case "group":
		s.bulkGroup(w, r, list)
	case "tag_add", "tag_remove":
		s.bulkTag(w, r, list, action == "tag_add")
	case "delete":
		for _, c := range list {
			if err := s.St.DeleteClient(ctx, c); err != nil && !errors.Is(err, store.ErrNotFound) {
				s.internal(w, r, err)
				return
			}
			s.audit(r, "client.delete", "client", c.ID, c.Name, &c.RegionID, nil)
		}
		s.redirect(w, r, "/clients", "ok", tr(r).T("clients.bulk.deleted", "n", len(list)))
	default:
		s.redirect(w, r, "/clients", "error", tr(r).T("clients.bulk.invalid_action"))
	}
}

func clientIDs(list []*store.Client) []int64 {
	ids := make([]int64, len(list))
	for i, c := range list {
		ids[i] = c.ID
	}
	return ids
}

func (s *Server) bulkTemplate(w http.ResponseWriter, r *http.Request, list []*store.Client) {
	tid := formID(r, "template")
	for _, c := range list {
		ok := false
		for _, t := range s.usableClientTemplates(r, c.RegionID) {
			ok = ok || t.ID == tid
		}
		if !ok {
			s.redirect(w, r, "/clients", "error", tr(r).T("clients.bulk.template_unusable", "name", c.Name))
			return
		}
	}
	if err := s.St.SetClientTemplate(r.Context(), clientIDs(list), tid); err != nil {
		s.internal(w, r, err)
		return
	}
	for _, c := range list {
		s.audit(r, "client.update", "client", c.ID, c.Name, &c.RegionID, changes(field{"client_template_id", c.ClientTemplateID, tid}))
	}
	s.redirect(w, r, "/clients", "ok", tr(r).T("clients.bulk.template_changed", "n", len(list)))
}

// groupName is the name of a client's group for the audit log, nil for none.
func (s *Server) groupName(ctx context.Context, id *int64) any {
	if id == nil {
		return nil
	}
	if g, err := s.St.GroupByID(ctx, *id); err == nil {
		return g.Name
	}
	return nil
}

// bulkGroup puts the clients into one group of their region, or into none ("0").
func (s *Server) bulkGroup(w http.ResponseWriter, r *http.Request, list []*store.Client) {
	ctx := r.Context()
	gid, err := strconv.ParseInt(r.FormValue("group"), 10, 64)
	var g *store.Group
	if err == nil && gid > 0 {
		g, err = s.St.GroupByID(ctx, gid)
		if err == nil && !actor(r).Can(auth.GroupManage, &g.RegionID) {
			err = store.ErrNotFound
		}
	}
	if err != nil || gid < 0 {
		s.redirect(w, r, "/clients", "error", tr(r).T("clients.bulk.forbidden"))
		return
	}
	var target *int64
	var name any
	if g != nil {
		target, name = &g.ID, g.Name
		for _, c := range list {
			if c.RegionID != g.RegionID {
				s.redirect(w, r, "/clients", "error", tr(r).T("clients.bulk.group_region", "group", g.Name, "name", c.Name))
				return
			}
		}
	}
	if err := s.St.SetClientsGroup(ctx, clientIDs(list), target); err != nil {
		s.internal(w, r, err)
		return
	}
	for _, c := range list {
		if old := s.groupName(ctx, c.GroupID); old != name {
			s.audit(r, "client.update", "client", c.ID, c.Name, &c.RegionID, changes(field{"group", old, name}))
		}
	}
	s.redirect(w, r, "/clients", "ok", tr(r).T("clients.bulk.group_set", "n", len(list)))
}

// bulkTag puts one tag on the clients, or takes it off them.
func (s *Server) bulkTag(w http.ResponseWriter, r *http.Request, list []*store.Client, add bool) {
	ctx := r.Context()
	tags, err := s.allTags(ctx)
	if err != nil {
		s.internal(w, r, err)
		return
	}
	byID := tagsByID(tags)
	tid, _ := strconv.ParseInt(r.FormValue("tag"), 10, 64)
	tag := byID[tid]
	if tag == nil {
		s.redirect(w, r, "/clients", "error", tr(r).T("clients.bulk.forbidden"))
		return
	}
	ids := clientIDs(list)
	before, err := s.St.ClientTags(ctx, ids)
	if err != nil {
		s.internal(w, r, err)
		return
	}
	if add {
		for _, c := range list {
			if !slices.Contains(before[c.ID], tid) && len(before[c.ID]) >= catalog.MaxTagsPerClient {
				s.redirect(w, r, "/clients", "error", tr(r).T("clients.bulk.tags_full", "name", c.Name, "max", catalog.MaxTagsPerClient))
				return
			}
		}
		err = s.St.AddClientsTag(ctx, ids, tid, catalog.MaxTagsPerClient)
	} else {
		err = s.St.RemoveClientsTag(ctx, ids, tid)
	}
	if errors.Is(err, store.ErrLimit) {
		s.redirect(w, r, "/clients", "error", tr(r).T("clients.tags.too_many", "max", catalog.MaxTagsPerClient))
		return
	}
	if err != nil {
		s.internal(w, r, err)
		return
	}
	after, _ := s.St.ClientTags(ctx, ids)
	for _, c := range list {
		if old, now := tagNames(byID, before[c.ID]), tagNames(byID, after[c.ID]); !slices.Equal(old, now) {
			s.audit(r, "client.update", "client", c.ID, c.Name, &c.RegionID, changes(field{"tags", old, now}))
		}
	}
	key := "clients.bulk.tag_removed"
	if add {
		key = "clients.bulk.tag_added"
	}
	s.redirect(w, r, "/clients", "ok", tr(r).T(key, "name", tag.Name, "n", len(list)))
}
