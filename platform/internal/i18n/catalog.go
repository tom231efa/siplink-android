package i18n

import (
	"bytes"
	"embed"
	"encoding/json"
	"fmt"
	"io/fs"
	"path"
	"regexp"
	"sort"
)

// The catalogs: locales/{code}/{area}.json, flat JSON objects of key → text, the same
// files and keys in every language (design §5.5).
//
//go:embed locales
var localeFiles embed.FS

// placeholder is an argument in a text: {name}.
var placeholder = regexp.MustCompile(`\{[a-z][a-z0-9_]*\}`)

// catalogs maps a language code to its texts. A broken catalog stops the program at
// start; the tests read every catalog, so it cannot reach a build.
var catalogs = func() map[string]map[string]string {
	out := map[string]map[string]string{}
	for _, l := range Langs {
		m, _, err := loadCatalog(localeFiles, l.Code)
		if err != nil {
			panic(err)
		}
		out[l.Code] = m
	}
	return out
}()

// loadCatalog reads every file of one language. It refuses a key that appears twice,
// in one file or in two, and a value that is not a string. files maps each key to the
// file it came from.
func loadCatalog(fsys fs.FS, code string) (texts map[string]string, files map[string]string, err error) {
	names, err := fs.Glob(fsys, "locales/"+code+"/*.json")
	if err != nil {
		return nil, nil, err
	}
	if len(names) == 0 {
		return nil, nil, fmt.Errorf("i18n: no catalog for %s", code)
	}
	sort.Strings(names)
	texts, files = map[string]string{}, map[string]string{}
	for _, name := range names {
		data, err := fs.ReadFile(fsys, name)
		if err != nil {
			return nil, nil, err
		}
		pairs, err := decodeObject(data)
		if err != nil {
			return nil, nil, fmt.Errorf("i18n: %s: %w", name, err)
		}
		for _, p := range pairs {
			if prev, dup := files[p[0]]; dup {
				return nil, nil, fmt.Errorf("i18n: key %q in %s and %s", p[0], prev, name)
			}
			texts[p[0]], files[p[0]] = p[1], path.Base(name)
		}
	}
	return texts, files, nil
}

// decodeObject reads one flat JSON object of strings, keeping every key it sees, so
// that a repeated key is found instead of silently replaced.
func decodeObject(data []byte) ([][2]string, error) {
	dec := json.NewDecoder(bytes.NewReader(data))
	if tok, err := dec.Token(); err != nil || tok != json.Delim('{') {
		return nil, fmt.Errorf("not a JSON object")
	}
	var out [][2]string
	for dec.More() {
		tok, err := dec.Token()
		if err != nil {
			return nil, err
		}
		key := tok.(string) // object keys are always strings
		var text string
		if err := dec.Decode(&text); err != nil {
			return nil, fmt.Errorf("key %q: %w", key, err)
		}
		out = append(out, [2]string{key, text})
	}
	if tok, err := dec.Token(); err != nil || tok != json.Delim('}') {
		return nil, fmt.Errorf("unterminated object")
	}
	if dec.More() {
		return nil, fmt.Errorf("data after the object")
	}
	return out, nil
}

// Placeholders lists the argument names of a text, sorted, each once.
func Placeholders(text string) []string {
	seen := map[string]bool{}
	var out []string
	for _, ph := range placeholder.FindAllString(text, -1) {
		if name := ph[1 : len(ph)-1]; !seen[name] {
			seen[name] = true
			out = append(out, name)
		}
	}
	sort.Strings(out)
	return out
}

// Keys lists the keys of a language's catalog, sorted.
func Keys(l *Lang) []string {
	out := make([]string, 0, len(catalogs[l.Code]))
	for k := range catalogs[l.Code] {
		out = append(out, k)
	}
	sort.Strings(out)
	return out
}

// Has reports whether the language's catalog holds key.
func Has(l *Lang, key string) bool {
	_, ok := catalogs[l.Code][key]
	return ok
}
