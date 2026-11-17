package store

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"strings"
	"unicode/utf16"

	"siplink/internal/rules"
	"siplink/internal/secret"
)

// inlineExternal is an external directory typed into a template, as stored up to schema 7.
type inlineExternal struct {
	rowID    int64
	region   sql.NullInt64
	name     string
	e        ExternalDirectory
	password string // opened; "" when none is stored
}

// sameConnection reports whether two inline directories reach the same server the same
// way, password included: they may share one entry.
func (a *inlineExternal) sameConnection(b *inlineExternal) bool {
	x, y := a.e, b.e
	return a.region == b.region && x.Host == y.Host && x.Port == y.Port && x.Security == y.Security && x.BindDN == y.BindDN &&
		x.PasswordMode == y.PasswordMode && a.password == b.password && x.BaseDN == y.BaseDN && x.MaxResults == y.MaxResults &&
		x.NameAttributes == y.NameAttributes && x.NumberAttributes == y.NumberAttributes && x.NameFilter == y.NameFilter &&
		x.NumberFilter == y.NumberFilter
}

// moveExternalDirectories is the Go step of migration 0008 (design §2.5): every external
// directory typed into a template becomes an entry of external_directories, rows of one
// region with the same connection share one, and the template's row refers to it. Stored
// passwords are opened with the associated data of their old row and sealed with that of
// the entry.
func moveExternalDirectories(ctx context.Context, tx *sql.Tx, box *secret.Box, now string) error {
	rows, err := tx.QueryContext(ctx, `SELECT d.id, t.region_id, d.name, d.host, d.port, d.security, d.bind_dn, d.password_mode,
		COALESCE(d.password_enc, ''), d.base_dn, d.max_results, d.name_attributes, d.number_attributes, d.name_filter, d.number_filter
		FROM template_directories d JOIN config_templates t ON t.id = d.config_template_id
		WHERE d.kind = 'external' ORDER BY d.id`)
	if err != nil {
		return err
	}
	var list []*inlineExternal
	for rows.Next() {
		x := &inlineExternal{}
		var enc string
		if err := rows.Scan(&x.rowID, &x.region, &x.name, &x.e.Host, &x.e.Port, &x.e.Security, &x.e.BindDN, &x.e.PasswordMode,
			&enc, &x.e.BaseDN, &x.e.MaxResults, &x.e.NameAttributes, &x.e.NumberAttributes, &x.e.NameFilter, &x.e.NumberFilter); err != nil {
			rows.Close()
			return err
		}
		if x.e.PasswordMode == "template" && enc != "" {
			if box == nil {
				rows.Close()
				return errors.New("stored LDAP passwords have to be sealed again; the key file is needed")
			}
			plain, err := box.Open(enc, secret.AAD("template_directories", x.rowID, "password_enc"))
			if err != nil {
				rows.Close()
				return fmt.Errorf("directory %d: the stored password does not open with this key file: %w", x.rowID, err)
			}
			x.password = string(plain)
		}
		list = append(list, x)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return err
	}
	var made []*inlineExternal // the first row of each entry
	entry := map[*inlineExternal]int64{}
	taken := map[string]bool{} // region + lower-case name
	for _, x := range list {
		var id int64
		for _, m := range made {
			if m.sameConnection(x) {
				id = entry[m]
				break
			}
		}
		if id == 0 {
			if id, err = insertMovedEntry(ctx, tx, box, x, uniqueName(x, taken), now); err != nil {
				return err
			}
			made, entry[x] = append(made, x), id
		}
		if _, err := tx.ExecContext(ctx, `UPDATE template_directories SET external_id = ?, password_mode = 'none',
			password_enc = NULL WHERE id = ?`, id, x.rowID); err != nil {
			return err
		}
	}
	return nil
}

// uniqueName is the row's name (its host when it has none), with "（2）", "（3）" … when
// another entry of the region has it already, cut to the length the app allows.
func uniqueName(x *inlineExternal, taken map[string]bool) string {
	base := strings.TrimSpace(x.name)
	if base == "" {
		base = x.e.Host
	}
	region := fmt.Sprint(x.region.Int64, x.region.Valid)
	for n := 1; ; n++ {
		suffix := ""
		if n > 1 {
			suffix = fmt.Sprintf("（%d）", n)
		}
		name := []rune(base)
		for len(utf16.Encode(append(append([]rune{}, name...), []rune(suffix)...))) > rules.MaxDirectoryNameLength {
			name = name[:len(name)-1]
		}
		candidate := string(name) + suffix
		if key := region + "|" + strings.ToLower(candidate); !taken[key] {
			taken[key] = true
			return candidate
		}
	}
}

func insertMovedEntry(ctx context.Context, tx *sql.Tx, box *secret.Box, x *inlineExternal, name, now string) (int64, error) {
	e := x.e
	res, err := tx.ExecContext(ctx, `INSERT INTO external_directories(region_id, name, host, port, security, bind_dn, password_mode,
		base_dn, max_results, name_attributes, number_attributes, name_filter, number_filter, created_at, updated_at)
		VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)`, x.region, name, e.Host, e.Port, e.Security, e.BindDN, e.PasswordMode, e.BaseDN,
		e.MaxResults, e.NameAttributes, e.NumberAttributes, e.NameFilter, e.NumberFilter, now, now)
	if err != nil {
		return 0, err
	}
	id, _ := res.LastInsertId()
	if x.password != "" {
		enc, err := box.Seal([]byte(x.password), ExternalDirectoryAAD(id))
		if err != nil {
			return 0, err
		}
		if _, err := tx.ExecContext(ctx, `UPDATE external_directories SET password_enc = ? WHERE id = ?`, enc, id); err != nil {
			return 0, err
		}
	}
	return id, nil
}
