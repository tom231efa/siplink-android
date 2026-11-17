package web

import (
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"reflect"
	"slices"
	"strconv"
	"strings"

	"siplink/internal/auth"
	"siplink/internal/books"
	"siplink/internal/catalog"
	"siplink/internal/provision"
	"siplink/internal/rules"
	"siplink/internal/store"
)

func (s *Server) routesTemplates() {
	s.page("GET /config-templates", s.configList)
	s.page("GET /config-templates/new", s.configNew)
	s.page("POST /config-templates", s.configCreate)
	s.page("GET /config-templates/{id}", s.configShow)
	s.page("POST /config-templates/{id}/meta", s.configMeta)
	s.page("POST /config-templates/{id}/settings", s.configSettings)
	s.page("POST /config-templates/{id}/cas", s.configCAAdd)
	s.page("POST /config-templates/{id}/cas/remove", s.configCARemove)
	s.page("POST /config-templates/{id}/delete", s.configDelete)
	s.page("GET /config-templates/{id}/profiles/new", s.profileForm)
	s.page("GET /config-templates/{id}/profiles/{pid}", s.profileForm)
	s.page("POST /config-templates/{id}/profiles", s.profileSave)
	s.page("POST /config-templates/{id}/profiles/{pid}", s.profileSave)
	s.page("POST /config-templates/{id}/profiles/order", s.profileOrder)
	s.page("POST /config-templates/{id}/profiles/{pid}/{op}", s.profileOp)
	s.page("GET /config-templates/{id}/directories/new", s.directoryForm)
	s.page("GET /config-templates/{id}/directories/{did}", s.directoryForm)
	s.page("POST /config-templates/{id}/directories", s.directorySave)
	s.page("POST /config-templates/{id}/directories/{did}", s.directorySave)
	s.page("POST /config-templates/{id}/directories/order", s.directoryOrder)
	s.page("POST /config-templates/{id}/directories/{did}/delete", s.directoryDelete)
	s.routesKeyTemplates()
	s.routesClientTemplates()
}

// templateScope is what an administrator may see of templates (own + global).
func (s *Server) templateScope(r *http.Request) store.BookScope { return s.bookScope(r) }

// owned is anything with a region (nil = global).
func checkOwned(act auth.Actor, region *int64, edit bool) (visible, editable bool) {
	return act.Can(auth.TemplateView, region), edit && act.Can(auth.TemplateEdit, region)
}

type templateRow struct {
	ID      int64
	Name    string
	Note    string
	Region  string
	Global  bool
	Clients int
	Extra   string
}

func (s *Server) configList(w http.ResponseWriter, r *http.Request) {
	list, err := s.St.ListConfigTemplates(r.Context(), s.templateScope(r))
	if err != nil {
		s.internal(w, r, err)
		return
	}
	names, _ := s.allRegionNames(r)
	var rows []templateRow
	for _, t := range list {
		n, _ := s.St.ClientCountOfConfigTemplate(r.Context(), t.ID)
		profiles, _ := s.St.Profiles(r.Context(), t.ID)
		rows = append(rows, templateRow{ID: t.ID, Name: t.Name, Note: t.Note, Region: regionLabel(names, t.RegionID),
			Global: t.RegionID == nil, Clients: n, Extra: strconv.Itoa(len(profiles)) + " 个账号"})
	}
	s.ok(w, r, "templates", &Page{Title: "配置模板", Nav: "config", Data: templatesData{Rows: rows, Base: "/config-templates", Kind: "配置模板"}})
}

type templatesData struct {
	Rows []templateRow
	Base string
	Kind string
}

// metaForm is the name / region / note form shared by the three template kinds.
type metaForm struct {
	Base    string
	Kind    string
	ID      int64
	Name    string
	Note    string
	Region  *int64
	Regions []store.Region
	Global  bool
	Error   string
}

func (s *Server) metaFormPage(w http.ResponseWriter, r *http.Request, status int, f metaForm) {
	a := current(r)
	f.Regions, _ = s.St.ListRegions(r.Context(), a.RegionIDs, a.Role == store.RoleSuper)
	f.Global = a.Role == store.RoleSuper
	s.render(w, r, status, "template_new", &Page{Title: "新建" + f.Kind, Nav: navOf(f.Base), Data: f})
}

func navOf(base string) string {
	return map[string]string{"/config-templates": "config", "/key-templates": "keys", "/client-templates": "clienttpl"}[base]
}

func (s *Server) configNew(w http.ResponseWriter, r *http.Request) {
	s.metaFormPage(w, r, http.StatusOK, metaForm{Base: "/config-templates", Kind: "配置模板", Region: s.defaultRegion(r)})
}

// defaultRegion is the region preselected in a new object's form.
func (s *Server) defaultRegion(r *http.Request) *int64 {
	if sel := s.selectedRegion(r); sel != 0 {
		return &sel
	}
	if a := current(r); a.Role != store.RoleSuper && len(a.RegionIDs) > 0 {
		return &a.RegionIDs[0]
	}
	return nil
}

// readMeta reads name, note and region (when editable) and checks the region.
func (s *Server) readMeta(r *http.Request, name, note *string, region **int64, regionEditable bool) string {
	*name, *note = trimmed(r, "name"), truncate(trimmed(r, "note"), books.MaxNote)
	if regionEditable {
		*region = formRegion(r)
	}
	if msg := catalog.NameProblem(*name); msg != "" {
		return msg
	}
	if !actor(r).Can(auth.TemplateEdit, *region) {
		return "您无权在所选区域创建或修改模板。"
	}
	if *region != nil {
		if _, err := s.St.RegionByID(r.Context(), **region); err != nil {
			return "所选区域不存在。"
		}
	}
	return ""
}

func (s *Server) configCreate(w http.ResponseWriter, r *http.Request) {
	t := &store.ConfigTemplate{SettingsJSON: catalog.SettingsJSON(provision.DefaultSettings()), RefreshMinutes: 60}
	f := metaForm{Base: "/config-templates", Kind: "配置模板"}
	if f.Error = s.readMeta(r, &t.Name, &t.Note, &t.RegionID, true); f.Error == "" {
		if err := s.St.CreateConfigTemplate(r.Context(), t); errors.Is(err, store.ErrConflict) {
			f.Error = "同一区域已有同名模板。"
		} else if err != nil {
			s.internal(w, r, err)
			return
		}
	}
	if f.Error != "" {
		f.Name, f.Note, f.Region = t.Name, t.Note, t.RegionID
		s.metaFormPage(w, r, http.StatusBadRequest, f)
		return
	}
	s.audit(r, "config_template.create", "config_template", t.ID, t.Name, t.RegionID, nil)
	s.redirect(w, r, "/config-templates/"+itoa(t.ID), "ok", "配置模板已创建。")
}

// loadConfig returns the template of the path when visible (and editable when edit).
func (s *Server) loadConfig(w http.ResponseWriter, r *http.Request, edit bool) *store.ConfigTemplate {
	id, ok := pathID(r, "id")
	t, err := s.St.ConfigTemplateByID(r.Context(), id)
	if !ok || errors.Is(err, store.ErrNotFound) {
		s.notFound(w, r)
		return nil
	}
	if err != nil {
		s.internal(w, r, err)
		return nil
	}
	return s.checkTemplate(w, r, t.RegionID, edit, t)
}

func (s *Server) checkTemplate(w http.ResponseWriter, r *http.Request, region *int64, edit bool, t *store.ConfigTemplate) *store.ConfigTemplate {
	visible, editable := checkOwned(actor(r), region, edit)
	if !visible {
		s.notFound(w, r)
		return nil
	}
	if edit && !editable {
		s.fail(w, r, http.StatusForbidden, "全局模板仅可由超级管理员修改。")
		return nil
	}
	return t
}

var configTabs = []struct{ ID, Label string }{
	{"accounts", "账号"}, {"calls", "高级设置"}, {"features", "功能码"}, {"general", "接听与保活"},
	{"media", "音视频"}, {"security", "安全"}, {"directories", "通讯录"}, {"advanced", "同步与管理"},
}

type configData struct {
	T           *store.ConfigTemplate
	Tab         string
	Tabs        []struct{ ID, Label string }
	Region      string
	CanEdit     bool
	Clients     int
	Users       []string
	Settings    provision.Settings
	Profiles    []*store.Profile
	Directories []directoryRow
	CAs         []catalog.CAInfo
	PlatformCAs []catalog.CAInfo
	Codecs      *provision.Codecs
	CodecGroups []codecGroup
	HostedNote  string
	HostedPlain bool
	Error       string
	Options     settingOptions
	HasPassword bool
	RegionList  []store.Region
}

type directoryRow struct {
	*store.Directory
	Line string
	Mode string // the password handling that applies (store.Directory.Password)
}

type codecRow struct {
	Kind, Key string
	Enabled   bool
	Order     int // 1 = first; 0 = not in the template
}

// codecGroup is the audio or the video part of the codec table, in order.
type codecGroup struct {
	Kind, Label string
	Rows        []codecRow
}

type settingOptions struct {
	MediaSecurity []rules.MediaSecurity
	DndModes      []rules.DndMode
	DtmfModes     []rules.DtmfMode
	VideoSizes    []rules.VideoSize
	FrameRates    []int
	Transports    []rules.SipTransport
	AutoAnswer    []int
}

func options() settingOptions {
	return settingOptions{rules.MediaSecurities, rules.DndModes, rules.DtmfModes, rules.VideoSizes, rules.FrameRates, rules.SipTransports,
		rules.AutoAnswerDelays}
}

func (s *Server) configShow(w http.ResponseWriter, r *http.Request) {
	t := s.loadConfig(w, r, false)
	if t == nil {
		return
	}
	s.renderConfig(w, r, http.StatusOK, t, r.URL.Query().Get("tab"), "")
}

func (s *Server) renderConfig(w http.ResponseWriter, r *http.Request, status int, t *store.ConfigTemplate, tab, errMsg string) {
	ctx := r.Context()
	if !slices.ContainsFunc(configTabs, func(x struct{ ID, Label string }) bool { return x.ID == tab }) {
		tab = "accounts"
	}
	d := configData{T: t, Tab: tab, Tabs: configTabs, CanEdit: actor(r).Can(auth.TemplateEdit, t.RegionID), Error: errMsg,
		Settings: catalog.ParseSettings(t.SettingsJSON), Options: options(), HasPassword: t.ConfigPasswordHash != ""}
	names, _ := s.allRegionNames(r)
	d.Region = regionLabel(names, t.RegionID)
	if a := current(r); a.Role == store.RoleSuper {
		d.RegionList, _ = s.St.ListRegions(ctx, nil, true)
	}
	d.Clients, _ = s.St.ClientCountOfConfigTemplate(ctx, t.ID)
	d.Users, _ = s.St.ConfigTemplateUsers(ctx, t.ID)
	var err error
	if d.Profiles, err = s.St.Profiles(ctx, t.ID); err != nil {
		s.internal(w, r, err)
		return
	}
	dirs, err := s.St.Directories(ctx, t.ID)
	if err != nil {
		s.internal(w, r, err)
		return
	}
	for _, dir := range dirs {
		row := directoryRow{Directory: dir}
		row.Mode, _, _ = dir.Password()
		for _, p := range d.Profiles {
			if dir.LineProfileID != nil && *dir.LineProfileID == p.ID {
				row.Line = p.Name
			}
		}
		d.Directories = append(d.Directories, row)
	}
	d.CAs, _ = catalog.SplitCAs(t.CAPEMs)
	d.PlatformCAs, _ = catalog.SplitCAs(strings.Join(s.Ext.Certs.DocumentCAs(), ""))
	d.Codecs = catalog.ParseCodecs(t.CodecsJSON)
	d.CodecGroups = s.codecGroups(r, d.Codecs)
	sec, ok := s.hosted().Security()
	switch {
	case !ok:
		d.HostedNote = rules.HostedNeedsName
	case sec == rules.LdapSecurityNone:
		d.HostedPlain = true
	}
	s.render(w, r, status, "config_template", &Page{Title: t.Name, Nav: "config", Data: d})
}

// hosted describes the platform's LDAP service for templates and documents.
func (s *Server) hosted() catalog.Hosted {
	return catalog.Hosted{Host: s.Cfg.LDAPPublicHost, Port: s.Cfg.LDAPPublicPort, Suffix: s.Cfg.LDAPSuffix, RequireTLS: s.Cfg.LDAPRequireTLS}
}

// codecGroups lists the template's codecs first, then the keys phones reported, each
// kind in its own group; a row's Order is its place in the group.
func (s *Server) codecGroups(r *http.Request, c *provision.Codecs) []codecGroup {
	var rows []codecRow
	seen := map[string]bool{}
	if c != nil {
		for kind, list := range map[string][]provision.Codec{"audio": c.Audio, "video": c.Video} {
			for i, x := range list {
				rows = append(rows, codecRow{Kind: kind, Key: x.Key, Enabled: x.Enabled, Order: i + 1})
				seen[kind+"|"+x.Key] = true
			}
		}
	}
	reported, _ := s.St.ReportedCodecs(r.Context())
	for _, kind := range []string{"audio", "video"} {
		for _, k := range reported[kind] {
			if !seen[kind+"|"+k] {
				rows = append(rows, codecRow{Kind: kind, Key: k, Order: 0})
			}
		}
	}
	slices.SortStableFunc(rows, func(a, b codecRow) int {
		if (a.Order == 0) != (b.Order == 0) {
			if a.Order == 0 {
				return 1
			}
			return -1
		}
		return a.Order - b.Order
	})
	groups := []codecGroup{{Kind: "audio", Label: "音频"}, {Kind: "video", Label: "视频"}}
	for i := range groups {
		for _, row := range rows {
			if row.Kind == groups[i].Kind {
				row.Order = len(groups[i].Rows) + 1
				groups[i].Rows = append(groups[i].Rows, row)
			}
		}
	}
	return slices.DeleteFunc(groups, func(g codecGroup) bool { return len(g.Rows) == 0 })
}

func (s *Server) configMeta(w http.ResponseWriter, r *http.Request) {
	t := s.loadConfig(w, r, true)
	if t == nil {
		return
	}
	old := *t
	msg := s.readMeta(r, &t.Name, &t.Note, &t.RegionID, current(r).Role == store.RoleSuper)
	if msg == "" && !sameRegion(old.RegionID, t.RegionID) {
		msg = s.configMoveProblem(r, t)
	}
	if msg == "" {
		if err := s.St.UpdateConfigTemplate(r.Context(), t); errors.Is(err, store.ErrConflict) {
			msg = "同一区域已有同名模板。"
		} else if err != nil {
			s.internal(w, r, err)
			return
		}
	}
	if msg != "" {
		s.renderConfig(w, r, http.StatusBadRequest, &old, "advanced", msg)
		return
	}
	s.audit(r, "config_template.update", "config_template", t.ID, t.Name, t.RegionID, changes(
		field{"name", old.Name, t.Name}, field{"note", old.Note, t.Note}, field{"region_id", derefOr(old.RegionID), derefOr(t.RegionID)}))
	s.redirect(w, r, "/config-templates/"+itoa(t.ID)+"?tab=advanced", "ok", "已保存。")
}

// configMoveProblem refuses a move that would break the ownership rule (§2.3): the
// template's directories and its client templates must stay compatible.
func (s *Server) configMoveProblem(r *http.Request, t *store.ConfigTemplate) string {
	dirs, _ := s.St.Directories(r.Context(), t.ID)
	for _, d := range dirs {
		var region *int64
		if d.BookID != nil {
			if b, err := s.St.BookByID(r.Context(), *d.BookID); err == nil {
				region = b.RegionID
			}
		} else if d.External != nil {
			region = d.External.RegionID
		}
		if !compatible(t.RegionID, region) {
			return "模板引用了其他区域的通讯录「" + d.Source + "」，无法移至该区域。"
		}
	}
	users, _ := s.St.ListClientTemplates(r.Context(), store.BookScope{All: true})
	for _, ct := range users {
		if ct.ConfigTemplateID == t.ID && t.RegionID != nil && (ct.RegionID == nil || *ct.RegionID != *t.RegionID) {
			return "客户端模板「" + ct.Name + "」使用此模板，移动后将违反区域规则。"
		}
	}
	return ""
}

func checkbox(r *http.Request, name string) bool { return r.FormValue(name) == "1" }

func intValue(r *http.Request, name string, def int) int {
	n, err := strconv.Atoi(strings.TrimSpace(r.FormValue(name)))
	if err != nil {
		return def
	}
	return n
}

// configSettings saves one settings tab (calls, features, general, media, advanced).
func (s *Server) configSettings(w http.ResponseWriter, r *http.Request) {
	t := s.loadConfig(w, r, true)
	if t == nil {
		return
	}
	old := *t
	set := catalog.ParseSettings(t.SettingsJSON)
	before := set
	tab := r.FormValue("tab")
	var problems []string
	switch tab {
	case "calls":
		set.DndMode = rules.DndMode(r.FormValue("dndMode"))
		set.CallWaiting, set.RejectAnonymous = checkbox(r, "callWaiting"), checkbox(r, "rejectAnonymous")
		set.DtmfMode = rules.DtmfMode(r.FormValue("dtmfMode"))
		set.ForwardAlways, set.ForwardBusy, set.ForwardNoAnswer = trimmed(r, "forwardAlways"), trimmed(r, "forwardBusy"), trimmed(r, "forwardNoAnswer")
		set.ForwardNoAnswerSeconds = intValue(r, "forwardNoAnswerSeconds", -1)
		set.UserAgent = r.FormValue("userAgent")
		set.AdaptiveRateControl, set.EchoCancellation = checkbox(r, "adaptiveRateControl"), checkbox(r, "echoCancellation")
		set.IPv6 = checkbox(r, "ipv6")
	case "features":
		f := &set.Features
		for _, p := range []struct {
			name string
			v    *string
		}{{"park", &f.Park}, {"listen", &f.Listen}, {"whisper", &f.Whisper}, {"barge", &f.Barge}, {"forceHangUp", &f.ForceHangUp},
			{"pickup", &f.Pickup}, {"groupPickup", &f.GroupPickup}, {"voicemail", &f.Voicemail}, {"intercom", &f.Intercom},
			{"dndOn", &f.DndOn}, {"dndOff", &f.DndOff}} {
			*p.v = trimmed(r, p.name)
			if strings.ContainsAny(*p.v, " \t") || len(*p.v) > 32 {
				problems = append(problems, "功能码不得包含空格，最多 32 个字符")
			}
		}
	case "general":
		set.KeepAlive, set.IntercomAutoAnswer, set.IntercomSpeaker = checkbox(r, "keepAlive"), checkbox(r, "intercomAutoAnswer"), checkbox(r, "intercomSpeaker")
		// The wait keeps its stored value while auto answer is off (its select is disabled then).
		if set.AutoAnswer = checkbox(r, "autoAnswer"); set.AutoAnswer {
			set.AutoAnswerSeconds = intValue(r, "autoAnswerSeconds", -1)
		}
	case "security":
		set.VerifyTLSCertificate, set.VerifyTLSHostname = checkbox(r, "verifyTlsCertificate"), checkbox(r, "verifyTlsHostname")
		set.MediaSecurity = rules.MediaSecurity(r.FormValue("mediaSecurity"))
	case "media":
		set.VideoCalls = checkbox(r, "videoCalls")
		set.VideoSize = rules.VideoSize(r.FormValue("videoSize"))
		set.VideoFrameRate = intValue(r, "videoFrameRate", -1)
		set.CallRecording = checkbox(r, "callRecording")
		set.RecordByDefault = set.CallRecording && checkbox(r, "recordByDefault")
		codecs, p := readCodecs(r)
		problems = append(problems, p...)
		t.CodecsJSON = catalog.CodecsJSON(codecs)
	case "advanced":
		set.AutoUpdateCheck = checkbox(r, "autoUpdateCheck")
		t.RefreshMinutes = intValue(r, "refreshMinutes", -1)
		if p := catalog.RefreshProblem(t.RefreshMinutes); p != "" {
			problems = append(problems, p)
		}
		switch {
		case checkbox(r, "clearPassword"):
			t.ConfigPasswordHash = ""
		case r.FormValue("configPassword") != "":
			hash, p := catalog.ConfigPasswordHash(r.FormValue("configPassword"))
			if p != "" {
				problems = append(problems, p)
			}
			t.ConfigPasswordHash = hash
		}
	default:
		s.notFound(w, r)
		return
	}
	problems = append(problems, provision.SettingsProblems(set)...)
	if len(problems) > 0 {
		s.renderConfig(w, r, http.StatusBadRequest, &old, tab, strings.Join(problems, "；"))
		return
	}
	t.SettingsJSON = catalog.SettingsJSON(set)
	if err := s.St.UpdateConfigTemplate(r.Context(), t); err != nil {
		s.internal(w, r, err)
		return
	}
	detail := settingsChanges(before, set)
	if old.ConfigPasswordHash != t.ConfigPasswordHash {
		detail["config_password"] = "已修改"
	}
	if old.RefreshMinutes != t.RefreshMinutes {
		detail["refresh_minutes"] = map[string]any{"old": old.RefreshMinutes, "new": t.RefreshMinutes}
	}
	if old.CodecsJSON != t.CodecsJSON {
		detail["codecs"] = "已修改"
	}
	s.audit(r, "config_template.update", "config_template", t.ID, t.Name, t.RegionID, detail)
	s.redirect(w, r, "/config-templates/"+itoa(t.ID)+"?tab="+tab, "ok", "已保存。")
}

// settingsChanges lists the changed settings fields by their JSON names.
func settingsChanges(a, b provision.Settings) map[string]any {
	out := map[string]any{}
	var ma, mb map[string]any
	_ = json.Unmarshal([]byte(catalog.SettingsJSON(a)), &ma)
	_ = json.Unmarshal([]byte(catalog.SettingsJSON(b)), &mb)
	for k, v := range mb {
		if !reflect.DeepEqual(ma[k], v) {
			out[k] = map[string]any{"old": ma[k], "new": v}
		}
	}
	return out
}

// readCodecs reads the codec table of the media tab; mode "default" = SDK defaults.
func readCodecs(r *http.Request) (*provision.Codecs, []string) {
	if r.FormValue("codecMode") != "custom" {
		return nil, nil
	}
	type item struct {
		kind, key string
		on        bool
		order     int
	}
	var items []item
	var problems []string
	for i, key := range r.Form["codecKey"] {
		key = strings.TrimSpace(key)
		if key == "" {
			continue
		}
		kind := formAt(r, "codecKind", i)
		order, err := strconv.Atoi(formAt(r, "codecOrder", i))
		if err != nil || order <= 0 {
			continue // no order number = not named, so the phone keeps it off at the end
		}
		items = append(items, item{kind, key, formAt(r, "codecOn", i) == "1", order})
	}
	if key := trimmed(r, "newCodec"); key != "" {
		if p := catalog.CodecProblem(key); p != "" {
			problems = append(problems, p)
		} else {
			kind := "audio"
			if r.FormValue("newCodecKind") == "video" {
				kind = "video"
			}
			items = append(items, item{kind, key, true, 1 << 20})
		}
	}
	slices.SortStableFunc(items, func(a, b item) int { return a.order - b.order })
	c := &provision.Codecs{Audio: []provision.Codec{}, Video: []provision.Codec{}}
	seen := map[string]bool{}
	for _, it := range items {
		if p := catalog.CodecProblem(it.key); p != "" {
			problems = append(problems, p)
			continue
		}
		if seen[it.kind+it.key] {
			continue
		}
		seen[it.kind+it.key] = true
		if it.kind == "video" {
			c.Video = append(c.Video, provision.Codec{Key: it.key, Enabled: it.on})
		} else {
			c.Audio = append(c.Audio, provision.Codec{Key: it.key, Enabled: it.on})
		}
	}
	return c, problems
}

// formAt returns the i-th value of a repeated field ("" when missing).
func formAt(r *http.Request, name string, i int) string {
	if v := r.Form[name]; i < len(v) {
		return v[i]
	}
	return ""
}

func (s *Server) configCAAdd(w http.ResponseWriter, r *http.Request) {
	t := s.loadConfig(w, r, true)
	if t == nil {
		return
	}
	r.Body = http.MaxBytesReader(w, r.Body, 1<<20)
	if err := r.ParseMultipartForm(1 << 20); err != nil && !errors.Is(err, http.ErrNotMultipart) {
		s.renderConfig(w, r, http.StatusBadRequest, t, "security", "文件过大（最大 1 MB）")
		return
	}
	text := r.FormValue("pem")
	if f, _, err := r.FormFile("file"); err == nil {
		b, _ := io.ReadAll(io.LimitReader(f, 1<<20))
		f.Close()
		text += "\n" + string(b)
	}
	added, err := catalog.SplitCAs(text)
	if err == nil && len(added) == 0 {
		err = errors.New("未找到证书")
	}
	if err != nil {
		s.renderConfig(w, r, http.StatusBadRequest, t, "security", err.Error())
		return
	}
	merged := catalog.MergeCAs(t.CAPEMs, text)
	if len(merged) > catalog.MaxCAs {
		s.renderConfig(w, r, http.StatusBadRequest, t, "security", "最多 50 个 CA 证书")
		return
	}
	t.CAPEMs = strings.Join(merged, "")
	if err := s.St.UpdateConfigTemplate(r.Context(), t); err != nil {
		s.internal(w, r, err)
		return
	}
	s.audit(r, "config_template.update", "config_template", t.ID, t.Name, t.RegionID, map[string]any{"ca_certificates": len(merged)})
	s.redirect(w, r, "/config-templates/"+itoa(t.ID)+"?tab=security", "ok", "CA 证书已添加。")
}

func (s *Server) configCARemove(w http.ResponseWriter, r *http.Request) {
	t := s.loadConfig(w, r, true)
	if t == nil {
		return
	}
	list, _ := catalog.SplitCAs(t.CAPEMs)
	var keep []string
	for _, c := range list {
		if c.Fingerprint != r.FormValue("fingerprint") {
			keep = append(keep, c.PEM)
		}
	}
	t.CAPEMs = strings.Join(keep, "")
	if err := s.St.UpdateConfigTemplate(r.Context(), t); err != nil {
		s.internal(w, r, err)
		return
	}
	s.audit(r, "config_template.update", "config_template", t.ID, t.Name, t.RegionID, map[string]any{"ca_certificates": len(keep)})
	s.redirect(w, r, "/config-templates/"+itoa(t.ID)+"?tab=security", "ok", "CA 证书已移除。")
}

func (s *Server) configDelete(w http.ResponseWriter, r *http.Request) {
	t := s.loadConfig(w, r, true)
	if t == nil {
		return
	}
	if err := s.St.DeleteConfigTemplate(r.Context(), t.ID); errors.Is(err, store.ErrInUse) {
		s.redirect(w, r, "/config-templates/"+itoa(t.ID)+"?tab=advanced", "error", "模板仍被客户端模板使用，无法删除。")
		return
	} else if err != nil {
		s.internal(w, r, err)
		return
	}
	s.audit(r, "config_template.delete", "config_template", t.ID, t.Name, t.RegionID, nil)
	s.redirect(w, r, "/config-templates", "ok", "配置模板「"+t.Name+"」已删除。")
}
