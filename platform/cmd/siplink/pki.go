package main

import (
	"context"
	"fmt"
	"io"
	"os"
	"slices"
	"strings"
	"time"

	"siplink/internal/i18n"
	"siplink/internal/pki"
	"siplink/internal/store"
)

// openCerts opens the built-in CA and the choice of certificates (design §6.7).
func openCerts(ctx context.Context, e *env, logCreated bool) (*pki.Certs, error) {
	ca, created, err := pki.Open(ctx, e.st, e.box, nil)
	if err != nil {
		return nil, err
	}
	if created && logCreated {
		e.log.Info("built-in CA created", "subject", ca.CA().Subject.CommonName)
	}
	return pki.OpenCerts(ctx, ca, pki.OptionsFrom(e.cfg))
}

func pkiCommand(args []string, out io.Writer) error {
	if len(args) == 0 {
		return i18n.E("cli.pki.subcommand")
	}
	yes := slices.Contains(args[1:], "--yes")
	rest := slices.DeleteFunc(slices.Clone(args[1:]), func(a string) bool { return a == "--yes" })
	ctx := context.Background()
	e, err := open(ctx)
	if err != nil {
		return err
	}
	defer e.st.Close()
	certs, err := openCerts(ctx, e, false)
	if err != nil {
		return err
	}
	ca := certs.CA()
	switch args[0] {
	case "show":
		pkiShow(ctx, certs, out)
	case "export-ca":
		if len(rest) != 1 {
			return i18n.E("cli.pki.export_usage")
		}
		if err := os.WriteFile(rest[0], ca.CAPEM(), 0o644); err != nil {
			return err
		}
		fmt.Fprintln(out, cli().T("cli.pki.exported"), rest[0]) // after the label, never an argument: no isolates
	case "renew":
		if err := certs.Issue(ctx); err != nil {
			return err
		}
		fmt.Fprintln(out, cli().T("cli.pki.renewed"))
	case "import":
		if len(rest) != 2 {
			return i18n.E("cli.pki.import_usage")
		}
		return pkiImport(ctx, e, certs, rest[0], rest[1], yes, out)
	case "use-builtin":
		return pkiUseBuiltin(ctx, e, certs, yes, out)
	default:
		return i18n.E("cli.pki.unknown", "name", args[0])
	}
	return nil
}

func pkiShow(ctx context.Context, certs *pki.Certs, out io.Writer) {
	ca := certs.CA()
	https, ldap := certs.Sources()
	t := cli()
	fmt.Fprintln(out, t.T("cli.pki.mode", "mode", certs.Mode()))
	fmt.Fprintf(out, "  HTTPS: %s\n  LDAP: %s\n",
		t.Msg(pki.SourceLabel(https, i18n.M("pki.source.proxy"))), t.Msg(pki.SourceLabel(ldap, i18n.M("pki.source.off"))))
	// Values people copy (pins, fingerprints) follow their labels as they are, never as
	// arguments, which a right-to-left language wraps in isolates.
	issuing := t.T("cli.pki.issuing_running")
	if certs.Paused() {
		issuing = t.T("cli.pki.issuing_paused")
	}
	fmt.Fprintln(out, "  "+t.T("cli.pki.issuing"), issuing)
	pin := certs.Pin()
	if pin == "" {
		pin = t.T("cli.pki.qr_pin_none")
	}
	fmt.Fprintln(out, "  "+t.T("cli.pki.qr_pin"), pin)
	c := ca.CA()
	fmt.Fprintln(out, t.T("cli.pki.ca"), c.Subject.String())
	fmt.Fprintln(out, "  "+t.T("cli.pki.validity", "from", localDay(c.NotBefore), "to", localDay(c.NotAfter)))
	fmt.Fprintln(out, "  "+t.T("cli.pki.fingerprint"), pki.Fingerprint(c))
	fmt.Fprintln(out, "  pin:", ca.Pin())
	if s := ca.Server(); s != nil {
		fmt.Fprintln(out, t.T("cli.pki.server"), s.DNSNames, s.IPAddresses)
		fmt.Fprintln(out, "  "+t.T("cli.pki.expires"), localDay(s.NotAfter))
	} else {
		fmt.Fprintln(out, t.T("cli.pki.server"), t.T("cli.pki.not_issued"))
	}
	httpsFiles, ldapFiles := certs.Files()
	if httpsFiles != nil {
		showOwn(out, t.T("cli.pki.https_files"), httpsFiles, certs)
	}
	if ldapFiles != nil && ldapFiles != httpsFiles {
		showOwn(out, t.T("cli.pki.ldap_files"), ldapFiles, certs)
	}
	if o := certs.Uploaded(); o != nil {
		showOwn(out, t.T("cli.pki.uploaded"), o, certs)
	}
	days := certs.ReminderDays(ctx)
	fmt.Fprintln(out, t.T("cli.pki.reminder", "n", days))
	for _, n := range certs.Notices(days) {
		fmt.Fprintln(out, t.T(map[string]string{pki.LevelWarn: "cli.pki.level_warn", pki.LevelError: "cli.pki.level_error"}[n.Level]), t.Msg(n.Text))
	}
}

// showOwn shows an operator's certificate under its title (a label with its colon).
// Values people copy (names, pins, fingerprints) follow their label as they are, never
// as arguments, which a right-to-left language would wrap in isolates.
func showOwn(out io.Writer, title string, o *pki.Own, certs *pki.Certs) {
	t := cli()
	leaf := o.Leaf()
	trust, pin := t.T("cli.pki.trust_public"), ""
	if p := o.Private(); p != nil {
		trust, pin = t.T("cli.pki.trust_private")+" "+p.Subject.String(), pki.Pin(p)
	} else if !o.Public {
		trust = t.T("cli.pki.trust_none")
	}
	fmt.Fprintln(out, title, leaf.Subject.String())
	fmt.Fprintln(out, "  "+t.T("cli.pki.issuer"), leaf.Issuer.String())
	fmt.Fprintln(out, "  "+t.T("cli.pki.names"), strings.Join(o.Names(), ", "))
	fmt.Fprintln(out, "  "+t.T("cli.pki.expires_left", "date", localDay(leaf.NotAfter), "n", certs.DaysLeft(leaf.NotAfter)))
	fmt.Fprintln(out, "  "+t.T("cli.pki.trust"), trust)
	if pin != "" {
		fmt.Fprintln(out, "    pin:", pin)
	}
	fmt.Fprintln(out, "  "+t.T("cli.pki.fingerprint"), pki.Fingerprint(leaf))
}

func localDay(t time.Time) string { return t.Local().Format("2006-01-02") }

func pkiImport(ctx context.Context, e *env, certs *pki.Certs, chainFile, keyFile string, yes bool, out io.Writer) error {
	chainPEM, err := os.ReadFile(chainFile)
	if err != nil {
		return err
	}
	keyPEM, err := os.ReadFile(keyFile)
	if err != nil {
		return err
	}
	o, err := pki.ParseOwn(chainPEM, keyPEM)
	if err != nil {
		return err
	}
	a, err := certs.Assess(ctx, o)
	if err != nil {
		return err
	}
	for _, w := range a.Warnings {
		fmt.Fprintln(out, cli().T("cli.pki.notice"), cli().Msg(w))
	}
	if len(a.Warnings) > 0 && !yes {
		return i18n.E("cli.pki.import_unconfirmed")
	}
	if err := certs.Import(ctx, o); err != nil {
		return err
	}
	_ = e.st.AddAudit(ctx, &store.AuditEntry{AdminName: "CLI", Action: "pki.own_import", ObjectType: "system",
		ObjectName: o.Leaf().Subject.String(), Detail: pki.AuditDetail(a)})
	fmt.Fprintln(out, cli().T("cli.pki.imported"), cli().Msg(certs.Mode()))
	return nil
}

func pkiUseBuiltin(ctx context.Context, e *env, certs *pki.Certs, yes bool, out io.Writer) error {
	o := certs.Uploaded()
	if o == nil {
		return i18n.E("cli.pki.no_own")
	}
	warnings, rescan, err := certs.RemovalWarnings(ctx)
	if err != nil {
		return err
	}
	for _, w := range warnings {
		fmt.Fprintln(out, cli().T("cli.pki.notice"), cli().Msg(w))
	}
	if len(warnings) > 0 && !yes {
		return i18n.E("cli.pki.remove_unconfirmed")
	}
	reason, err := certs.Remove(ctx)
	if err != nil {
		return err
	}
	_ = e.st.AddAudit(ctx, &store.AuditEntry{AdminName: "CLI", Action: "pki.own_remove", ObjectType: "system",
		ObjectName: o.Leaf().Subject.String(), Detail: map[string]any{"rescan": rescan}})
	if reason != "" {
		fmt.Fprintln(out, cli().T("cli.pki.issued", "reason", reason))
	}
	fmt.Fprintln(out, cli().T("cli.pki.removed"))
	return nil
}
