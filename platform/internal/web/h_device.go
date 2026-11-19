package web

import (
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strings"
	"time"
	"unicode/utf8"

	"siplink/internal/auth"
	"siplink/internal/catalog"
	"siplink/internal/i18n"
	"siplink/internal/provision"
	"siplink/internal/secret"
	"siplink/internal/store"
)

// deviceLimits are the rate limits of the device API (design §6.5).
type deviceLimits struct {
	enrollFails *auth.Window // failed tokens per IP
	perKey      *auth.Window // syncs per device key
	perIP       *auth.Window // device requests per IP (NAT of a whole office)
}

func newDeviceLimits(now func() time.Time) deviceLimits {
	return deviceLimits{enrollFails: auth.NewWindow(10, 10*time.Minute, now),
		perKey: auth.NewWindow(120, time.Hour, now), perIP: auth.NewWindow(600, time.Minute, now)}
}

func (s *Server) routesDevice() {
	s.handle("GET /p/{token}", s.enrollInfo)
	s.handle("POST /p/{token}", s.device(s.enroll))
	s.handle("POST /api/device/v1/sync", s.device(s.sync))
	s.handle("POST /api/device/v1/leave", s.device(s.leave))
}

// device wraps the device API: HTTPS (as the admin UI), the per-IP limit, no CSRF
// (no cookies are used), JSON errors.
func (s *Server) device(h http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if !s.isSecure(r) && !s.Cfg.AllowHTTPProvisioning {
			jsonError(w, http.StatusForbidden, "https_required")
			return
		}
		if !s.limits.perIP.Allow(s.clientIP(r)) {
			w.Header().Set("Retry-After", "60")
			jsonError(w, http.StatusTooManyRequests, "rate_limited")
			return
		}
		h(w, r)
	}
}

func jsonError(w http.ResponseWriter, status int, code string, extra ...string) {
	body := map[string]string{"error": code}
	for i := 0; i+1 < len(extra); i += 2 {
		body[extra[i]] = extra[i+1]
	}
	writeJSON(w, status, body)
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(v)
}

// enrollInfo is the page a browser sees for a QR code: the same for every token.
func (s *Server) enrollInfo(w http.ResponseWriter, r *http.Request) {
	s.render(w, r, http.StatusOK, "enroll_info", &Page{Title: tr(r).T("clients.enroll_info.title"), Admin: nil, Bare: true})
}

// deviceReport is what the phone says about itself (§3.4).
type deviceReport struct {
	AppVersion     string `json:"appVersion"`
	AppVersionCode int64  `json:"appVersionCode"`
	AndroidRelease string `json:"androidRelease"`
	AndroidSDK     int64  `json:"androidSdk"`
	Model          string `json:"model"`
	Applied        struct {
		Version string `json:"version"`
		At      string `json:"at"`
		Error   string `json:"error"`
	} `json:"applied"`
	Registrations []struct {
		Account string `json:"account"`
		State   string `json:"state"`
		Error   string `json:"error"`
	} `json:"registrations"`
	Codecs struct {
		Audio []string `json:"audio"`
		Video []string `json:"video"`
	} `json:"codecs"`
}

type deviceRequest struct {
	InstallID string       `json:"installId"`
	Have      string       `json:"have"`
	Device    deviceReport `json:"device"`
}

func readDeviceRequest(w http.ResponseWriter, r *http.Request) (*deviceRequest, bool) {
	var req deviceRequest
	body, err := io.ReadAll(http.MaxBytesReader(w, r.Body, 64<<10))
	if err == nil && len(strings.TrimSpace(string(body))) > 0 {
		err = json.Unmarshal(body, &req)
	}
	if err != nil {
		jsonError(w, http.StatusBadRequest, "bad_request")
		return nil, false
	}
	return &req, true
}

func clip(s string, n int) string {
	if utf8.RuneCountInString(s) <= n {
		return s
	}
	return string([]rune(s)[:n])
}

// updateReport copies the device report into the stored one.
func (s *Server) updateReport(r *http.Request, rep *store.Report, d *deviceReport) {
	rep.LastSeenAt, rep.LastIP = s.Now().UTC().Format(store.TimeFormat), s.clientIP(r)
	rep.AppVersion, rep.AppVersionCode, rep.UserAgent = clip(d.AppVersion, 40), d.AppVersionCode, clip(r.UserAgent(), 200)
	rep.AndroidRelease, rep.AndroidSDK, rep.DeviceModel = clip(d.AndroidRelease, 20), d.AndroidSDK, clip(d.Model, 80)
	rep.AppliedVersion, rep.AppliedAt, rep.ApplyError = clip(d.Applied.Version, 64), clip(d.Applied.At, 40), clip(d.Applied.Error, 500)
	regs := d.Registrations
	if len(regs) > 100 {
		regs = regs[:100]
	}
	for i := range regs {
		regs[i].Account, regs[i].State, regs[i].Error = clip(regs[i].Account, 20), clip(regs[i].State, 20), clip(regs[i].Error, 200)
	}
	if b, err := json.Marshal(regs); err == nil && regs != nil {
		rep.RegistrationsJSON = string(b)
	}
	clipList := func(l []string) []string {
		var out []string
		for i, k := range l {
			if i >= 64 {
				break
			}
			if catalog.CodecProblem(k) == nil {
				out = append(out, k)
			}
		}
		return out
	}
	if err := s.St.ReportCodecs(r.Context(), rep.AppVersion, clipList(d.Codecs.Audio), clipList(d.Codecs.Video)); err != nil {
		s.Log.Error("codec report failed", "err", err)
	}
}

// buildError is what a sync stores in the client's report when the document does not
// validate: the problems in English. Only whether it is empty counts (the client list
// and the dashboard flag it; the client page builds the document again and shows the
// problems in the viewer's language).
func buildError(problems []i18n.Msg) string {
	return strings.Join(i18n.For(i18n.English).Msgs(problems), "; ")
}

// PlatformName is shown on the phone's remote configuration screen.
const PlatformName = "SipLink 管理平台"

func (s *Server) platform(r *http.Request) catalog.Platform {
	name := PlatformName
	if v, err := s.St.Meta(r.Context(), "instance_name"); err == nil && v != "" {
		name = v
	}
	p := catalog.Platform{Name: name, SyncURL: strings.TrimRight(s.Cfg.PublicURL, "/") + "/api/device/v1/sync",
		Hosted: s.hosted(), Box: s.Ext.Box, LDAPKey: s.Ext.LDAPKey, CAPEMs: s.Ext.Certs.DocumentCAs()}
	return p
}

// built is the outcome of building a client's document.
type built struct {
	doc      *provision.Document
	problems []i18n.Msg
	revKey   string
}

// buildDocument assembles and validates the current document of a client.
func (s *Server) buildDocument(r *http.Request, c *store.Client) (*built, error) {
	p := s.platform(r)
	key, err := s.St.RevisionKey(r.Context(), c.ID)
	if err != nil {
		return nil, err
	}
	in, err := catalog.Assemble(r.Context(), s.St, p, c)
	if err != nil {
		return nil, err
	}
	doc, problems := provision.Build(in)
	return &built{doc: doc, problems: problems, revKey: key + "|s" + p.ServerKey()}, nil
}

func lastDocAAD(clientID int64) []byte { return secret.AAD("client_reports", clientID, "last_doc_enc") }

// lastValid opens the last valid document sent to a client, nil when there is none.
func (s *Server) lastValid(rep *store.Report) *provision.Document {
	if rep.LastDocEnc == "" {
		return nil
	}
	b, err := s.Ext.Box.Open(rep.LastDocEnc, lastDocAAD(rep.ClientID))
	if err != nil {
		return nil
	}
	var d provision.Document
	if json.Unmarshal(b, &d) != nil {
		return nil
	}
	return &d
}

// remember stores a valid document as the one sent (sealed: it holds passwords).
func (s *Server) remember(rep *store.Report, b *built) error {
	raw, err := provision.Marshal(b.doc)
	if err != nil {
		return err
	}
	enc, err := s.Ext.Box.Seal(raw, lastDocAAD(rep.ClientID))
	if err != nil {
		return err
	}
	rep.SentVersion, rep.RevisionKey, rep.LastDocEnc, rep.BuildError = b.doc.Version, b.revKey, enc, ""
	return nil
}

func (s *Server) sendDocument(w http.ResponseWriter, d *provision.Document, deviceKey string) {
	out := *d
	out.GeneratedAt = s.Now().UTC().Format(time.RFC3339)
	out.DeviceKey = deviceKey
	raw, err := provision.Marshal(&out)
	if err != nil {
		jsonError(w, http.StatusInternalServerError, "internal")
		return
	}
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.Write(raw)
}

func (s *Server) deviceAudit(r *http.Request, action string, c *store.Client) {
	id := c.ID
	s.addAudit(r, &store.AuditEntry{AdminName: "device", Action: action, ObjectType: "client", ObjectID: &id,
		ObjectName: c.Name, RegionID: &c.RegionID})
}

// enroll binds a phone with a single-use token (§3.2).
func (s *Server) enroll(w http.ResponseWriter, r *http.Request) {
	ip := s.clientIP(r)
	if s.limits.enrollFails.Exceeded(ip) {
		w.Header().Set("Retry-After", "600")
		jsonError(w, http.StatusTooManyRequests, "rate_limited")
		return
	}
	req, ok := readDeviceRequest(w, r)
	if !ok {
		return
	}
	hash := secret.HashHex(r.PathValue("token"))
	c, err := s.St.ClientByEnrollToken(r.Context(), hash)
	if errors.Is(err, store.ErrNotFound) {
		s.limits.enrollFails.AddHold(ip)
		jsonError(w, http.StatusNotFound, "invalid_token")
		return
	}
	if err != nil {
		s.deviceFailed(w, r, err)
		return
	}
	b, err := s.buildDocument(r, c)
	if err != nil {
		s.deviceFailed(w, r, err)
		return
	}
	rep, _ := s.St.ClientReport(r.Context(), c.ID)
	if len(b.problems) > 0 {
		// The QR stays valid: nothing is bound before the configuration is right.
		rep.BuildError = buildError(b.problems)
		s.St.SaveReport(r.Context(), rep)
		jsonError(w, http.StatusConflict, "invalid_configuration")
		return
	}
	deviceKey := secret.Token(32)
	c, err = s.St.Enroll(r.Context(), hash, secret.HashHex(deviceKey), clip(req.InstallID, 64))
	if errors.Is(err, store.ErrNotFound) {
		jsonError(w, http.StatusNotFound, "invalid_token")
		return
	}
	if err != nil {
		s.deviceFailed(w, r, err)
		return
	}
	s.updateReport(r, rep, &req.Device)
	if err := s.remember(rep, b); err != nil {
		s.deviceFailed(w, r, err)
		return
	}
	if err := s.St.SaveReport(r.Context(), rep); err != nil {
		s.deviceFailed(w, r, err)
		return
	}
	s.deviceAudit(r, "device.enroll", c)
	s.sendDocument(w, b.doc, deviceKey)
}

// deviceClient authenticates the device key of a request; it answers 401 or 410 itself.
func (s *Server) deviceClient(w http.ResponseWriter, r *http.Request) *store.Client {
	token, ok := strings.CutPrefix(r.Header.Get("Authorization"), "Bearer ")
	if !ok || token == "" {
		jsonError(w, http.StatusUnauthorized, "unauthorized")
		return nil
	}
	hash := secret.HashHex(token)
	if !s.limits.perKey.Allow(hash) {
		w.Header().Set("Retry-After", "300")
		jsonError(w, http.StatusTooManyRequests, "rate_limited")
		return nil
	}
	c, err := s.St.ClientByDeviceKey(r.Context(), hash)
	if err == nil {
		return c
	}
	if !errors.Is(err, store.ErrNotFound) {
		s.deviceFailed(w, r, err)
		return nil
	}
	if reason, err := s.St.Revoked(r.Context(), hash); err == nil && reason != "" {
		jsonError(w, http.StatusGone, "revoked", "reason", reason)
		return nil
	}
	jsonError(w, http.StatusUnauthorized, "unauthorized")
	return nil
}

func (s *Server) deviceFailed(w http.ResponseWriter, r *http.Request, err error) {
	s.Log.Error("device request failed", "route", logPattern(r), "err", err)
	w.Header().Set("Retry-After", "60")
	jsonError(w, http.StatusServiceUnavailable, "unavailable")
}

// sync answers 204 when the phone has the current document, else 200 with it (§3.3).
func (s *Server) sync(w http.ResponseWriter, r *http.Request) {
	c := s.deviceClient(w, r)
	if c == nil {
		return
	}
	req, ok := readDeviceRequest(w, r)
	if !ok {
		return
	}
	ctx := r.Context()
	rep, err := s.St.ClientReport(ctx, c.ID)
	if err != nil {
		s.deviceFailed(w, r, err)
		return
	}
	s.updateReport(r, rep, &req.Device)
	key, err := s.St.RevisionKey(ctx, c.ID)
	if err != nil {
		s.deviceFailed(w, r, err)
		return
	}
	// Cheap path: nothing the document depends on changed since it was sent.
	if rep.BuildError == "" && rep.SentVersion != "" && rep.RevisionKey == key+"|s"+s.platform(r).ServerKey() {
		s.St.SaveReport(ctx, rep)
		if req.Have == rep.SentVersion {
			w.WriteHeader(http.StatusNoContent)
			return
		}
		if d := s.lastValid(rep); d != nil {
			s.sendDocument(w, d, "")
			return
		}
	}
	b, err := s.buildDocument(r, c)
	if err != nil {
		s.deviceFailed(w, r, err)
		return
	}
	doc := b.doc
	if len(b.problems) > 0 {
		rep.BuildError = buildError(b.problems)
		if doc = s.lastValid(rep); doc == nil {
			s.St.SaveReport(ctx, rep)
			s.deviceFailed(w, r, errors.New("no valid document for client"))
			return
		}
	} else if err := s.remember(rep, b); err != nil {
		s.deviceFailed(w, r, err)
		return
	}
	if err := s.St.SaveReport(ctx, rep); err != nil {
		s.deviceFailed(w, r, err)
		return
	}
	if req.Have == doc.Version {
		w.WriteHeader(http.StatusNoContent)
		return
	}
	s.sendDocument(w, doc, "")
}

// leave: the user removed the remote configuration on the phone (§3.3).
func (s *Server) leave(w http.ResponseWriter, r *http.Request) {
	c := s.deviceClient(w, r)
	if c == nil {
		return
	}
	if err := s.newEnrollment(r, c); err != nil {
		s.deviceFailed(w, r, err)
		return
	}
	s.deviceAudit(r, "device.leave", c)
	w.WriteHeader(http.StatusNoContent)
}
