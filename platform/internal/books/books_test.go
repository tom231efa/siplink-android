package books

import (
	"bytes"
	"encoding/base64"
	"fmt"
	"siplink/internal/i18n"
	"strings"
	"testing"

	"golang.org/x/text/encoding/simplifiedchinese"

	"siplink/internal/store"
)

func TestParseUTF8AndBOM(t *testing.T) {
	body := "姓名,办公电话,手机,单位,部门,职务,邮箱,姓,名\n" +
		"张伟,1001;021-5555 0001,138 0000 1001,示例公司,研发部,工程师,zhang@example.com,张,伟\n" +
		"\n" +
		"\"李, 娜\",1002,,,,,,,\n"
	for _, data := range [][]byte{[]byte(body), append([]byte("\xef\xbb\xbf"), body...)} {
		p, err := ParseCSV(data, 10000)
		if err != nil {
			t.Fatal(err)
		}
		if len(p.Errors) != 0 || len(p.Entries) != 2 || p.Encoding != "UTF-8" {
			t.Fatalf("parsed %+v", p)
		}
		e := p.Entries[0]
		if e.Name != "张伟" || len(e.Phones) != 2 || e.Phones[1] != "021-5555 0001" || e.Mobiles[0] != "138 0000 1001" ||
			e.Organization != "示例公司" || e.Surname != "张" || e.GivenName != "伟" || e.Email != "zhang@example.com" {
			t.Errorf("entry %+v", e)
		}
		if p.Entries[1].Name != "李, 娜" || p.Lines[1] != 4 {
			t.Errorf("quoted name %q line %d", p.Entries[1].Name, p.Lines[1])
		}
	}
}

func TestParseGB18030AndEnglishHeader(t *testing.T) {
	text := "name,mobile,Extra\n王芳,13900001002,x\n"
	gb, err := simplifiedchinese.GB18030.NewEncoder().Bytes([]byte(text))
	if err != nil {
		t.Fatal(err)
	}
	p, err := ParseCSV(gb, 10000)
	if err != nil {
		t.Fatal(err)
	}
	if p.Encoding != "GB18030" || len(p.Entries) != 1 || p.Entries[0].Name != "王芳" || p.Entries[0].Mobiles[0] != "13900001002" {
		t.Fatalf("GB18030: %+v", p)
	}
	if len(p.Warnings) != 1 || !strings.Contains(i18n.For(i18n.Source).Msg(p.Warnings[0]), "Extra") {
		t.Errorf("unknown column warning: %v", p.Warnings)
	}
}

func TestParseErrors(t *testing.T) {
	if _, err := ParseCSV([]byte("电话\n123\n"), 10000); err != ErrNoName {
		t.Errorf("no name column: %v", err)
	}
	if _, err := ParseCSV(nil, 10000); err == nil {
		t.Error("empty file accepted")
	}
	if _, err := ParseCSV(bytes.Repeat([]byte("x"), MaxCSVBytes+1), 10000); err == nil {
		t.Error("oversized file accepted")
	}
	body := "姓名,办公电话,手机\n" +
		",1001,\n" + // 2: no name
		"甲,1;2;3;4;5,\n" + // 3: too many numbers
		"乙,abc,\n" + // 4: letters
		"丙,," + strings.Repeat("1", 33) + "\n" + // 5: too long
		strings.Repeat("丁", 65) + ",,\n" + // 6: name too long
		"戊,1001,13800001001\n" // 7: fine
	p, err := ParseCSV([]byte(body), 10000)
	if err != nil {
		t.Fatal(err)
	}
	if len(p.Entries) != 1 || len(p.Errors) != 5 {
		t.Fatalf("entries %d errors %v", len(p.Entries), p.Errors)
	}
	for i, line := range []int{2, 3, 4, 5, 6} {
		if p.Errors[i].Line != line {
			t.Errorf("error %d at line %d, want %d (%s)", i, p.Errors[i].Line, line, p.Errors[i].Problems)
		}
	}
	p, _ = ParseCSV([]byte("姓名\n\"broken\n"), 10000)
	if len(p.Errors) != 1 || len(p.Entries) != 0 {
		t.Errorf("broken quote: %+v", p)
	}
}

func TestParseManyLines(t *testing.T) {
	var b strings.Builder
	b.WriteString("姓名,手机\n")
	for i := 0; i < 10001; i++ {
		fmt.Fprintf(&b, "人员%05d,1380000%04d\n", i, i%10000)
	}
	p, err := ParseCSV([]byte(b.String()), 10001)
	if err != nil || len(p.Entries) != 10001 || len(p.Errors) != 0 {
		t.Fatalf("10,001 lines: %d entries, %v", len(p.Entries), err)
	}
}

func TestCSVRoundTrip(t *testing.T) {
	in := []*store.Entry{
		{Name: "=SUM(A1)", Phones: []string{"+86 21 5555 0001", "1001"}, Mobiles: []string{"13800001001"}, Organization: "@corp",
			Department: "研发", Title: "-", Email: "a@example.com", Surname: "张", GivenName: "伟"},
		{Name: "李娜"},
	}
	var buf bytes.Buffer
	if err := WriteCSV(&buf, in, true); err != nil {
		t.Fatal(err)
	}
	if !bytes.HasPrefix(buf.Bytes(), []byte("\xef\xbb\xbf姓名,办公电话,手机")) {
		t.Errorf("no BOM or header: %q", buf.String()[:20])
	}
	if !strings.Contains(buf.String(), "'=SUM(A1)") {
		t.Error("formula cell not protected")
	}
	p, err := ParseCSV(buf.Bytes(), 10000)
	if err != nil || len(p.Entries) != 2 {
		t.Fatalf("re-read: %v %+v", err, p)
	}
	got := p.Entries[0]
	if got.Name != "=SUM(A1)" || got.Organization != "@corp" || got.Title != "-" || strings.Join(got.Phones, ";") != "+86 21 5555 0001;1001" {
		t.Errorf("round trip %+v", got)
	}
}

func TestLDIF(t *testing.T) {
	b := &store.Book{Slug: "staff", Name: "员工"}
	e := &store.Entry{ID: 7, Name: "张伟", Phones: []string{"1001"}, Mobiles: []string{"13800001001", "13900001001"}, Organization: "Example"}
	var buf bytes.Buffer
	if err := WriteLDIF(&buf, b, []*store.Entry{e}, "dc=siplink"); err != nil {
		t.Fatal(err)
	}
	out := buf.String()
	for _, want := range []string{
		"version: 1\n",
		"dn: ou=staff,ou=books,dc=siplink\n",
		"description:: " + base64.StdEncoding.EncodeToString([]byte("员工")) + "\n",
		"dn: uid=7,ou=staff,ou=books,dc=siplink\n",
		"objectClass: inetOrgPerson\n",
		"cn:: " + base64.StdEncoding.EncodeToString([]byte("张伟")) + "\n",
		"sn:: " + base64.StdEncoding.EncodeToString([]byte("张伟")) + "\n",
		"telephoneNumber: 1001\n",
		"mobile: 13800001001\nmobile: 13900001001\n",
		"o: Example\n",
	} {
		if !strings.Contains(out, want) {
			t.Errorf("LDIF lacks %q:\n%s", want, out)
		}
	}
	if strings.Contains(out, "givenName") || strings.Contains(out, "mail") {
		t.Error("empty attributes written")
	}
}

func TestValidateAndSlug(t *testing.T) {
	for slug, ok := range map[string]bool{"staff": true, "a-1": true, "A": false, "a": false, "员工": false,
		strings.Repeat("a", 33): false, "a_b": false} {
		if (SlugProblem(slug) == nil) != ok {
			t.Errorf("slug %q", slug)
		}
	}
	e := &store.Entry{Name: " 张伟 ", Phones: []string{" 1001 ", ""}, Email: strings.Repeat("a", 129)}
	Normalize(e)
	if e.Name != "张伟" || len(e.Phones) != 1 {
		t.Errorf("normalize %+v", e)
	}
	if p := Validate(e); len(p) != 1 || !strings.Contains(i18n.For(i18n.Source).Msg(p[0]), "邮箱") {
		t.Errorf("validate %v", p)
	}
	if got := SplitNumbers("1001；1002; ;1003"); len(got) != 3 {
		t.Errorf("split %v", got)
	}
}

// TestParseLimits: a file with more lines of people than can be imported is refused at the
// line past the limit, good or bad lines alike; errors and warnings kept for the page stop
// at rules.MaxShownLineErrors and the others are counted.
func TestParseLimits(t *testing.T) {
	if _, err := ParseCSV([]byte("姓名\n甲\n\n乙\n丙\n"), 3); err != nil {
		t.Errorf("3 lines, limit 3: %v", err)
	}
	for body, max := range map[string]int{"姓名\n甲\n乙\n丙\n": 2, "姓名\n甲\n\"broken\n": 1} {
		_, err := ParseCSV([]byte(body), max)
		if m, ok := i18n.AsMsg(err); !ok || m.Key != "dirs.import.too_many" {
			t.Errorf("%q over the limit: %v", body, err)
		}
	}
	var b strings.Builder
	b.WriteString("姓名")
	for i := range 250 {
		fmt.Fprintf(&b, ",extra%d", i)
	}
	b.WriteString("\n")
	for range 250 {
		b.WriteString(",x\n") // no name
	}
	p, err := ParseCSV([]byte(b.String()), 1000)
	if err != nil {
		t.Fatal(err)
	}
	if len(p.Errors) != 200 || p.MoreErrors != 50 || len(p.Warnings) != 200 || p.MoreWarnings != 50 {
		t.Errorf("kept %d errors (+%d), %d warnings (+%d)", len(p.Errors), p.MoreErrors, len(p.Warnings), p.MoreWarnings)
	}
}
