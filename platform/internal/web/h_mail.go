package web

import (
	"context"
	"net/http"
	"strconv"

	"siplink/internal/mail"
	"siplink/internal/store"
)

// 邮件 (/mail): the SMTP settings, super administrators only (design §6.8). The
// password is write-only.

func (s *Server) routesMail() {
	s.page("GET /mail", s.requireSuper(s.mailPage))
	s.page("POST /mail", s.requireSuper(s.mailSave))
	s.page("POST /mail/test", s.requireSuper(s.mailTest))
}

// mailSettings loads the SMTP settings with the password opened.
func (s *Server) mailSettings(ctx context.Context) (mail.Settings, error) {
	m, err := s.St.MailSettings(ctx)
	if err != nil {
		return mail.Settings{}, err
	}
	set := settingsOf(m)
	if m.PasswordEnc != "" {
		pw, err := s.Ext.Box.Open(m.PasswordEnc, store.MailPasswordAAD())
		if err != nil {
			return set, err
		}
		set.Password = string(pw)
	}
	return set, nil
}

// settingsOf is a stored configuration without its password.
func settingsOf(m *store.MailRow) mail.Settings {
	return mail.Settings{Host: m.Host, Port: m.Port, Security: m.Security, Username: m.Username, From: m.FromAddress, FromName: m.FromName}
}

type mailData struct {
	S           mail.Settings // without the password
	PasswordSet bool
	Error       string
	TestTo      string
	TestError   string
}

func (s *Server) mailPage(w http.ResponseWriter, r *http.Request) {
	s.renderMail(w, r, http.StatusOK, mailData{})
}

func (s *Server) renderMail(w http.ResponseWriter, r *http.Request, status int, d mailData) {
	m, err := s.St.MailSettings(r.Context())
	if err != nil {
		s.internal(w, r, err)
		return
	}
	if d.Error == "" {
		d.S = settingsOf(m)
	}
	d.PasswordSet = m.PasswordEnc != ""
	s.render(w, r, status, "mail", &Page{Title: "邮件", Nav: "mail", Data: d})
}

func (s *Server) mailSave(w http.ResponseWriter, r *http.Request) {
	old, err := s.St.MailSettings(r.Context())
	if err != nil {
		s.internal(w, r, err)
		return
	}
	set := mail.Settings{Host: trimmed(r, "host"), Security: r.FormValue("security"), Username: trimmed(r, "username"),
		From: trimmed(r, "from_address"), FromName: trimmed(r, "from_name")}
	set.Port, _ = strconv.Atoi(trimmed(r, "port"))
	if trimmed(r, "port") == "" {
		set.Port = mail.DefaultPort(set.Security)
	}
	if msg := set.Problem(); msg != "" {
		s.renderMail(w, r, http.StatusBadRequest, mailData{S: set, Error: msg})
		return
	}
	enc := old.PasswordEnc
	pw := r.FormValue("password")
	// The stored password goes only to the server it was typed for.
	moved := set.Host != old.Host || set.Port != old.Port || set.Security != old.Security
	if moved && pw == "" && set.Username != "" && enc != "" {
		s.renderMail(w, r, http.StatusBadRequest, mailData{S: set, Error: "修改服务器、端口或加密方式后，须重新输入密码。"})
		return
	}
	switch {
	case set.Username == "":
		enc = ""
	case pw != "":
		if enc, err = s.Ext.Box.Seal([]byte(pw), store.MailPasswordAAD()); err != nil {
			s.internal(w, r, err)
			return
		}
	}
	row := &store.MailRow{Host: set.Host, Port: set.Port, Security: set.Security, Username: set.Username, PasswordEnc: enc,
		FromAddress: set.From, FromName: set.FromName}
	if err := s.St.PutMailSettings(r.Context(), row); err != nil {
		s.internal(w, r, err)
		return
	}
	detail := changes(field{"host", old.Host, row.Host}, field{"port", old.Port, row.Port}, field{"security", old.Security, row.Security},
		field{"username", old.Username, row.Username}, field{"from_address", old.FromAddress, row.FromAddress},
		field{"from_name", old.FromName, row.FromName})
	if enc != old.PasswordEnc {
		detail["password"] = "已修改"
	}
	s.audit(r, "mail.update", "system", 0, "邮件", nil, detail)
	s.redirect(w, r, "/mail", "ok", "邮件设置已保存。")
}

func (s *Server) mailTest(w http.ResponseWriter, r *http.Request) {
	to := trimmed(r, "to")
	set, err := s.mailSettings(r.Context())
	if err != nil {
		s.internal(w, r, err)
		return
	}
	d := mailData{TestTo: to}
	err = s.Mailer.Send(r.Context(), set, to, "SipLink 管理平台测试邮件", "这是一封测试邮件。\n")
	detail := map[string]any{"to": truncate(to, 254)}
	if err != nil {
		d.TestError = err.Error()
		detail["error"] = truncate(err.Error(), 300)
	}
	s.audit(r, "mail.test", "system", 0, "邮件", nil, detail)
	if err != nil {
		s.renderMail(w, r, http.StatusOK, d)
		return
	}
	s.redirect(w, r, "/mail", "ok", "测试邮件已发送至 "+to+"。")
}
