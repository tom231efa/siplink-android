package i18n

import (
	"regexp"
	"slices"
	"strings"
	"testing"
	"time"
)

var keyPattern = regexp.MustCompile(`^[a-z][a-z0-9_]*(\.[A-Za-z0-9_]+)+$`)

// TestCatalogsComplete: every language has the files and keys of the source, each key
// in the same file, with the same arguments and a text that is not empty (§8.1).
func TestCatalogsComplete(t *testing.T) {
	src, srcFiles, err := loadCatalog(localeFiles, Source.Code)
	if err != nil {
		t.Fatal(err)
	}
	for key := range src {
		if !keyPattern.MatchString(key) {
			t.Errorf("key %q: not lower case and dot-separated", key)
		}
	}
	for _, l := range Langs {
		texts, files, err := loadCatalog(localeFiles, l.Code)
		if err != nil {
			t.Errorf("%s: %v", l.Code, err)
			continue
		}
		for key, text := range src {
			got, ok := texts[key]
			switch {
			case !ok:
				t.Errorf("%s: key %s missing", l.Code, key)
				continue
			case files[key] != srcFiles[key]:
				t.Errorf("%s: key %s in %s, not %s", l.Code, key, files[key], srcFiles[key])
			case strings.TrimSpace(got) == "":
				t.Errorf("%s: key %s is empty", l.Code, key)
			}
			if a, b := Placeholders(text), Placeholders(got); !slices.Equal(a, b) {
				t.Errorf("%s: key %s has arguments %v, the source %v", l.Code, key, b, a)
			}
		}
		for key := range texts {
			if _, ok := src[key]; !ok {
				t.Errorf("%s: key %s is not in the source", l.Code, key)
			}
		}
	}
}

// TestFormats: the date patterns hold the tokens and only separators besides, so that
// dates are written with ASCII digits in every language.
func TestFormats(t *testing.T) {
	tokens := strings.NewReplacer("YYYY", "", "MM", "", "DD", "", "HH", "", "mm", "")
	for _, l := range Langs {
		tr := For(l)
		for kind, need := range map[string][]string{"date": {"YYYY", "MM", "DD"}, "datetime": {"YYYY", "MM", "DD", "HH", "mm"}} {
			p := tr.Raw("format." + kind)
			for _, tok := range need {
				if !strings.Contains(p, tok) {
					t.Errorf("%s format.%s %q lacks %s", l.Code, kind, p, tok)
				}
			}
			if rest := tokens.Replace(p); strings.ContainsFunc(rest, func(r rune) bool { return r >= '0' && r <= '9' || r >= 'A' && r <= 'z' }) {
				t.Errorf("%s format.%s %q holds more than separators", l.Code, kind, p)
			}
		}
		got := tr.Date(time.Date(2026, 11, 19, 8, 5, 0, 0, time.UTC), false)
		if !strings.Contains(got, "2026") || !strings.Contains(got, "08:05") || strings.ContainsAny(got, "٠١٢٣٤٥٦٧٨٩") {
			t.Errorf("%s: %q", l.Code, got)
		}
		if tr.Raw("format.list_sep") == "" {
			t.Errorf("%s: no list separator", l.Code)
		}
	}
}

func TestMatch(t *testing.T) {
	for header, want := range map[string]string{
		"":                       "en",
		"de-DE,de;q=0.9":         "en",
		"zh-CN,zh;q=0.9,en;q=.8": "zh-Hans",
		"zh":                     "zh-Hans",
		"zh-SG":                  "zh-Hans",
		"zh-TW":                  "zh-Hant",
		"zh-HK,en;q=0.5":         "zh-Hant",
		"zh-MO":                  "zh-Hant",
		"ja-JP":                  "ja",
		"ar-EG,ar;q=0.9":         "ar",
		"fr-CA":                  "fr",
		"ru":                     "ru",
		"es-419,es;q=0.9":        "es",
		"en-GB":                  "en",
		"de,fr;q=0.5":            "fr",
		"not a header ;;;":       "en",
	} {
		if got := Match(header).Code; got != want {
			t.Errorf("Match(%q) = %s, want %s", header, got, want)
		}
	}
	for env, want := range map[[3]string]string{
		{"", "", ""}:                       "en",
		{"", "", "C"}:                      "en",
		{"", "", "POSIX"}:                  "en",
		{"", "", "C.UTF-8"}:                "en",
		{"", "", "zh_CN.UTF-8"}:            "zh-Hans",
		{"", "", "zh_SG.GB18030"}:          "en", // a codeset other than UTF-8: English (§5.5)
		{"", "", "zh_CN.gb2312"}:           "en",
		{"", "", "ru_RU.KOI8-R"}:           "en",
		{"", "", "ja_JP.eucJP"}:            "en",
		{"", "", "fr_FR.ISO-8859-15@euro"}: "en",
		{"", "", "ar_SA.utf8"}:             "ar",
		{"", "", "fr_FR.UTF-8@euro"}:       "fr",
		{"", "", "zh_CN"}:                  "zh-Hans",
		{"", "", "zh_TW.Utf-8"}:            "zh-Hant",
		{"", "", "zh_TW.UTF-8"}:            "zh-Hant",
		{"", "", "zh_HK"}:                  "zh-Hant",
		{"", "", "es_ES@euro"}:             "es",
		{"", "", "ar_SA.UTF-8"}:            "ar",
		{"", "", "de_DE.UTF-8"}:            "en",
		{"", "", "nonsense-value-!!"}:      "en",
		{"", "ja_JP.UTF-8", "zh_CN.UTF-8"}: "ja",
		{"ru_RU.UTF-8", "ja_JP", "en_US"}:  "ru",
		{"POSIX", "zh_TW.UTF-8", "zh_CN"}:  "en",
	} {
		vals := map[string]string{"LC_ALL": env[0], "LC_MESSAGES": env[1], "LANG": env[2]}
		if got := FromEnv(func(k string) string { return vals[k] }).Code; got != want {
			t.Errorf("FromEnv(%v) = %s, want %s", env, got, want)
		}
	}
}

// TestArguments: strings as they are (isolated only in a right-to-left language),
// numbers in ASCII digits, nested messages and lists in the same language, a missing
// argument and a missing key marked.
func TestArguments(t *testing.T) {
	zh, en, ar := For(Source), For(English), For(ByCode("ar"))
	nested := M("provision.account_problem", "name", "Main", "problem", M("rules.account.name_too_long", "max", 30))
	if got := zh.Msg(nested); got != "账号「Main」：账号名称最多 30 个字符" {
		t.Errorf("zh: %q", got)
	}
	if got := en.Msg(nested); got != "Account “Main”: Account name: at most 30 characters" {
		t.Errorf("en: %q", got)
	}
	if got := ar.Msg(nested); !strings.Contains(got, "⁨Main⁩") || strings.Contains(got, "⁨30⁩") {
		t.Errorf("ar: %q", got)
	}
	if got := zh.T("catalog.key.line_unknown", "line", "x", "names", []string{"a", "b"}); !strings.Contains(got, "a、b") {
		t.Errorf("list: %q", got)
	}
	if got := en.T("catalog.key.line_unknown", "line", "x", "names", []string{"a", "b"}); !strings.Contains(got, "a, b") {
		t.Errorf("list: %q", got)
	}
	if got := en.T("provision.account_problem", "name", "x"); !strings.Contains(got, "⟦problem⟧") {
		t.Errorf("missing argument: %q", got)
	}
	if got := en.T("no.such.key"); got != "⟦no.such.key⟧" {
		t.Errorf("missing key: %q", got)
	}
	if got := M("no.such.key").String(); got != "⟦no.such.key⟧" {
		t.Errorf("printed message: %q", got)
	}
	// A value's own embedding, override and isolate characters are removed in every
	// language, so it cannot end the isolate early or reorder the text around it.
	evil := "x\u2069\u202eevil\u202a\u2066"
	if got := ar.T("provision.account_problem", "name", evil, "problem", "p"); strings.Count(got, "\u2069") != strings.Count(got, "\u2068") || strings.ContainsAny(got, "\u202a\u202e\u2066") || !strings.Contains(got, "\u2068xevil\u2069") {
		t.Errorf("ar bidi: %q", got)
	}
	if got := en.T("provision.account_problem", "name", evil, "problem", "p"); strings.ContainsAny(got, bidiControls) || !strings.Contains(got, "xevil") {
		t.Errorf("en bidi: %q", got)
	}
	if !HasBidiControl("a\u202eb") || !HasBidiControl("\u2067") || HasBidiControl("abc 中文 عربي") {
		t.Error("HasBidiControl")
	}
	// A list of names that may grow long shows five and then how many more.
	names := Names{"a", "b", "c", "d", "e", "f", "g"}
	if got := en.T("dirs.in_use", "users", names); !strings.HasSuffix(got, "a, b, c, d, e and 2 more") {
		t.Errorf("en names: %q", got)
	}
	if got := zh.T("dirs.in_use", "users", names[:5]); !strings.HasSuffix(got, "a、b、c、d、e") {
		t.Errorf("zh five names: %q", got)
	}
	if got := ar.T("dirs.in_use", "users", names); strings.Count(got, "\u2068") != 5 || !strings.Contains(got, "2") {
		t.Errorf("ar names: %q", got)
	}
	err := Wrap(E("mail.no_starttls"), "mail.send_failed")
	if m, ok := AsMsg(err); !ok || m.Key != "mail.send_failed" || zh.Err(err) != "发送失败: 服务器不支持 STARTTLS" {
		t.Errorf("wrapped: %v %q", m, zh.Err(err))
	}
	if err.Error() != "Sending failed: The server does not support STARTTLS" {
		t.Errorf("English error text: %q", err.Error())
	}
}
