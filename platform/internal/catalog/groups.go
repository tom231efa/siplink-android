package catalog

import (
	"slices"
	"strings"
	"unicode/utf8"

	"siplink/internal/i18n"
	"siplink/internal/store"
)

// Limits of groups and tags (design §2.6).
const (
	MaxGroupName       = 40
	MaxTagName         = 24
	MaxGroupsPerRegion = 100
	MaxTags            = 500
	MaxTagsPerClient   = 10
)

// TagColors are the colours a tag may have, the first the default: muted tints that read
// in light and dark mode (the stylesheet's .chip.c-{color}).
var TagColors = []string{"gray", "sand", "clay", "olive", "sage", "teal", "steel"}

// TagColor is the colour as stored, or the default for one the palette does not have.
func TagColor(c string) string {
	if slices.Contains(TagColors, c) {
		return c
	}
	return TagColors[0]
}

// TagSeparators separate several tags in one CSV cell; a tag's name cannot contain them.
const TagSeparators = ";；"

// GroupNameProblem checks the name of a client group.
func GroupNameProblem(name string) error { return labelProblem(name, MaxGroupName, "") }

// TagNameProblem checks the name of a tag.
func TagNameProblem(name string) error { return labelProblem(name, MaxTagName, TagSeparators) }

func labelProblem(name string, max int, banned string) error {
	switch {
	case name == "":
		return i18n.E("catalog.name_required")
	case utf8.RuneCountInString(name) > max:
		return i18n.E("catalog.name_too_long", "max", max)
	case strings.ContainsAny(name, "\r\n\t"):
		return i18n.E("catalog.client.name_control")
	case i18n.HasBidiControl(name):
		return i18n.E("names.bidi_control")
	case banned != "" && strings.ContainsAny(name, banned):
		return i18n.E("catalog.tag.separator")
	}
	return nil
}

// SplitTags reads the tags of a CSV cell: names separated by ";" or "；", blanks dropped,
// each name once (ignoring case, as tags are unique).
func SplitTags(cell string) []string {
	var out, keys []string
	for _, part := range strings.FieldsFunc(cell, func(r rune) bool { return strings.ContainsRune(TagSeparators, r) }) {
		name, key := strings.TrimSpace(part), store.NameKey(part)
		if name != "" && !slices.Contains(keys, key) {
			out, keys = append(out, name), append(keys, key)
		}
	}
	return out
}
