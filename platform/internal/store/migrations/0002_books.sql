-- Hosted address books (design §2.2, §4.7).

CREATE TABLE address_books (
    id              INTEGER PRIMARY KEY,
    region_id       INTEGER REFERENCES regions(id) ON DELETE RESTRICT,
    slug            TEXT NOT NULL UNIQUE,
    name            TEXT NOT NULL,
    note            TEXT NOT NULL DEFAULT '',
    allow_anonymous INTEGER NOT NULL DEFAULT 0,
    entry_count     INTEGER NOT NULL DEFAULT 0,
    revision        INTEGER NOT NULL DEFAULT 1,
    created_at      TEXT NOT NULL,
    updated_at      TEXT NOT NULL
);
CREATE INDEX address_books_region ON address_books(region_id);

CREATE TABLE book_entries (
    id           INTEGER PRIMARY KEY,
    book_id      INTEGER NOT NULL REFERENCES address_books(id) ON DELETE CASCADE,
    name         TEXT NOT NULL,
    surname      TEXT NOT NULL DEFAULT '',
    given_name   TEXT NOT NULL DEFAULT '',
    phones       TEXT NOT NULL DEFAULT '',
    mobiles      TEXT NOT NULL DEFAULT '',
    organization TEXT NOT NULL DEFAULT '',
    department   TEXT NOT NULL DEFAULT '',
    title        TEXT NOT NULL DEFAULT '',
    email        TEXT NOT NULL DEFAULT '',
    sort_key     TEXT NOT NULL DEFAULT '',
    created_at   TEXT NOT NULL,
    updated_at   TEXT NOT NULL
);
CREATE INDEX book_entries_sort ON book_entries(book_id, sort_key);

-- entry_count follows the rows; the limits below are the second line behind the Go checks.
CREATE TRIGGER book_entries_count_add AFTER INSERT ON book_entries
BEGIN UPDATE address_books SET entry_count = entry_count + 1 WHERE id = NEW.book_id; END;
CREATE TRIGGER book_entries_count_del AFTER DELETE ON book_entries
BEGIN UPDATE address_books SET entry_count = entry_count - 1 WHERE id = OLD.book_id; END;

CREATE TRIGGER book_entries_limit BEFORE INSERT ON book_entries
WHEN (SELECT entry_count FROM address_books WHERE id = NEW.book_id) >= 10000
BEGIN SELECT RAISE(ABORT, 'book entry limit reached'); END;

CREATE TRIGGER address_books_limit BEFORE INSERT ON address_books
WHEN (SELECT count(*) FROM address_books) >= 500
BEGIN SELECT RAISE(ABORT, 'address book limit reached'); END;
