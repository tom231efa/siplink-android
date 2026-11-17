package rules

import "testing"

// Not ported from Kotlin: these pin the Kotlin/JVM string semantics that text.go reproduces.

func TestKotlinWhitespace(t *testing.T) {
	for _, r := range []rune{' ', '\t', '\v', 0x1C, 0x1F, 0xA0, 0x3000, 0x2028, 0x202F} {
		if !isWhitespace(r) {
			t.Errorf("%U is whitespace in Kotlin", r)
		}
	}
	for _, r := range []rune{0x85, 0xFEFF, 'a', 0x200B} {
		if isWhitespace(r) {
			t.Errorf("%U is no whitespace in Kotlin", r)
		}
	}
	if trim("\u3000 前台\u00A0") != "前台" {
		t.Error("trim")
	}
}

func TestKotlinToIntOrNull(t *testing.T) {
	cases := []struct {
		in   string
		want int
		ok   bool
	}{
		{"5060", 5060, true}, {"+5060", 5060, true}, {"-1", -1, true}, {"", 0, false}, {"+", 0, false},
		{"５０９０", 5090, true}, {"٥٠٦٠", 5060, true}, {"2147483647", 2147483647, true},
		{"2147483648", 0, false}, {"-2147483648", -2147483648, true}, {"50a", 0, false}, {" 5", 0, false},
		{"\U0001D7CE", 0, false},
	}
	for _, c := range cases {
		if got, ok := toIntOrNull(c.in); got != c.want || ok != c.ok {
			t.Errorf("%q: got %d %v", c.in, got, ok)
		}
	}
}

func TestKotlinCase(t *testing.T) {
	if !equalsIgnoreCase("ldapſ", "LDAPS") || equalsIgnoreCase("ab", "abc") || !equalsIgnoreCase("Front", "fRONT") {
		t.Error("equalsIgnoreCase")
	}
	if n, ok := prefixIgnoreCase("ldapſ://host", "ldaps://"); !ok || n != 9 {
		t.Error("prefixIgnoreCase", n, ok)
	}
	if lowercase("İ") != "i\u0307" || lowercase("ΑΣ") != "ας" || lowercase("ΣΑ") != "σα" || lowercase("PBX.Example") != "pbx.example" {
		t.Error("lowercase")
	}
	if take("ab😀c", 3) != "ab" || take("ab😀c", 4) != "ab😀" || utf16Len("a😀") != 3 {
		t.Error("take / utf16Len")
	}
}
