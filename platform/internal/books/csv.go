package books

import (
	"bytes"
	"encoding/csv"
	"errors"
	"io"
	"strings"
	"unicode/utf8"

	"golang.org/x/text/encoding/simplifiedchinese"

	"siplink/internal/i18n"
	"siplink/internal/rules"
	"siplink/internal/store"
)

// MaxCSVBytes is the largest import file accepted (§4.7).
const MaxCSVBytes = 10 << 20

// Column keys and their accepted header names (Chinese and English).
var columns = []struct {
	key   string
	names []string
}{
	{"name", []string{"姓名", "name"}},
	{"phone", []string{"办公电话", "phone"}},
	{"mobile", []string{"手机", "mobile"}},
	{"organization", []string{"单位", "organization"}},
	{"department", []string{"部门", "department"}},
	{"title", []string{"职务", "title"}},
	{"email", []string{"邮箱", "email"}},
	{"surname", []string{"姓", "surname"}},
	{"given_name", []string{"名", "given_name"}},
}

// ExportHeader is the header line of exported files: the Chinese column names, or the
// English ones (§5.5).
func ExportHeader(chinese bool) []string {
	out := make([]string, len(columns))
	for i, c := range columns {
		if chinese {
			out[i] = c.names[0]
		} else {
			out[i] = c.names[1]
		}
	}
	return out
}

// LineError is what is wrong with one line of the file.
type LineError struct {
	Line     int
	Problems []i18n.Msg
}

// Parsed is the result of reading a CSV file. Errors and Warnings keep the first
// rules.MaxShownLineErrors; MoreErrors and MoreWarnings count the others.
type Parsed struct {
	Entries      []*store.Entry
	Lines        []int // file line of each entry
	Errors       []LineError
	Warnings     []i18n.Msg
	MoreErrors   int
	MoreWarnings int
	Encoding     string // "UTF-8" or "GB18030"
}

func (p *Parsed) addError(e LineError) {
	if len(p.Errors) < rules.MaxShownLineErrors {
		p.Errors = append(p.Errors, e)
	} else {
		p.MoreErrors++
	}
}

func (p *Parsed) addWarning(m i18n.Msg) {
	if len(p.Warnings) < rules.MaxShownLineErrors {
		p.Warnings = append(p.Warnings, m)
	} else {
		p.MoreWarnings++
	}
}

// ErrNoName is returned when the header has no name column.
var ErrNoName = i18n.E("books.csv.no_name")

// Decode returns the text of a file: UTF-8 (with or without BOM), else GB18030.
func Decode(data []byte) (string, string, error) {
	data = bytes.TrimPrefix(data, []byte("\xef\xbb\xbf"))
	if utf8.Valid(data) {
		return string(data), "UTF-8", nil
	}
	out, err := simplifiedchinese.GB18030.NewDecoder().Bytes(data)
	if err != nil {
		return "", "", i18n.E("books.csv.encoding")
	}
	return string(out), "GB18030", nil
}

// ParseCSV reads people from a CSV file with a header line. A file with more than
// maxEntries lines of people (good or bad) is refused once that line is read.
func ParseCSV(data []byte, maxEntries int) (*Parsed, error) {
	if len(data) > MaxCSVBytes {
		return nil, i18n.E("books.csv.too_big", "max", MaxCSVBytes>>20)
	}
	text, enc, err := Decode(data)
	if err != nil {
		return nil, err
	}
	r := csv.NewReader(strings.NewReader(text))
	r.FieldsPerRecord = -1
	header, err := r.Read()
	if errors.Is(err, io.EOF) {
		return nil, i18n.E("books.csv.empty")
	}
	if err != nil {
		return nil, i18n.E("books.csv.header", "err", err.Error())
	}
	index := map[string]int{}
	p := &Parsed{Encoding: enc}
	for i, h := range header {
		h = strings.TrimSpace(h)
		key := ""
		for _, c := range columns {
			for _, n := range c.names {
				if strings.EqualFold(h, n) {
					key = c.key
				}
			}
		}
		switch {
		case key == "":
			if h != "" {
				p.addWarning(i18n.M("books.csv.unknown_column", "name", h))
			}
		case index[key] != 0:
			return nil, i18n.E("books.csv.duplicate_column", "name", h)
		default:
			index[key] = i + 1 // 0 means absent
		}
	}
	if index["name"] == 0 {
		return nil, ErrNoName
	}
	for lines := 0; ; {
		record, err := r.Read()
		if errors.Is(err, io.EOF) {
			break
		}
		if err == nil && strings.TrimSpace(strings.Join(record, "")) == "" {
			continue // blank line
		}
		if lines++; lines > maxEntries {
			return nil, i18n.E("dirs.import.too_many", "max", maxEntries)
		}
		if err != nil {
			line := 0
			var pe *csv.ParseError
			if errors.As(err, &pe) {
				line = pe.StartLine
			}
			p.addError(LineError{line, []i18n.Msg{i18n.M("books.csv.bad_line")}})
			continue
		}
		line, _ := r.FieldPos(0)
		get := func(key string) string {
			if i := index[key]; i > 0 && i <= len(record) {
				return record[i-1]
			}
			return ""
		}
		e := &store.Entry{
			Name: unescapeCell(get("name")), Phones: SplitNumbers(get("phone")), Mobiles: SplitNumbers(get("mobile")),
			Organization: unescapeCell(get("organization")), Department: unescapeCell(get("department")),
			Title: unescapeCell(get("title")), Email: unescapeCell(get("email")),
			Surname: unescapeCell(get("surname")), GivenName: unescapeCell(get("given_name")),
		}
		Normalize(e)
		if problems := Validate(e); len(problems) > 0 {
			p.addError(LineError{line, problems})
			continue
		}
		p.Entries = append(p.Entries, e)
		p.Lines = append(p.Lines, line)
	}
	return p, nil
}

// escapeCell protects spreadsheet users from text cells that would start a formula.
func escapeCell(s string) string {
	if s != "" && strings.ContainsRune("=+-@\t\r", rune(s[0])) {
		return "'" + s
	}
	return s
}

// unescapeCell removes the protection added by escapeCell.
func unescapeCell(s string) string {
	if len(s) > 1 && s[0] == '\'' && strings.ContainsRune("=+-@\t\r", rune(s[1])) {
		return s[1:]
	}
	return s
}

// WriteCSV writes entries as UTF-8 with BOM (opens correctly in Excel), numbers joined
// with ';', under the Chinese or the English column names.
func WriteCSV(w io.Writer, entries []*store.Entry, chinese bool) error {
	if _, err := w.Write([]byte("\xef\xbb\xbf")); err != nil {
		return err
	}
	cw := csv.NewWriter(w)
	cw.UseCRLF = true
	if err := cw.Write(ExportHeader(chinese)); err != nil {
		return err
	}
	for _, e := range entries {
		if err := cw.Write([]string{escapeCell(e.Name), strings.Join(e.Phones, ";"), strings.Join(e.Mobiles, ";"),
			escapeCell(e.Organization), escapeCell(e.Department), escapeCell(e.Title), escapeCell(e.Email),
			escapeCell(e.Surname), escapeCell(e.GivenName)}); err != nil {
			return err
		}
	}
	cw.Flush()
	return cw.Error()
}
