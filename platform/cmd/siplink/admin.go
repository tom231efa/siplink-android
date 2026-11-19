package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"io"
	"strings"

	"siplink/internal/auth"
	"siplink/internal/i18n"
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
		return i18n.E("cli.admin.subcommand")
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
	return i18n.E("cli.admin.unknown", "name", args[0])
}

func adminCreate(ctx context.Context, st *store.Store, args []string, out io.Writer) error {
	t := cli()
	fs := flag.NewFlagSet("admin create", flag.ContinueOnError)
	fs.SetOutput(out)
	super := fs.Bool("super", false, t.T("common.role.super"))
	display := fs.String("name", "", t.T("cli.admin.flag_name"))
	var regions multiFlag
	fs.Var(&regions, "region", t.T("cli.admin.flag_region"))
	if err := fs.Parse(args); err != nil {
		return err
	}
	if fs.NArg() != 1 {
		return i18n.E("cli.admin.create_usage")
	}
	username := fs.Arg(0)
	if !auth.ValidUsername(username) {
		return i18n.E("cli.admin.username_invalid")
	}
	if i18n.HasBidiControl(*display) {
		return i18n.E("names.bidi_control")
	}
	a := &store.Admin{Username: username, DisplayName: *display, Role: store.RoleSuper, MustChangePassword: true}
	if !*super {
		if len(regions) == 0 {
			return i18n.E("cli.admin.region_needed")
		}
		a.Role = store.RoleRegion
		for _, name := range regions {
			r, err := st.RegionByName(ctx, name)
			if errors.Is(err, store.ErrNotFound) {
				return i18n.E("cli.admin.region_unknown", "name", name)
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
			return i18n.E("cli.admin.username_taken", "name", username)
		}
		return err
	}
	_ = st.AddAudit(ctx, &store.AuditEntry{AdminName: "CLI", Action: "admin.create", ObjectType: "admin",
		ObjectID: &a.ID, ObjectName: a.Username, Detail: map[string]any{"role": a.Role}})
	fmt.Fprintln(out, t.T("cli.admin.created", "username", a.Username, "role", roleName(t, a.Role)))
	fmt.Fprintln(out, t.T("cli.admin.one_time_password"), password) // not an argument: no isolates around it
	fmt.Fprintln(out, t.T("cli.admin.change_on_login"))
	return nil
}

func adminReset(ctx context.Context, st *store.Store, args []string, out io.Writer) error {
	if len(args) != 1 {
		return i18n.E("cli.admin.reset_password_usage")
	}
	a, err := st.AdminByUsername(ctx, args[0])
	if errors.Is(err, store.ErrNotFound) {
		return i18n.E("cli.admin.unknown_admin", "name", args[0])
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
		ObjectID: &a.ID, ObjectName: a.Username, Detail: map[string]any{"password": "changed"}}) // a code, as the web pages store it
	t := cli()
	fmt.Fprintln(out, t.T("cli.admin.password_reset", "username", a.Username))
	fmt.Fprintln(out, t.T("cli.admin.one_time_password"), password)
	fmt.Fprintln(out, t.T("cli.admin.password_reset_after"))
	return nil
}

// adminResetTwoStep removes every two-step method of an administrator (design §6.8),
// for one who lost them all; their sessions end.
func adminResetTwoStep(ctx context.Context, st *store.Store, args []string, out io.Writer) error {
	if len(args) != 1 {
		return i18n.E("cli.admin.reset_2fa_usage")
	}
	a, err := st.AdminByUsername(ctx, args[0])
	if errors.Is(err, store.ErrNotFound) {
		return i18n.E("cli.admin.unknown_admin", "name", args[0])
	}
	if err != nil {
		return err
	}
	if err := st.ResetTwoStep(ctx, a.ID); err != nil {
		return err
	}
	_ = st.AddAudit(ctx, &store.AuditEntry{AdminName: "CLI", Action: "two_step.reset", ObjectType: "admin",
		ObjectID: &a.ID, ObjectName: a.Username})
	t := cli()
	fmt.Fprintln(out, t.T("cli.admin.two_step_reset", "username", a.Username))
	fmt.Fprintln(out, t.T("cli.admin.two_step_reset_after"))
	return nil
}

func roleName(t *i18n.Tr, role string) string {
	if role == store.RoleSuper {
		return t.T("common.role.super")
	}
	return t.T("common.role.region")
}
