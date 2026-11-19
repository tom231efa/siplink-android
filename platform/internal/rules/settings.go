package rules

import (
	"crypto/sha256"
	"crypto/subtle"
	"encoding/hex"
	"siplink/internal/i18n"
)

// ThemeMode values are the Kotlin enum names.
// Port of enum ThemeMode (android/app/src/main/java/com/sky/siplink/core/model/Models.kt).
type ThemeMode string

// ThemeModes lists every ThemeMode in the order of the Kotlin enum.
var ThemeModes = []ThemeMode{"SYSTEM", "LIGHT", "DARK"}

// Valid reports whether m is one of ThemeModes.
func (m ThemeMode) Valid() bool { return contains(ThemeModes, m) }

// MediaSecurity values are the Kotlin enum names.
// Port of enum MediaSecurity (android/app/src/main/java/com/sky/siplink/core/model/Models.kt).
type MediaSecurity string

// MediaSecurities lists every MediaSecurity in the order of the Kotlin enum.
var MediaSecurities = []MediaSecurity{"NONE", "SRTP_OPTIONAL", "SRTP_MANDATORY"}

// Valid reports whether m is one of MediaSecurities.
func (m MediaSecurity) Valid() bool { return contains(MediaSecurities, m) }

// DndMode says who refuses the calls while do not disturb is on; values are the Kotlin enum names.
// Port of enum DndMode (android/app/src/main/java/com/sky/siplink/core/model/Models.kt).
type DndMode string

// DndModes lists every DndMode in the order of the Kotlin enum.
var DndModes = []DndMode{"SOFTWARE", "SERVER"}

// Valid reports whether m is one of DndModes.
func (m DndMode) Valid() bool { return contains(DndModes, m) }

// DtmfMode says how the keys pressed during a call reach the other side; values are the
// Kotlin enum names.
// Port of enum DtmfMode (android/app/src/main/java/com/sky/siplink/core/model/Models.kt).
type DtmfMode string

// DtmfModes lists every DtmfMode in the order of the Kotlin enum.
var DtmfModes = []DtmfMode{"RFC2833", "SIP_INFO"}

// Valid reports whether m is one of DtmfModes.
func (m DtmfMode) Valid() bool { return contains(DtmfModes, m) }

// BannerPosition says where the strip back to a running call sits; values are the Kotlin
// enum names.
// Port of enum BannerPosition (android/app/src/main/java/com/sky/siplink/core/model/Models.kt).
type BannerPosition string

// BannerPositions lists every BannerPosition in the order of the Kotlin enum.
var BannerPositions = []BannerPosition{"TOP", "BOTTOM"}

// Valid reports whether p is one of BannerPositions.
func (p BannerPosition) Valid() bool { return contains(BannerPositions, p) }

// VideoSize is how large the picture is that the camera is sent with; values are the Kotlin
// enum names.
// Port of enum VideoSize (android/app/src/main/java/com/sky/siplink/core/model/Models.kt).
type VideoSize string

// VideoSizes lists every VideoSize in the order of the Kotlin enum.
var VideoSizes = []VideoSize{"QVGA", "VGA", "HD", "FHD"}

var videoSizeData = map[VideoSize]struct {
	definition    string
	width, height int
}{
	"QVGA": {"qvga", 320, 240},
	"VGA":  {"vga", 640, 480},
	"HD":   {"720p", 1280, 720},
	"FHD":  {"1080p", 1920, 1080},
}

// Valid reports whether s is one of VideoSizes.
func (s VideoSize) Valid() bool { return contains(VideoSizes, s) }

// Definition is what the SIP core calls the size (VideoSize.definition).
func (s VideoSize) Definition() string { return videoSizeData[s].definition }

// Width is VideoSize.width in pixels.
func (s VideoSize) Width() int { return videoSizeData[s].width }

// Height is VideoSize.height in pixels.
func (s VideoSize) Height() int { return videoSizeData[s].height }

// FeatureCodes are the PBX feature codes.
// Port of data class FeatureCodes (android/app/src/main/java/com/sky/siplink/core/model/Models.kt);
// DefaultFeatureCodes gives its defaults.
type FeatureCodes struct {
	Park        string `json:"park"`
	Listen      string `json:"listen"`
	Whisper     string `json:"whisper"`
	Barge       string `json:"barge"`
	ForceHangUp string `json:"forceHangUp"`
	Pickup      string `json:"pickup"`
	GroupPickup string `json:"groupPickup"`
	Voicemail   string `json:"voicemail"`
	Intercom    string `json:"intercom"`
	DndOn       string `json:"dndOn"`
	DndOff      string `json:"dndOff"`
}

// DefaultFeatureCodes returns FeatureCodes() with the Kotlin default values.
func DefaultFeatureCodes() FeatureCodes {
	return FeatureCodes{
		Park: "700", Listen: "*91", Whisper: "*92", Barge: "*93", ForceHangUp: "*94",
		Pickup: "**", GroupPickup: "*8", Voicemail: "*97", Intercom: "*80", DndOn: "*78", DndOff: "*79",
	}
}

// Settings are the settings of the app.
// Port of data class Settings (android/app/src/main/java/com/sky/siplink/core/model/Models.kt);
// DefaultSettings gives its defaults.
type Settings struct {
	KeepAlive                bool           `json:"keepAlive"`
	IntercomAutoAnswer       bool           `json:"intercomAutoAnswer"`
	IntercomSpeaker          bool           `json:"intercomSpeaker"`
	ThemeMode                ThemeMode      `json:"themeMode"`
	DynamicColor             bool           `json:"dynamicColor"`
	CallBannerPosition       BannerPosition `json:"callBannerPosition"`
	VerifyTLSCertificate     bool           `json:"verifyTlsCertificate"`
	VerifyTLSHostname        bool           `json:"verifyTlsHostname"`
	MediaSecurity            MediaSecurity  `json:"mediaSecurity"`
	DoNotDisturb             bool           `json:"doNotDisturb"`
	DndMode                  DndMode        `json:"dndMode"`
	CallWaiting              bool           `json:"callWaiting"`
	RejectAnonymous          bool           `json:"rejectAnonymous"`
	DtmfMode                 DtmfMode       `json:"dtmfMode"`
	ForwardAlways            string         `json:"forwardAlways"`
	ForwardBusy              string         `json:"forwardBusy"`
	ForwardNoAnswer          string         `json:"forwardNoAnswer"`
	ForwardNoAnswerSeconds   int            `json:"forwardNoAnswerSeconds"`
	Features                 FeatureCodes   `json:"features"`
	VideoCalls               bool           `json:"videoCalls"`
	VideoSize                VideoSize      `json:"videoSize"`
	VideoFrameRate           int            `json:"videoFrameRate"`
	LockScreenPromptDeclined bool           `json:"lockScreenPromptDeclined"`
	AutoUpdateCheck          bool           `json:"autoUpdateCheck"`
}

// DefaultSettings returns Settings() with the Kotlin default values.
func DefaultSettings() Settings {
	return Settings{
		KeepAlive:              true,
		IntercomSpeaker:        true,
		ThemeMode:              "SYSTEM",
		CallBannerPosition:     "BOTTOM",
		VerifyTLSCertificate:   true,
		VerifyTLSHostname:      true,
		MediaSecurity:          "NONE",
		DndMode:                "SOFTWARE",
		CallWaiting:            true,
		DtmfMode:               "RFC2833",
		ForwardNoAnswerSeconds: 20,
		Features:               DefaultFeatureCodes(),
		VideoCalls:             true,
		VideoSize:              "VGA",
		VideoFrameRate:         AutoFrameRate,
		AutoUpdateCheck:        true,
	}
}

// CoerceSettings brings settings to what the app makes of them when it reads them: an enum
// value that is no name of its Kotlin enum falls back to the default (enumOf), and a frame
// rate that cannot be chosen becomes AutoFrameRate (VideoRules.frameRate).
// ForwardNoAnswerSeconds is stored as it is; the app clamps it only where it is used
// (ClampNoAnswerSeconds).
// Port of the value handling of SettingsStore.load (android/app/src/main/java/com/sky/siplink/core/SettingsStore.kt).
func CoerceSettings(s Settings) Settings {
	d := DefaultSettings()
	if !s.ThemeMode.Valid() {
		s.ThemeMode = d.ThemeMode
	}
	if !s.CallBannerPosition.Valid() {
		s.CallBannerPosition = d.CallBannerPosition
	}
	if !s.MediaSecurity.Valid() {
		s.MediaSecurity = d.MediaSecurity
	}
	if !s.DndMode.Valid() {
		s.DndMode = d.DndMode
	}
	if !s.DtmfMode.Valid() {
		s.DtmfMode = d.DtmfMode
	}
	if !s.VideoSize.Valid() {
		s.VideoSize = d.VideoSize
	}
	s.VideoFrameRate = FrameRate(s.VideoFrameRate)
	return s
}

// The range of the delay before a call that is not answered is forwarded.
// Port of IncomingPolicy.MIN_NO_ANSWER_SECONDS / MAX_NO_ANSWER_SECONDS
// (android/app/src/main/java/com/sky/siplink/core/logic/IncomingPolicy.kt).
const (
	MinNoAnswerSeconds = 5
	MaxNoAnswerSeconds = 120
)

// ClampNoAnswerSeconds is the delay the app forwards an unanswered call after.
// Port of `forwardNoAnswerSeconds.coerceIn(MIN_NO_ANSWER_SECONDS, MAX_NO_ANSWER_SECONDS)` in
// IncomingPolicy.decide (android/app/src/main/java/com/sky/siplink/core/logic/IncomingPolicy.kt).
func ClampNoAnswerSeconds(seconds int) int {
	return min(max(seconds, MinNoAnswerSeconds), MaxNoAnswerSeconds)
}

// Frame rates. Port of VideoRules.FRAME_RATES, AUTO_FRAME_RATE and NO_UPLOAD_LIMIT
// (android/app/src/main/java/com/sky/siplink/core/logic/VideoRules.kt).
const (
	AutoFrameRate = 0
	NoUploadLimit = 0
)

// FrameRates are the frame rates that can be chosen; AutoFrameRate leaves it to the SIP core.
var FrameRates = []int{0, 15, 24, 30}

// FrameRate is value when it is one of FrameRates, else AutoFrameRate.
// Port of VideoRules.frameRate (android/app/src/main/java/com/sky/siplink/core/logic/VideoRules.kt).
func FrameRate(value int) int {
	if contains(FrameRates, value) {
		return value
	}
	return AutoFrameRate
}

// AutoAnswerDelays are the waits, in seconds, after which a regular incoming call is answered
// by itself (settings key autoAnswerSeconds, agreed with the app on 2026-11-17); 0 answers at once.
var AutoAnswerDelays = []int{0, 3, 5, 10, 20, 30}

// AutoAnswerDelay is the one of AutoAnswerDelays nearest to seconds; of two equally near, the
// longer, as in the app (AutoAnswerRules.seconds): a call that rings a little longer does less
// harm than one answered too soon. It brings a stored value that cannot be chosen back to one
// that can.
func AutoAnswerDelay(seconds int) int {
	best := AutoAnswerDelays[0]
	for _, d := range AutoAnswerDelays[1:] {
		if abs(d-seconds) <= abs(best-seconds) {
			best = d
		}
	}
	return best
}

func abs(n int) int {
	if n < 0 {
		return -n
	}
	return n
}

// The size of the conference room: how many members it holds besides the user.
// Port of RoomRules.LIMIT and MIN_LIMIT (android/app/src/main/java/com/sky/siplink/core/logic/RoomRules.kt).
const (
	RoomLimit    = 30
	MinRoomLimit = 2
)

// RoomLimitOf is the size of the room a setting asks for, within what the room can hold.
// Port of RoomRules.limitOf (android/app/src/main/java/com/sky/siplink/core/logic/RoomRules.kt).
func RoomLimitOf(setting int) int { return min(max(setting, MinRoomLimit), RoomLimit) }

// IsValidStunServer reports whether value can be the STUN server of the line checks: empty
// for none, else `host[:port]` as IsValidHostPort takes it (a name or an IPv4 address, an IPv6
// address in brackets, a port 1–65535). The app has no rule of its own for it yet; this is
// the rule of an account's server address (AccountRules.isValidHostPort).
func IsValidStunServer(value string) bool { return value == "" || IsValidHostPort(value) }

const (
	fullFrameRate = 30  // VideoRules.FULL_FRAME_RATE
	soundKbits    = 100 // VideoRules.SOUND_KBITS
)

// UploadLimit is how much the app sends at most, in kbit/s with the sound included.
// Port of VideoRules.uploadLimit (android/app/src/main/java/com/sky/siplink/core/logic/VideoRules.kt).
func UploadLimit(size VideoSize, frameRate int) int {
	if frameRate == AutoFrameRate {
		return NoUploadLimit
	}
	atFullRate := map[VideoSize]int{"QVGA": 256, "VGA": 768, "HD": 1536, "FHD": 3072}[size]
	return atFullRate*frameRate/fullFrameRate + soundKbits
}

// ManagedNotice is what a change tried on the phone is answered with while the configuration
// is managed. Port of ManagedConfig.NOTICE (android/app/src/main/java/com/sky/siplink/core/logic/ManagedConfig.kt).
var ManagedNotice = i18n.M("rules.managed")

// LocalUpdate is what a settings change made on the phone comes to while the configuration
// is managed: only the user's own settings (the do not disturb switch, the appearance, the
// answer to the lock screen question) are taken from wanted.
// Port of ManagedConfig.localUpdate (android/app/src/main/java/com/sky/siplink/core/logic/ManagedConfig.kt).
func LocalUpdate(current, wanted Settings) Settings {
	out := current
	out.DoNotDisturb = wanted.DoNotDisturb
	out.ThemeMode = wanted.ThemeMode
	out.DynamicColor = wanted.DynamicColor
	out.CallBannerPosition = wanted.CallBannerPosition
	out.LockScreenPromptDeclined = wanted.LockScreenPromptDeclined
	return out
}

// IsPassword reports whether typed is the password of a managed configuration. hash is the
// SHA-256 of the password (as UTF-8) in hex, in either case of the letters, blanks around it
// ignored; an empty hash stands for "no password was set", which nothing typed can be.
// Port of ManagedConfig.isPassword (android/app/src/main/java/com/sky/siplink/core/logic/ManagedConfig.kt).
func IsPassword(hash, typed string) bool {
	expected := lowercase(trim(hash))
	if expected == "" {
		return false
	}
	return subtle.ConstantTimeCompare([]byte(expected), []byte(PasswordHash(typed))) == 1
}

// PasswordHash is the hash IsPassword checks against: the SHA-256 of password as UTF-8, in
// lower case hex. It is what the platform hands out with a managed configuration.
func PasswordHash(password string) string {
	sum := sha256.Sum256([]byte(password))
	return hex.EncodeToString(sum[:])
}
