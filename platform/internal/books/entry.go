// Package books validates people of hosted address books, reads and writes them as
// CSV and LDIF, and maps them to LDAP attributes (design §4.2, §4.7).
package books

import (
	"regexp"
	"strconv"
	"strings"
	"unicode/utf8"

	"siplink/internal/i18n"
	"siplink/internal/store"
)

// Field limits of §4.7.
const (
	MaxText    = 64
	MaxEmail   = 128
	MaxNumbers = 4
	MaxNumber  = 32
	MaxNote    = 200
)

var (
	slugPattern   = regexp.MustCompile(`^[a-z0-9-]{2,32}$`)
	numberPattern = regexp.MustCompile(`^[0-9+*#\-() ]+$`)
)

// SlugProblem says why slug is not acceptable, nil when it is.
func SlugProblem(slug string) error {
	if !slugPattern.MatchString(slug) {
		return i18n.E("books.slug_invalid")
	}
	return nil
}

// NameProblem checks a book name.
func NameProblem(name string) error {
	switch n := utf8.RuneCountInString(name); {
	case n == 0:
		return i18n.E("books.name_required")
	case n > MaxText:
		return i18n.E("books.name_too_long", "max", MaxText)
	case i18n.HasBidiControl(name):
		return i18n.E("names.bidi_control")
	}
	return nil
}

// SplitNumbers splits "a;b" (also the full-width "；") into trimmed, non-empty numbers.
func SplitNumbers(s string) []string {
	var out []string
	for _, part := range strings.FieldsFunc(s, func(r rune) bool { return r == ';' || r == '；' || r == '\n' }) {
		if part = strings.TrimSpace(part); part != "" {
			out = append(out, part)
		}
	}
	return out
}

// Normalize trims every field of an entry.
func Normalize(e *store.Entry) {
	for _, p := range []*string{&e.Name, &e.Surname, &e.GivenName, &e.Organization, &e.Department, &e.Title, &e.Email} {
		*p = strings.TrimSpace(*p)
	}
	e.Phones = trimAll(e.Phones)
	e.Mobiles = trimAll(e.Mobiles)
}

func trimAll(list []string) []string {
	var out []string
	for _, v := range list {
		if v = strings.TrimSpace(v); v != "" {
			out = append(out, v)
		}
	}
	return out
}

// Validate returns the problems of an entry; empty when it is fine.
func Validate(e *store.Entry) []i18n.Msg {
	var out []i18n.Msg
	if e.Name == "" {
		out = append(out, i18n.M("books.entry.name_required"))
	}
	for _, f := range []struct {
		field, value string
		max          int
	}{
		{"name", e.Name, MaxText}, {"surname", e.Surname, MaxText}, {"given_name", e.GivenName, MaxText},
		{"organization", e.Organization, MaxText}, {"department", e.Department, MaxText}, {"title", e.Title, MaxText},
		{"email", e.Email, MaxEmail},
	} {
		label := i18n.M("books.field." + f.field)
		if utf8.RuneCountInString(f.value) > f.max {
			out = append(out, i18n.M("books.entry.too_long", "field", label, "max", f.max))
		}
		if strings.ContainsAny(f.value, "\r\n\x00") {
			out = append(out, i18n.M("books.entry.control", "field", label))
		}
	}
	out = append(out, numberProblems(i18n.M("books.field.phone"), e.Phones)...)
	out = append(out, numberProblems(i18n.M("books.field.mobile"), e.Mobiles)...)
	return out
}

func numberProblems(label i18n.Msg, numbers []string) []i18n.Msg {
	var out []i18n.Msg
	if len(numbers) > MaxNumbers {
		out = append(out, i18n.M("books.entry.too_many_numbers", "field", label, "max", MaxNumbers))
	}
	for _, n := range numbers {
		switch {
		case len(n) > MaxNumber:
			out = append(out, i18n.M("books.entry.number_too_long", "field", label, "number", n, "max", MaxNumber))
		case !numberPattern.MatchString(n):
			out = append(out, i18n.M("books.entry.number_invalid", "field", label, "number", n))
		}
	}
	return out
}

// Attr is one LDAP attribute with its values.
type Attr struct {
	Name   string
	Values []string
}

// PersonClasses are the object classes of a person entry.
var PersonClasses = []string{"top", "person", "organizationalPerson", "inetOrgPerson"}

// UID is the uid (RDN value) of an entry.
func UID(e *store.Entry) string { return strconv.FormatInt(e.ID, 10) }

// Attributes maps an entry to its LDAP attributes in a fixed order (§4.2). Empty
// attributes are left out.
func Attributes(e *store.Entry) []Attr {
	sn := e.Surname
	if sn == "" {
		sn = e.Name // sn is mandatory in person
	}
	all := []Attr{
		{"objectClass", PersonClasses},
		{"uid", []string{UID(e)}},
		{"cn", []string{e.Name}},
		{"sn", []string{sn}},
		{"givenName", one(e.GivenName)},
		{"displayName", []string{e.Name}},
		{"telephoneNumber", e.Phones},
		{"mobile", e.Mobiles},
		{"o", one(e.Organization)},
		{"ou", one(e.Department)},
		{"title", one(e.Title)},
		{"mail", one(e.Email)},
	}
	out := all[:0]
	for _, a := range all {
		if len(a.Values) > 0 {
			out = append(out, a)
		}
	}
	return out
}

func one(v string) []string {
	if v == "" {
		return nil
	}
	return []string{v}
}
