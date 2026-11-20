// Package ldapd is the read-only LDAPv3 server of the hosted address books
// (design §4): simple bind, search, StartTLS, abandon and unbind on top of
// go-asn1-ber; every other operation is refused.
package ldapd

import (
	"bufio"
	"context"
	"crypto/tls"
	"errors"
	"io"
	"log/slog"
	"net"
	"net/netip"
	"sync"
	"time"

	ber "github.com/go-asn1-ber/asn1-ber"

	"siplink/internal/auth"
	"siplink/internal/store"
)

// Config holds the limits and options of the server.
type Config struct {
	Suffix       string
	RequireTLS   bool
	MaxResults   int // server cap, ≤ 1000
	CacheEntries int
	TLS          *tls.Config // StartTLS and LDAPS; nil disables both
	LDAPKey      [32]byte    // derives the passwords of provisioned clients (design §4.3)

	MaxConns      int           // default 2000
	MaxConnsPerIP int           // default 64
	IdleTimeout   time.Duration // default 5 min
	OpTimeout     time.Duration // default 5 s
	FailDelay     time.Duration // constant delay of a failed bind; default 1 s
	FailLimit     int           // failed binds per IP per minute before the slow path; default 20
	SlowDelay     time.Duration // delay of every bind of an IP over the limit; default 2 s
}

func (c *Config) defaults() {
	set := func(d *time.Duration, v time.Duration) {
		if *d == 0 {
			*d = v
		}
	}
	if c.MaxConns == 0 {
		c.MaxConns = 2000
	}
	if c.MaxConnsPerIP == 0 {
		c.MaxConnsPerIP = 64
	}
	if c.MaxResults <= 0 || c.MaxResults > 1000 {
		c.MaxResults = 1000
	}
	if c.CacheEntries <= 0 {
		c.CacheEntries = 1000000
	}
	if c.FailLimit == 0 {
		c.FailLimit = 20
	}
	set(&c.IdleTimeout, 5*time.Minute)
	set(&c.OpTimeout, 5*time.Second)
	set(&c.FailDelay, time.Second)
	set(&c.SlowDelay, 2*time.Second)
}

// Server serves LDAP connections.
type Server struct {
	cfg   Config
	st    *store.Store
	log   *slog.Logger
	dit   *dit
	cache *cache
	fails *auth.Window

	mu     sync.Mutex
	conns  map[net.Conn]struct{}
	perIP  map[netip.Addr]int
	closed bool
	wg     sync.WaitGroup
}

// New makes a server.
func New(cfg Config, st *store.Store, log *slog.Logger) *Server {
	cfg.defaults()
	d := newDIT(cfg.Suffix)
	return &Server{
		cfg: cfg, st: st, log: log, dit: d,
		cache: newCache(st, d, cfg.CacheEntries),
		fails: auth.NewWindow(cfg.FailLimit, time.Minute, nil),
		conns: map[net.Conn]struct{}{},
		perIP: map[netip.Addr]int{},
	}
}

// Serve accepts connections until the listener is closed. implicitTLS marks an
// LDAPS listener (the listener itself does the TLS handshake).
func (s *Server) Serve(ln net.Listener, implicitTLS bool) error {
	for {
		c, err := ln.Accept()
		if err != nil {
			if errors.Is(err, net.ErrClosed) {
				return nil
			}
			var ne net.Error
			if errors.As(err, &ne) && ne.Timeout() {
				time.Sleep(50 * time.Millisecond)
				continue
			}
			return err
		}
		ip := remoteIP(c)
		if !s.admit(c, ip) {
			c.Close()
			continue
		}
		s.wg.Add(1)
		go func() {
			defer s.wg.Done()
			defer s.release(c, ip)
			s.handle(c, implicitTLS, ip)
		}()
	}
}

func remoteIP(c net.Conn) netip.Addr {
	if ap, err := netip.ParseAddrPort(c.RemoteAddr().String()); err == nil {
		return ap.Addr().Unmap()
	}
	return netip.Addr{}
}

func (s *Server) admit(c net.Conn, ip netip.Addr) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closed || len(s.conns) >= s.cfg.MaxConns || s.perIP[ip] >= s.cfg.MaxConnsPerIP {
		return false
	}
	s.conns[c] = struct{}{}
	s.perIP[ip]++
	return true
}

func (s *Server) release(c net.Conn, ip netip.Addr) {
	c.Close()
	s.mu.Lock()
	defer s.mu.Unlock()
	delete(s.conns, c)
	if s.perIP[ip]--; s.perIP[ip] <= 0 {
		delete(s.perIP, ip)
	}
}

// Close ends every connection and waits for their goroutines. Listeners are
// closed by their owner.
func (s *Server) Close() {
	s.mu.Lock()
	s.closed = true
	for c := range s.conns {
		c.Close()
	}
	s.mu.Unlock()
	s.wg.Wait()
}

// conn is the state of one client connection. Requests are processed in order.
type conn struct {
	s     *Server
	nc    net.Conn
	r     *bufio.Reader
	w     *bufio.Writer // responses; send flushes, a search flushes once at its end
	ip    netip.Addr
	tls   bool
	ident identity
}

func (s *Server) handle(nc net.Conn, implicitTLS bool, ip netip.Addr) {
	c := &conn{s: s, nc: nc, r: bufio.NewReader(nc), w: bufio.NewWriter(nc), ip: ip, tls: implicitTLS}
	if implicitTLS {
		nc.SetDeadline(time.Now().Add(s.cfg.OpTimeout))
		if err := nc.(*tls.Conn).Handshake(); err != nil {
			return
		}
	}
	for {
		nc.SetReadDeadline(time.Now().Add(s.cfg.IdleTimeout))
		p, err := readMessage(c.r)
		if err != nil {
			if !errors.Is(err, io.EOF) && !errors.Is(err, net.ErrClosed) {
				s.log.Debug("ldap read failed", "err", err)
			}
			return
		}
		m, err := parseMessage(p)
		if err != nil {
			return // protocol error: drop the connection
		}
		nc.SetDeadline(time.Now().Add(s.cfg.OpTimeout + time.Second))
		if !c.dispatch(m) {
			return
		}
	}
}

// send writes a response and flushes it with whatever was queued before.
func (c *conn) send(id int64, op *ber.Packet) bool {
	return c.queue(id, op) && c.w.Flush() == nil
}

// queue writes a response into the connection's buffer, which goes out when it is full
// or at the next send: the entries of a search leave in a few writes, not one each.
func (c *conn) queue(id int64, op *ber.Packet) bool {
	_, err := c.w.Write(envelope(id, op).Bytes())
	return err == nil
}

// dispatch handles one request; false ends the connection.
func (c *conn) dispatch(m *message) bool {
	op := int(m.op.Tag)
	switch op {
	case opUnbindRequest:
		return false
	case opAbandonRequest:
		return true // requests run in order: nothing is outstanding to abandon
	}
	resp := responseTag(op)
	if resp < 0 {
		return false // unknown operation
	}
	if m.critical {
		return c.send(m.id, result(resp, rcUnavailableCriticalExt, "", "critical control not supported"))
	}
	switch op {
	case opBindRequest:
		return c.bind(m)
	case opSearchRequest:
		return c.search(m)
	case opExtendedRequest:
		return c.extended(m)
	}
	return c.send(m.id, result(resp, rcUnwillingToPerform, "", "the directory is read-only"))
}

func (c *conn) extended(m *message) bool {
	var name string
	if len(m.op.Children) > 0 {
		name = string(raw(m.op.Children[0]))
	}
	if name != OIDStartTLS {
		return c.send(m.id, result(opExtendedResp, rcUnwillingToPerform, "", "extended operation not supported"))
	}
	if c.tls {
		return c.send(m.id, result(opExtendedResp, rcOperationsError, "", "TLS already established"))
	}
	if c.s.cfg.TLS == nil {
		return c.send(m.id, result(opExtendedResp, rcUnavailable, "", "TLS not available"))
	}
	if c.r.Buffered() > 0 {
		return false // data after the request would be read in clear: refuse
	}
	resp := result(opExtendedResp, rcSuccess, "", "")
	resp.AppendChild(ber.NewString(ber.ClassContext, ber.TypePrimitive, 10, OIDStartTLS, "responseName"))
	if !c.send(m.id, resp) {
		return false
	}
	tc := tls.Server(c.nc, c.s.cfg.TLS)
	if err := tc.HandshakeContext(context.Background()); err != nil {
		return false
	}
	c.nc, c.r, c.w, c.tls = tc, bufio.NewReader(tc), bufio.NewWriter(tc), true
	return true
}
