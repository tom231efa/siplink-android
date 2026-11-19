package i18n

import (
	"errors"
	"fmt"
	"strconv"
	"strings"
	"time"
)

// Msg is a text for a person that is translated where it is shown: a catalog key and
// its arguments as name, value pairs (design §5.5). Printed untranslated (by fmt or a
// template) it reads ⟦key⟧, which the tests catch.
type Msg struct {
	Key  string
	Args []any
}

// M makes a Msg: M("rules.account.name_too_long", "max", 30).
func M(key string, args ...any) Msg { return Msg{Key: key, Args: args} }

// String marks a message that was printed without being translated.
func (m Msg) String() string { return "⟦" + m.Key + "⟧" }

// MsgError is an error that carries a message for people: *Error, the rule errors
// of package rules, the refusals of package pki.
type MsgError interface {
	error
	Msg() Msg
}

// Error is the plain MsgError; its Error text is the English one, for logs and for
// code that only passes errors on. It may wrap another error.
type Error struct {
	msg Msg
	err error
}

// E makes an Error.
func E(key string, args ...any) error { return &Error{msg: M(key, args...)} }

// ErrorOf makes an Error of a message.
func ErrorOf(m Msg) error { return &Error{msg: m} }

// Wrap makes an Error around err; the message gets err as its argument "err".
func Wrap(err error, key string, args ...any) error {
	return &Error{msg: M(key, append(args, "err", err)...), err: err}
}

// Msg is the error's message.
func (e *Error) Msg() Msg { return e.msg }

func (e *Error) Error() string { return For(English).Msg(e.msg) }

func (e *Error) Unwrap() error { return e.err }

// AsMsg finds the message of a MsgError in err's chain.
func AsMsg(err error) (Msg, bool) {
	var me MsgError
	if errors.As(err, &me) {
		return me.Msg(), true
	}
	return Msg{}, false
}

// MsgOf is the message of err: its Msg when it carries one, else its own text as it
// is (for errors from outside, such as a mail server's answer).
func MsgOf(err error) Msg {
	if m, ok := AsMsg(err); ok {
		return m
	}
	return M("error.text", "text", err.Error())
}

// Tr translates into one language.
type Tr struct {
	Lang *Lang
	msgs map[string]string
}

var trs = func() map[*Lang]*Tr {
	out := map[*Lang]*Tr{}
	for _, l := range Langs {
		out[l] = &Tr{Lang: l, msgs: catalogs[l.Code]}
	}
	return out
}()

// For returns the translator of a language; nil means English.
func For(l *Lang) *Tr {
	if t, ok := trs[l]; ok {
		return t
	}
	return trs[English]
}

// Unicode isolates around an argument inside right-to-left text.
const (
	fsi = "\u2068"
	pdi = "\u2069"
)

// Raw is the text of key as the catalog holds it, its arguments not filled in (for
// the scripts, which fill them themselves). A key that the language lacks comes from
// English, else it shows as ⟦key⟧.
func (t *Tr) Raw(key string) string {
	if text, ok := t.msgs[key]; ok {
		return text
	}
	if text, ok := catalogs[English.Code][key]; ok {
		return text
	}
	return "⟦" + key + "⟧"
}

// T gives the text of key with its arguments (name, value pairs), as Raw; an argument
// that is not passed shows as ⟦name⟧.
func (t *Tr) T(key string, args ...any) string {
	text := t.Raw(key)
	if !strings.Contains(text, "{") {
		return text
	}
	return placeholder.ReplaceAllStringFunc(text, func(ph string) string {
		name := ph[1 : len(ph)-1]
		for i := 0; i+1 < len(args); i += 2 {
			if n, _ := args[i].(string); n == name {
				return t.value(args[i+1])
			}
		}
		return "⟦" + name + "⟧"
	})
}

// Msg translates a message.
func (t *Tr) Msg(m Msg) string { return t.T(m.Key, m.Args...) }

// Msgs translates a list of messages.
func (t *Tr) Msgs(ms []Msg) []string {
	if ms == nil {
		return nil
	}
	out := make([]string, len(ms))
	for i, m := range ms {
		out[i] = t.Msg(m)
	}
	return out
}

// Err is the text of an error (MsgOf).
func (t *Tr) Err(err error) string { return t.Msg(MsgOf(err)) }

// value is an argument as text: strings as they are (isolated in a right-to-left
// language), numbers in ASCII digits, messages translated, lists joined.
func (t *Tr) value(v any) string {
	switch x := v.(type) {
	case string:
		return t.isolate(x)
	case Msg:
		return t.Msg(x)
	case []Msg:
		return strings.Join(t.Msgs(x), t.T("format.list_sep"))
	case []string:
		parts := make([]string, len(x))
		for i, s := range x {
			parts[i] = t.isolate(s)
		}
		return strings.Join(parts, t.T("format.list_sep"))
	case []int:
		parts := make([]string, len(x))
		for i, n := range x {
			parts[i] = strconv.Itoa(n)
		}
		return strings.Join(parts, t.T("format.list_sep"))
	case int:
		return strconv.Itoa(x)
	case int64:
		return strconv.FormatInt(x, 10)
	case time.Time:
		return t.Date(x.In(time.Local), true)
	case error:
		return t.Msg(MsgOf(x))
	case fmt.Stringer:
		return t.isolate(x.String())
	}
	return t.isolate(fmt.Sprint(v))
}

func (t *Tr) isolate(s string) string {
	if t.Lang.RTL && s != "" {
		return fsi + s + pdi
	}
	return s
}

// datePattern turns a catalog pattern (YYYY MM DD HH mm) into a Go layout.
var datePattern = strings.NewReplacer("YYYY", "2006", "MM", "01", "DD", "02", "HH", "15", "mm", "04")

// Layout is the Go layout of the language's date ("date") or date and time
// ("datetime") pattern.
func (t *Tr) Layout(kind string) string { return datePattern.Replace(t.T("format." + kind)) }

// Date writes a time in the language's numeric pattern; dateOnly leaves out the time.
func (t *Tr) Date(tm time.Time, dateOnly bool) string {
	if dateOnly {
		return tm.Format(t.Layout("date"))
	}
	return tm.Format(t.Layout("datetime"))
}
