package rules

import (
	"reflect"
	"strings"
	"testing"
)

// Tests ported from VideoRulesTest.kt, IncomingPolicyTest.kt and ManagedConfigTest.kt
// (android/app/src/test/java/com/sky/siplink/core/logic/).

// VideoRulesTest.aFrameRateThatCannotBeChosenIsLeftToTheCore
func TestVideoFrameRate(t *testing.T) {
	if !reflect.DeepEqual(FrameRates, []int{0, 15, 24, 30}) {
		t.Error(FrameRates)
	}
	for _, rate := range FrameRates {
		if FrameRate(rate) != rate {
			t.Error(rate)
		}
	}
	for _, rate := range []int{-1, 1, 20, 25, 60} {
		if FrameRate(rate) != AutoFrameRate {
			t.Error(rate)
		}
	}
}

// Not a Kotlin test: the waits of auto answer for regular calls and how a stored value that
// cannot be chosen is brought back to the nearest one.
func TestAutoAnswerDelay(t *testing.T) {
	if !reflect.DeepEqual(AutoAnswerDelays, []int{0, 3, 5, 10, 20, 30}) {
		t.Error(AutoAnswerDelays)
	}
	for _, d := range AutoAnswerDelays {
		if AutoAnswerDelay(d) != d {
			t.Error(d)
		}
	}
	for in, want := range map[int]int{-5: 0, 1: 0, 2: 3, 4: 5, 6: 5, 8: 10, 15: 20, 16: 20, 25: 30, 26: 30, 999: 30} {
		if got := AutoAnswerDelay(in); got != want {
			t.Errorf("%d: %d, want %d", in, got, want)
		}
	}
}

// VideoRulesTest.aFrameRateOfTheAppsChoosingComesWithALimitOnWhatIsSent
func TestVideoUploadLimit(t *testing.T) {
	// Left to the SIP core, the rate needs none: the core is modest by itself.
	for _, size := range VideoSizes {
		if UploadLimit(size, AutoFrameRate) != NoUploadLimit {
			t.Error(size)
		}
	}
	cases := []struct {
		size VideoSize
		rate int
		want int
	}{{"QVGA", 30, 356}, {"VGA", 30, 868}, {"HD", 30, 1636}, {"FHD", 30, 3172}, {"VGA", 15, 484}, {"QVGA", 24, 304}}
	for _, c := range cases {
		if got := UploadLimit(c.size, c.rate); got != c.want {
			t.Errorf("%v %d: %d", c.size, c.rate, got)
		}
	}
	// A larger picture or more frames never get less.
	for _, rate := range FrameRates {
		if rate == AutoFrameRate {
			continue
		}
		last := 0
		for _, size := range VideoSizes {
			limit := UploadLimit(size, rate)
			if limit <= 0 || limit < last {
				t.Errorf("%v %d: %d", size, rate, limit)
			}
			last = limit
		}
	}
}

// IncomingPolicyTest.`idle line rings without forwarding by default` (the delay part) and
// IncomingPolicyTest.`no answer forward carries a clamped delay`.
func TestNoAnswerSeconds(t *testing.T) {
	if got := ClampNoAnswerSeconds(DefaultSettings().ForwardNoAnswerSeconds); got != 20 {
		t.Error(got)
	}
	if ClampNoAnswerSeconds(1) != 5 || ClampNoAnswerSeconds(999) != 120 {
		t.Error("not clamped")
	}
}

// managedCurrent is `current` of ManagedConfigTest.
func managedCurrent() Settings {
	s := DefaultSettings()
	s.ForwardBusy = "1002"
	s.Features.Park = "701"
	s.VideoCalls = false
	return s
}

// everythingChanged is `everythingChanged` of ManagedConfigTest.
func everythingChanged() Settings {
	c := managedCurrent()
	features := DefaultFeatureCodes()
	features.Park, features.Voicemail, features.DndOn = "702", "", "*76"
	return Settings{
		KeepAlive:                !c.KeepAlive,
		IntercomAutoAnswer:       !c.IntercomAutoAnswer,
		IntercomSpeaker:          !c.IntercomSpeaker,
		ThemeMode:                "DARK",
		DynamicColor:             !c.DynamicColor,
		CallBannerPosition:       "TOP",
		VerifyTLSCertificate:     !c.VerifyTLSCertificate,
		VerifyTLSHostname:        !c.VerifyTLSHostname,
		MediaSecurity:            "SRTP_MANDATORY",
		DoNotDisturb:             !c.DoNotDisturb,
		DndMode:                  "SERVER",
		CallWaiting:              !c.CallWaiting,
		RejectAnonymous:          !c.RejectAnonymous,
		DtmfMode:                 "SIP_INFO",
		ForwardAlways:            "1003",
		ForwardBusy:              "1004",
		ForwardNoAnswer:          "1005",
		ForwardNoAnswerSeconds:   c.ForwardNoAnswerSeconds + 10,
		Features:                 features,
		VideoCalls:               !c.VideoCalls,
		VideoSize:                "HD",
		VideoFrameRate:           30,
		LockScreenPromptDeclined: !c.LockScreenPromptDeclined,
		AutoUpdateCheck:          !c.AutoUpdateCheck,
	}
}

func personalChanged() Settings {
	p := managedCurrent()
	e := everythingChanged()
	p.DoNotDisturb, p.ThemeMode, p.DynamicColor = e.DoNotDisturb, e.ThemeMode, e.DynamicColor
	p.CallBannerPosition, p.LockScreenPromptDeclined = e.CallBannerPosition, e.LockScreenPromptDeclined
	return p
}

// ManagedConfigTest.`the test changes every setting`
func TestManagedTestChangesEverySetting(t *testing.T) {
	before, after := reflect.ValueOf(managedCurrent()), reflect.ValueOf(everythingChanged())
	if before.NumField() != 24 {
		t.Errorf("Settings has %d fields, the Kotlin class 24", before.NumField())
	}
	for i := 0; i < before.NumField(); i++ {
		if reflect.DeepEqual(before.Field(i).Interface(), after.Field(i).Interface()) {
			t.Errorf("Setting %d is not changed by the test", i+1)
		}
	}
}

// ManagedConfigTest.`only the user's own settings are taken from a local change`
func TestManagedOnlyOwnSettingsTaken(t *testing.T) {
	if got := LocalUpdate(managedCurrent(), everythingChanged()); got != personalChanged() {
		t.Errorf("%+v", got)
	}
}

// ManagedConfigTest.`a change of personal settings is taken in full`
func TestManagedPersonalChangeTaken(t *testing.T) {
	c := managedCurrent()
	if LocalUpdate(c, personalChanged()) != personalChanged() {
		t.Error("personal")
	}
	dnd := c
	dnd.DoNotDisturb = true
	declined := c
	declined.LockScreenPromptDeclined = true
	if LocalUpdate(c, dnd) != dnd || LocalUpdate(c, declined) != declined {
		t.Error("dnd or lock screen answer")
	}
}

// ManagedConfigTest.`a change of locked settings comes to nothing`
func TestManagedLockedChangeIgnored(t *testing.T) {
	locked := []func(*Settings){
		func(s *Settings) { s.KeepAlive = !s.KeepAlive },
		func(s *Settings) { s.IntercomAutoAnswer = !s.IntercomAutoAnswer },
		func(s *Settings) { s.IntercomSpeaker = !s.IntercomSpeaker },
		func(s *Settings) { s.VerifyTLSCertificate = false },
		func(s *Settings) { s.VerifyTLSHostname = false },
		func(s *Settings) { s.MediaSecurity = "SRTP_OPTIONAL" },
		func(s *Settings) { s.DndMode = "SERVER" },
		func(s *Settings) { s.CallWaiting = !s.CallWaiting },
		func(s *Settings) { s.RejectAnonymous = !s.RejectAnonymous },
		func(s *Settings) { s.DtmfMode = "SIP_INFO" },
		func(s *Settings) { s.ForwardAlways = "1003" },
		func(s *Settings) { s.ForwardBusy = "" },
		func(s *Settings) { s.ForwardNoAnswer = "1003" },
		func(s *Settings) { s.ForwardNoAnswerSeconds = 45 },
		func(s *Settings) { s.Features.Intercom = "" },
		func(s *Settings) { s.Features = DefaultFeatureCodes() },
		func(s *Settings) { s.VideoCalls = !s.VideoCalls },
		func(s *Settings) { s.VideoSize = "QVGA" },
		func(s *Settings) { s.VideoFrameRate = 24 },
		// An administrator decides whether the phones ask GitHub for updates.
		func(s *Settings) { s.AutoUpdateCheck = !s.AutoUpdateCheck },
	}
	current := managedCurrent()
	for i, change := range locked {
		wanted := current
		change(&wanted)
		if wanted == current {
			t.Errorf("Change %d changes nothing", i)
		}
		if LocalUpdate(current, wanted) != current {
			t.Errorf("Change %d was taken", i)
		}
	}
}

// ManagedConfigTest.`who keeps do not disturb is locked while its switch is not`
func TestManagedDndModeLocked(t *testing.T) {
	server := managedCurrent()
	server.DndMode, server.DoNotDisturb = "SERVER", true
	wanted := server
	wanted.DndMode, wanted.DoNotDisturb = "SOFTWARE", false
	taken := LocalUpdate(server, wanted)
	want := server
	want.DoNotDisturb = false
	if taken != want || taken == wanted {
		t.Errorf("%+v", taken)
	}
}

// ManagedConfigTest.`nothing changes when nothing is asked for`
func TestManagedNothingAsked(t *testing.T) {
	if LocalUpdate(managedCurrent(), managedCurrent()) != managedCurrent() {
		t.Error("changed")
	}
}

// ManagedConfigTest.`the password of the configuration is the one its hash was made of`
func TestManagedPassword(t *testing.T) {
	abc := "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
	chinese := "6608321b199d7bba1d7fd8c4dc6daf3c808e1b05598c8607898061dab35fa667"
	cases := []struct {
		hash, typed string
		want        bool
	}{
		{abc, "abc", true},
		{strings.ToUpper(abc), "abc", true},
		{"  " + abc + "\n", "abc", true},
		{chinese, "查看密码1", true},
		{abc, "abd", false},
		{abc, "ABC", false},
		{abc, "", false},
		{abc, abc, false},
	}
	for _, c := range cases {
		if IsPassword(c.hash, c.typed) != c.want {
			t.Errorf("%q %q", c.hash, c.typed)
		}
	}
	if PasswordHash("abc") != abc {
		t.Error("PasswordHash")
	}
}

// ManagedConfigTest.`without a hash, or with something that is none, nothing is the password`
func TestManagedNoHash(t *testing.T) {
	for _, c := range [][2]string{{"", ""}, {"", "abc"}, {"   ", "abc"}, {"abc", "abc"}, {"not a hash", "not a hash"}} {
		if IsPassword(c[0], c[1]) {
			t.Errorf("%q %q", c[0], c[1])
		}
	}
}

// Not a Kotlin test: what SettingsStore.load makes of values it does not know.
func TestCoerceSettings(t *testing.T) {
	s := DefaultSettings()
	s.ThemeMode, s.DndMode, s.VideoSize, s.VideoFrameRate, s.ForwardNoAnswerSeconds = "dark", "", "UHD", 25, 999
	got := CoerceSettings(s)
	want := DefaultSettings()
	want.ForwardNoAnswerSeconds = 999
	if got != want {
		t.Errorf("%+v", got)
	}
	if CoerceSettings(everythingChanged()) != everythingChanged() {
		t.Error("valid values changed")
	}
	if VideoSize("FHD").Definition() != "1080p" || VideoSize("HD").Width() != 1280 || VideoSize("QVGA").Height() != 240 {
		t.Error("video size data")
	}
}

// RoomRules.limitOf: the app clamps a room size into MIN_LIMIT..LIMIT.
func TestRoomLimitOf(t *testing.T) {
	if RoomLimit != 30 || MinRoomLimit != 2 {
		t.Errorf("bounds %d–%d", MinRoomLimit, RoomLimit)
	}
	for in, want := range map[int]int{-5: 2, 0: 2, 1: 2, 2: 2, 17: 17, 30: 30, 31: 30, 1000: 30} {
		if got := RoomLimitOf(in); got != want {
			t.Errorf("RoomLimitOf(%d) = %d, want %d", in, got, want)
		}
	}
}

// Not a Kotlin rule yet: the STUN server takes the address rule of an account's server.
func TestStunServer(t *testing.T) {
	for v, ok := range map[string]bool{"": true, "stun.example.com": true, "stun.example.com:3478": true, "192.0.2.1": true,
		"[::1]:3478": true, " stun.example.com": false, "stun:stun.example.com": false, "stun.example.com:0": false,
		"stun.example.com:99999": false, "[::1": false, "stun example.com": false} {
		if IsValidStunServer(v) != ok {
			t.Errorf("IsValidStunServer(%q) != %v", v, ok)
		}
	}
}
