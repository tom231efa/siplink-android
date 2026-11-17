package ldapd

import (
	"strings"
	"unicode"
	"unicode/utf8"
)

// attribute indexes of the attributes the DIT serves.
const (
	aObjectClass = iota
	aUID
	aCN
	aSN
	aGivenName
	aDisplayName
	aTelephoneNumber
	aMobile
	aO
	aOU
	aTitle
	aMail
	aDescription
	aDC
	nAttrs
)

// attrNames are the canonical names, in output order.
var attrNames = [nAttrs]string{"objectClass", "uid", "cn", "sn", "givenName", "displayName",
	"telephoneNumber", "mobile", "o", "ou", "title", "mail", "description", "dc"}

var attrIndex = func() map[string]int {
	m := map[string]int{}
	for i, n := range attrNames {
		m[strings.ToLower(n)] = i
	}
	// Common aliases of RFC 4519.
	m["commonname"] = aCN
	m["surname"] = aSN
	m["gn"] = aGivenName
	m["organizationname"] = aO
	m["organizationalunitname"] = aOU
	m["rfc822mailbox"] = aMail
	m["userid"] = aUID
	m["domaincomponent"] = aDC
	m["2.5.4.3"] = aCN
	m["2.5.4.4"] = aSN
	m["2.5.4.20"] = aTelephoneNumber
	m["0.9.2342.19200300.100.1.41"] = aMobile
	m["2.5.4.0"] = aObjectClass
	return m
}()

// lookupAttr returns the index of an attribute description (options after ';' ignored).
func lookupAttr(name string) (int, bool) {
	if i := strings.IndexByte(name, ';'); i >= 0 {
		name = name[:i]
	}
	i, ok := attrIndex[strings.ToLower(name)]
	return i, ok
}

// phoneAttr reports whether an attribute uses telephoneNumberMatch.
func phoneAttr(a int) bool { return a == aTelephoneNumber || a == aMobile }

// foldRune maps a rune to the smallest member of its simple case folding orbit, so
// two runes compare equal exactly when they are equal under simple case folding.
func foldRune(r rune) rune {
	if r < utf8.RuneSelf {
		if 'A' <= r && r <= 'Z' {
			return r + 'a' - 'A'
		}
		return r
	}
	min := r
	for f := unicode.SimpleFold(r); f != r; f = unicode.SimpleFold(f) {
		if f < min {
			min = f
		}
	}
	return min
}

// foldCase applies simple case folding and collapses runs of white space into one
// space (caseIgnoreMatch, design §4.4). When trim is true leading and trailing
// spaces are removed too (values and equality assertions; substring pieces keep them).
func foldCase(s string, trim bool) string {
	var b strings.Builder
	b.Grow(len(s))
	space := false
	for _, r := range s {
		if unicode.IsSpace(r) {
			space = true
			continue
		}
		if space && (b.Len() > 0 || !trim) {
			b.WriteByte(' ')
		}
		space = false
		b.WriteRune(foldRune(r))
	}
	if space && !trim {
		b.WriteByte(' ')
	}
	return b.String()
}

// foldPhone removes spaces and '-' (telephoneNumberMatch) and folds the case of letters.
func foldPhone(s string) string {
	var b strings.Builder
	b.Grow(len(s))
	for _, r := range s {
		if r == '-' || unicode.IsSpace(r) {
			continue
		}
		b.WriteRune(foldRune(r))
	}
	return b.String()
}

// normalize prepares a value or an equality / ordering assertion of attribute a.
func normalize(a int, v string) string {
	if phoneAttr(a) {
		return foldPhone(v)
	}
	return foldCase(v, true)
}

// normalizePiece prepares a substring piece of attribute a.
func normalizePiece(a int, v string) string {
	if phoneAttr(a) {
		return foldPhone(v)
	}
	return foldCase(v, false)
}

// matchSubstrings checks initial*any*...*final against a normalized value.
func matchSubstrings(v, initial string, any []string, final string) bool {
	if !strings.HasPrefix(v, initial) {
		return false
	}
	v = v[len(initial):]
	for _, piece := range any {
		i := strings.Index(v, piece)
		if i < 0 {
			return false
		}
		v = v[i+len(piece):]
	}
	return strings.HasSuffix(v, final)
}
