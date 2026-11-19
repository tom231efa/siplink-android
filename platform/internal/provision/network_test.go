package provision_test

import (
	"context"
	"encoding/json"
	"strings"
	"testing"

	"siplink/internal/catalog"
	"siplink/internal/i18n"
	"siplink/internal/provision"
	"siplink/internal/store"
)

// TestGoldenRoomAndNetwork: a template that sets the room size, turns the adaptation to the
// network off and names a STUN server carries the three fields; the other golden files,
// whose templates leave them at the app's defaults, do not.
func TestGoldenRoomAndNetwork(t *testing.T) {
	w := newWorld(t, "platform.example.com", true)
	cfg := w.config("网络", func(s *provision.Settings, _ *store.ConfigTemplate) {
		s.SetRoomLimit(8)
		s.SetAutoNetwork(false)
		s.StunServer = "stun.example.com:3478"
	})
	p := w.profile(cfg, "总部", "pbx.example.com", "UDP", true)
	tpl := &store.ClientTemplate{Name: "网络", ConfigTemplateID: cfg.ID}
	w.must(w.st.CreateClientTemplate(context.Background(), tpl))
	c := w.client("net", tpl, []store.CredentialInput{{ProfileID: p.ID, Username: "1001", Password: "test-password-1001"}}, nil, nil)
	d, problems := w.document(c)
	if len(problems) > 0 {
		t.Fatal(problems)
	}
	golden(t, "testdata/room-network.json", d)
	if d.Settings.RoomSize() != 8 || d.Settings.AutoNetworkOn() || d.Settings.StunServer != "stun.example.com:3478" {
		t.Errorf("settings: %+v", d.Settings)
	}
}

// settingsKeys are the keys of the document's settings object.
func settingsKeys(t *testing.T, s provision.Settings) map[string]any {
	t.Helper()
	raw, err := json.Marshal(s)
	if err != nil {
		t.Fatal(err)
	}
	var m map[string]any
	if err := json.Unmarshal(raw, &m); err != nil {
		t.Fatal(err)
	}
	return m
}

// TestRoomAndNetworkOnlyWhenSet: each of the three fields is in the document only while it
// differs from the app's default, alone, with its value.
func TestRoomAndNetworkOnlyWhenSet(t *testing.T) {
	d := provision.DefaultSettings()
	if d.RoomSize() != 30 || !d.AutoNetworkOn() || d.StunServer != "" {
		t.Errorf("defaults: %d %v %q", d.RoomSize(), d.AutoNetworkOn(), d.StunServer)
	}
	fields := []string{"roomLimit", "autoNetwork", "stunServer"}
	for _, c := range []struct {
		name string
		edit func(*provision.Settings)
		want map[string]any // the three fields in the JSON
	}{
		{"defaults", func(*provision.Settings) {}, map[string]any{}},
		{"defaults set", func(s *provision.Settings) { s.SetRoomLimit(30); s.SetAutoNetwork(true) }, map[string]any{}},
		{"room", func(s *provision.Settings) { s.SetRoomLimit(2) }, map[string]any{"roomLimit": float64(2)}},
		{"network", func(s *provision.Settings) { s.SetAutoNetwork(false) }, map[string]any{"autoNetwork": false}},
		{"stun", func(s *provision.Settings) { s.StunServer = "192.0.2.7" }, map[string]any{"stunServer": "192.0.2.7"}},
		{"room back", func(s *provision.Settings) { s.SetRoomLimit(12); s.SetRoomLimit(30) }, map[string]any{}},
	} {
		s := provision.DefaultSettings()
		c.edit(&s)
		m := settingsKeys(t, s)
		got := map[string]any{}
		for _, f := range fields {
			if v, ok := m[f]; ok {
				got[f] = v
			}
		}
		if len(got) != len(c.want) {
			t.Errorf("%s: %v, want %v", c.name, got, c.want)
			continue
		}
		for k, v := range c.want {
			if got[k] != v {
				t.Errorf("%s: %s = %v, want %v", c.name, k, got[k], v)
			}
		}
	}
}

// TestRoomAndNetworkRules: the template refuses a room size outside 2–30 (the app would
// clamp it, so the platform never sends one) and a STUN server that is no host[:port]; a
// stored value is read as the app reads it.
func TestRoomAndNetworkRules(t *testing.T) {
	zh := i18n.For(i18n.Source)
	problems := func(edit func(*provision.Settings)) string {
		s := provision.DefaultSettings()
		edit(&s)
		return strings.Join(zh.Msgs(provision.SettingsProblems(s)), "|")
	}
	for n, ok := range map[int]bool{-1: false, 0: false, 1: false, 2: true, 17: true, 30: true, 31: false, 99: false} {
		got := problems(func(s *provision.Settings) { s.SetRoomLimit(n) })
		if (got == "") != ok || !ok && got != "会议室人数上限应为 2–30" {
			t.Errorf("room %d: %q", n, got)
		}
	}
	for v, ok := range map[string]bool{"": true, "stun.example.com": true, "stun.example.com:3478": true, "192.0.2.1:19302": true,
		"[2001:db8::1]": true, "[2001:db8::1]:3478": true, "stun:stun.example.com": false, "stun.example.com:0": false,
		"stun.example.com:65536": false, "stun.example.com:": false, "a b": false, "[2001:db8::1": false, "host_name": false} {
		got := problems(func(s *provision.Settings) { s.StunServer = v })
		if (got == "") != ok || !ok && !strings.HasPrefix(got, "STUN 服务器应为") {
			t.Errorf("stun %q: %q", v, got)
		}
	}
	for stored, want := range map[string]int{`{}`: 30, `{"roomLimit":30}`: 30, `{"roomLimit":8}`: 8, `{"roomLimit":1}`: 2, `{"roomLimit":99}`: 30} {
		s := catalog.ParseSettings(stored)
		if s.RoomSize() != want || (want == 30) != (s.RoomLimit == nil) {
			t.Errorf("%s: %d %v", stored, s.RoomSize(), s.RoomLimit)
		}
	}
	if s := catalog.ParseSettings(`{"autoNetwork":true}`); s.AutoNetwork != nil || !s.AutoNetworkOn() {
		t.Error("stored default of autoNetwork kept")
	}
	if s := catalog.ParseSettings(`{"autoNetwork":false,"stunServer":"stun.example.com"}`); s.AutoNetworkOn() || s.StunServer != "stun.example.com" {
		t.Errorf("stored values: %+v", s)
	}
}
