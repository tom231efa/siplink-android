package main

import (
	"context"
	"errors"
	"fmt"
	"io"
	"os"
	"slices"
	"strings"
	"time"

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
		return errors.New("pki 需要子命令: show, export-ca, renew, import, use-builtin")
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
			return errors.New("用法: siplink pki export-ca <路径>")
		}
		if err := os.WriteFile(rest[0], ca.CAPEM(), 0o644); err != nil {
			return err
		}
		fmt.Fprintln(out, "CA 证书已写入", rest[0])
	case "renew":
		if err := certs.Issue(ctx); err != nil {
			return err
		}
		fmt.Fprintln(out, "服务器证书已重新签发，运行中的平台将在一分钟内启用。")
	case "import":
		if len(rest) != 2 {
			return errors.New("用法: siplink pki import [--yes] <证书链文件> <私钥文件>")
		}
		return pkiImport(ctx, e, certs, rest[0], rest[1], yes, out)
	case "use-builtin":
		return pkiUseBuiltin(ctx, e, certs, yes, out)
	default:
		return fmt.Errorf("未知的 pki 子命令 %q", args[0])
	}
	return nil
}

func pkiShow(ctx context.Context, certs *pki.Certs, out io.Writer) {
	ca := certs.CA()
	https, ldap := certs.Sources()
	fmt.Fprintf(out, "模式: %s\n  HTTPS: %s\n  LDAP: %s\n", certs.Mode(),
		pki.SourceLabel(https, "关闭（SIPLINK_TLS=off，由反向代理提供 HTTPS）"), pki.SourceLabel(ldap, "未开启"))
	if certs.Paused() {
		fmt.Fprintln(out, "  内置 CA 自动签发: 已暂停")
	} else {
		fmt.Fprintln(out, "  内置 CA 自动签发: 运行中（剩余不足 30 天或名称变化时续签）")
	}
	if pin := certs.Pin(); pin != "" {
		fmt.Fprintln(out, "  二维码 pin:", pin)
	} else {
		fmt.Fprintln(out, "  二维码 pin: 无（设备按 Android 系统 CA 校验）")
	}
	c := ca.CA()
	fmt.Fprintf(out, "内置 CA: %s\n  有效期: %s 至 %s\n  SHA-256 指纹: %s\n  pin: %s\n",
		c.Subject.String(), localDay(c.NotBefore), localDay(c.NotAfter), pki.Fingerprint(c), ca.Pin())
	if s := ca.Server(); s != nil {
		fmt.Fprintf(out, "内置服务器证书: %v %v\n  到期: %s\n", s.DNSNames, s.IPAddresses, localDay(s.NotAfter))
	} else {
		fmt.Fprintln(out, "内置服务器证书: 尚未签发")
	}
	httpsFiles, ldapFiles := certs.Files()
	if httpsFiles != nil {
		showOwn(out, "HTTPS 证书文件", httpsFiles, certs)
	}
	if ldapFiles != nil && ldapFiles != httpsFiles {
		showOwn(out, "LDAP 证书文件", ldapFiles, certs)
	}
	if o := certs.Uploaded(); o != nil {
		showOwn(out, "自有证书（已上传）", o, certs)
	}
	days := certs.ReminderDays(ctx)
	fmt.Fprintf(out, "到期提醒: 提前 %d 天\n", days)
	for _, n := range certs.Notices(days) {
		fmt.Fprintf(out, "%s: %s\n", map[string]string{pki.LevelWarn: "提醒", pki.LevelError: "错误"}[n.Level], n.Text)
	}
}

func showOwn(out io.Writer, title string, o *pki.Own, certs *pki.Certs) {
	leaf := o.Leaf()
	trust := "公共 CA（设备按 Android 系统 CA 校验）"
	if p := o.Private(); p != nil {
		trust = "私有根证书 " + p.Subject.String() + "，pin " + pki.Pin(p)
	} else if !o.Public {
		trust = "不受公共 CA 信任，且证书链不含根证书"
	}
	fmt.Fprintf(out, "%s: %s\n  签发者: %s\n  名称: %s\n  到期: %s（剩余 %d 天）\n  信任: %s\n  SHA-256 指纹: %s\n",
		title, leaf.Subject.String(), leaf.Issuer.String(), strings.Join(o.Names(), ", "),
		localDay(leaf.NotAfter), certs.DaysLeft(leaf.NotAfter), trust, pki.Fingerprint(leaf))
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
		fmt.Fprintln(out, "提示:", w)
	}
	if len(a.Warnings) > 0 && !yes {
		return errors.New("证书未启用：确认以上提示后加 --yes 重新运行")
	}
	if err := certs.Import(ctx, o); err != nil {
		return err
	}
	_ = e.st.AddAudit(ctx, &store.AuditEntry{AdminName: "CLI", Action: "pki.own_import", ObjectType: "system",
		ObjectName: o.Leaf().Subject.String(), Detail: pki.AuditDetail(a)})
	fmt.Fprintln(out, "自有证书已保存，运行中的平台将在一分钟内启用。", certs.Mode())
	return nil
}

func pkiUseBuiltin(ctx context.Context, e *env, certs *pki.Certs, yes bool, out io.Writer) error {
	o := certs.Uploaded()
	if o == nil {
		return errors.New("尚未上传自有证书")
	}
	warnings, rescan, err := certs.RemovalWarnings(ctx)
	if err != nil {
		return err
	}
	for _, w := range warnings {
		fmt.Fprintln(out, "提示:", w)
	}
	if len(warnings) > 0 && !yes {
		return errors.New("未停用：确认以上提示后加 --yes 重新运行")
	}
	reason, err := certs.Remove(ctx)
	if err != nil {
		return err
	}
	_ = e.st.AddAudit(ctx, &store.AuditEntry{AdminName: "CLI", Action: "pki.own_remove", ObjectType: "system",
		ObjectName: o.Leaf().Subject.String(), Detail: map[string]any{"rescan": rescan}})
	if reason != "" {
		fmt.Fprintln(out, "已签发内置服务器证书（"+reason+"）。")
	}
	fmt.Fprintln(out, "已停用自有证书并恢复使用内置 CA，运行中的平台将在一分钟内切换。")
	return nil
}
