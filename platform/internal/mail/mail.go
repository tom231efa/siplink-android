// Package mail sends the platform's few e-mails (codes of the two-step sign-in, test
// mails) over SMTP with the standard library (design §6.8).
package mail

import (
	"bytes"
	"context"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"mime"
	"net"
	"net/mail"
	"net/smtp"
	"strconv"
	"strings"
	"time"
	"unicode/utf8"
)

// Connection security of the SMTP server.
const (
	SecuritySTARTTLS = "starttls"
	SecurityTLS      = "tls"
	SecurityNone     = "none"
)

// Settings are the SMTP settings of the 邮件 page.
type Settings struct {
	Host     string
	Port     int
	Security string
	Username string
	Password string
	From     string // sender address
	FromName string
}

// Configured reports whether mail can be sent at all.
func (s Settings) Configured() bool { return s.Host != "" && s.From != "" }

// DefaultPort is the usual port of a security mode.
func DefaultPort(security string) int {
	switch security {
	case SecurityTLS:
		return 465
	case SecurityNone:
		return 25
	}
	return 587
}

// Problem checks the settings and returns a message in Chinese, or "".
func (s Settings) Problem() string {
	switch {
	case s.Host == "" || len(s.Host) > 253 || strings.ContainsAny(s.Host, " /\r\n"):
		return "请填写有效的服务器地址。"
	case s.Port < 1 || s.Port > 65535:
		return "端口须为 1-65535。"
	case s.Security != SecuritySTARTTLS && s.Security != SecurityTLS && s.Security != SecurityNone:
		return "请选择加密方式。"
	case len(s.Username) > 256 || strings.ContainsAny(s.Username, "\r\n"):
		return "用户名无效。"
	case ValidAddress(s.From) != nil:
		return "发件地址无效。"
	case utf8.RuneCountInString(s.FromName) > 64 || strings.ContainsAny(s.FromName, "\r\n"):
		return "发件人名称无效。"
	}
	return ""
}

// ValidAddress accepts one plain address such as name@example.com (no display name).
func ValidAddress(addr string) error {
	a, err := mail.ParseAddress(addr)
	if err != nil || a.Address != addr || a.Name != "" || len(addr) > 254 {
		return errors.New("mail: invalid address")
	}
	return nil
}

// Sender sends mail. Its zero value uses the system roots and the default timeouts.
type Sender struct {
	RootCAs     *x509.CertPool // nil: the system's
	DialTimeout time.Duration  // default 10 s
	Timeout     time.Duration  // the whole exchange, default 30 s
}

// Send delivers one plain-text mail to one address. The error carries the server's
// answer when it refused; it never contains the password.
func (x *Sender) Send(ctx context.Context, set Settings, to, subject, body string) error {
	if !set.Configured() {
		return errors.New("邮件服务未配置")
	}
	if ValidAddress(to) != nil {
		return errors.New("收件地址无效")
	}
	dialTimeout, timeout := x.DialTimeout, x.Timeout
	if dialTimeout <= 0 {
		dialTimeout = 10 * time.Second
	}
	if timeout <= 0 {
		timeout = 30 * time.Second
	}
	addr := net.JoinHostPort(set.Host, strconv.Itoa(set.Port))
	tlsConf := &tls.Config{ServerName: set.Host, RootCAs: x.RootCAs, MinVersion: tls.VersionTLS12}
	d := &net.Dialer{Timeout: dialTimeout}
	var conn net.Conn
	var err error
	if set.Security == SecurityTLS {
		conn, err = (&tls.Dialer{NetDialer: d, Config: tlsConf}).DialContext(ctx, "tcp", addr)
	} else {
		conn, err = d.DialContext(ctx, "tcp", addr)
	}
	if err != nil {
		return fmt.Errorf("连接失败: %w", err)
	}
	defer conn.Close()
	deadline := time.Now().Add(timeout)
	if dl, ok := ctx.Deadline(); ok && dl.Before(deadline) {
		deadline = dl
	}
	if err := conn.SetDeadline(deadline); err != nil {
		return err
	}
	c, err := smtp.NewClient(conn, set.Host)
	if err != nil {
		return fmt.Errorf("服务器无应答: %w", err)
	}
	defer c.Close()
	if set.Security == SecuritySTARTTLS {
		if ok, _ := c.Extension("STARTTLS"); !ok {
			return errors.New("服务器不支持 STARTTLS")
		}
		if err := c.StartTLS(tlsConf); err != nil {
			return fmt.Errorf("STARTTLS 失败: %w", err)
		}
	}
	if set.Username != "" {
		if err := c.Auth(smtp.PlainAuth("", set.Username, set.Password, set.Host)); err != nil {
			return fmt.Errorf("认证失败: %w", err)
		}
	}
	if err := c.Mail(set.From); err != nil {
		return fmt.Errorf("发件地址被拒绝: %w", err)
	}
	if err := c.Rcpt(to); err != nil {
		return fmt.Errorf("收件地址被拒绝: %w", err)
	}
	w, err := c.Data()
	if err != nil {
		return fmt.Errorf("发送失败: %w", err)
	}
	if _, err := w.Write(Message(set, to, subject, body, time.Now())); err != nil {
		return fmt.Errorf("发送失败: %w", err)
	}
	if err := w.Close(); err != nil {
		return fmt.Errorf("发送失败: %w", err)
	}
	// The server accepted the mail with its answer to DATA: a failing QUIT does not
	// take that back.
	_ = c.Quit()
	return nil
}

// Message builds the mail: UTF-8 plain text, base64 body, encoded header words.
func Message(set Settings, to, subject, body string, now time.Time) []byte {
	var b bytes.Buffer
	from := set.From
	if set.FromName != "" {
		from = (&mail.Address{Name: set.FromName, Address: set.From}).String()
	}
	id := make([]byte, 12)
	_, _ = rand.Read(id)
	domain := set.From[strings.LastIndexByte(set.From, '@')+1:]
	header := [][2]string{
		{"From", from},
		{"To", to},
		{"Subject", mime.BEncoding.Encode("utf-8", subject)},
		{"Date", now.Format(time.RFC1123Z)},
		{"Message-ID", "<" + hex.EncodeToString(id) + "@" + domain + ">"},
		{"MIME-Version", "1.0"},
		{"Content-Type", "text/plain; charset=utf-8"},
		{"Content-Transfer-Encoding", "base64"},
	}
	for _, h := range header {
		b.WriteString(h[0] + ": " + h[1] + "\r\n")
	}
	b.WriteString("\r\n")
	enc := base64.StdEncoding.EncodeToString([]byte(body))
	for len(enc) > 76 {
		b.WriteString(enc[:76] + "\r\n")
		enc = enc[76:]
	}
	b.WriteString(enc + "\r\n")
	return b.Bytes()
}
