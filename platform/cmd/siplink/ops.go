package main

import (
	"context"
	"crypto/tls"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"strings"
	"time"

	"siplink/internal/backup"
	"siplink/internal/config"
	"siplink/internal/i18n"
	"siplink/internal/pki"
	"siplink/internal/store"
)

// backupCommand writes a copy of the database to a path ("siplink backup {path}").
func backupCommand(args []string, out io.Writer) error {
	if len(args) != 1 {
		return i18n.E("cli.backup.usage")
	}
	ctx := context.Background()
	e, err := open(ctx)
	if err != nil {
		return err
	}
	defer e.st.Close()
	if err := backup.To(ctx, e.st.DB, args[0]); err != nil {
		return err
	}
	_ = e.st.AddAudit(ctx, &store.AuditEntry{AdminName: "CLI", Action: "backup.create", ObjectType: "backup", ObjectName: args[0]})
	fmt.Fprintln(out, cli().T("cli.backup.done"), args[0]) // after the label: no isolates around the path
	fmt.Fprintln(out, cli().T("cli.backup.key_separate"))
	return nil
}

// healthCommand asks the local HTTP(S) listener for /healthz (Docker HEALTHCHECK). It
// reads only the configuration, not the database or the key file: the certificate
// notices come from the running platform.
func healthCommand(out io.Writer) error {
	cfg, err := config.Load()
	if err != nil {
		return err
	}
	_, port, err := net.SplitHostPort(cfg.HTTPListen)
	if err != nil {
		return err
	}
	scheme := "https"
	if cfg.TLSMode == config.TLSOff {
		scheme = "http"
	}
	client := &http.Client{Timeout: 5 * time.Second, Transport: &http.Transport{
		// Liveness on the loopback interface only: the certificate is not the point here.
		TLSClientConfig: &tls.Config{InsecureSkipVerify: true}, //nolint:gosec
	}}
	req, err := http.NewRequest(http.MethodGet, scheme+"://127.0.0.1:"+port+"/healthz", nil)
	if err != nil {
		return err
	}
	req.Header.Set("Accept-Language", cli().Lang.Code) // the notices in the terminal's language
	resp, err := client.Do(req)
	if err != nil {
		return err
	}
	body, err := io.ReadAll(io.LimitReader(resp.Body, 4<<10))
	resp.Body.Close()
	if err != nil {
		return err
	}
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("healthz: %s", resp.Status)
	}
	return healthReport(string(body), out)
}

// healthReport prints the answer of /healthz: "ok", then one line per certificate notice
// ("warn …" inside the reminder window, "error …" after expiry). An expired certificate of
// the operator's makes the check fail: phones can no longer sync (design §6.7).
func healthReport(body string, out io.Writer) error {
	var expired []string
	for _, line := range strings.Split(strings.TrimSpace(body), "\n") {
		fmt.Fprintln(out, line)
		if text, ok := strings.CutPrefix(line, pki.LevelError+" "); ok {
			expired = append(expired, text)
		}
	}
	if len(expired) > 0 {
		return errors.New(strings.Join(expired, cli().T("format.problem_sep")))
	}
	return nil
}
