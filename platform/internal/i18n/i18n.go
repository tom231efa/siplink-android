// Package i18n holds the console's languages, their embedded catalogs, the translator
// of one language and Msg, the text that packages hand to the web layer and the CLI
// instead of a sentence of their own (design §5.5).
package i18n

import (
	"strings"

	"golang.org/x/text/language"
)

// Lang is one language of the console.
type Lang struct {
	// Code is the BCP 47 tag: the catalog's directory, the value stored for an
	// administrator and the page's lang attribute.
	Code string
	// Name is the language's name in itself, as the pickers show it.
	Name string
	// RTL is set for a language written right to left.
	RTL bool
	tag language.Tag
}

// Dir is the value of the page's dir attribute.
func (l *Lang) Dir() string {
	if l.RTL {
		return "rtl"
	}
	return "ltr"
}

// Langs are the console's languages in the order of the pickers.
var Langs = []*Lang{
	{Code: "zh-Hans", Name: "中文（简体）", tag: language.SimplifiedChinese},
	{Code: "en", Name: "English", tag: language.English},
	{Code: "ja", Name: "日本語", tag: language.Japanese},
	{Code: "ar", Name: "العربية", RTL: true, tag: language.Arabic},
	{Code: "fr", Name: "Français", tag: language.French},
	{Code: "zh-Hant", Name: "中文（繁體）", tag: language.TraditionalChinese},
	{Code: "ru", Name: "Русский", tag: language.Russian},
	{Code: "es", Name: "Español", tag: language.Spanish},
}

// Source is the language the catalogs are translated from (the console's texts as
// they were before), English the one when nothing else fits.
var (
	Source  = ByCode("zh-Hans")
	English = ByCode("en")
)

// ByCode returns the language with exactly this code, nil when there is none.
func ByCode(code string) *Lang {
	for _, l := range Langs {
		if l.Code == code {
			return l
		}
	}
	return nil
}

// matchLangs are the languages in the matcher's order: English first, so that it is
// what the matcher falls back to.
var matchLangs = func() []*Lang {
	out := []*Lang{English}
	for _, l := range Langs {
		if l != English {
			out = append(out, l)
		}
	}
	return out
}()

var matcher = func() language.Matcher {
	tags := make([]language.Tag, len(matchLangs))
	for i, l := range matchLangs {
		tags[i] = l.tag
	}
	return language.NewMatcher(tags)
}()

// matchTags returns the language closest to the wanted tags, English when none is
// close enough to be understood.
func matchTags(want []language.Tag) *Lang {
	if len(want) == 0 {
		return English
	}
	_, index, conf := matcher.Match(want...)
	if conf == language.No || index < 0 || index >= len(matchLangs) {
		return English
	}
	return matchLangs[index]
}

// Match picks the language for an Accept-Language header; English when the header is
// empty, unreadable or names none of the eight.
func Match(acceptLanguage string) *Lang {
	tags, _, err := language.ParseAcceptLanguage(acceptLanguage)
	if err != nil {
		return English
	}
	return matchTags(tags)
}

// FromEnv picks the language of the POSIX locale (LC_ALL, else LC_MESSAGES, else
// LANG), language[_territory][.codeset][@modifier] such as "zh_CN.UTF-8"; English when
// it is unset, "C", "POSIX", names none of the eight, or names a codeset other than
// UTF-8 (the output is UTF-8, which such a terminal would garble).
func FromEnv(getenv func(string) string) *Lang {
	v := ""
	for _, name := range []string{"LC_ALL", "LC_MESSAGES", "LANG"} {
		if v = getenv(name); v != "" {
			break
		}
	}
	v, _, _ = strings.Cut(v, "@")
	v, codeset, named := strings.Cut(v, ".")
	if named && strings.ReplaceAll(strings.ToLower(codeset), "-", "") != "utf8" {
		return English
	}
	if v == "" || v == "C" || v == "POSIX" {
		return English
	}
	tag, err := language.Parse(strings.ReplaceAll(v, "_", "-"))
	if err != nil {
		return English
	}
	return matchTags([]language.Tag{tag})
}
