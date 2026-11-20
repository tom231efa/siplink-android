package ldapd

import (
	"context"
	"errors"
	"strings"

	ber "github.com/go-asn1-ber/asn1-ber"

	"siplink/internal/store"
)

// Search scopes.
const (
	scopeBase = 0
	scopeOne  = 1
	scopeSub  = 2
)

var errStop = errors.New("size limit reached")

type searchReq struct {
	base      string
	scope     int
	sizeLimit int
	typesOnly bool
	filter    *filter
	sel       selection
}

func parseSearch(op *ber.Packet) (*searchReq, error) {
	if len(op.Children) != 8 {
		return nil, errFilter
	}
	scope, _ := op.Children[1].Value.(int64)
	size, _ := op.Children[3].Value.(int64)
	typesOnly, _ := op.Children[5].Value.(bool)
	if scope < scopeBase || scope > scopeSub || size < 0 {
		return nil, errFilter
	}
	f, err := compileFilter(op.Children[6])
	if err != nil {
		return nil, err
	}
	return &searchReq{base: string(raw(op.Children[0])), scope: int(scope), sizeLimit: int(size),
		typesOnly: typesOnly, filter: f, sel: parseSelection(op.Children[7])}, nil
}

// search handles a SearchRequest.
func (c *conn) search(m *message) bool {
	req, err := parseSearch(m.op)
	if err != nil {
		return c.send(m.id, result(opSearchDone, rcProtocolError, "", "malformed search request"))
	}
	t := c.s.dit.resolve(req.base)
	if t.kind == tRoot && req.scope == scopeBase {
		if req.filter.eval(rootDSENode(c.s)) == tTrue {
			if !c.queue(m.id, rootDSE(c.s, req.sel)) {
				return false
			}
		}
		return c.send(m.id, result(opSearchDone, rcSuccess, "", ""))
	}
	if c.s.cfg.RequireTLS && !c.tls {
		return c.send(m.id, result(opSearchDone, rcConfidentialityRequired, "", "use StartTLS first"))
	}
	limit := c.s.cfg.MaxResults
	if req.sizeLimit > 0 && req.sizeLimit < limit {
		limit = req.sizeLimit
	}
	ctx, cancel := context.WithTimeout(context.Background(), c.s.cfg.OpTimeout)
	defer cancel()
	sent := 0
	writeFailed := false
	emit := func(n *node) error {
		if req.filter.eval(n) != tTrue {
			return nil
		}
		if sent == limit {
			return errStop
		}
		if !c.queue(m.id, entryPacket(n, req.sel, req.typesOnly)) {
			writeFailed = true
			return errStop
		}
		sent++
		return nil
	}
	code, err := c.s.walk(ctx, c.ident, t, req.scope, emit)
	switch {
	case writeFailed:
		return false
	case errors.Is(err, errStop):
		code = rcSizeLimitExceeded
	case errors.Is(err, context.DeadlineExceeded):
		code = rcTimeLimitExceeded
	case err != nil:
		c.s.log.Error("ldap search failed", "err", err)
		code = rcOther
	}
	return c.send(m.id, result(opSearchDone, code, "", ""))
}

// walk visits the nodes a search covers, in DIT order, calling emit for each. It
// returns noSuchObject for a base outside the permitted books (design §4.3).
func (s *Server) walk(ctx context.Context, id identity, t target, scope int, emit func(*node) error) (int, error) {
	if t.kind == tNone || t.kind == tRoot {
		return rcNoSuchObject, nil
	}
	permitted, err := s.permittedBooks(ctx, id)
	if err != nil {
		return rcOther, err
	}
	var book *store.Book
	if t.kind == tBook || t.kind == tEntry {
		for _, b := range permitted {
			if strings.EqualFold(b.Slug, t.slug) {
				book = b
			}
		}
		if book == nil {
			return rcNoSuchObject, nil
		}
	}
	entries := func(b *store.Book) error {
		snap, err := s.cache.get(ctx, b)
		if err != nil {
			return err
		}
		for _, n := range snap.nodes {
			if err := ctx.Err(); err != nil {
				return err
			}
			if err := emit(n); err != nil {
				return err
			}
		}
		return nil
	}
	books := func(withEntries bool) error {
		for _, b := range permitted {
			if err := emit(s.dit.bookNode(b)); err != nil {
				return err
			}
			if withEntries {
				if err := entries(b); err != nil {
					return err
				}
			}
		}
		return nil
	}
	switch t.kind {
	case tSuffix:
		switch scope {
		case scopeBase:
			err = emit(s.dit.suffixNode())
		case scopeOne:
			err = emit(s.dit.booksNode())
		default:
			if err = emit(s.dit.suffixNode()); err == nil {
				if err = emit(s.dit.booksNode()); err == nil {
					err = books(true)
				}
			}
		}
	case tBooks:
		switch scope {
		case scopeBase:
			err = emit(s.dit.booksNode())
		case scopeOne:
			err = books(false)
		default:
			if err = emit(s.dit.booksNode()); err == nil {
				err = books(true)
			}
		}
	case tBook:
		switch scope {
		case scopeBase:
			err = emit(s.dit.bookNode(book))
		case scopeOne:
			err = entries(book)
		default:
			if err = emit(s.dit.bookNode(book)); err == nil {
				err = entries(book)
			}
		}
	case tEntry:
		snap, lerr := s.cache.get(ctx, book)
		if lerr != nil {
			return rcOther, lerr
		}
		n := snap.byID[t.entry]
		if n == nil {
			return rcNoSuchObject, nil
		}
		if scope != scopeOne {
			err = emit(n)
		}
	}
	return rcSuccess, err
}

// rootDSENode is the root DSE as a node, for filter evaluation.
func rootDSENode(*Server) *node {
	n := &node{}
	n.set(aObjectClass, "top")
	return n
}

// rootDSE answers base "" scope base (design §4.2, addendum 2 C: StartTLS always listed).
func rootDSE(s *Server, sel selection) *ber.Packet {
	p := ber.Encode(ber.ClassApplication, ber.TypeConstructed, opSearchEntry, nil, "")
	p.AppendChild(octets(""))
	attrs := ber.NewSequence("attributes")
	add := func(name string, values ...string) {
		a := ber.NewSequence("")
		a.AppendChild(octets(name))
		set := ber.Encode(ber.ClassUniversal, ber.TypeConstructed, ber.TagSet, nil, "")
		for _, v := range values {
			set.AppendChild(octets(v))
		}
		a.AppendChild(set)
		attrs.AppendChild(a)
	}
	named := func(name string) bool {
		return sel.all || sel.plus || sel.raw[strings.ToLower(name)]
	}
	if named("objectClass") {
		add("objectClass", "top")
	}
	if named("namingContexts") {
		add("namingContexts", s.cfg.Suffix)
	}
	if named("supportedLDAPVersion") {
		add("supportedLDAPVersion", "3")
	}
	if named("supportedExtension") && s.cfg.TLS != nil {
		add("supportedExtension", OIDStartTLS)
	}
	if named("vendorName") {
		add("vendorName", "SipLink")
	}
	p.AppendChild(attrs)
	return p
}
