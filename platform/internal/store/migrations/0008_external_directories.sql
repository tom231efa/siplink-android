-- External directories get a place of their own; templates refer to them (design §2.5).
-- The rows of templates are moved over by the Go step of this migration (migrate_0008.go),
-- which re-seals stored passwords; migration 0009 then drops the old columns.

CREATE TABLE external_directories (
    id                INTEGER PRIMARY KEY,
    region_id         INTEGER REFERENCES regions(id) ON DELETE RESTRICT,
    name              TEXT NOT NULL,
    note              TEXT NOT NULL DEFAULT '',
    host              TEXT NOT NULL,
    port              INTEGER NOT NULL DEFAULT 389,
    security          TEXT NOT NULL DEFAULT 'START_TLS' CHECK (security IN ('NONE', 'START_TLS')),
    bind_dn           TEXT NOT NULL DEFAULT '',
    password_mode     TEXT NOT NULL DEFAULT 'none' CHECK (password_mode IN ('none', 'template', 'client')),
    password_enc      TEXT,
    base_dn           TEXT NOT NULL DEFAULT '',
    max_results       INTEGER NOT NULL DEFAULT 50,
    name_attributes   TEXT NOT NULL DEFAULT 'cn sn',
    number_attributes TEXT NOT NULL DEFAULT 'telephoneNumber mobile',
    name_filter       TEXT NOT NULL DEFAULT '',
    number_filter     TEXT NOT NULL DEFAULT '',
    created_at        TEXT NOT NULL,
    updated_at        TEXT NOT NULL
);
CREATE UNIQUE INDEX external_directories_name ON external_directories(COALESCE(region_id, 0), name COLLATE NOCASE);

ALTER TABLE template_directories ADD COLUMN external_id INTEGER REFERENCES external_directories(id) ON DELETE RESTRICT;
CREATE INDEX template_directories_external ON template_directories(external_id);

-- What the phone reports: the User-Agent of its requests (design §3.4).
ALTER TABLE client_reports ADD COLUMN user_agent TEXT NOT NULL DEFAULT '';
