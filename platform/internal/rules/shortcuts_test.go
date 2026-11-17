package rules

import (
	"fmt"
	"reflect"
	"strings"
	"testing"
)

// Tests ported from app/src/test/java/com/sky/siplink/core/logic/ShortcutRulesTest.kt.

// key mirrors the helper `key` of ShortcutRulesTest.
func key(label, number string, action ShortcutAction, category, line string, id int64) Shortcut {
	return Shortcut{ID: id, Label: label, Number: number, Action: action, Category: category, Line: line}
}

func defaultKey() Shortcut { return key("前台", "1001", ShortcutSpeedDial, "", "", 1) }

func wantShortcutErrors(t *testing.T, got []ShortcutError, want ...ShortcutError) {
	t.Helper()
	if want == nil {
		want = []ShortcutError{}
	}
	if !reflect.DeepEqual(got, want) {
		t.Errorf("want %v, got %v", want, got)
	}
}

// ShortcutRulesTest.`a category cannot take a name that is in use or that a list needs`
func TestShortcutCategoryName(t *testing.T) {
	taken := []string{"销售", "Sales"}
	for _, name := range []string{"门禁", " 门禁 "} {
		if e, ok := CategoryNameError(name, taken); ok {
			t.Errorf("%q: %v", name, e)
		}
	}
	cases := []struct {
		name string
		want ShortcutError
	}{
		{"  ", ShortcutCategoryEmpty},
		{"销售", ShortcutCategoryTaken},
		{" sales ", ShortcutCategoryTaken},
		{"未分类", ShortcutCategoryReserved},
		{"全部", ShortcutCategoryReserved},
		{"新建分类", ShortcutCategoryReserved},
		{strings.Repeat("a", MaxCategoryLength+1), ShortcutCategoryTooLong},
	}
	for _, c := range cases {
		if e, ok := CategoryNameError(c.name, taken); !ok || e != c.want {
			t.Errorf("%q: got %v %v", c.name, e, ok)
		}
	}
	// Also where no dialog asks: in an import.
	k := defaultKey()
	k.Category = "全部"
	wantShortcutErrors(t, ValidateShortcut(k), ShortcutCategoryReserved)
}

// ShortcutRulesTest.`a category spelled in another case joins the one there is`
func TestShortcutJoinCategories(t *testing.T) {
	categories := func(list []Shortcut) []string {
		out := []string{}
		for _, s := range list {
			out = append(out, s.Category)
		}
		return out
	}
	withCategory := func(category string, id int64) Shortcut {
		return key("前台", "1001", ShortcutSpeedDial, category, "", id)
	}
	existing := []Shortcut{withCategory("Sales", 1), withCategory("", 2)}
	added := []Shortcut{withCategory("sales", 3), withCategory("门禁", 4), withCategory("SALES", 5), withCategory("", 6)}
	if got := categories(JoinCategories(added, existing)); !reflect.DeepEqual(got, []string{"Sales", "门禁", "Sales", ""}) {
		t.Error(got)
	}
	// Among new keys the first spelling counts.
	fresh := []Shortcut{withCategory("vip", 1), withCategory("VIP", 2)}
	if got := categories(JoinCategories(fresh, nil)); !reflect.DeepEqual(got, []string{"vip", "vip"}) {
		t.Error(got)
	}
}

// ShortcutRulesTest.`the limit is two thousand keys`
func TestShortcutLimit(t *testing.T) {
	if MaxShortcuts != 2000 || !CanAddShortcut(1999) || CanAddShortcut(2000) {
		t.Error("limit")
	}
}

// ShortcutRulesTest.`normalize trims and names a key after its number or its type`
func TestShortcutNormalizeNames(t *testing.T) {
	named := NormalizeShortcut(key("  前台 ", " 1001 ", ShortcutSpeedDial, " 常用 ", " 总机 ", 1))
	if want := key("前台", "1001", ShortcutSpeedDial, "常用", "总机", 1); named != want {
		t.Errorf("got %+v", named)
	}
	if got := NormalizeShortcut(key(" ", "*801005", ShortcutSpeedDial, "", "", 1)).Label; got != "*801005" {
		t.Error(got)
	}
	if got := NormalizeShortcut(key("", "", ShortcutDND, "", "", 1)).Label; got != "免打扰" {
		t.Error(got)
	}
}

// ShortcutRulesTest.`normalize drops what a type has no use for`
func TestShortcutNormalizeDrops(t *testing.T) {
	// Do not disturb has no number; keys that never call out have no line.
	dnd := NormalizeShortcut(key("前台", "123", ShortcutDND, "", "总机", 1))
	if dnd.Number != "" {
		t.Error(dnd.Number)
	}
	// Kept: where the PBX holds do not disturb, the key calls its feature code on that line.
	if dnd.Line != "总机" {
		t.Error(dnd.Line)
	}
	if got := NormalizeShortcut(key("前台", "#9", ShortcutDTMF, "", "总机", 1)).Line; got != "" {
		t.Error(got)
	}
	if got := NormalizeShortcut(key("前台", "1001", ShortcutBlindTransfer, "", "总机", 1)).Line; got != "总机" {
		t.Error(got)
	}
}

// ShortcutRulesTest.`every type but do not disturb needs a number`
func TestShortcutNumberRequired(t *testing.T) {
	for _, action := range ShortcutActions {
		if action == ShortcutDND {
			continue
		}
		wantShortcutErrors(t, ValidateShortcut(key("前台", "", action, "", "", 1)), ShortcutNumberEmpty)
	}
	wantShortcutErrors(t, ValidateShortcut(key("前台", "", ShortcutDND, "", "", 1)))
}

// ShortcutRulesTest.`what a feature code does is a speed dial and no type of its own`
func TestShortcutTypeWords(t *testing.T) {
	if got := TypeWords(); !reflect.DeepEqual(got, []string{"speed", "bxfer", "axfer", "dtmf", "forward", "dnd"}) {
		t.Error(got)
	}
	for _, gone := range []string{"park", "pickup", "voicemail", "redial", "listen", "whisper", "barge"} {
		if a, ok := ParseAction(gone); ok {
			t.Errorf("%q: %v", gone, a)
		}
	}
}

// ShortcutRulesTest.`feature codes and sip addresses are numbers and dtmf keys are digits star and hash`
func TestShortcutNumbersAndDTMF(t *testing.T) {
	wantShortcutErrors(t, ValidateShortcut(key("前台", "*801005", ShortcutSpeedDial, "", "", 1)))
	wantShortcutErrors(t, ValidateShortcut(key("前台", "sip:alice@example.org", ShortcutSpeedDial, "", "", 1)))
	wantShortcutErrors(t, ValidateShortcut(key("前台", "10 01", ShortcutSpeedDial, "", "", 1)), ShortcutNumberInvalid)
	wantShortcutErrors(t, ValidateShortcut(key("前台", "#9*0", ShortcutDTMF, "", "", 1)))
	wantShortcutErrors(t, ValidateShortcut(key("前台", "12a", ShortcutDTMF, "", "", 1)), ShortcutDTMFInvalid)
}

// ShortcutRulesTest.`lengths are limited`
func TestShortcutLengths(t *testing.T) {
	errors := ValidateShortcut(key(strings.Repeat("x", MaxLabelLength+1), strings.Repeat("1", MaxNumberLength+1),
		ShortcutSpeedDial, strings.Repeat("c", MaxCategoryLength+1), "", 1))
	want := []ShortcutError{ShortcutNumberTooLong, ShortcutLabelTooLong, ShortcutCategoryTooLong}
	if len(errors) != len(want) {
		t.Fatal(errors)
	}
	for _, e := range want {
		if !contains(errors, e) {
			t.Errorf("missing %v", e)
		}
	}
}

// ShortcutRulesTest.`types are written as words and also read by their chinese name`
func TestShortcutParseAction(t *testing.T) {
	if len(distinct(TypeWords())) != len(ShortcutActions) {
		t.Error("type words not distinct")
	}
	cases := map[string]ShortcutAction{"": ShortcutSpeedDial, "BXFER": ShortcutBlindTransfer, "咨询转": ShortcutAttendedTransfer, " dtmf ": ShortcutDTMF}
	for in, want := range cases {
		if got, ok := ParseAction(in); !ok || got != want {
			t.Errorf("%q: got %v %v", in, got, ok)
		}
	}
	// Intercom is not a type of its own: it is a speed dial of the PBX feature code.
	if _, ok := ParseAction("intercom"); ok {
		t.Error("intercom parsed")
	}
}

// ShortcutRulesTest.`csv text is read with defaults and reports bad lines`
func TestShortcutDecode(t *testing.T) {
	text := strings.Join([]string{
		"前台,1001",
		"# 注释",
		"\"A, 一号线\",1002,bxfer,常用,总机",
		"开门,12a,dtmf",
		"未知,1003,teleport",
		"只有名称",
		"\"没关引号,1004",
	}, "\n")
	parsed := DecodeShortcuts(text, MaxShortcuts, 10)
	want := []Shortcut{
		key("前台", "1001", ShortcutSpeedDial, "", "", 10),
		key("A, 一号线", "1002", ShortcutBlindTransfer, "常用", "总机", 11),
	}
	if !reflect.DeepEqual(parsed.Shortcuts, want) {
		t.Errorf("got %+v", parsed.Shortcuts)
	}
	var lines []int
	for _, e := range parsed.Errors {
		lines = append(lines, e.Line)
	}
	if !reflect.DeepEqual(lines, []int{4, 5, 6, 7}) || parsed.SkippedForLimit != 0 {
		t.Errorf("errors %+v, skipped %d", parsed.Errors, parsed.SkippedForLimit)
	}
	// Not in the Kotlin test: the reasons are the app's own texts.
	reasons := []string{
		"按键仅限 0-9 * #",
		"未知的类型“teleport”，可用 speed / bxfer / axfer / dtmf / forward / dnd",
		"字段不足，格式：名称,号码[,类型[,分类[,线路[,图标]]]]",
		"引号未闭合",
	}
	for i, e := range parsed.Errors {
		if e.Reason != reasons[i] {
			t.Errorf("line %d: %q", e.Line, e.Reason)
		}
	}
}

// ShortcutRulesTest.`an icon is written as a word and read back`
func TestShortcutIcons(t *testing.T) {
	parsed := DecodeShortcuts("开门,#9,dtmf,门禁,,door\n仓库,*801005,,,,仓库\n没有图标,1001\n坏图标,1,,,,rocket", MaxShortcuts, 1)
	var icons []ShortcutIcon
	for _, s := range parsed.Shortcuts {
		icons = append(icons, s.Icon)
	}
	if !reflect.DeepEqual(icons, []ShortcutIcon{"DOOR", "WAREHOUSE", ""}) {
		t.Error(icons)
	}
	if len(parsed.Errors) != 1 || parsed.Errors[0].Line != 4 {
		t.Error(parsed.Errors)
	}
	want := "开门,#9,dtmf,门禁,,door\n仓库,*801005,speed,,,warehouse\n没有图标,1001,speed,,,\n"
	if got := EncodeShortcuts(parsed.Shortcuts); got != want {
		t.Errorf("got %q", got)
	}
	if len(distinct(IconWords())) != len(ShortcutIcons) {
		t.Error("icon words not distinct")
	}
}

// ShortcutRulesTest.`reading stops at the room that is left`
func TestShortcutDecodeRoom(t *testing.T) {
	var lines []string
	for i := 1; i <= 5; i++ {
		lines = append(lines, fmt.Sprintf("键%d,10%d", i, i))
	}
	parsed := DecodeShortcuts(strings.Join(lines, "\n"), 3, 1)
	if len(parsed.Shortcuts) != 3 || parsed.SkippedForLimit != 2 || len(parsed.Errors) != 0 {
		t.Errorf("%+v", parsed)
	}
}

// kotlinToString mirrors the toString of the Kotlin data class Shortcut, which the Kotlin
// test filters the digits of a DTMF key from.
func kotlinToString(s Shortcut) string {
	icon := "null"
	if s.Icon != "" {
		icon = string(s.Icon)
	}
	return fmt.Sprintf("Shortcut(id=%d, label=%s, number=%s, action=%s, category=%s, line=%s, icon=%s, own=%v)",
		s.ID, s.Label, s.Number, s.Action, s.Category, s.Line, icon, s.Own)
}

// ShortcutRulesTest.`two thousand keys survive writing and reading`
func TestShortcutRoundTrip(t *testing.T) {
	var all []Shortcut
	for i := 1; i <= MaxShortcuts; i++ {
		s := key(fmt.Sprintf("键, \"%d\"", i), fmt.Sprintf("*80%d", i), ShortcutActions[i%len(ShortcutActions)],
			fmt.Sprintf("组%d", i%7), "", int64(i))
		if n := i % (len(ShortcutIcons) + 1); n < len(ShortcutIcons) {
			s.Icon = ShortcutIcons[n]
		}
		s = NormalizeShortcut(s)
		if s.Action == ShortcutDTMF {
			var digits strings.Builder
			for _, c := range "#" + kotlinToString(s) {
				if isDigit(c) || c == '#' {
					digits.WriteRune(c)
				}
			}
			s.Number = take(digits.String(), 8)
		}
		all = append(all, s)
	}
	parsed := DecodeShortcuts(EncodeShortcuts(all), MaxShortcuts, 1)
	if len(parsed.Errors) != 0 {
		t.Fatal(parsed.Errors)
	}
	if !reflect.DeepEqual(parsed.Shortcuts, all) {
		t.Error("keys changed on the way")
	}
}

// ShortcutRulesTest.`a line matters only when the key calls out by itself`
func TestShortcutPlacesCall(t *testing.T) {
	speed := defaultKey()
	blind := key("前台", "1001", ShortcutBlindTransfer, "", "", 1)
	attended := key("前台", "1001", ShortcutAttendedTransfer, "", "", 1)
	if !PlacesCall(speed, false, false) || PlacesCall(speed, true, false) {
		t.Error("speed dial")
	}
	if !PlacesCall(blind, false, false) || PlacesCall(blind, true, false) || PlacesCall(attended, true, false) {
		t.Error("transfer")
	}
	for _, action := range []ShortcutAction{ShortcutDTMF, ShortcutForward} {
		for _, serverDnd := range []bool{false, true} {
			if UsesLine(action, serverDnd) || PlacesCall(key("前台", "1001", action, "", "", 1), false, serverDnd) {
				t.Errorf("%v %v", action, serverDnd)
			}
		}
	}
}

// ShortcutRulesTest.`a do not disturb key calls out only where the pbx keeps that switch`
func TestShortcutDNDCallsOut(t *testing.T) {
	dnd := key("前台", "", ShortcutDND, "", "", 1)
	if UsesLine(ShortcutDND, false) || PlacesCall(dnd, false, false) {
		t.Error("software dnd calls out")
	}
	if !UsesLine(ShortcutDND, true) || !PlacesCall(dnd, false, true) || !PlacesCall(dnd, true, true) {
		t.Error("server dnd does not call out")
	}
}

// ShortcutRulesTest.`next to a managed configuration the user makes fifty keys of their own`
func TestShortcutOwnLimit(t *testing.T) {
	if MaxOwn != 50 {
		t.Error(MaxOwn)
	}
	var handedOut []Shortcut
	for i := 0; i < 300; i++ {
		handedOut = append(handedOut, Shortcut{ID: int64(i + 1), Label: fmt.Sprintf("总机%d", i), Number: "1000", Action: ShortcutSpeedDial})
	}
	own := func(count int) []Shortcut {
		var out []Shortcut
		for i := 0; i < count; i++ {
			out = append(out, Shortcut{ID: int64(1000 + i), Label: fmt.Sprintf("自建%d", i), Number: "2000", Action: ShortcutSpeedDial, Own: true})
		}
		return out
	}
	// The keys that were handed out do not count, however many there are.
	if !CanAddOwn(handedOut) || !CanAddOwn(append(append([]Shortcut{}, handedOut...), own(49)...)) {
		t.Error("cannot add")
	}
	if CanAddOwn(append(append([]Shortcut{}, handedOut...), own(50)...)) || CanAddOwn(own(50)) {
		t.Error("can add the 51st")
	}
}

// ShortcutRulesTest.`a category is the user's to rename when every key in it is their own`
func TestShortcutOwnsCategory(t *testing.T) {
	keys := []Shortcut{
		{ID: 1, Label: "前台", Number: "1001", Action: ShortcutSpeedDial, Category: "常用"},
		{ID: 2, Label: "家", Number: "1002", Action: ShortcutSpeedDial, Category: "常用", Own: true},
		{ID: 3, Label: "车库", Number: "1003", Action: ShortcutSpeedDial, Category: "我的", Own: true},
		{ID: 4, Label: "门卫", Number: "1004", Action: ShortcutSpeedDial, Category: "我的", Own: true},
	}
	if !OwnsCategory(keys, "我的") || OwnsCategory(keys, "常用") {
		t.Error("owns category")
	}
}

// ShortcutRulesTest.`whose key it is is no part of the csv format`
func TestShortcutOwnNotInCSV(t *testing.T) {
	own := Shortcut{ID: 1, Label: "家", Number: "1002", Action: ShortcutSpeedDial, Category: "我的", Own: true}
	notOwn := own
	notOwn.Own = false
	if EncodeShortcuts([]Shortcut{notOwn}) != EncodeShortcuts([]Shortcut{own}) {
		t.Error("own is written")
	}
	parsed := DecodeShortcuts(EncodeShortcuts([]Shortcut{own}), MaxShortcuts, 1)
	if len(parsed.Shortcuts) != 1 || parsed.Shortcuts[0].Own {
		t.Error("own is read")
	}
}

// Not a Kotlin test: the error texts are the app's own.
func TestShortcutErrorTexts(t *testing.T) {
	if ShortcutLimitReached.Full() != "最多 2000 个快捷键" || ShortcutOwnLimitReached.Full() != "最多自建 50 个快捷键" ||
		ShortcutNumberTooLong.Full() != "号码最多 128 个字符" || ShortcutDTMFInvalid.Name() != "DTMF_INVALID" ||
		len(shortcutErrors) != int(ShortcutManaged)+1 {
		t.Error("error texts differ")
	}
}
