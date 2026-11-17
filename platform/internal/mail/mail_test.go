package mail

import (
	"context"
	"net"
	"strconv"
	"strings"
	"testing"
	"time"

	"siplink/internal/testutil"
)

func settingsFor(t *testing.T, srv *testutil.SMTPServer, security string) Settings {
	_, port, _ := net.SplitHostPort(srv.Addr)
	p, _ := strconv.Atoi(port)
	return Settings{Host: srv.Host(), Port: p, Security: security, Username: srv.User, Password: srv.Password,
		From: "noreply@example.com", FromName: "SipLink 平台"}
}

func TestSendSTARTTLSAndImplicitTLS(t *testing.T) {
	for _, mode := range []string{SecuritySTARTTLS, SecurityTLS} {
		srv := testutil.NewSMTPServer(t, mode == SecurityTLS)
		x := &Sender{RootCAs: srv.Roots}
		set := settingsFor(t, srv, mode)
		if err := x.Send(context.Background(), set, "admin@example.com", "SipLink 管理平台验证码", "验证码：123456\n10 分钟内有效。"); err != nil {
			t.Fatalf("%s: %v", mode, err)
		}
		m := srv.Mails()
		if len(m) != 1 || m[0].To != "admin@example.com" || m[0].From != "noreply@example.com" || !m[0].TLS || !m[0].Authed ||
			m[0].Subject != "SipLink 管理平台验证码" || !strings.Contains(m[0].Body, "123456") {
			t.Fatalf("%s: %+v", mode, m)
		}
		if strings.Contains(m[0].Raw, srv.Password) {
			t.Error("password in the mail")
		}
	}
}

func TestSendErrors(t *testing.T) {
	srv := testutil.NewSMTPServer(t, false)
	x := &Sender{RootCAs: srv.Roots, Timeout: 5 * time.Second}
	set := settingsFor(t, srv, SecuritySTARTTLS)
	srv.RejectRcpt = "nobody@example.com"
	err := x.Send(context.Background(), set, "nobody@example.com", "s", "b")
	if err == nil || !strings.Contains(err.Error(), "550") || !strings.Contains(err.Error(), "收件地址被拒绝") {
		t.Errorf("rejected recipient: %v", err)
	}
	bad := set
	bad.Password = "wrong-pass"
	if err := x.Send(context.Background(), bad, "admin@example.com", "s", "b"); err == nil || !strings.Contains(err.Error(), "535") ||
		strings.Contains(err.Error(), "wrong-pass") {
		t.Errorf("wrong password: %v", err)
	}
	// The system roots do not know the test certificate.
	if err := (&Sender{}).Send(context.Background(), set, "admin@example.com", "s", "b"); err == nil {
		t.Error("certificate of an unknown CA accepted")
	}
	tlsSrv := testutil.NewSMTPServer(t, true)
	noStart := settingsFor(t, tlsSrv, SecuritySTARTTLS)
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	if err := x.Send(ctx, noStart, "admin@example.com", "s", "b"); err == nil {
		t.Error("STARTTLS against a TLS port succeeded")
	}
	if err := x.Send(context.Background(), Settings{}, "admin@example.com", "s", "b"); err == nil {
		t.Error("unconfigured settings sent")
	}
	if err := x.Send(context.Background(), set, "bad address", "s", "b"); err == nil {
		t.Error("bad recipient sent")
	}
}

func TestSendTimeout(t *testing.T) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			defer c.Close() // never greets
		}
	}()
	_, port, _ := net.SplitHostPort(ln.Addr().String())
	p, _ := strconv.Atoi(port)
	set := Settings{Host: "127.0.0.1", Port: p, Security: SecurityNone, From: "noreply@example.com"}
	start := time.Now()
	err = (&Sender{Timeout: 500 * time.Millisecond}).Send(context.Background(), set, "admin@example.com", "s", "b")
	if err == nil || time.Since(start) > 5*time.Second {
		t.Errorf("silent server: %v after %s", err, time.Since(start))
	}
}

func TestProblem(t *testing.T) {
	ok := Settings{Host: "smtp.example.com", Port: 587, Security: SecurityNone, From: "noreply@example.com"}
	if p := ok.Problem(); p != "" {
		t.Fatal(p)
	}
	for _, bad := range []func(*Settings){
		func(s *Settings) { s.Host = "" }, func(s *Settings) { s.Port = 0 }, func(s *Settings) { s.Security = "ssl" },
		func(s *Settings) { s.From = "SipLink <noreply@example.com>" }, func(s *Settings) { s.FromName = "a\r\nBcc: x@example.com" },
		func(s *Settings) { s.Username = "u\r\n" },
	} {
		s := ok
		bad(&s)
		if s.Problem() == "" {
			t.Errorf("accepted %+v", s)
		}
	}
	if DefaultPort(SecurityTLS) != 465 || DefaultPort(SecuritySTARTTLS) != 587 || DefaultPort(SecurityNone) != 25 {
		t.Error("default ports")
	}
	msg := string(Message(ok, "admin@example.com", "验证码", "正文", time.Unix(0, 0)))
	if !strings.Contains(msg, "Subject: =?utf-8?b?") || !strings.Contains(msg, "Content-Transfer-Encoding: base64") {
		t.Errorf("message: %s", msg)
	}
}

// TestQuitErrorAfterData: once DATA is accepted the mail is sent, whatever QUIT does.
func TestQuitErrorAfterData(t *testing.T) {
	srv := testutil.NewSMTPServer(t, false)
	srv.DropQuit = true
	x := &Sender{RootCAs: srv.Roots, Timeout: 5 * time.Second}
	if err := x.Send(context.Background(), settingsFor(t, srv, SecuritySTARTTLS), "admin@example.com", "s", "b"); err != nil {
		t.Errorf("QUIT without an answer reported as failure: %v", err)
	}
	if len(srv.Mails()) != 1 {
		t.Error("mail not recorded")
	}
}
