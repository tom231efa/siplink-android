// Package rules holds the validation rules of the SipLink Android app, ported from Kotlin
// (app/src/main/java/com/sky/siplink/core/logic/) so that the platform accepts and rejects
// exactly what the app accepts and rejects.
//
// This file holds the string helpers that reproduce the Kotlin/JVM string semantics the
// rules rely on: Char.isWhitespace, String.trim, String.length (UTF-16 units), String.take,
// String.lowercase, equals(ignoreCase = true) and String.toIntOrNull.
package rules

import (
	"strings"
	"unicode"
)

// isWhitespace mirrors Kotlin's Char.isWhitespace on the JVM:
// Character.isWhitespace(c) || Character.isSpaceChar(c). That is every Zs, Zl and Zp
// character (no-break spaces included) plus \t \n \v \f \r and U+001C..U+001F.
// Go's unicode.IsSpace differs: it includes U+0085 and leaves out U+001C..U+001F.
func isWhitespace(r rune) bool {
	switch r {
	case '\t', '\n', '\v', '\f', '\r', 0x1C, 0x1D, 0x1E, 0x1F:
		return true
	}
	return unicode.In(r, unicode.Zs, unicode.Zl, unicode.Zp)
}

// trim mirrors Kotlin's String.trim(), which drops leading and trailing Char.isWhitespace.
func trim(s string) string { return strings.TrimFunc(s, isWhitespace) }

// hasWhitespace mirrors `value.any { it.isWhitespace() }`.
func hasWhitespace(s string) bool { return strings.IndexFunc(s, isWhitespace) >= 0 }

// utf16Len mirrors Kotlin's String.length: the number of UTF-16 code units.
func utf16Len(s string) int {
	n := 0
	for _, r := range s {
		if r > 0xFFFF {
			n += 2
		} else {
			n++
		}
	}
	return n
}

// take mirrors Kotlin's String.take(n), which counts UTF-16 code units. Where Kotlin would
// cut a surrogate pair in half and keep a lone high surrogate, this drops the whole
// character: a lone surrogate cannot be held in valid UTF-8.
func take(s string, n int) string {
	units := 0
	for i, r := range s {
		w := 1
		if r > 0xFFFF {
			w = 2
		}
		if units+w > n {
			return s[:i]
		}
		units += w
	}
	return s
}

// isDigit mirrors Kotlin's Char.isDigit (Unicode category Nd). Kotlin looks at UTF-16
// units, so a supplementary character (a surrogate pair) is never a digit.
func isDigit(r rune) bool { return r <= 0xFFFF && unicode.IsDigit(r) }

// digitValue mirrors Character.digit(c, 10): the value of a decimal digit of any script,
// -1 for anything else. Every Nd block starts at its zero and runs in tens.
func digitValue(r rune) int {
	if !isDigit(r) {
		return -1
	}
	for _, rg := range unicode.Nd.R16 {
		if rune(rg.Lo) <= r && r <= rune(rg.Hi) {
			return int(r-rune(rg.Lo)) % 10
		}
	}
	return -1
}

// toIntOrNull mirrors Kotlin's String.toIntOrNull() (radix 10) on the JVM: an optional
// leading '+' or '-', then digits of any script (Character.digit), within the 32-bit range.
func toIntOrNull(s string) (int, bool) {
	runes := []rune(s)
	if len(runes) == 0 {
		return 0, false
	}
	start, negative := 0, false
	if runes[0] < '0' {
		if len(runes) == 1 {
			return 0, false
		}
		switch runes[0] {
		case '-':
			negative = true
		case '+':
		default:
			return 0, false
		}
		start = 1
	}
	var value int64
	for _, r := range runes[start:] {
		d := digitValue(r)
		if d < 0 {
			return 0, false
		}
		value = value*10 + int64(d)
		if value > 1<<31 {
			return 0, false
		}
	}
	if negative {
		value = -value
	}
	if value > 1<<31-1 || value < -(1<<31) {
		return 0, false
	}
	return int(value), true
}

// charEqualsIgnoreCase mirrors Char.equals(other, ignoreCase = true) /
// String.regionMatches(ignoreCase = true): equal, or equal in upper case, or equal in
// the lower case of the upper case.
func charEqualsIgnoreCase(a, b rune) bool {
	if a == b {
		return true
	}
	ua, ub := unicode.ToUpper(a), unicode.ToUpper(b)
	return ua == ub || unicode.ToLower(ua) == unicode.ToLower(ub)
}

// equalsIgnoreCase mirrors Kotlin's String.equals(other, ignoreCase = true), compared
// code point by code point (as the JDK does since version 16). Go's strings.EqualFold
// uses full case folding orbits and is not used here.
func equalsIgnoreCase(a, b string) bool {
	if utf16Len(a) != utf16Len(b) {
		return false
	}
	ra, rb := []rune(a), []rune(b)
	if len(ra) != len(rb) {
		return false
	}
	for i := range ra {
		if !charEqualsIgnoreCase(ra[i], rb[i]) {
			return false
		}
	}
	return true
}

// prefixIgnoreCase mirrors String.startsWith(prefix, ignoreCase = true) and returns the
// number of bytes of s that the prefix covers.
func prefixIgnoreCase(s, prefix string) (int, bool) {
	pr := []rune(prefix)
	i := 0
	for pos, r := range s {
		if i == len(pr) {
			return pos, true
		}
		if !charEqualsIgnoreCase(r, pr[i]) {
			return 0, false
		}
		i++
	}
	if i == len(pr) {
		return len(s), true
	}
	return 0, false
}

// lowercase mirrors Kotlin's String.lowercase() (toLowerCase(Locale.ROOT)), which next to
// the simple mappings lowers U+0130 to "i\u0307" and a final capital sigma to 'ς'.
func lowercase(s string) string {
	runes := []rune(s)
	var b strings.Builder
	b.Grow(len(s))
	for i, r := range runes {
		switch {
		case r == 0x130:
			b.WriteString("i\u0307")
		case r == 0x3A3 && isFinalSigma(runes, i):
			b.WriteRune(0x3C2)
		default:
			b.WriteRune(unicode.ToLower(r))
		}
	}
	return b.String()
}

// isFinalSigma is the Final_Sigma condition of Unicode SpecialCasing: a cased letter before
// (skipping case-ignorable characters), and none after.
func isFinalSigma(runes []rune, at int) bool {
	before := false
	for i := at - 1; i >= 0; i-- {
		if isCaseIgnorable(runes[i]) {
			continue
		}
		before = isCased(runes[i])
		break
	}
	if !before {
		return false
	}
	for i := at + 1; i < len(runes); i++ {
		if isCaseIgnorable(runes[i]) {
			continue
		}
		return !isCased(runes[i])
	}
	return true
}

func isCased(r rune) bool {
	return unicode.IsUpper(r) || unicode.IsLower(r) || unicode.IsTitle(r) ||
		unicode.In(r, unicode.Other_Lowercase, unicode.Other_Uppercase)
}

func isCaseIgnorable(r rune) bool {
	switch r {
	case '\'', '.', ':', 0x5E, 0x60, 0xA8, 0xAD, 0xAF, 0xB4, 0xB7, 0xB8, 0x387, 0x5F4,
		0x2018, 0x2019, 0x2024, 0x2027, 0xFE13, 0xFE52, 0xFE55, 0xFF07, 0xFF0E, 0xFF1A:
		return true
	}
	return unicode.In(r, unicode.Mn, unicode.Me, unicode.Cf, unicode.Lm, unicode.Sk)
}
