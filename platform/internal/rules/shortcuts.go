package rules

import (
	"strconv"
	"strings"
)

// ShortcutAction is what a shortcut key does; its values are the Kotlin enum names.
// Port of enum ShortcutAction (app/src/main/java/com/sky/siplink/core/model/Shortcut.kt).
type ShortcutAction string

// The values of ShortcutAction, in the order of the Kotlin enum.
const (
	ShortcutSpeedDial        ShortcutAction = "SPEED_DIAL"
	ShortcutBlindTransfer    ShortcutAction = "BLIND_TRANSFER"
	ShortcutAttendedTransfer ShortcutAction = "ATTENDED_TRANSFER"
	ShortcutDTMF             ShortcutAction = "DTMF"
	ShortcutForward          ShortcutAction = "FORWARD"
	ShortcutDND              ShortcutAction = "DND"
)

// ShortcutActions lists every ShortcutAction in the order of the Kotlin enum.
var ShortcutActions = []ShortcutAction{
	ShortcutSpeedDial, ShortcutBlindTransfer, ShortcutAttendedTransfer, ShortcutDTMF, ShortcutForward, ShortcutDND,
}

var shortcutActionLabels = map[ShortcutAction]string{
	ShortcutSpeedDial:        "速拨",
	ShortcutBlindTransfer:    "盲转",
	ShortcutAttendedTransfer: "咨询转",
	ShortcutDTMF:             "发送按键",
	ShortcutForward:          "呼叫前转",
	ShortcutDND:              "免打扰",
}

// Valid reports whether a is one of ShortcutActions.
func (a ShortcutAction) Valid() bool { return contains(ShortcutActions, a) }

// Label is the Chinese name of the type (ShortcutAction.label).
func (a ShortcutAction) Label() string { return shortcutActionLabels[a] }

// HasNumber reports whether a key of this type carries a number (ShortcutAction.hasNumber).
func (a ShortcutAction) HasNumber() bool { return a != ShortcutDND }

// ShortcutIcon is a picture a key can carry instead of the one of its type; its values are
// the Kotlin enum names, "" stands for no icon (Kotlin: null).
// Port of enum ShortcutIcon (app/src/main/java/com/sky/siplink/core/model/Shortcut.kt).
type ShortcutIcon string

// ShortcutIcons lists every ShortcutIcon in the order of the Kotlin enum.
var ShortcutIcons = []ShortcutIcon{
	"PHONE", "PERSON", "GROUP", "STAR", "HOME", "OFFICE", "STORE",
	"RECEPTION", "SUPPORT", "SPEAKER", "MEETING", "DOOR", "LOCK", "KEY",
	"GARAGE", "CAR", "ELEVATOR", "SECURITY", "CAMERA", "BELL", "ALARM",
	"MEDICAL", "FIRE", "FOOD", "HOTEL", "WAREHOUSE", "FACTORY", "SCHOOL",
	"REPAIR", "COMPUTER", "DELIVERY", "LIGHT",
}

var shortcutIconLabels = []string{
	"电话", "个人", "团队", "收藏", "家", "办公楼", "门店",
	"前台", "客服", "广播", "会议", "门", "锁", "钥匙",
	"车库", "车辆", "电梯", "保安", "监控", "门铃", "报警",
	"医务", "消防", "餐厅", "客房", "仓库", "车间", "教室",
	"维修", "机房", "物流", "照明",
}

// Valid reports whether i is one of ShortcutIcons.
func (i ShortcutIcon) Valid() bool { return contains(ShortcutIcons, i) }

// Label is the Chinese name of the icon (ShortcutIcon.label).
func (i ShortcutIcon) Label() string {
	for n, icon := range ShortcutIcons {
		if icon == i {
			return shortcutIconLabels[n]
		}
	}
	return ""
}

// Shortcut is a programmable key.
// Port of data class Shortcut (app/src/main/java/com/sky/siplink/core/model/Shortcut.kt).
// The Kotlin defaults are Action SPEED_DIAL and no icon; a Go zero Action is no valid type.
type Shortcut struct {
	ID       int64          `json:"id"`
	Label    string         `json:"label"`
	Number   string         `json:"number"`
	Action   ShortcutAction `json:"action"`
	Category string         `json:"category"`
	Line     string         `json:"line"`
	Icon     ShortcutIcon   `json:"icon,omitempty"`
	Own      bool           `json:"own"`
}

// Limits of ShortcutRules (app/src/main/java/com/sky/siplink/core/logic/ShortcutRules.kt):
// MAX_SHORTCUTS, MAX_OWN, MAX_LABEL_LENGTH, MAX_NUMBER_LENGTH, MAX_CATEGORY_LENGTH,
// MAX_LINE_LENGTH, FORMAT_HINT.
const (
	MaxShortcuts      = 2000
	MaxOwn            = 50
	MaxLabelLength    = 24
	MaxNumberLength   = 128
	MaxCategoryLength = 16
	MaxLineLength     = 160
	FormatHint        = "名称,号码[,类型[,分类[,线路[,图标]]]]"
)

// ShortcutError is what is wrong with a shortcut key.
// Port of enum ShortcutRules.Error (app/src/main/java/com/sky/siplink/core/logic/ShortcutRules.kt).
type ShortcutError int

// The values of ShortcutError, in the order of the Kotlin enum; Name gives the Kotlin name.
const (
	ShortcutLimitReached ShortcutError = iota
	ShortcutOwnLimitReached
	ShortcutNumberEmpty
	ShortcutNumberInvalid
	ShortcutDTMFInvalid
	ShortcutNumberTooLong
	ShortcutLabelTooLong
	ShortcutCategoryTooLong
	ShortcutCategoryEmpty
	ShortcutCategoryReserved
	ShortcutCategoryTaken
	ShortcutManaged
)

var shortcutErrors = []errorText{
	{"LIMIT_REACHED", "", "最多 " + strconv.Itoa(MaxShortcuts) + " 个快捷键"},
	{"OWN_LIMIT_REACHED", "", "最多自建 " + strconv.Itoa(MaxOwn) + " 个快捷键"},
	{"NUMBER_EMPTY", "号码", "必填"},
	{"NUMBER_INVALID", "号码", "不能有空格"},
	{"DTMF_INVALID", "按键", "仅限 0-9 * #"},
	{"NUMBER_TOO_LONG", "号码", "最多 " + strconv.Itoa(MaxNumberLength) + " 个字符"},
	{"LABEL_TOO_LONG", "名称", "最多 " + strconv.Itoa(MaxLabelLength) + " 个字符"},
	{"CATEGORY_TOO_LONG", "分类", "最多 " + strconv.Itoa(MaxCategoryLength) + " 个字符"},
	{"CATEGORY_EMPTY", "分类", "必填"},
	{"CATEGORY_RESERVED", "分类", "名称不可用"},
	{"CATEGORY_TAKEN", "分类", "已存在"},
	{"MANAGED", "", ManagedNotice},
}

// Name is the name of the Kotlin enum value, e.g. "DTMF_INVALID".
func (e ShortcutError) Name() string { return shortcutErrors[e].name }

// Subject is what the error is about.
func (e ShortcutError) Subject() string { return shortcutErrors[e].subject }

// Message is what is wrong, as shown right under that field.
func (e ShortcutError) Message() string { return shortcutErrors[e].message }

// Full is the error where no field stands next to it. Port of ShortcutRules.Error.full.
func (e ShortcutError) Full() string { return shortcutErrors[e].full() }

// Error returns Full, so that a ShortcutError is an error.
func (e ShortcutError) Error() string { return e.Full() }

// String returns Name.
func (e ShortcutError) String() string { return e.Name() }

// TypeWord is the word of the CSV format for a type.
// Port of ShortcutRules.typeWord (app/src/main/java/com/sky/siplink/core/logic/ShortcutRules.kt).
func TypeWord(action ShortcutAction) string {
	switch action {
	case ShortcutSpeedDial:
		return "speed"
	case ShortcutBlindTransfer:
		return "bxfer"
	case ShortcutAttendedTransfer:
		return "axfer"
	case ShortcutDTMF:
		return "dtmf"
	case ShortcutForward:
		return "forward"
	case ShortcutDND:
		return "dnd"
	}
	return ""
}

// TypeWords are the words of the CSV format, one per type, in the order of the types.
// Port of ShortcutRules.typeWords (app/src/main/java/com/sky/siplink/core/logic/ShortcutRules.kt).
func TypeWords() []string {
	out := make([]string, len(ShortcutActions))
	for i, action := range ShortcutActions {
		out[i] = TypeWord(action)
	}
	return out
}

// IconWords are the icons of the CSV format, in the order of ShortcutIcons.
// Port of ShortcutRules.iconWords (app/src/main/java/com/sky/siplink/core/logic/ShortcutRules.kt).
func IconWords() []string {
	out := make([]string, len(ShortcutIcons))
	for i, icon := range ShortcutIcons {
		out[i] = strings.ToLower(string(icon))
	}
	return out
}

// CanAddShortcut reports whether one more key fits.
// Port of ShortcutRules.canAdd (app/src/main/java/com/sky/siplink/core/logic/ShortcutRules.kt).
func CanAddShortcut(count int) bool { return count < MaxShortcuts }

// CanAddOwn reports whether the user may make one more key of their own next to a managed
// configuration.
// Port of ShortcutRules.canAddOwn (app/src/main/java/com/sky/siplink/core/logic/ShortcutRules.kt).
func CanAddOwn(all []Shortcut) bool {
	own := 0
	for _, shortcut := range all {
		if shortcut.Own {
			own++
		}
	}
	return own < MaxOwn
}

// OwnsCategory reports whether every key of category is the user's own.
// Port of ShortcutRules.ownsCategory (app/src/main/java/com/sky/siplink/core/logic/ShortcutRules.kt).
func OwnsCategory(all []Shortcut, category string) bool {
	for _, shortcut := range all {
		if shortcut.Category == category && !shortcut.Own {
			return false
		}
	}
	return true
}

// UsesLine reports whether keys of this type ever call out, and so have a line to choose.
// serverDnd: do not disturb is kept by the PBX, so a do not disturb key calls out as well.
// Port of ShortcutRules.usesLine (app/src/main/java/com/sky/siplink/core/logic/ShortcutRules.kt).
func UsesLine(action ShortcutAction, serverDnd bool) bool {
	switch action {
	case ShortcutSpeedDial, ShortcutBlindTransfer, ShortcutAttendedTransfer:
		return true
	case ShortcutDND:
		return serverDnd
	}
	return false
}

// PlacesCall reports whether pressing the key now starts a call of its own.
// Port of ShortcutRules.placesCall (app/src/main/java/com/sky/siplink/core/logic/ShortcutRules.kt).
func PlacesCall(shortcut Shortcut, inCall, serverDnd bool) bool {
	if shortcut.Action == ShortcutDND {
		return serverDnd
	}
	return UsesLine(shortcut.Action, serverDnd) && !inCall
}

// NormalizeShortcut trims every field and drops the number of a type that has none. A key
// without a name is called by its number, or by its type when it has no number either.
// Port of ShortcutRules.normalize (app/src/main/java/com/sky/siplink/core/logic/ShortcutRules.kt).
func NormalizeShortcut(shortcut Shortcut) Shortcut {
	number := ""
	if shortcut.Action.HasNumber() {
		number = trim(shortcut.Number)
	}
	label := trim(shortcut.Label)
	if label == "" {
		label = take(number, MaxLabelLength)
		if label == "" {
			label = shortcut.Action.Label()
		}
	}
	line := ""
	// Kept for every type that may call out, whatever the do not disturb mode is right now.
	if UsesLine(shortcut.Action, true) {
		line = take(trim(shortcut.Line), MaxLineLength)
	}
	out := shortcut
	out.Label = label
	out.Number = number
	out.Category = trim(shortcut.Category)
	out.Line = line
	return out
}

// isDtmf mirrors Formatters.isDtmf (app/src/main/java/com/sky/siplink/core/logic/Formatters.kt):
// Char.isDigit (any script), '*' or '#'.
func isDtmf(r rune) bool { return isDigit(r) || r == '*' || r == '#' }

// reservedCategories are what the lists call every key, the keys without a category, and
// the way to make a category.
var reservedCategories = []string{"全部", "未分类", "新建分类"}

// ValidateShortcut checks an already normalized key.
// Port of ShortcutRules.validate (app/src/main/java/com/sky/siplink/core/logic/ShortcutRules.kt).
func ValidateShortcut(shortcut Shortcut) []ShortcutError {
	errors := []ShortcutError{}
	number := shortcut.Number
	switch {
	case number == "":
		if shortcut.Action.HasNumber() {
			errors = append(errors, ShortcutNumberEmpty)
		}
	case shortcut.Action == ShortcutDTMF:
		if strings.IndexFunc(number, func(r rune) bool { return !isDtmf(r) }) >= 0 {
			errors = append(errors, ShortcutDTMFInvalid)
		}
	case hasWhitespace(number):
		errors = append(errors, ShortcutNumberInvalid)
	}
	if utf16Len(number) > MaxNumberLength {
		errors = append(errors, ShortcutNumberTooLong)
	}
	if utf16Len(shortcut.Label) > MaxLabelLength {
		errors = append(errors, ShortcutLabelTooLong)
	}
	if utf16Len(shortcut.Category) > MaxCategoryLength {
		errors = append(errors, ShortcutCategoryTooLong)
	}
	if contains(reservedCategories, shortcut.Category) {
		errors = append(errors, ShortcutCategoryReserved)
	}
	return errors
}

// CategoryNameError returns what keeps name from being the name of a category; ok is false
// when nothing does (Kotlin: null). taken are the names of the other categories.
// Port of ShortcutRules.categoryNameError (app/src/main/java/com/sky/siplink/core/logic/ShortcutRules.kt).
func CategoryNameError(name string, taken []string) (err ShortcutError, ok bool) {
	trimmed := trim(name)
	switch {
	case trimmed == "":
		return ShortcutCategoryEmpty, true
	case utf16Len(trimmed) > MaxCategoryLength:
		return ShortcutCategoryTooLong, true
	case contains(reservedCategories, trimmed):
		return ShortcutCategoryReserved, true
	case IsAccountNameTaken(trimmed, taken):
		return ShortcutCategoryTaken, true
	}
	return 0, false
}

// JoinCategories returns added with their categories spelled the way existing and the keys
// before them spell them: "sales" joins "Sales".
// Port of ShortcutRules.joinCategories (app/src/main/java/com/sky/siplink/core/logic/ShortcutRules.kt).
func JoinCategories(added, existing []Shortcut) []Shortcut {
	spelling := map[string]string{}
	for _, shortcut := range existing {
		if _, ok := spelling[lowercase(shortcut.Category)]; !ok {
			spelling[lowercase(shortcut.Category)] = shortcut.Category
		}
	}
	out := make([]Shortcut, len(added))
	for i, shortcut := range added {
		key := lowercase(shortcut.Category)
		known, ok := spelling[key]
		if !ok {
			known = shortcut.Category
			spelling[key] = known
		}
		shortcut.Category = known
		out[i] = shortcut
	}
	return out
}

// ParseAction returns the type behind a word of the CSV format (a type word, or its Chinese
// name); empty is a speed dial. ok is false for an unknown word (Kotlin: null).
// Port of ShortcutRules.parseAction (app/src/main/java/com/sky/siplink/core/logic/ShortcutRules.kt).
func ParseAction(value string) (ShortcutAction, bool) {
	word := trim(value)
	if word == "" {
		return ShortcutSpeedDial, true
	}
	for _, action := range ShortcutActions {
		if equalsIgnoreCase(TypeWord(action), word) || action.Label() == word {
			return action, true
		}
	}
	return "", false
}

// ParseIcon returns the icon behind a word of the CSV format (an icon word, or its Chinese
// name). ok is false for an unknown word (Kotlin: null).
// Port of ShortcutRules.parseIcon (app/src/main/java/com/sky/siplink/core/logic/ShortcutRules.kt).
func ParseIcon(value string) (ShortcutIcon, bool) {
	word := trim(value)
	for _, icon := range ShortcutIcons {
		if equalsIgnoreCase(string(icon), word) || icon.Label() == word {
			return icon, true
		}
	}
	return "", false
}

// ParsedShortcuts is what DecodeShortcuts read.
// Port of ShortcutRules.Parsed (app/src/main/java/com/sky/siplink/core/logic/ShortcutRules.kt).
type ParsedShortcuts struct {
	Shortcuts       []Shortcut     `json:"shortcuts"`
	Errors          []CsvLineError `json:"errors"`
	SkippedForLimit int            `json:"skippedForLimit"`
}

func field(fields []string, i int) string {
	if i < len(fields) {
		return fields[i]
	}
	return ""
}

// DecodeShortcuts reads keys from CSV text (FormatHint). room is how many keys still fit
// (Kotlin default MaxShortcuts); firstID is the id of the first key read (Kotlin default 1).
// Port of ShortcutRules.decode (app/src/main/java/com/sky/siplink/core/logic/ShortcutRules.kt).
func DecodeShortcuts(text string, room int, firstID int64) ParsedShortcuts {
	parsed := ParsedShortcuts{Shortcuts: []Shortcut{}, Errors: []CsvLineError{}}
	for _, row := range CsvRows(text) {
		fields := row.Fields
		var reason string
		var action ShortcutAction
		var icon ShortcutIcon
		if fields == nil {
			reason = "引号未闭合"
		} else {
			var actionOK, iconOK bool
			action, actionOK = ParseAction(field(fields, 2))
			iconWord := trim(field(fields, 5))
			icon, iconOK = ParseIcon(iconWord)
			switch {
			case len(fields) < 2:
				reason = "字段不足，格式：" + FormatHint
			case !actionOK:
				reason = "未知的类型“" + fields[2] + "”，可用 " + strings.Join(TypeWords(), " / ")
			case iconWord != "" && !iconOK:
				reason = "未知的图标“" + iconWord + "”，可用 " + strings.Join(IconWords(), " / ")
			}
		}
		if reason != "" {
			parsed.Errors = append(parsed.Errors, CsvLineError{Line: row.Line, Reason: reason})
			continue
		}
		shortcut := NormalizeShortcut(Shortcut{
			ID:       firstID + int64(len(parsed.Shortcuts)),
			Label:    fields[0],
			Number:   fields[1],
			Action:   action,
			Category: field(fields, 3),
			Line:     field(fields, 4),
			Icon:     icon,
		})
		problems := ValidateShortcut(shortcut)
		switch {
		case len(problems) > 0:
			parsed.Errors = append(parsed.Errors, CsvLineError{Line: row.Line, Reason: problems[0].Full()})
		case len(parsed.Shortcuts) >= room:
			parsed.SkippedForLimit++
		default:
			parsed.Shortcuts = append(parsed.Shortcuts, shortcut)
		}
	}
	return parsed
}

// EncodeShortcuts is the opposite of DecodeShortcuts; whose key it is (Own) is no part of it.
// Port of ShortcutRules.encode (app/src/main/java/com/sky/siplink/core/logic/ShortcutRules.kt).
func EncodeShortcuts(shortcuts []Shortcut) string {
	var b strings.Builder
	for _, shortcut := range shortcuts {
		b.WriteString(CsvJoin([]string{
			shortcut.Label,
			shortcut.Number,
			TypeWord(shortcut.Action),
			shortcut.Category,
			shortcut.Line,
			strings.ToLower(string(shortcut.Icon)),
		}))
		b.WriteByte('\n')
	}
	return b.String()
}
