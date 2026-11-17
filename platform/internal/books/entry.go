// Package books validates people of hosted address books, reads and writes them as
// CSV and LDIF, and maps them to LDAP attributes (design §4.2, §4.7).
package books

import (
	"fmt"
	"regexp"
	"strconv"
	"strings"
	"unicode/utf8"

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

// SlugProblem returns a message when slug is not acceptable, or "".
func SlugProblem(slug string) string {
	if !slugPattern.MatchString(slug) {
		return "标识仅可使用 2-32 个小写字母、数字或 -。"
	}
	return ""
}

// NameProblem checks a book name.
func NameProblem(name string) string {
	switch n := utf8.RuneCountInString(name); {
	case n == 0:
		return "请填写通讯录名称。"
	case n > MaxText:
		return fmt.Sprintf("通讯录名称不得超过 %d 个字符。", MaxText)
	}
	return ""
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

// Validate returns the problems of an entry in Chinese; empty when it is fine.
func Validate(e *store.Entry) []string {
	var out []string
	if e.Name == "" {
		out = append(out, "姓名为必填项")
	}
	for _, f := range []struct {
		label, value string
		max          int
	}{
		{"姓名", e.Name, MaxText}, {"姓", e.Surname, MaxText}, {"名", e.GivenName, MaxText},
		{"单位", e.Organization, MaxText}, {"部门", e.Department, MaxText}, {"职务", e.Title, MaxText},
		{"邮箱", e.Email, MaxEmail},
	} {
		if utf8.RuneCountInString(f.value) > f.max {
			out = append(out, fmt.Sprintf("%s超过 %d 个字符", f.label, f.max))
		}
		if strings.ContainsAny(f.value, "\r\n\x00") {
			out = append(out, f.label+"含有换行或控制字符")
		}
	}
	out = append(out, numberProblems("办公电话", e.Phones)...)
	out = append(out, numberProblems("手机", e.Mobiles)...)
	return out
}

func numberProblems(label string, numbers []string) []string {
	var out []string
	if len(numbers) > MaxNumbers {
		out = append(out, fmt.Sprintf("%s最多 %d 个号码", label, MaxNumbers))
	}
	for _, n := range numbers {
		switch {
		case len(n) > MaxNumber:
			out = append(out, fmt.Sprintf("%s「%s」超过 %d 个字符", label, n, MaxNumber))
		case !numberPattern.MatchString(n):
			out = append(out, fmt.Sprintf("%s「%s」仅限数字、空格及 + * # - ( )", label, n))
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
