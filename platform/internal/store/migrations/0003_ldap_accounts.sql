-- LDAP service accounts (design §2.2, §4.3).

CREATE TABLE ldap_accounts (
    id            INTEGER PRIMARY KEY,
    region_id     INTEGER REFERENCES regions(id) ON DELETE RESTRICT,
    name          TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    note          TEXT NOT NULL DEFAULT '',
    disabled      INTEGER NOT NULL DEFAULT 0,
    created_at    TEXT NOT NULL,
    updated_at    TEXT NOT NULL
);
CREATE INDEX ldap_accounts_region ON ldap_accounts(region_id);

CREATE TABLE ldap_account_books (
    account_id INTEGER NOT NULL REFERENCES ldap_accounts(id) ON DELETE CASCADE,
    book_id    INTEGER NOT NULL REFERENCES address_books(id) ON DELETE RESTRICT,
    PRIMARY KEY (account_id, book_id)
);
CREATE INDEX ldap_account_books_book ON ldap_account_books(book_id);
