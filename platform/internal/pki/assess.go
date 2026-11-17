package pki

import (
	"context"
	"crypto/x509"
	"fmt"
	"strings"
	"time"

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
	Warnings []string
}

// Assess applies the upload's checks to o (§6.7) and works out what using it would
// change. A *Refusal says why it cannot be used.
func (c *Certs) Assess(ctx context.Context, o *Own) (*Assessment, error) {
	now := c.ca.now()
	o.classify(c.opt.Roots, now)
	leaf := o.Leaf()
	for i, cert := range o.Chain {
		what := fmt.Sprintf("证书链中第 %d 个证书", i+1)
		if i == 0 {
			what = "服务器证书"
		}
		if now.Before(cert.NotBefore) {
			return nil, refuse("%s尚未生效（%s 起生效）", what, day(cert.NotBefore))
		}
		if !now.Before(cert.NotAfter) {
			return nil, refuse("%s已过期（%s 到期）", what, day(cert.NotAfter))
		}
	}
	if !serverAuth(leaf) {
		return nil, refuse("服务器证书不允许用于服务器身份验证：扩展密钥用法（EKU）中不含 serverAuth")
	}
	if len(leaf.DNSNames) == 0 && len(leaf.IPAddresses) == 0 {
		return nil, refuse("服务器证书缺少使用者可选名称（SAN），设备仅按 SAN 中的名称校验")
	}
	https, ldap := c.sources(o)
	a := &Assessment{Own: o, HTTPS: https == SourceOwn, LDAP: ldap == SourceOwn}
	names := strings.Join(o.Names(), ", ")
	if !c.opt.HTTPSOff && c.httpsFiles == nil && !a.HTTPS {
		return nil, refuse("证书不包含公开地址的主机名 %s（证书中的名称：%s）", c.opt.HTTPSHost, names)
	}
	if !a.HTTPS && !a.LDAP {
		httpsBy := "使用环境变量指定的证书文件"
		if c.httpsFiles == nil {
			httpsBy = "由反向代理提供"
		}
		ldapNeeds := "LDAP 服务未开启"
		if !c.opt.LDAPOff {
			ldapNeeds = "LDAP 需要名称 " + c.opt.LDAPHost
			if c.ldapFiles != nil {
				ldapNeeds = "LDAP 使用环境变量指定的证书文件"
			}
		}
		return nil, refuse("无服务使用此证书：HTTPS %s，%s（证书中的名称：%s）", httpsBy, ldapNeeds, names)
	}
	if !o.Public && o.Anchor == nil {
		return nil, refuse("证书链不受公共 CA 信任，且不含根证书，设备无法信任。请将签发它的根证书（自签名 CA 证书）附于链末尾后重新上传")
	}
	if !c.opt.LDAPOff && c.ldapFiles == nil && !a.LDAP {
		a.Warnings = append(a.Warnings, fmt.Sprintf("证书不包含 LDAP 地址 %s，LDAP 继续使用内置 CA 签发的证书，自动签发不暂停。", c.opt.LDAPHost))
	}
	if len(o.EmptyAKI) > 0 && (a.LDAP || o.Private() != nil) {
		a.Warnings = append(a.Warnings, fmt.Sprintf("证书链中第 %s 个证书的授权密钥标识符（AuthorityKeyIdentifier）扩展为空，设备的 SIP/LDAP TLS 库（mbedTLS 3.6.0）将中止握手，LDAP 通讯录及使用这些证书的 SIP TLS 连接将失败。请改用不含此空扩展的证书。",
			joinInts(o.EmptyAKI)))
	}
	if o.Private() != nil && a.HTTPS && !o.Anchor.IsCA {
		a.Warnings = append(a.Warnings, "此为自签名服务器证书（非 CA），设备扫码时将直接信任该证书，日后更换证书时所有设备均须重新扫码。")
	}
	if a.HTTPS {
		rescan, err := c.rescan(ctx, o.Chain, o.Public)
		if err != nil {
			return nil, err
		}
		a.Rescan = rescan
	}
	if a.Rescan > 0 {
		a.Warnings = append(a.Warnings, fmt.Sprintf("已绑定的 %d 台设备信任当前 HTTPS 证书的签发者，而新证书既非由其签发，也不受公共 CA 信任。启用后这些设备将无法同步，须在平台上重新生成二维码并重新扫码。", a.Rescan))
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
		if w != "" {
			a.Warnings = append(a.Warnings, w)
		}
	}
	if days := c.ReminderDays(ctx); leaf.NotAfter.Sub(now) <= time.Duration(days)*24*time.Hour {
		a.Warnings = append(a.Warnings, fmt.Sprintf("证书将在 %d 天后（%s）到期。", daysLeft(now, leaf.NotAfter), day(leaf.NotAfter)))
	}
	return a, nil
}

// RemovalWarnings say what removing the uploaded certificate would change for the
// phones when the HTTPS listener returns to the built-in CA; rescan is the number of
// bound clients that would have to scan again.
func (c *Certs) RemovalWarnings(ctx context.Context) (warnings []string, rescan int, err error) {
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
		warnings = append(warnings, fmt.Sprintf("已绑定的 %d 台设备信任自有证书的签发者，内置 CA 签发的证书不在其信任范围内。停用后这些设备将无法同步，须在平台上重新生成二维码并重新扫码。", rescan))
	}
	w, err := c.reprint(ctx, c.ca.Pin())
	if err != nil {
		return nil, 0, err
	}
	if w != "" {
		warnings = append(warnings, w)
	}
	return warnings, rescan, nil
}

// reprint warns when the pin of QR codes would change while clients wait for their
// first scan: QR codes printed or sent before carry the old pin.
func (c *Certs) reprint(ctx context.Context, newPin string) (string, error) {
	if newPin == c.Pin() {
		return "", nil
	}
	counts, err := c.ca.st.CountClients(ctx, store.ClientFilter{All: true})
	if err != nil || counts[store.StatusPending] == 0 {
		return "", err
	}
	return fmt.Sprintf("二维码中的 pin 将改变：%d 个待上线客户端已打印或发出的二维码将失效，须重新打印或发送（平台页面上的二维码自动更新）。", counts[store.StatusPending]), nil
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
	Text     string
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
		o, label := own, "自有证书"
		switch l.src {
		case SourceFiles:
			o, label = l.files, "证书文件"
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
				Text: fmt.Sprintf("证书已过期，设备将无法同步（%s于 %s 到期）", label, day(notAfter))})
		case left <= time.Duration(days)*24*time.Hour:
			n := daysLeft(now, notAfter)
			out = append(out, Notice{Level: LevelWarn, Cert: o, DaysLeft: n,
				Text: fmt.Sprintf("%s将在 %d 天后（%s）到期，请及时更换", label, n, day(notAfter))})
		}
	}
	return out
}

// DaysLeft is the number of whole days from now to t, never negative.
func (c *Certs) DaysLeft(t time.Time) int { return daysLeft(c.ca.now(), t) }

func daysLeft(now, t time.Time) int {
	return max(0, int(t.Sub(now)/(24*time.Hour)))
}

func day(t time.Time) string { return t.Local().Format("2006-01-02") }

func joinInts(list []int) string {
	parts := make([]string, len(list))
	for i, n := range list {
		parts[i] = fmt.Sprint(n)
	}
	return strings.Join(parts, "、")
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
