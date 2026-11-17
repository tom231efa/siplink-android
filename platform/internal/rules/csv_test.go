package rules

import (
	"reflect"
	"testing"
)

// Tests ported from app/src/test/java/com/sky/siplink/core/logic/CsvTest.kt.

func split(t *testing.T, line string) []string {
	t.Helper()
	fields, ok := CsvSplit(line)
	if !ok {
		t.Fatalf("%q: open quote", line)
	}
	return fields
}

// CsvTest.`plain fields are trimmed and empty ones are kept`
func TestCsvPlainFields(t *testing.T) {
	if got := split(t, " a , b ,, d "); !reflect.DeepEqual(got, []string{"a", "b", "", "d"}) {
		t.Error(got)
	}
	if got := split(t, ""); !reflect.DeepEqual(got, []string{""}) {
		t.Error(got)
	}
}

// CsvTest.`quoted fields keep commas spaces and doubled quotes`
func TestCsvQuotedFields(t *testing.T) {
	got := split(t, "\"x, y\",\" z \",\"say \"\"hi\"\"\"")
	if !reflect.DeepEqual(got, []string{"x, y", " z ", "say \"hi\""}) {
		t.Error(got)
	}
}

// CsvTest.`an open quote is reported as null`
func TestCsvOpenQuote(t *testing.T) {
	if fields, ok := CsvSplit("a,\"b"); ok || fields != nil {
		t.Error(fields)
	}
}

// CsvTest.`join quotes only what needs it and split reads it back`
func TestCsvJoin(t *testing.T) {
	fields := []string{"前台", "a,b", "say \"hi\"", " padded ", ""}
	line := CsvJoin(fields)
	if line != "前台,\"a,b\",\"say \"\"hi\"\"\",\" padded \"," {
		t.Error(line)
	}
	if got := split(t, line); !reflect.DeepEqual(got, fields) {
		t.Error(got)
	}
}

// CsvTest.`rows skip blank lines comments and a byte order mark and keep line numbers`
func TestCsvRows(t *testing.T) {
	rows := CsvRows("\uFEFFa,b\n\n# comment\n  c,d  \n\"open")
	want := []CsvRow{{1, []string{"a", "b"}}, {4, []string{"c", "d"}}, {5, nil}}
	if !reflect.DeepEqual(rows, want) {
		t.Errorf("%#v", rows)
	}
}
