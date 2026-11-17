package testutil

import (
	"bufio"
	"crypto/tls"
	"crypto/x509"
	"encoding/base64"
	"io"
	"mime"
	"net"
	"net/mail"
	"strings"
	"sync"
	"testing"
)

// SMTPServer is a tiny in-process SMTP server for tests: EHLO, STARTTLS or implicit
// TLS with a throwaway certificate, AUTH PLAIN, MAIL, RCPT, DATA, QUIT.
type SMTPServer struct {
	Addr     string         // 127.0.0.1:port
	Roots    *x509.CertPool // trusts the server's certificate
	User     string         // AUTH PLAIN credentials it accepts
	Password string
	// RejectRcpt makes RCPT TO answer 550 for this address.
	RejectRcpt string
	// DropQuit closes the connection on QUIT without an answer.
	DropQuit bool

	implicitTLS bool
	tlsConf     *tls.Config
	ln          net.Listener
	mu          sync.Mutex
	mails       []SentMail
}

// SentMail is one mail the server accepted.
type SentMail struct {
	From, To string
	Subject  string
	Body     string // decoded
	Raw      string
	TLS      bool // the mail arrived over an encrypted connection
	Authed   bool
}

// NewSMTPServer starts a server on 127.0.0.1; implicitTLS selects TLS from the first
// byte (port 465 style), otherwise it offers STARTTLS.
func NewSMTPServer(t testing.TB, implicitTLS bool) *SMTPServer {
	t.Helper()
	cert := MakeCert(t, CertSpec{CN: "smtp test", Names: []string{"127.0.0.1"}}, nil)
	roots := x509.NewCertPool()
	roots.AddCert(cert.Cert)
	s := &SMTPServer{Roots: roots, User: "mailer", Password: "mail-pass-test", implicitTLS: implicitTLS,
		tlsConf: &tls.Config{Certificates: []tls.Certificate{{Certificate: [][]byte{cert.Cert.Raw}, PrivateKey: cert.Key}}}}
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	if implicitTLS {
		ln = tls.NewListener(ln, s.tlsConf)
	}
	s.ln, s.Addr = ln, ln.Addr().String()
	t.Cleanup(func() { ln.Close() })
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go s.serve(c)
		}
	}()
	return s
}

// Host and Port of the listener.
func (s *SMTPServer) Host() string { h, _, _ := net.SplitHostPort(s.Addr); return h }

// Mails returns what was accepted so far.
func (s *SMTPServer) Mails() []SentMail {
	s.mu.Lock()
	defer s.mu.Unlock()
	return append([]SentMail(nil), s.mails...)
}

func (s *SMTPServer) serve(conn net.Conn) {
	defer func() { conn.Close() }()
	secure := s.implicitTLS
	r, w := bufio.NewReader(conn), bufio.NewWriter(conn)
	say := func(line string) { w.WriteString(line + "\r\n"); w.Flush() }
	say("220 smtp.test ESMTP")
	var cur SentMail
	authed := false
	for {
		line, err := r.ReadString('\n')
		if err != nil {
			return
		}
		line = strings.TrimRight(line, "\r\n")
		verb := strings.ToUpper(strings.SplitN(line, " ", 2)[0])
		switch verb {
		case "EHLO", "HELO":
			w.WriteString("250-smtp.test\r\n")
			if !secure && !s.implicitTLS {
				w.WriteString("250-STARTTLS\r\n")
			}
			if secure {
				w.WriteString("250-AUTH PLAIN\r\n")
			}
			say("250 8BITMIME")
		case "STARTTLS":
			say("220 go ahead")
			tc := tls.Server(conn, s.tlsConf)
			if tc.Handshake() != nil {
				return
			}
			conn, secure = tc, true
			r, w = bufio.NewReader(conn), bufio.NewWriter(conn)
		case "AUTH":
			parts := strings.Fields(line)
			raw, _ := base64.StdEncoding.DecodeString(parts[len(parts)-1])
			f := strings.Split(string(raw), "\x00")
			if len(f) == 3 && f[1] == s.User && f[2] == s.Password {
				authed = true
				say("235 ok")
			} else {
				say("535 5.7.8 authentication failed")
			}
		case "MAIL":
			cur = SentMail{From: addrOf(line), TLS: secure, Authed: authed}
			say("250 ok")
		case "RCPT":
			to := addrOf(line)
			if s.RejectRcpt != "" && to == s.RejectRcpt {
				say("550 5.1.1 mailbox unavailable")
				continue
			}
			cur.To = to
			say("250 ok")
		case "DATA":
			say("354 go on")
			var b strings.Builder
			for {
				l, err := r.ReadString('\n')
				if err != nil {
					return
				}
				if l == ".\r\n" {
					break
				}
				b.WriteString(strings.TrimPrefix(l, "."))
			}
			cur.Raw = b.String()
			if m, err := mail.ReadMessage(strings.NewReader(cur.Raw)); err == nil {
				dec := new(mime.WordDecoder)
				cur.Subject, _ = dec.DecodeHeader(m.Header.Get("Subject"))
				body, _ := io.ReadAll(base64.NewDecoder(base64.StdEncoding, m.Body))
				cur.Body = string(body)
			}
			s.mu.Lock()
			s.mails = append(s.mails, cur)
			s.mu.Unlock()
			say("250 queued")
		case "QUIT":
			if s.DropQuit {
				return
			}
			say("221 bye")
			return
		default:
			say("250 ok")
		}
	}
}

func addrOf(line string) string {
	if i, j := strings.IndexByte(line, '<'), strings.IndexByte(line, '>'); i >= 0 && j > i {
		return line[i+1 : j]
	}
	return ""
}
