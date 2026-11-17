package store

import (
	"context"
	"database/sql"
	"errors"
	"strings"
)

// Book is a hosted address book.
type Book struct {
	ID             int64
	RegionID       *int64
	Slug           string
	Name           string
	Note           string
	AllowAnonymous bool
	EntryCount     int
	Revision       int64
	CreatedAt      string
	UpdatedAt      string
}

// Entry is one person of a book.
type Entry struct {
	ID           int64
	BookID       int64
	Name         string
	Surname      string
	GivenName    string
	Phones       []string
	Mobiles      []string
	Organization string
	Department   string
	Title        string
	Email        string
	UpdatedAt    string
}

// ErrLimit is returned when a write would exceed the limit of books or entries.
var ErrLimit = errors.New("store: limit reached")

// SortKey orders entries by name, case-insensitively.
func SortKey(name string) string { return strings.ToLower(strings.TrimSpace(name)) }

const bookCols = `id, region_id, slug, name, note, allow_anonymous, entry_count, revision, created_at, updated_at`

func scanBook(row interface{ Scan(...any) error }) (*Book, error) {
	b := &Book{}
	var region sql.NullInt64
	err := row.Scan(&b.ID, &region, &b.Slug, &b.Name, &b.Note, &b.AllowAnonymous, &b.EntryCount, &b.Revision,
		&b.CreatedAt, &b.UpdatedAt)
	b.RegionID = ptrID(region)
	return b, mapErr(err)
}

// BookScope selects books: all, or global plus the given regions.
type BookScope struct {
	All     bool
	Regions []int64
	Region  int64 // narrow to one region (0 = no narrowing)
}

// ListBooks returns the books of a scope ordered by name.
func (s *Store) ListBooks(ctx context.Context, sc BookScope) ([]*Book, error) {
	q := `SELECT ` + bookCols + ` FROM address_books`
	var where []string
	var args []any
	if !sc.All {
		cond := `region_id IS NULL`
		if len(sc.Regions) > 0 {
			cond = `(region_id IS NULL OR region_id IN (` + placeholders(len(sc.Regions)) + `))`
			args = append(args, int64Args(sc.Regions)...)
		}
		where = append(where, cond)
	}
	if sc.Region != 0 {
		where, args = append(where, `region_id = ?`), append(args, sc.Region)
	}
	if len(where) > 0 {
		q += ` WHERE ` + strings.Join(where, ` AND `)
	}
	rows, err := s.DB.QueryContext(ctx, q+` ORDER BY name COLLATE NOCASE, id`, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []*Book
	for rows.Next() {
		b, err := scanBook(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, b)
	}
	return out, rows.Err()
}

// BookByID loads one book.
func (s *Store) BookByID(ctx context.Context, id int64) (*Book, error) {
	return scanBook(s.DB.QueryRowContext(ctx, `SELECT `+bookCols+` FROM address_books WHERE id = ?`, id))
}

// BookBySlug loads one book by its slug.
func (s *Store) BookBySlug(ctx context.Context, slug string) (*Book, error) {
	return scanBook(s.DB.QueryRowContext(ctx, `SELECT `+bookCols+` FROM address_books WHERE slug = ?`, slug))
}

// CreateBook inserts a book unless maxBooks books exist already.
func (s *Store) CreateBook(ctx context.Context, b *Book, maxBooks int) error {
	now := s.NowText()
	return s.Tx(ctx, func(tx *sql.Tx) error {
		var n int
		if err := tx.QueryRowContext(ctx, `SELECT count(*) FROM address_books`).Scan(&n); err != nil {
			return err
		}
		if n >= maxBooks {
			return ErrLimit
		}
		res, err := tx.ExecContext(ctx, `INSERT INTO address_books(region_id, slug, name, note, allow_anonymous, created_at, updated_at)
			VALUES(?,?,?,?,?,?,?)`, nullID(b.RegionID), b.Slug, b.Name, b.Note, boolInt(b.AllowAnonymous), now, now)
		if err != nil {
			return mapErr(err)
		}
		b.ID, _ = res.LastInsertId()
		b.Revision, b.CreatedAt, b.UpdatedAt = 1, now, now
		return nil
	})
}

// UpdateBook writes region, name, note and the anonymous flag and bumps the revision.
// The slug never changes (it is part of the DNs phones have stored).
func (s *Store) UpdateBook(ctx context.Context, b *Book) error {
	res, err := s.DB.ExecContext(ctx, `UPDATE address_books SET region_id = ?, name = ?, note = ?, allow_anonymous = ?,
		revision = revision + 1, updated_at = ? WHERE id = ?`,
		nullID(b.RegionID), b.Name, b.Note, boolInt(b.AllowAnonymous), s.NowText(), b.ID)
	if err != nil {
		return mapErr(err)
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrNotFound
	}
	return nil
}

// BookUsers lists what refers to a book (names for the UI); deletion needs it empty.
func (s *Store) BookUsers(ctx context.Context, id int64) ([]string, error) {
	return bookUsers(ctx, s.DB, id)
}

// DeleteBook removes a book and its entries when nothing refers to it.
func (s *Store) DeleteBook(ctx context.Context, id int64) error {
	return s.Tx(ctx, func(tx *sql.Tx) error {
		users, err := bookUsers(ctx, tx, id)
		if err != nil {
			return err
		}
		if len(users) > 0 {
			return ErrInUse
		}
		res, err := tx.ExecContext(ctx, `DELETE FROM address_books WHERE id = ?`, id)
		if err != nil {
			return err
		}
		if n, _ := res.RowsAffected(); n == 0 {
			return ErrNotFound
		}
		return nil
	})
}

func bumpBook(ctx context.Context, tx *sql.Tx, bookID int64, now string) error {
	_, err := tx.ExecContext(ctx, `UPDATE address_books SET revision = revision + 1, updated_at = ? WHERE id = ?`, now, bookID)
	return err
}

func joinNumbers(n []string) string { return strings.Join(n, "\n") }

func splitNumbers(s string) []string {
	if s == "" {
		return nil
	}
	return strings.Split(s, "\n")
}

const entryCols = `id, book_id, name, surname, given_name, phones, mobiles, organization, department, title, email, updated_at`

func scanEntry(row interface{ Scan(...any) error }) (*Entry, error) {
	e := &Entry{}
	var phones, mobiles string
	err := row.Scan(&e.ID, &e.BookID, &e.Name, &e.Surname, &e.GivenName, &phones, &mobiles, &e.Organization,
		&e.Department, &e.Title, &e.Email, &e.UpdatedAt)
	e.Phones, e.Mobiles = splitNumbers(phones), splitNumbers(mobiles)
	return e, mapErr(err)
}

func insertEntry(ctx context.Context, stmt *sql.Stmt, bookID int64, e *Entry, now string) error {
	res, err := stmt.ExecContext(ctx, bookID, e.Name, e.Surname, e.GivenName, joinNumbers(e.Phones), joinNumbers(e.Mobiles),
		e.Organization, e.Department, e.Title, e.Email, SortKey(e.Name), now, now)
	if err != nil {
		if strings.Contains(err.Error(), "book entry limit") {
			return ErrLimit
		}
		return err
	}
	e.ID, _ = res.LastInsertId()
	e.BookID, e.UpdatedAt = bookID, now
	return nil
}

const insertEntrySQL = `INSERT INTO book_entries(book_id, name, surname, given_name, phones, mobiles, organization,
	department, title, email, sort_key, created_at, updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)`

// AddEntries appends entries (replace=false) or replaces all entries (replace=true) of a
// book in one transaction, refusing when the book would hold more than maxEntries.
func (s *Store) AddEntries(ctx context.Context, bookID int64, entries []*Entry, replace bool, maxEntries int) error {
	now := s.NowText()
	return s.Tx(ctx, func(tx *sql.Tx) error {
		var count int
		if err := tx.QueryRowContext(ctx, `SELECT entry_count FROM address_books WHERE id = ?`, bookID).Scan(&count); err != nil {
			return mapErr(err)
		}
		if replace {
			if _, err := tx.ExecContext(ctx, `DELETE FROM book_entries WHERE book_id = ?`, bookID); err != nil {
				return err
			}
			count = 0
		}
		if count+len(entries) > maxEntries {
			return ErrLimit
		}
		stmt, err := tx.PrepareContext(ctx, insertEntrySQL)
		if err != nil {
			return err
		}
		defer stmt.Close()
		for _, e := range entries {
			if err := insertEntry(ctx, stmt, bookID, e, now); err != nil {
				return err
			}
		}
		return bumpBook(ctx, tx, bookID, now)
	})
}

// UpdateEntry rewrites one entry of a book.
func (s *Store) UpdateEntry(ctx context.Context, e *Entry) error {
	now := s.NowText()
	return s.Tx(ctx, func(tx *sql.Tx) error {
		res, err := tx.ExecContext(ctx, `UPDATE book_entries SET name = ?, surname = ?, given_name = ?, phones = ?, mobiles = ?,
			organization = ?, department = ?, title = ?, email = ?, sort_key = ?, updated_at = ? WHERE id = ? AND book_id = ?`,
			e.Name, e.Surname, e.GivenName, joinNumbers(e.Phones), joinNumbers(e.Mobiles), e.Organization, e.Department,
			e.Title, e.Email, SortKey(e.Name), now, e.ID, e.BookID)
		if err != nil {
			return err
		}
		if n, _ := res.RowsAffected(); n == 0 {
			return ErrNotFound
		}
		e.UpdatedAt = now
		return bumpBook(ctx, tx, e.BookID, now)
	})
}

// DeleteEntry removes one entry of a book.
func (s *Store) DeleteEntry(ctx context.Context, bookID, id int64) error {
	now := s.NowText()
	return s.Tx(ctx, func(tx *sql.Tx) error {
		res, err := tx.ExecContext(ctx, `DELETE FROM book_entries WHERE id = ? AND book_id = ?`, id, bookID)
		if err != nil {
			return err
		}
		if n, _ := res.RowsAffected(); n == 0 {
			return ErrNotFound
		}
		return bumpBook(ctx, tx, bookID, now)
	})
}

// EntryByID loads one entry of a book.
func (s *Store) EntryByID(ctx context.Context, bookID, id int64) (*Entry, error) {
	return scanEntry(s.DB.QueryRowContext(ctx, `SELECT `+entryCols+` FROM book_entries WHERE id = ? AND book_id = ?`, id, bookID))
}

// SearchEntries returns one page of a book's entries in name order, optionally
// filtered by a text found in name, numbers, organization, department or email,
// plus the total number of matches.
func (s *Store) SearchEntries(ctx context.Context, bookID int64, text string, limit, offset int) ([]*Entry, int, error) {
	where := `book_id = ?`
	args := []any{bookID}
	if text = strings.TrimSpace(text); text != "" {
		like := "%" + strings.NewReplacer(`\`, `\\`, `%`, `\%`, `_`, `\_`).Replace(text) + "%"
		where += ` AND (name LIKE ? ESCAPE '\' OR phones LIKE ? ESCAPE '\' OR mobiles LIKE ? ESCAPE '\'
			OR organization LIKE ? ESCAPE '\' OR department LIKE ? ESCAPE '\' OR email LIKE ? ESCAPE '\')`
		args = append(args, like, like, like, like, like, like)
	}
	var total int
	if err := s.DB.QueryRowContext(ctx, `SELECT count(*) FROM book_entries WHERE `+where, args...).Scan(&total); err != nil {
		return nil, 0, err
	}
	rows, err := s.DB.QueryContext(ctx, `SELECT `+entryCols+` FROM book_entries WHERE `+where+
		` ORDER BY sort_key, id LIMIT ? OFFSET ?`, append(args, limit, offset)...)
	if err != nil {
		return nil, 0, err
	}
	defer rows.Close()
	var out []*Entry
	for rows.Next() {
		e, err := scanEntry(rows)
		if err != nil {
			return nil, 0, err
		}
		out = append(out, e)
	}
	return out, total, rows.Err()
}

// AllEntries returns every entry of a book in name order and the revision they
// belong to, read in one statement so both are consistent.
func (s *Store) AllEntries(ctx context.Context, bookID int64) ([]*Entry, int64, error) {
	rows, err := s.DB.QueryContext(ctx, `SELECT b.revision, e.id, e.book_id, e.name, e.surname, e.given_name, e.phones,
		e.mobiles, e.organization, e.department, e.title, e.email, e.updated_at
		FROM address_books b LEFT JOIN book_entries e ON e.book_id = b.id
		WHERE b.id = ? ORDER BY e.sort_key, e.id`, bookID)
	if err != nil {
		return nil, 0, err
	}
	defer rows.Close()
	var out []*Entry
	var rev int64
	found := false
	for rows.Next() {
		found = true
		var id, book sql.NullInt64
		var name, surname, given, phones, mobiles, org, dept, title, email, updated sql.NullString
		if err := rows.Scan(&rev, &id, &book, &name, &surname, &given, &phones, &mobiles, &org, &dept, &title, &email, &updated); err != nil {
			return nil, 0, err
		}
		if !id.Valid {
			continue // a book without entries
		}
		out = append(out, &Entry{ID: id.Int64, BookID: book.Int64, Name: name.String, Surname: surname.String,
			GivenName: given.String, Phones: splitNumbers(phones.String), Mobiles: splitNumbers(mobiles.String),
			Organization: org.String, Department: dept.String, Title: title.String, Email: email.String, UpdatedAt: updated.String})
	}
	if err := rows.Err(); err != nil {
		return nil, 0, err
	}
	if !found {
		return nil, 0, ErrNotFound
	}
	return out, rev, nil
}

// bookUsers names what refers to a book.
func bookUsers(ctx context.Context, q querier, id int64) ([]string, error) {
	rows, err := q.QueryContext(ctx, `SELECT 'LDAP 账号 ' || a.name FROM ldap_accounts a JOIN ldap_account_books g ON g.account_id = a.id
		WHERE g.book_id = ?
		UNION ALL SELECT DISTINCT '配置模板 ' || t.name FROM config_templates t JOIN template_directories d ON d.config_template_id = t.id
		WHERE d.book_id = ? ORDER BY 1`, id, id)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []string
	for rows.Next() {
		var name string
		if err := rows.Scan(&name); err != nil {
			return nil, err
		}
		out = append(out, name)
	}
	return out, rows.Err()
}

// CountBooks returns the number of books.
func (s *Store) CountBooks(ctx context.Context) (int, error) {
	var n int
	err := s.DB.QueryRowContext(ctx, `SELECT count(*) FROM address_books`).Scan(&n)
	return n, err
}
