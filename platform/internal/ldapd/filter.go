package ldapd

import (
	"errors"
	"fmt"

	ber "github.com/go-asn1-ber/asn1-ber"
)

// Filter choices of RFC 4511 4.5.1 (context-specific tags).
const (
	fAnd        = 0
	fOr         = 1
	fNot        = 2
	fEquality   = 3
	fSubstrings = 4
	fGreater    = 5
	fLess       = 6
	fPresent    = 7
	fApprox     = 8
	fExtensible = 9
)

// tri is a three-valued filter result.
type tri int8

const (
	tFalse tri = iota
	tTrue
	tUndefined
)

// filter is a compiled search filter. attr < 0 means an unknown attribute.
type filter struct {
	op       int
	children []*filter
	attr     int
	value    string // normalized assertion value
	initial  string
	any      []string
	final    string
}

const maxFilterDepth = 32

var errFilter = errors.New("malformed filter")

// raw returns the content bytes of a primitive packet of any class.
func raw(p *ber.Packet) []byte {
	if p.ByteValue != nil {
		return p.ByteValue
	}
	if p.Data != nil {
		return p.Data.Bytes()
	}
	return nil
}

func compileFilter(p *ber.Packet) (*filter, error) { return compile(p, 0) }

func compile(p *ber.Packet, depth int) (*filter, error) {
	if depth > maxFilterDepth || p.ClassType != ber.ClassContext {
		return nil, errFilter
	}
	f := &filter{op: int(p.Tag), attr: -1}
	switch f.op {
	case fAnd, fOr:
		if p.TagType != ber.TypeConstructed {
			return nil, errFilter
		}
		for _, c := range p.Children {
			cf, err := compile(c, depth+1)
			if err != nil {
				return nil, err
			}
			f.children = append(f.children, cf)
		}
	case fNot:
		if len(p.Children) != 1 {
			return nil, errFilter
		}
		cf, err := compile(p.Children[0], depth+1)
		if err != nil {
			return nil, err
		}
		f.children = []*filter{cf}
	case fEquality, fGreater, fLess, fApprox:
		if len(p.Children) != 2 {
			return nil, errFilter
		}
		if a, ok := lookupAttr(string(raw(p.Children[0]))); ok {
			f.attr = a
			f.value = normalize(a, string(raw(p.Children[1])))
		}
	case fSubstrings:
		if len(p.Children) != 2 || len(p.Children[1].Children) == 0 {
			return nil, errFilter
		}
		a, known := lookupAttr(string(raw(p.Children[0])))
		if known {
			f.attr = a
		}
		for i, part := range p.Children[1].Children {
			if part.ClassType != ber.ClassContext || part.Tag > 2 {
				return nil, errFilter
			}
			if !known {
				continue
			}
			v := normalizePiece(a, string(raw(part)))
			switch part.Tag {
			case 0:
				if i != 0 {
					return nil, errFilter
				}
				f.initial = v
			case 1:
				f.any = append(f.any, v)
			case 2:
				if i != len(p.Children[1].Children)-1 {
					return nil, errFilter
				}
				f.final = v
			}
		}
	case fPresent:
		if a, ok := lookupAttr(string(raw(p))); ok {
			f.attr = a
		}
	case fExtensible:
		// Not supported: evaluates to Undefined (design §4.4).
	default:
		return nil, fmt.Errorf("%w: choice %d", errFilter, f.op)
	}
	return f, nil
}

// eval evaluates the filter against a node.
func (f *filter) eval(n *node) tri {
	switch f.op {
	case fAnd:
		res := tTrue
		for _, c := range f.children {
			switch c.eval(n) {
			case tFalse:
				return tFalse
			case tUndefined:
				res = tUndefined
			}
		}
		return res
	case fOr:
		res := tFalse
		for _, c := range f.children {
			switch c.eval(n) {
			case tTrue:
				return tTrue
			case tUndefined:
				res = tUndefined
			}
		}
		return res
	case fNot:
		switch f.children[0].eval(n) {
		case tTrue:
			return tFalse
		case tFalse:
			return tTrue
		}
		return tUndefined
	case fPresent:
		if f.attr < 0 {
			return tFalse // an attribute the entry cannot have is absent
		}
		return boolTri(len(n.fold[f.attr]) > 0)
	case fExtensible:
		return tUndefined
	}
	if f.attr < 0 {
		return tUndefined
	}
	for _, v := range n.fold[f.attr] {
		var ok bool
		switch f.op {
		case fEquality, fApprox:
			ok = v == f.value
		case fGreater:
			ok = v >= f.value
		case fLess:
			ok = v <= f.value
		case fSubstrings:
			ok = matchSubstrings(v, f.initial, f.any, f.final)
		}
		if ok {
			return tTrue
		}
	}
	return tFalse
}

func boolTri(b bool) tri {
	if b {
		return tTrue
	}
	return tFalse
}
