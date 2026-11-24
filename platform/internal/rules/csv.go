package rules

import (
	"iter"
	"slices"
	"strings"

	"siplink/internal/i18n"
)

// CsvLineError is a line of CSV text that could not be read, and why.
// Port of Csv.LineError (android/app/src/main/java/com/sky/siplink/core/logic/Csv.kt).
type CsvLineError struct {
	Line   int      `json:"line"`
	Reason i18n.Msg `json:"reason"`
}

// CsvRow is one row of CSV text: its line number (from 1) and its fields; Fields is nil
// when a quote is left open (Kotlin: a null list).
type CsvRow struct {
	Line   int
	Fields []string
}

// CsvRows returns the rows of a text. Blank lines and lines starting with `#` are skipped,
// as is a byte order mark.
// Port of Csv.rows (android/app/src/main/java/com/sky/siplink/core/logic/Csv.kt).
func CsvRows(text string) []CsvRow { return slices.Collect(CsvRowSeq(text)) }

// CsvRowSeq yields the rows of CsvRows one at a time, so that a reader can stop early.
func CsvRowSeq(text string) iter.Seq[CsvRow] {
	return func(yield func(CsvRow) bool) {
		for index, raw := range lineSequence(text) {
			line := trim(strings.TrimPrefix(trim(raw), "\uFEFF"))
			if line == "" || strings.HasPrefix(line, "#") {
				continue
			}
			fields, ok := CsvSplit(line)
			if !ok {
				fields = nil
			}
			if !yield(CsvRow{Line: index + 1, Fields: fields}) {
				return
			}
		}
	}
}

// CsvRowsOver reports whether text has more than max rows, reading no row past that.
func CsvRowsOver(text string, max int) bool {
	n := 0
	for range CsvRowSeq(text) {
		if n++; n > max {
			return true
		}
	}
	return false
}

// MaxShownLineErrors is how many line errors of an import a page lists; the others are
// only counted.
const MaxShownLineErrors = 200

// lineSequence mirrors Kotlin's CharSequence.lineSequence(): lines end at \r\n, \n or \r.
// It yields each line with its index.
func lineSequence(text string) iter.Seq2[int, string] {
	return func(yield func(int, string) bool) {
		index, start := 0, 0
		for i := 0; i < len(text); i++ {
			if text[i] != '\n' && text[i] != '\r' {
				continue
			}
			if !yield(index, text[start:i]) {
				return
			}
			if text[i] == '\r' && i+1 < len(text) && text[i+1] == '\n' {
				i++
			}
			index, start = index+1, i+1
		}
		yield(index, text[start:])
	}
}

// CsvSplit splits one CSV line; ok is false when a quoted field is not closed.
// Port of Csv.split (android/app/src/main/java/com/sky/siplink/core/logic/Csv.kt).
func CsvSplit(line string) (fields []string, ok bool) {
	fields = []string{}
	var current strings.Builder
	quoted, wasQuoted := false, false
	runes := []rune(line)
	finish := func() {
		if wasQuoted {
			fields = append(fields, current.String())
		} else {
			fields = append(fields, trim(current.String()))
		}
		current.Reset()
	}
	for i := 0; i < len(runes); i++ {
		c := runes[i]
		switch {
		case quoted && c == '"' && i+1 < len(runes) && runes[i+1] == '"':
			current.WriteRune('"')
			i++
		case c == '"':
			quoted = !quoted
			wasQuoted = true
		case c == ',' && !quoted:
			finish()
			wasQuoted = false
		default:
			current.WriteRune(c)
		}
	}
	if quoted {
		return nil, false
	}
	finish()
	return fields, true
}

// CsvJoin is the opposite of CsvSplit: it quotes a field when it holds a comma, a quote or
// outer spaces.
// Port of Csv.join (android/app/src/main/java/com/sky/siplink/core/logic/Csv.kt).
func CsvJoin(fields []string) string {
	out := make([]string, len(fields))
	for i, field := range fields {
		if strings.ContainsAny(field, ",\"") || field != trim(field) {
			out[i] = "\"" + strings.ReplaceAll(field, "\"", "\"\"") + "\""
		} else {
			out[i] = field
		}
	}
	return strings.Join(out, ",")
}
