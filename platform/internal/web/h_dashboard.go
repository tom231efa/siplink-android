package web

import (
	"net/http"

	"siplink/internal/pki"
	"siplink/internal/store"
)

type dashboardData struct {
	Regions int
	Admins  int
	Books   int
	Entries int
	Full    []*store.Book // books at 90 % of the limit or more
	Max     int
	Recent  []store.AuditEntry
	Names   map[int64]string // region names for the audit table
	Active  int
	Pending int
	Offline []attentionRow // the first attentionRows offline clients (store.ClientAttention)
	Trouble []attentionRow // the first attentionRows with an apply error, invalid configuration or failed registration
	// OfflineTotal and TroubleTotal count all of them.
	OfflineTotal, TroubleTotal int
	Cert                       []pki.Notice // super only: the operator's certificate expires soon or has expired
	Healthy                    int          // bound clients that are neither offline nor in trouble
	Bar                        []barSegment // the client tile's stacked bar
}

// attentionRows is how many clients of each kind the attention list shows; the offline
// ones that do not fit are a link to the client list's offline filter.
const attentionRows = 10

// OfflineMore and TroubleMore are the clients the attention list leaves out.
func (d dashboardData) OfflineMore() int { return d.OfflineTotal - len(d.Offline) }
func (d dashboardData) TroubleMore() int { return d.TroubleTotal - len(d.Trouble) }

// barSegment is one part of a stacked bar drawn as SVG (widths in % of the bar; inline
// styles are not allowed by the CSP, SVG attributes are).
type barSegment struct {
	Class, Label string
	N            int
	X, W         float64
}

func stackedBar(parts ...barSegment) []barSegment {
	total := 0
	for _, p := range parts {
		total += p.N
	}
	var out []barSegment
	x := 0.0
	for _, p := range parts {
		if total == 0 {
			p.W = 0
		} else {
			p.W = float64(p.N) * 100 / float64(total)
		}
		p.X = x
		x += p.W
		out = append(out, p)
	}
	return out
}

type attentionRow struct {
	ID       int64
	Name     string
	Reason   string // in the request's language, for the trouble list
	LastSeen string // stored time, for the offline list
}

func (s *Server) dashboard(w http.ResponseWriter, r *http.Request) {
	a := current(r)
	ctx := r.Context()
	d := dashboardData{}
	regions, err := s.St.ListRegions(ctx, a.RegionIDs, a.Role == store.RoleSuper)
	if err != nil {
		s.internal(w, r, err)
		return
	}
	d.Regions = len(regions)
	d.Names = regionNames(regions)
	if a.Role == store.RoleSuper {
		if d.Admins, err = s.St.CountAdmins(ctx); err != nil {
			s.internal(w, r, err)
			return
		}
		d.Cert = s.certNotices(r)
	}
	books, err := s.St.ListBooks(ctx, store.BookScope{All: a.Role == store.RoleSuper, Regions: a.RegionIDs})
	if err != nil {
		s.internal(w, r, err)
		return
	}
	d.Books, d.Max = len(books), s.Cfg.MaxBookEntries
	for _, b := range books {
		d.Entries += b.EntryCount
		if b.EntryCount*10 >= d.Max*9 {
			d.Full = append(d.Full, b)
		}
	}
	scope := store.ClientFilter{All: a.Role == store.RoleSuper, Regions: a.RegionIDs}
	counts, err := s.St.CountClients(ctx, scope)
	if err != nil {
		s.internal(w, r, err)
		return
	}
	d.Active, d.Pending = counts[store.StatusActive], counts[store.StatusPending]
	scope.Now = s.Now()
	att, err := s.St.ClientAttention(ctx, scope, attentionRows)
	if err != nil {
		s.internal(w, r, err)
		return
	}
	t := tr(r)
	d.OfflineTotal, d.TroubleTotal, d.Healthy = att.Offline, att.Trouble, att.Healthy
	for _, c := range att.OfflineRows {
		d.Offline = append(d.Offline, attentionRow{ID: c.ID, Name: c.Name, LastSeen: c.LastSeenAt})
	}
	for _, c := range att.TroubleRows {
		row := attentionRow{ID: c.ID, Name: c.Name}
		switch {
		case c.BuildError != "":
			row.Reason = t.T("dashboard.attention.invalid_config")
		case c.ApplyError != "":
			row.Reason = t.T("dashboard.attention.apply_failed", "err", truncate(c.ApplyError, 80))
		default:
			row.Reason = t.T("dashboard.attention.reg_failed", "summary", regSummary(t, c.Registrations))
		}
		d.Trouble = append(d.Trouble, row)
	}
	d.Bar = stackedBar(barSegment{Class: "ok", Label: t.T("dashboard.bar.healthy"), N: d.Healthy},
		barSegment{Class: "warn", Label: t.T("dashboard.bar.attention"), N: d.Active - d.Healthy},
		barSegment{Class: "idle", Label: t.T("dashboard.bar.pending"), N: d.Pending})
	f := visibleAudit(a)
	f.Limit = 8
	if d.Recent, err = s.St.ListAudit(ctx, f); err != nil {
		s.internal(w, r, err)
		return
	}
	s.ok(w, r, "dashboard", &Page{Title: t.T("nav.dashboard"), Nav: "dashboard", Data: d})
}

func regionNames(regions []store.Region) map[int64]string {
	m := make(map[int64]string, len(regions))
	for _, r := range regions {
		m[r.ID] = r.Name
	}
	return m
}

// visibleAudit is the audit scope of an administrator (design §2.4).
func visibleAudit(a *store.Admin) store.AuditFilter {
	if a.Role == store.RoleSuper {
		return store.AuditFilter{AllRegions: true}
	}
	return store.AuditFilter{RegionIDs: a.RegionIDs, OwnAdminID: a.ID}
}
