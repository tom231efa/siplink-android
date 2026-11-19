package web

import (
	"encoding/json"
	"fmt"
	"strings"

	"siplink/internal/i18n"
)

// The audit log's labels and the registration summary come from the catalogs
// (audit.json): "audit.action.{action}", "audit.object.{type}", "audit.field.{key}",
// "reg.state.{state}". A code without a text shows as it is stored.

func actionLabel(t *i18n.Tr, action string) string { return labelOr(t, "audit.action."+action, action) }

// fieldLabel names an audit detail key for the "改动" column; the stored keys stay as
// they are. Settings keys are the document's JSON names.
func fieldLabel(t *i18n.Tr, key string) string { return labelOr(t, "audit.field."+key, key) }

// objectName is the object name of an audit entry: a fixed name of the platform's
// own (a setting, such as "password_check") in words, any other name as it is stored.
func objectName(t *i18n.Tr, name string) string { return labelOr(t, "audit.name."+name, name) }

func labelOr(t *i18n.Tr, key, fallback string) string {
	if i18n.Has(t.Lang, key) {
		return t.T(key)
	}
	return fallback
}

// objectTypes are the object types of the audit filter.
var objectTypes = []string{"admin", "region", "book", "entry", "directory", "ldap_account",
	"config_template", "key_template", "client_template", "client", "backup", "system"}

// objectLabels names the object types of the audit filter.
func objectLabels(t *i18n.Tr) map[string]string {
	out := make(map[string]string, len(objectTypes))
	for _, o := range objectTypes {
		out[o] = t.T("audit.object." + o)
	}
	return out
}

// regStates are the RegState values a phone reports (§3.4), in the order of the summary.
var regStates = []string{"OK", "FAILED", "PROGRESS", "NONE", "DISABLED"}

// regSummary sums up the registrations of a report, e.g. "正常 2 · 失败 1".
func regSummary(t *i18n.Tr, raw string) string {
	var regs []struct {
		State string `json:"state"`
	}
	if json.Unmarshal([]byte(raw), &regs) != nil || len(regs) == 0 {
		return "—"
	}
	count := map[string]int{}
	for _, r := range regs {
		count[r.State]++
	}
	var parts []string
	for _, st := range regStates {
		if n := count[st]; n > 0 {
			parts = append(parts, fmt.Sprintf("%s %d", t.T("reg.state."+strings.ToLower(st)), n))
		}
	}
	return strings.Join(parts, " · ")
}
