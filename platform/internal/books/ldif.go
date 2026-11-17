package books

import (
	"bufio"
	"encoding/base64"
	"io"
	"strings"

	"siplink/internal/store"
)

// BooksDN is the container of all books under the suffix.
func BooksDN(suffix string) string { return "ou=books," + suffix }

// BookDN is the DN of a book (§4.2).
func BookDN(slug, suffix string) string { return "ou=" + slug + "," + BooksDN(suffix) }

// EntryDN is the DN of a person in a book.
func EntryDN(e *store.Entry, slug, suffix string) string {
	return "uid=" + UID(e) + "," + BookDN(slug, suffix)
}

// BookAttributes are the attributes of a book's organizationalUnit entry.
func BookAttributes(b *store.Book) []Attr {
	return []Attr{
		{"objectClass", []string{"top", "organizationalUnit"}},
		{"ou", []string{b.Slug}},
		{"description", []string{b.Name}},
	}
}

// WriteLDIF writes a book and its entries as LDIF (RFC 2849) for moving it to
// another LDAP server.
func WriteLDIF(w io.Writer, b *store.Book, entries []*store.Entry, suffix string) error {
	bw := bufio.NewWriter(w)
	bw.WriteString("version: 1\n\n")
	writeRecord(bw, BookDN(b.Slug, suffix), BookAttributes(b))
	for _, e := range entries {
		writeRecord(bw, EntryDN(e, b.Slug, suffix), Attributes(e))
	}
	return bw.Flush()
}

func writeRecord(w *bufio.Writer, dn string, attrs []Attr) {
	writeLine(w, "dn", dn)
	for _, a := range attrs {
		for _, v := range a.Values {
			writeLine(w, a.Name, v)
		}
	}
	w.WriteString("\n")
}

// writeLine writes "name: value", or "name:: base64" when the value is not a
// safe string of RFC 2849 (non-ASCII, control characters, leading space, ':' or '<',
// trailing space).
func writeLine(w *bufio.Writer, name, value string) {
	if safeString(value) {
		w.WriteString(name + ": " + value + "\n")
		return
	}
	w.WriteString(name + ":: " + base64.StdEncoding.EncodeToString([]byte(value)) + "\n")
}

func safeString(s string) bool {
	if s == "" {
		return true
	}
	if s[0] == ' ' || s[0] == ':' || s[0] == '<' || strings.HasSuffix(s, " ") {
		return false
	}
	for i := 0; i < len(s); i++ {
		c := s[i]
		if c == 0 || c == '\n' || c == '\r' || c > 127 {
			return false
		}
	}
	return true
}
