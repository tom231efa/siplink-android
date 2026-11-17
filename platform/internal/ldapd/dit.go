package ldapd

import (
	"encoding/hex"
	"strconv"
	"strings"

	"siplink/internal/books"
	"siplink/internal/store"
)

// node is one entry of the DIT with raw values for output and normalized values
// for matching.
type node struct {
	dn   string
	vals [nAttrs][]string
	fold [nAttrs][]string
}

func (n *node) set(a int, values ...string) {
	n.vals[a] = values
	n.fold[a] = make([]string, len(values))
	for i, v := range values {
		n.fold[a][i] = normalize(a, v)
	}
}

func newNode(dn string, attrs []books.Attr) *node {
	n := &node{dn: dn}
	for _, at := range attrs {
		if i, ok := lookupAttr(at.Name); ok {
			n.set(i, at.Values...)
		}
	}
	return n
}

// rdn is one relative distinguished name, normalized.
type rdn struct{ attr, value string }

// parseDN splits a DN into normalized RDNs (attribute lower-cased, value unescaped
// and case-folded). Multi-valued RDNs are kept as one value. ok is false for a
// malformed DN.
func parseDN(dn string) (out []rdn, ok bool) {
	dn = strings.TrimSpace(dn)
	if dn == "" {
		return nil, true
	}
	var parts []string
	start, esc := 0, false
	for i := 0; i < len(dn); i++ {
		switch {
		case esc:
			esc = false
		case dn[i] == '\\':
			esc = true
		case dn[i] == ',' || dn[i] == ';':
			parts = append(parts, dn[start:i])
			start = i + 1
		}
	}
	parts = append(parts, dn[start:])
	for _, p := range parts {
		attr, value, found := strings.Cut(p, "=")
		attr = strings.ToLower(strings.TrimSpace(attr))
		if !found || attr == "" {
			return nil, false
		}
		v, ok := unescapeDNValue(strings.TrimSpace(value))
		if !ok {
			return nil, false
		}
		out = append(out, rdn{attr, foldCase(v, true)})
	}
	return out, true
}

func unescapeDNValue(v string) (string, bool) {
	if !strings.Contains(v, `\`) {
		return v, true
	}
	var b strings.Builder
	for i := 0; i < len(v); i++ {
		if v[i] != '\\' {
			b.WriteByte(v[i])
			continue
		}
		if i+1 >= len(v) {
			return "", false
		}
		if i+2 < len(v) && isHex(v[i+1]) && isHex(v[i+2]) {
			x, _ := hex.DecodeString(v[i+1 : i+3])
			b.Write(x)
			i += 2
			continue
		}
		b.WriteByte(v[i+1])
		i++
	}
	return b.String(), true
}

func isHex(c byte) bool { return '0' <= c && c <= '9' || 'a' <= c && c <= 'f' || 'A' <= c && c <= 'F' }

// dit knows the shape of the tree under the suffix (design §4.2).
type dit struct {
	suffix  string // as configured
	suffixN []rdn  // normalized
}

func newDIT(suffix string) *dit {
	n, _ := parseDN(suffix)
	return &dit{suffix: suffix, suffixN: n}
}

// target is what a search base names.
type target struct {
	kind  int // see below
	slug  string
	entry int64
}

const (
	tNone = iota
	tRoot
	tSuffix
	tBooks
	tBook
	tEntry
)

// resolve classifies a base DN.
func (d *dit) resolve(base string) target {
	rdns, ok := parseDN(base)
	if !ok {
		return target{}
	}
	if len(rdns) == 0 {
		return target{kind: tRoot}
	}
	n := len(d.suffixN)
	if len(rdns) < n {
		return target{}
	}
	for i := range d.suffixN {
		if rdns[len(rdns)-n+i] != d.suffixN[i] {
			return target{}
		}
	}
	rel := rdns[:len(rdns)-n]
	switch {
	case len(rel) == 0:
		return target{kind: tSuffix}
	case rel[len(rel)-1] != rdn{"ou", "books"}:
		return target{} // ou=clients and ou=accounts are not searchable
	case len(rel) == 1:
		return target{kind: tBooks}
	case rel[len(rel)-2].attr != "ou":
		return target{}
	case len(rel) == 2:
		return target{kind: tBook, slug: rel[0].value}
	case len(rel) == 3 && rel[0].attr == "uid":
		id, err := strconv.ParseInt(rel[0].value, 10, 64)
		if err != nil || id <= 0 {
			return target{}
		}
		return target{kind: tEntry, slug: rel[1].value, entry: id}
	}
	return target{}
}

// suffixNode is the entry of the suffix itself.
func (d *dit) suffixNode() *node {
	n := newNode(d.suffix, []books.Attr{{Name: "objectClass", Values: []string{"top", "domain"}}})
	if parts := strings.SplitN(d.suffix, ",", 2); len(parts) > 0 {
		if a, v, ok := strings.Cut(parts[0], "="); ok && strings.EqualFold(strings.TrimSpace(a), "dc") {
			n.set(aDC, strings.TrimSpace(v))
		}
	}
	return n
}

func (d *dit) booksNode() *node {
	return newNode(books.BooksDN(d.suffix), []books.Attr{
		{Name: "objectClass", Values: []string{"top", "organizationalUnit"}},
		{Name: "ou", Values: []string{"books"}},
	})
}

func (d *dit) bookNode(b *store.Book) *node {
	return newNode(books.BookDN(b.Slug, d.suffix), books.BookAttributes(b))
}

func (d *dit) entryNode(e *store.Entry, slug string) *node {
	return newNode(books.EntryDN(e, slug, d.suffix), books.Attributes(e))
}

// accountName returns the name of cn={name},ou=accounts,{suffix}, or "".
func (d *dit) accountName(dn string) string { return d.bindName(dn, "cn", "accounts") }

// clientID returns the public id of uid={public_id},ou=clients,{suffix}, or "".
func (d *dit) clientID(dn string) string { return d.bindName(dn, "uid", "clients") }

func (d *dit) bindName(dn, attr, ou string) string {
	rdns, ok := parseDN(dn)
	n := len(d.suffixN)
	if !ok || len(rdns) != n+2 {
		return ""
	}
	for i := range d.suffixN {
		if rdns[2+i] != d.suffixN[i] {
			return ""
		}
	}
	if rdns[0].attr != attr || rdns[1] != (rdn{"ou", ou}) {
		return ""
	}
	return rdns[0].value
}
