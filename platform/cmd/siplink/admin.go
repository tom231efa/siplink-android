package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"io"
	"strings"

	"siplink/internal/auth"
	"siplink/internal/secret"
	"siplink/internal/store"
)

type multiFlag []string

func (m *multiFlag) String() string     { return strings.Join(*m, ",") }
func (m *multiFlag) Set(v string) error { *m = append(*m, v); return nil }

// adminCommand handles "admin create", "admin reset-password" and "admin reset-2fa".
// The one-time password goes to out (the terminal), never to the log.
func adminCommand(args []string, out io.Writer) error {
	if len(args) == 0 {
		return errors.New("admin 需要子命令: create, reset-password, reset-2fa")
	}
	ctx := context.Background()
	e, err := open(ctx)
	if err != nil {
		return err
	}
	defer e.st.Close()
	switch args[0] {
	case "create":
		return adminCreate(ctx, e.st, args[1:], out)
	case "reset-password":
		return adminReset(ctx, e.st, args[1:], out)
	case "reset-2fa":
		return adminResetTwoStep(ctx, e.st, args[1:], out)
	}
	return fmt.Errorf("未知的 admin 子命令 %q", args[0])
}

func adminCreate(ctx context.Context, st *store.Store, args []string, out io.Writer) error {
	fs := flag.NewFlagSet("admin create", flag.ContinueOnError)
	fs.SetOutput(out)
	super := fs.Bool("super", false, "超级管理员")
	display := fs.String("name", "", "显示名")
	var regions multiFlag
	fs.Var(&regions, "region", "区域名称（区域管理员，可重复）")
	if err := fs.Parse(args); err != nil {
		return err
	}
	if fs.NArg() != 1 {
		return errors.New("用法: siplink admin create [--super] [--region 名称]... [--name 显示名] <用户名>")
	}
	username := fs.Arg(0)
	if !auth.ValidUsername(username) {
		return errors.New("用户名须为 2-32 个字母、数字或 . _ -")
	}
	a := &store.Admin{Username: username, DisplayName: *display, Role: store.RoleSuper, MustChangePassword: true}
	if !*super {
		if len(regions) == 0 {
			return errors.New("区域管理员至少需要一个 --region；超级管理员请加 --super")
		}
		a.Role = store.RoleRegion
		for _, name := range regions {
			r, err := st.RegionByName(ctx, name)
			if errors.Is(err, store.ErrNotFound) {
				return fmt.Errorf("区域 %q 不存在", name)
			}
			if err != nil {
				return err
			}
			a.RegionIDs = append(a.RegionIDs, r.ID)
		}
	}
	password := secret.RandomText(16)
	hash, err := auth.HashPassword(password)
	if err != nil {
		return err
	}
	a.PasswordHash = hash
	if err := st.CreateAdmin(ctx, a); err != nil {
		if errors.Is(err, store.ErrConflict) {
			return fmt.Errorf("用户名 %q 已存在", username)
		}
		return err
	}
	_ = st.AddAudit(ctx, &store.AuditEntry{AdminName: "CLI", Action: "admin.create", ObjectType: "admin",
		ObjectID: &a.ID, ObjectName: a.Username, Detail: map[string]any{"role": a.Role}})
	fmt.Fprintf(out, "已创建管理员 %s（%s）\n一次性密码: %s\n首次登录后须修改密码。\n", a.Username, roleName(a.Role), password)
	return nil
}

func adminReset(ctx context.Context, st *store.Store, args []string, out io.Writer) error {
	if len(args) != 1 {
		return errors.New("用法: siplink admin reset-password <用户名>")
	}
	a, err := st.AdminByUsername(ctx, args[0])
	if errors.Is(err, store.ErrNotFound) {
		return fmt.Errorf("管理员 %q 不存在", args[0])
	}
	if err != nil {
		return err
	}
	password := secret.RandomText(16)
	hash, err := auth.HashPassword(password)
	if err != nil {
		return err
	}
	if err := st.SetAdminPassword(ctx, a.ID, hash, true); err != nil {
		return err
	}
	_ = st.AddAudit(ctx, &store.AuditEntry{AdminName: "CLI", Action: "admin.password_reset", ObjectType: "admin",
		ObjectID: &a.ID, ObjectName: a.Username, Detail: map[string]any{"password": "已修改"}})
	fmt.Fprintf(out, "已重置 %s 的密码\n一次性密码: %s\n该管理员的全部会话已结束，登录后须修改密码。\n", a.Username, password)
	return nil
}

// adminResetTwoStep removes every two-step method of an administrator (design §6.8),
// for one who lost them all; their sessions end.
func adminResetTwoStep(ctx context.Context, st *store.Store, args []string, out io.Writer) error {
	if len(args) != 1 {
		return errors.New("用法: siplink admin reset-2fa <用户名>")
	}
	a, err := st.AdminByUsername(ctx, args[0])
	if errors.Is(err, store.ErrNotFound) {
		return fmt.Errorf("管理员 %q 不存在", args[0])
	}
	if err != nil {
		return err
	}
	if err := st.ResetTwoStep(ctx, a.ID); err != nil {
		return err
	}
	_ = st.AddAudit(ctx, &store.AuditEntry{AdminName: "CLI", Action: "two_step.reset", ObjectType: "admin",
		ObjectID: &a.ID, ObjectName: a.Username})
	fmt.Fprintf(out, "已重置 %s 的两步验证\n全部验证方式和恢复码已删除，该管理员的全部会话已结束。\n", a.Username)
	return nil
}

func roleName(role string) string {
	if role == store.RoleSuper {
		return "超级管理员"
	}
	return "区域管理员"
}
