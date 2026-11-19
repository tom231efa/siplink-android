package pki

import (
	"context"
	"crypto/x509"
	"strings"
	"time"

	"siplink/internal/i18n"
	"siplink/internal/store"
)

// Assessment is what using an uploaded certificate would change.
type Assessment struct {
	Own         *Own
	HTTPS, LDAP bool // the listeners that would use it
	// Rescan is the number of bound clients that would have to scan their QR code again
	// because their phones would no longer trust the HTTPS listener.
	Rescan int
	// Warnings are for the administrator to read before the change takes effect; when
	// there are any, the change needs an explicit confirmation.
	Warnings []i18n.Msg
}

// Assess applies the upload's checks to o (§6.7) and works out what using it would
// change. A *Refusal says why it cannot be used.
func (c *Certs) Assess(ctx context.Context, o *Own) (*Assessment, error) {
	now := c.ca.now()
	o.classify(c.opt.Roots, now)
	leaf := o.Leaf()
	for i, cert := range o.Chain {
		what := i18n.M("pki.chain_cert", "n", i+1)
		if i == 0 {
			what = i18n.M("pki.server_cert")
		}
		if now.Before(cert.NotBefore) {
			return nil, refuse("pki.refuse.not_yet_valid", "cert", what, "date", cert.NotBefore)
		}
		if !now.Before(cert.NotAfter) {
			return nil, refuse("pki.refuse.expired", "cert", what, "date", cert.NotAfter)
		}
	}
	if !serverAuth(leaf) {
		return nil, refuse("pki.refuse.no_server_auth")
	}
	if len(leaf.DNSNames) == 0 && len(leaf.IPAddresses) == 0 {
		return nil, refuse("pki.refuse.no_san")
	}
	https, ldap := c.sources(o)
	a := &Assessment{Own: o, HTTPS: https == SourceOwn, LDAP: ldap == SourceOwn}
	names := strings.Join(o.Names(), ", ")
	if !c.opt.HTTPSOff && c.httpsFiles == nil && !a.HTTPS {
		return nil, refuse("pki.refuse.no_public_host", "host", c.opt.HTTPSHost, "names", names)
	}
	if !a.HTTPS && !a.LDAP {
		httpsBy := i18n.M("pki.unused.https_files")
		if c.httpsFiles == nil {
			httpsBy = i18n.M("pki.unused.https_proxy")
		}
		ldapNeeds := i18n.M("pki.unused.ldap_off")
		if !c.opt.LDAPOff {
			ldapNeeds = i18n.M("pki.unused.ldap_needs", "host", c.opt.LDAPHost)
			if c.ldapFiles != nil {
				ldapNeeds = i18n.M("pki.unused.ldap_files")
			}
		}
		return nil, refuse("pki.refuse.unused", "https", httpsBy, "ldap", ldapNeeds, "names", names)
	}
	if !o.Public && o.Anchor == nil {
		return nil, refuse("pki.refuse.no_anchor")
	}
	if !c.opt.LDAPOff && c.ldapFiles == nil && !a.LDAP {
		a.Warnings = append(a.Warnings, i18n.M("pki.warn.no_ldap_host", "host", c.opt.LDAPHost))
	}
	if len(o.EmptyAKI) > 0 && (a.LDAP || o.Private() != nil) {
		a.Warnings = append(a.Warnings, i18n.M("pki.warn.empty_aki", "n", o.EmptyAKI))
	}
	if o.Private() != nil && a.HTTPS && !o.Anchor.IsCA {
		a.Warnings = append(a.Warnings, i18n.M("pki.warn.self_signed_leaf"))
	}
	if a.HTTPS {
		rescan, err := c.rescan(ctx, o.Chain, o.Public)
		if err != nil {
			return nil, err
		}
		a.Rescan = rescan
	}
	if a.Rescan > 0 {
		a.Warnings = append(a.Warnings, i18n.M("pki.warn.rescan_import", "n", a.Rescan))
	}
	if a.HTTPS {
		newPin := ""
		if p := o.Private(); p != nil {
			newPin = Pin(p)
		}
		w, err := c.reprint(ctx, newPin)
		if err != nil {
			return nil, err
		}
		a.Warnings = append(a.Warnings, w...)
	}
	if days := c.ReminderDays(ctx); leaf.NotAfter.Sub(now) <= time.Duration(days)*24*time.Hour {
		a.Warnings = append(a.Warnings, i18n.M("pki.warn.expires", "days", daysLeft(now, leaf.NotAfter), "date", leaf.NotAfter))
	}
	return a, nil
}

// RemovalWarnings say what removing the uploaded certificate would change for the
// phones when the HTTPS listener returns to the built-in CA; rescan is the number of
// bound clients that would have to scan again.
func (c *Certs) RemovalWarnings(ctx context.Context) (warnings []i18n.Msg, rescan int, err error) {
	if https, _ := c.Sources(); https != SourceOwn {
		return nil, 0, nil
	}
	chain := []*x509.Certificate{c.ca.CA()}
	if s := c.ca.Server(); s != nil {
		chain = []*x509.Certificate{s, c.ca.CA()}
	}
	if rescan, err = c.rescan(ctx, chain, false); err != nil {
		return nil, 0, err
	}
	if rescan > 0 {
		warnings = append(warnings, i18n.M("pki.warn.rescan_remove", "n", rescan))
	}
	w, err := c.reprint(ctx, c.ca.Pin())
	if err != nil {
		return nil, 0, err
	}
	return append(warnings, w...), rescan, nil
}

// reprint warns when the pin of QR codes would change while clients wait for their
// first scan: QR codes printed or sent before carry the old pin.
func (c *Certs) reprint(ctx context.Context, newPin string) ([]i18n.Msg, error) {
	if newPin == c.Pin() {
		return nil, nil
	}
	counts, err := c.ca.st.CountClients(ctx, store.ClientFilter{All: true})
	if err != nil || counts[store.StatusPending] == 0 {
		return nil, err
	}
	return []i18n.Msg{i18n.M("pki.warn.reprint", "n", counts[store.StatusPending])}, nil
}

// rescan counts the bound clients whose phones would refuse chain on the HTTPS listener.
// A phone keeps what it trusted at its first scan (§3.7): the pinned certificate plus
// Android's system CAs, or the system CAs alone. The platform does not know what each
// phone kept; it assumes the anchor of the chain served now.
func (c *Certs) rescan(ctx context.Context, chain []*x509.Certificate, public bool) (int, error) {
	if public {
		return 0, nil
	}
	own := c.Uploaded()
	https, _ := c.sources(own)
	if https == SourceOff {
		return 0, nil
	}
	kept, _ := c.anchor(https, c.httpsFiles, own)
	if kept != nil && trustedBy(chain, kept, c.ca.now()) {
		return 0, nil
	}
	counts, err := c.ca.st.CountClients(ctx, store.ClientFilter{All: true})
	if err != nil {
		return 0, err
	}
	return counts[store.StatusActive], nil
}

// trustedBy reports whether chain leads to anchor the way Android checks a kept CA.
func trustedBy(chain []*x509.Certificate, anchor *x509.Certificate, now time.Time) bool {
	roots := x509.NewCertPool()
	roots.AddCert(anchor)
	return verifies(chain, roots, now)
}

// Notice levels.
const (
	LevelWarn  = "warn"
	LevelError = "error"
)

// Notice is a reminder about an operator's certificate in use.
type Notice struct {
	Level    string
	Text     i18n.Msg
	DaysLeft int
	Cert     *Own
}

// Notices lists the reminders for the operator's certificates in use (uploaded or
// files): inside the window of days before expiry a warning, after expiry an error.
// The built-in certificate renews itself and never has a notice.
func (c *Certs) Notices(days int) []Notice {
	own := c.Uploaded()
	https, ldap := c.sources(own)
	now := c.ca.now()
	var out []Notice
	seen := map[*Own]bool{}
	for _, l := range []struct {
		src   Source
		files *Own
	}{{https, c.httpsFiles}, {ldap, c.ldapFiles}} {
		o, label := own, i18n.M("pki.label.own")
		switch l.src {
		case SourceFiles:
			o, label = l.files, i18n.M("pki.label.files")
		case SourceOwn:
		default:
			continue
		}
		if seen[o] {
			continue
		}
		seen[o] = true
		notAfter := o.Leaf().NotAfter
		switch left := notAfter.Sub(now); {
		case left <= 0:
			out = append(out, Notice{Level: LevelError, Cert: o,
				Text: i18n.M("pki.notice.expired", "cert", label, "date", notAfter)})
		case left <= time.Duration(days)*24*time.Hour:
			n := daysLeft(now, notAfter)
			out = append(out, Notice{Level: LevelWarn, Cert: o, DaysLeft: n,
				Text: i18n.M("pki.notice.expires", "cert", label, "days", n, "date", notAfter)})
		}
	}
	return out
}

// DaysLeft is the number of whole days from now to t, never negative.
func (c *Certs) DaysLeft(t time.Time) int { return daysLeft(c.ca.now(), t) }

func daysLeft(now, t time.Time) int {
	return max(0, int(t.Sub(now)/(24*time.Hour)))
}

// AuditDetail is what the audit trail keeps of an imported certificate: nothing secret.
func AuditDetail(a *Assessment) map[string]any {
	leaf := a.Own.Leaf()
	d := map[string]any{"issuer": leaf.Issuer.String(), "names": a.Own.Names(),
		"not_after": leaf.NotAfter.UTC().Format(time.RFC3339), "fingerprint": Fingerprint(leaf),
		"https": a.HTTPS, "ldap": a.LDAP, "public": a.Own.Public}
	if a.Rescan > 0 {
		d["rescan"] = a.Rescan
	}
	return d
}
