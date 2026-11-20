// Package provision builds the configuration document the SipLink app loads
// (design §3.5) and serves enrollment, sync and leave (§3.1–§3.3).
package provision

import "siplink/internal/rules"

// Schema is the major version of the document; the app refuses higher ones.
const Schema = 1

// Document is the JSON contract with the app's loader. Field names are those of
// design §3.5; they follow the app's model (core/model/Models.kt).
type Document struct {
	Schema             int          `json:"schema"`
	Version            string       `json:"version"`
	GeneratedAt        string       `json:"generatedAt"`
	DeviceKey          string       `json:"deviceKey,omitempty"`
	Platform           PlatformInfo `json:"platform"`
	Client             ClientInfo   `json:"client"`
	RefreshMinutes     int          `json:"refreshMinutes"`
	ConfigPasswordHash string       `json:"configPasswordHash"`
	Settings           Settings     `json:"settings"`
	Accounts           []Account    `json:"accounts"`
	DefaultAccount     string       `json:"defaultAccount"`
	Codecs             *Codecs      `json:"codecs,omitempty"`
	CACertificates     []string     `json:"caCertificates"`
	Directories        []Directory  `json:"directories"`
	Shortcuts          []Shortcut   `json:"shortcuts"`
}

// PlatformInfo is shown on the phone's remote configuration screen.
type PlatformInfo struct {
	Name    string `json:"name"`
	SyncURL string `json:"syncUrl"`
}

// ClientInfo names the client on the phone.
type ClientInfo struct {
	Name   string `json:"name"`
	Region string `json:"region"`
}

// Settings are the locked fields of the app's Settings (§3.5.3). The personal ones of
// ManagedConfig.localUpdate are never sent.
type Settings struct {
	KeepAlive              bool                `json:"keepAlive"`
	IntercomAutoAnswer     bool                `json:"intercomAutoAnswer"`
	IntercomSpeaker        bool                `json:"intercomSpeaker"`
	VerifyTLSCertificate   bool                `json:"verifyTlsCertificate"`
	VerifyTLSHostname      bool                `json:"verifyTlsHostname"`
	MediaSecurity          rules.MediaSecurity `json:"mediaSecurity"`
	DndMode                rules.DndMode       `json:"dndMode"`
	CallWaiting            bool                `json:"callWaiting"`
	RejectAnonymous        bool                `json:"rejectAnonymous"`
	DtmfMode               rules.DtmfMode      `json:"dtmfMode"`
	ForwardAlways          string              `json:"forwardAlways"`
	ForwardBusy            string              `json:"forwardBusy"`
	ForwardNoAnswer        string              `json:"forwardNoAnswer"`
	ForwardNoAnswerSeconds int                 `json:"forwardNoAnswerSeconds"`
	Features               rules.FeatureCodes  `json:"features"`
	VideoCalls             bool                `json:"videoCalls"`
	VideoSize              rules.VideoSize     `json:"videoSize"`
	VideoFrameRate         int                 `json:"videoFrameRate"`
	AutoUpdateCheck        bool                `json:"autoUpdateCheck"`
	// Options of the SIP library on the app's 高级设置 page (platform decision of 2026-11-16).
	UserAgent           string `json:"userAgent"` // "" = the app's own name
	AdaptiveRateControl bool   `json:"adaptiveRateControl"`
	EchoCancellation    bool   `json:"echoCancellation"`
	IPv6                bool   `json:"ipv6"`
	// Call recording (agreed with the app on 2026-11-17): whether the user may record,
	// and whether every call is recorded from the moment it is connected.
	CallRecording   bool `json:"callRecording"`
	RecordByDefault bool `json:"recordByDefault"`
	// Auto answer of regular incoming calls (agreed with the app on 2026-11-17), apart from
	// intercomAutoAnswer; AutoAnswerSeconds is one of rules.AutoAnswerDelays.
	AutoAnswer        bool `json:"autoAnswer"`
	AutoAnswerSeconds int  `json:"autoAnswerSeconds"`
	// The conference room and the adaptation to the network (agreed with the app on
	// 2026-11-20). Each is left out while it keeps the app's default, so that the document of a
	// template that does not set it stays as it was, with the same version: the app reads an
	// absent field as its default. Read them with RoomSize and AutoNetworkOn, set them with
	// SetRoomLimit and SetAutoNetwork.
	RoomLimit   *int   `json:"roomLimit,omitempty"`   // members besides the user, rules.MinRoomLimit–rules.RoomLimit
	AutoNetwork *bool  `json:"autoNetwork,omitempty"` // the lines adapt to the network by themselves
	StunServer  string `json:"stunServer,omitempty"`  // host[:port] the line checks ask; "" = none
}

// RoomSize is the size of the conference room: RoomLimit, or the app's default.
func (s Settings) RoomSize() int {
	if s.RoomLimit == nil {
		return rules.RoomLimit
	}
	return *s.RoomLimit
}

// SetRoomLimit sets the size of the conference room; the app's default leaves the field out.
func (s *Settings) SetRoomLimit(n int) {
	s.RoomLimit = nil
	if n != rules.RoomLimit {
		s.RoomLimit = &n
	}
}

// AutoNetworkOn is AutoNetwork, or the app's default (on).
func (s Settings) AutoNetworkOn() bool { return s.AutoNetwork == nil || *s.AutoNetwork }

// SetAutoNetwork sets whether the lines adapt to the network; the app's default (on) leaves
// the field out.
func (s *Settings) SetAutoNetwork(on bool) {
	s.AutoNetwork = nil
	if !on {
		s.AutoNetwork = &on
	}
}

// DefaultSettings are the app's defaults (Settings()) for the locked fields. Stored
// templates that lack a field read it as this default (catalog.ParseSettings).
func DefaultSettings() Settings {
	d := rules.DefaultSettings()
	return Settings{
		KeepAlive: d.KeepAlive, IntercomAutoAnswer: d.IntercomAutoAnswer, IntercomSpeaker: d.IntercomSpeaker,
		VerifyTLSCertificate: d.VerifyTLSCertificate, VerifyTLSHostname: d.VerifyTLSHostname,
		MediaSecurity: d.MediaSecurity, DndMode: d.DndMode, CallWaiting: d.CallWaiting,
		RejectAnonymous: d.RejectAnonymous, DtmfMode: d.DtmfMode, ForwardAlways: d.ForwardAlways,
		ForwardBusy: d.ForwardBusy, ForwardNoAnswer: d.ForwardNoAnswer, ForwardNoAnswerSeconds: d.ForwardNoAnswerSeconds,
		Features: d.Features, VideoCalls: d.VideoCalls, VideoSize: d.VideoSize, VideoFrameRate: d.VideoFrameRate,
		AutoUpdateCheck: d.AutoUpdateCheck,
		UserAgent:       "", AdaptiveRateControl: false, EchoCancellation: true, IPv6: true,
		CallRecording: true, RecordByDefault: false,
		AutoAnswer: false, AutoAnswerSeconds: 3,
		// RoomLimit, AutoNetwork and StunServer: absent, the app's defaults.
	}
}

// Account is one account of the document (§3.5.4), fields of AccountDraft plus key.
type Account struct {
	Key          string `json:"key"`
	Name         string `json:"name"`
	Username     string `json:"username"`
	Password     string `json:"password"`
	AuthUser     string `json:"authUser"`
	DisplayName  string `json:"displayName"`
	Domain       string `json:"domain"`
	Port         string `json:"port"`
	Server       string `json:"server"`
	BackupServer string `json:"backupServer"`
	// ServerTimeoutMs: rules.MinServerTimeoutMs–rules.MaxServerTimeoutMs; 0 (off) leaves
	// the field out, as the app's default.
	ServerTimeoutMs int                `json:"serverTimeoutMs,omitempty"`
	DNSServer       string             `json:"dnsServer"`
	Transport       rules.SipTransport `json:"transport"`
	Expires         int                `json:"expires"`
	SubscribeMwi    bool               `json:"subscribeMwi"`
	Enabled         bool               `json:"enabled"`
}

// Directory is one LDAP directory of the document (§3.5.5), fields of LdapDirectory plus key.
type Directory struct {
	Key              string             `json:"key"`
	Name             string             `json:"name"`
	Enabled          bool               `json:"enabled"`
	Host             string             `json:"host"`
	Port             int                `json:"port"`
	Security         rules.LdapSecurity `json:"security"`
	BindDn           string             `json:"bindDn"`
	Password         string             `json:"password"`
	BaseDn           string             `json:"baseDn"`
	Line             string             `json:"line"`
	LookupIncoming   bool               `json:"lookupIncoming"`
	LookupOutgoing   bool               `json:"lookupOutgoing"`
	MaxResults       int                `json:"maxResults"`
	NameAttributes   string             `json:"nameAttributes"`
	NumberAttributes string             `json:"numberAttributes"`
	NameFilter       string             `json:"nameFilter"`
	NumberFilter     string             `json:"numberFilter"`
}

// Codecs is the order and on/off state of codecs (§3.5.6); absent = SDK defaults.
type Codecs struct {
	Audio []Codec `json:"audio"`
	Video []Codec `json:"video"`
}

// Codec is one codec by its Codec.key ("mime/clockRate/channels").
type Codec struct {
	Key     string `json:"key"`
	Enabled bool   `json:"enabled"`
}

// Shortcut is one handed-out key (§3.5.7) in the words of the app's CSV format.
type Shortcut struct {
	Label    string `json:"label"`
	Number   string `json:"number"`
	Action   string `json:"action"`
	Category string `json:"category"`
	Line     string `json:"line"`
	Icon     string `json:"icon"`
}
