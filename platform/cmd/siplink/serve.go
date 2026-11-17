package main

import (
	"context"
	"crypto/tls"
	"errors"
	"log/slog"
	"net"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"siplink/internal/backup"
	"siplink/internal/ldapd"
	"siplink/internal/pki"
	"siplink/internal/rules"
	"siplink/internal/store"
	"siplink/internal/web"
)

// setupTLS chooses the certificate of every listener (design §6.7) and runs the check of
// the start: the built-in server certificate is issued when a listener needs it.
func setupTLS(ctx context.Context, e *env) (*pki.Certs, error) {
	certs, err := openCerts(ctx, e, true)
	if err != nil {
		return nil, err
	}
	if reason, err := certs.Ensure(ctx); err != nil {
		return nil, err
	} else if reason != "" {
		e.log.Info("server certificate issued", "reason", reason, "names", e.cfg.CertNames())
	}
	if certs.Paused() {
		e.log.Info("issuing of the built-in CA is paused: no listener uses its certificate")
	}
	certs.Remind(ctx, e.log)
	return certs, nil
}

func serve() error {
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	e, err := open(ctx)
	if err != nil {
		return err
	}
	defer e.st.Close()
	if n, _ := e.st.CountAdmins(ctx); n == 0 {
		e.log.Warn("no administrator yet; create one with: siplink admin create --super <username>")
	}
	certs, err := setupTLS(ctx, e)
	if err != nil {
		return err
	}
	go certs.Run(ctx, 24*time.Hour, e.log)

	srv, err := web.New(e.cfg, e.st, e.log, nil)
	if err != nil {
		return err
	}
	srv.Ext = web.Extensions{Box: e.box, LDAPKey: e.keys.LDAP, Certs: certs}
	if _, ok := rules.HostedSecurity(e.cfg.LDAPPublicHost, e.cfg.LDAPRequireTLS); !ok {
		e.log.Warn("hosted directories cannot be handed out", "reason", rules.HostedNeedsName)
	}
	go purge(ctx, srv, e.log)
	go backup.Daily(ctx, e.st.DB, backup.Dir(e.cfg.DataDir), e.cfg.BackupTime, e.cfg.BackupKeep, e.log, func(name string, err error) {
		entry := &store.AuditEntry{AdminName: "system", Action: "backup.create", ObjectType: "backup", ObjectName: name}
		if err != nil {
			entry.Action, entry.Detail = "backup.failed", map[string]any{"error": err.Error()}
		}
		_ = e.st.AddAudit(context.Background(), entry)
	})

	errc := make(chan error, 4)
	httpsTLS := certs.HTTPSConfig()
	httpServer := &http.Server{
		Addr:              e.cfg.HTTPListen,
		Handler:           srv.Handler(),
		TLSConfig:         httpsTLS,
		ReadHeaderTimeout: 10 * time.Second,
		ReadTimeout:       2 * time.Minute,
		WriteTimeout:      2 * time.Minute,
		IdleTimeout:       2 * time.Minute,
		MaxHeaderBytes:    64 << 10,
		ErrorLog:          slog.NewLogLogger(e.log.Handler(), slog.LevelWarn),
	}
	ln, err := net.Listen("tcp", e.cfg.HTTPListen)
	if err != nil {
		return err
	}
	go func() {
		if httpsTLS != nil {
			e.log.Info("listening", "https", e.cfg.HTTPListen)
			errc <- httpServer.Serve(tls.NewListener(ln, httpsTLS))
		} else {
			e.log.Info("listening", "http", e.cfg.HTTPListen)
			errc <- httpServer.Serve(ln)
		}
	}()
	stopLDAP, err := startLDAP(e, certs.LDAPConfig(), errc)
	if err != nil {
		return err
	}

	select {
	case <-ctx.Done():
	case err := <-errc:
		if err != nil && !errors.Is(err, http.ErrServerClosed) {
			e.log.Error("listener stopped", "err", err)
		}
	}
	e.log.Info("shutting down")
	shut, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	stopLDAP()
	return httpServer.Shutdown(shut)
}

// purge drops expired sessions and revoked device keys older than
// SIPLINK_REVOKED_KEEP_DAYS (design §2.2).
func purge(ctx context.Context, srv *web.Server, log *slog.Logger) {
	t := time.NewTicker(10 * time.Minute)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			if _, err := srv.Sessions.Purge(ctx); err != nil {
				log.Error("session purge failed", "err", err)
			}
			keep := time.Duration(srv.Cfg.RevokedKeepDays) * 24 * time.Hour
			if _, err := srv.St.PurgeRevoked(ctx, time.Now().Add(-keep)); err != nil {
				log.Error("revoked key purge failed", "err", err)
			}
		}
	}
}

// startLDAP starts the LDAP listener and the optional LDAPS listener; it returns a
// stop function. SIPLINK_LDAP_LISTEN empty turns the LDAP service off.
func startLDAP(e *env, ldapTLS *tls.Config, errc chan<- error) (func(), error) {
	cfg := e.cfg
	if cfg.LDAPListen == "" && cfg.LDAPSListen == "" {
		return func() {}, nil
	}
	srv := ldapd.New(ldapd.Config{
		Suffix:       cfg.LDAPSuffix,
		RequireTLS:   cfg.LDAPRequireTLS,
		LDAPKey:      e.keys.LDAP,
		MaxResults:   cfg.LDAPMaxResults,
		CacheEntries: cfg.LDAPCacheEntries,
		TLS:          ldapTLS,
	}, e.st, e.log)
	var listeners []net.Listener
	stop := func() {
		for _, l := range listeners {
			l.Close()
		}
		srv.Close()
	}
	listen := func(addr string, implicitTLS bool) error {
		ln, err := net.Listen("tcp", addr)
		if err != nil {
			return err
		}
		if implicitTLS {
			ln = tls.NewListener(ln, ldapTLS)
		}
		listeners = append(listeners, ln)
		e.log.Info("listening", map[bool]string{false: "ldap", true: "ldaps"}[implicitTLS], addr)
		go func() { errc <- srv.Serve(ln, implicitTLS) }()
		return nil
	}
	if cfg.LDAPListen != "" {
		if err := listen(cfg.LDAPListen, false); err != nil {
			stop()
			return nil, err
		}
	}
	if cfg.LDAPSListen != "" {
		if err := listen(cfg.LDAPSListen, true); err != nil {
			stop()
			return nil, err
		}
	}
	return stop, nil
}
