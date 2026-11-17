package books

import (
	"bytes"
	"encoding/csv"
	"errors"
	"fmt"
	"io"
	"strings"
	"unicode/utf8"

	"golang.org/x/text/encoding/simplifiedchinese"

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

// ExportHeader is the header line of exported files.
var ExportHeader = []string{"姓名", "办公电话", "手机", "单位", "部门", "职务", "邮箱", "姓", "名"}

// LineError is a problem of one line of the file.
type LineError struct {
	Line    int
	Message string
}

// Parsed is the result of reading a CSV file.
type Parsed struct {
	Entries  []*store.Entry
	Lines    []int // file line of each entry
	Errors   []LineError
	Warnings []string
	Encoding string // "UTF-8" or "GB18030"
}

// ErrNoName is returned when the header has no name column.
var ErrNoName = errors.New("文件第一行必须是表头，并包含「姓名」或 name 列")

// Decode returns the text of a file: UTF-8 (with or without BOM), else GB18030.
func Decode(data []byte) (string, string, error) {
	data = bytes.TrimPrefix(data, []byte("\xef\xbb\xbf"))
	if utf8.Valid(data) {
		return string(data), "UTF-8", nil
	}
	out, err := simplifiedchinese.GB18030.NewDecoder().Bytes(data)
	if err != nil {
		return "", "", fmt.Errorf("文件既不是 UTF-8 也不是 GB18030 编码")
	}
	return string(out), "GB18030", nil
}

// ParseCSV reads people from a CSV file with a header line.
func ParseCSV(data []byte) (*Parsed, error) {
	if len(data) > MaxCSVBytes {
		return nil, fmt.Errorf("文件超过 %d MB", MaxCSVBytes>>20)
	}
	text, enc, err := Decode(data)
	if err != nil {
		return nil, err
	}
	r := csv.NewReader(strings.NewReader(text))
	r.FieldsPerRecord = -1
	header, err := r.Read()
	if errors.Is(err, io.EOF) {
		return nil, errors.New("文件是空的")
	}
	if err != nil {
		return nil, fmt.Errorf("表头无法读取：%v", err)
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
				p.Warnings = append(p.Warnings, fmt.Sprintf("忽略未知的列「%s」", h))
			}
		case index[key] != 0:
			return nil, fmt.Errorf("列「%s」出现了两次", h)
		default:
			index[key] = i + 1 // 0 means absent
		}
	}
	if index["name"] == 0 {
		return nil, ErrNoName
	}
	for {
		record, err := r.Read()
		if errors.Is(err, io.EOF) {
			break
		}
		if err != nil {
			line := 0
			var pe *csv.ParseError
			if errors.As(err, &pe) {
				line = pe.StartLine
			}
			p.Errors = append(p.Errors, LineError{line, "格式错误（引号不匹配？）"})
			continue
		}
		line, _ := r.FieldPos(0)
		get := func(key string) string {
			if i := index[key]; i > 0 && i <= len(record) {
				return record[i-1]
			}
			return ""
		}
		if strings.TrimSpace(strings.Join(record, "")) == "" {
			continue // blank line
		}
		e := &store.Entry{
			Name: unescapeCell(get("name")), Phones: SplitNumbers(get("phone")), Mobiles: SplitNumbers(get("mobile")),
			Organization: unescapeCell(get("organization")), Department: unescapeCell(get("department")),
			Title: unescapeCell(get("title")), Email: unescapeCell(get("email")),
			Surname: unescapeCell(get("surname")), GivenName: unescapeCell(get("given_name")),
		}
		Normalize(e)
		if problems := Validate(e); len(problems) > 0 {
			p.Errors = append(p.Errors, LineError{line, strings.Join(problems, "；")})
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

// WriteCSV writes entries as UTF-8 with BOM (opens correctly in Excel), numbers joined with ';'.
func WriteCSV(w io.Writer, entries []*store.Entry) error {
	if _, err := w.Write([]byte("\xef\xbb\xbf")); err != nil {
		return err
	}
	cw := csv.NewWriter(w)
	cw.UseCRLF = true
	if err := cw.Write(ExportHeader); err != nil {
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
