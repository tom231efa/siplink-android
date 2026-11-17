-- Configuration, key and client templates (design §2.1, §2.2).

CREATE TABLE config_templates (
    id                   INTEGER PRIMARY KEY,
    region_id            INTEGER REFERENCES regions(id) ON DELETE RESTRICT,
    name                 TEXT NOT NULL,
    note                 TEXT NOT NULL DEFAULT '',
    settings_json        TEXT NOT NULL DEFAULT '{}',
    codecs_json          TEXT NOT NULL DEFAULT '',  -- '' = the SDK's defaults
    ca_pems              TEXT NOT NULL DEFAULT '',
    config_password_hash TEXT NOT NULL DEFAULT '',
    refresh_minutes      INTEGER NOT NULL DEFAULT 60,
    revision             INTEGER NOT NULL DEFAULT 1,
    created_at           TEXT NOT NULL,
    updated_at           TEXT NOT NULL
);
-- NULL regions are distinct in a UNIQUE constraint, so global names need the expression.
CREATE UNIQUE INDEX config_templates_name ON config_templates(COALESCE(region_id, 0), name COLLATE NOCASE);

CREATE TABLE account_profiles (
    id                 INTEGER PRIMARY KEY,
    config_template_id INTEGER NOT NULL REFERENCES config_templates(id) ON DELETE CASCADE,
    position           INTEGER NOT NULL,
    name               TEXT NOT NULL,
    domain             TEXT NOT NULL,
    port               TEXT NOT NULL DEFAULT '',
    server             TEXT NOT NULL DEFAULT '',
    backup_server      TEXT NOT NULL DEFAULT '',
    dns_server         TEXT NOT NULL DEFAULT '',
    transport          TEXT NOT NULL DEFAULT 'UDP' CHECK (transport IN ('UDP', 'TCP', 'TLS')),
    expires            INTEGER NOT NULL DEFAULT 3600,
    subscribe_mwi      INTEGER NOT NULL DEFAULT 0,
    enabled            INTEGER NOT NULL DEFAULT 1,
    is_default         INTEGER NOT NULL DEFAULT 0,
    created_at         TEXT NOT NULL,
    updated_at         TEXT NOT NULL
);
CREATE UNIQUE INDEX account_profiles_name ON account_profiles(config_template_id, name COLLATE NOCASE);

CREATE TABLE template_directories (
    id                 INTEGER PRIMARY KEY,
    config_template_id INTEGER NOT NULL REFERENCES config_templates(id) ON DELETE CASCADE,
    position           INTEGER NOT NULL,
    kind               TEXT NOT NULL CHECK (kind IN ('external', 'hosted')),
    book_id            INTEGER REFERENCES address_books(id) ON DELETE RESTRICT,
    name               TEXT NOT NULL,
    enabled            INTEGER NOT NULL DEFAULT 1,
    host               TEXT NOT NULL DEFAULT '',
    port               INTEGER NOT NULL DEFAULT 389,
    security           TEXT NOT NULL DEFAULT 'START_TLS' CHECK (security IN ('NONE', 'START_TLS')),
    bind_dn            TEXT NOT NULL DEFAULT '',
    password_mode      TEXT NOT NULL DEFAULT 'none' CHECK (password_mode IN ('none', 'template', 'client')),
    password_enc       TEXT,
    base_dn            TEXT NOT NULL DEFAULT '',
    line_profile_id    INTEGER REFERENCES account_profiles(id) ON DELETE SET NULL,
    lookup_incoming    INTEGER NOT NULL DEFAULT 1,
    lookup_outgoing    INTEGER NOT NULL DEFAULT 1,
    max_results        INTEGER NOT NULL DEFAULT 50,
    name_attributes    TEXT NOT NULL DEFAULT 'cn sn',
    number_attributes  TEXT NOT NULL DEFAULT 'telephoneNumber mobile',
    name_filter        TEXT NOT NULL DEFAULT '',
    number_filter      TEXT NOT NULL DEFAULT '',
    created_at         TEXT NOT NULL,
    updated_at         TEXT NOT NULL
);
CREATE INDEX template_directories_template ON template_directories(config_template_id);
CREATE INDEX template_directories_book ON template_directories(book_id);

CREATE TABLE key_templates (
    id         INTEGER PRIMARY KEY,
    region_id  INTEGER REFERENCES regions(id) ON DELETE RESTRICT,
    name       TEXT NOT NULL,
    note       TEXT NOT NULL DEFAULT '',
    revision   INTEGER NOT NULL DEFAULT 1,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);
CREATE UNIQUE INDEX key_templates_name ON key_templates(COALESCE(region_id, 0), name COLLATE NOCASE);

CREATE TABLE template_keys (
    id              INTEGER PRIMARY KEY,
    key_template_id INTEGER NOT NULL REFERENCES key_templates(id) ON DELETE CASCADE,
    position        INTEGER NOT NULL,
    label           TEXT NOT NULL,
    number          TEXT NOT NULL DEFAULT '',
    action          TEXT NOT NULL,
    category        TEXT NOT NULL DEFAULT '',
    line            TEXT NOT NULL DEFAULT '',
    icon            TEXT NOT NULL DEFAULT ''
);
CREATE INDEX template_keys_template ON template_keys(key_template_id, position);

CREATE TABLE client_templates (
    id                 INTEGER PRIMARY KEY,
    region_id          INTEGER REFERENCES regions(id) ON DELETE RESTRICT,
    name               TEXT NOT NULL,
    note               TEXT NOT NULL DEFAULT '',
    config_template_id INTEGER NOT NULL REFERENCES config_templates(id) ON DELETE RESTRICT,
    key_template_id    INTEGER REFERENCES key_templates(id) ON DELETE RESTRICT,
    revision           INTEGER NOT NULL DEFAULT 1,
    created_at         TEXT NOT NULL,
    updated_at         TEXT NOT NULL
);
CREATE UNIQUE INDEX client_templates_name ON client_templates(COALESCE(region_id, 0), name COLLATE NOCASE);

-- Codec keys reported by phones, offered in the codec editor (design §3.4, §3.5.6).
CREATE TABLE reported_codecs (
    kind        TEXT NOT NULL CHECK (kind IN ('audio', 'video')),
    key         TEXT NOT NULL,
    app_version TEXT NOT NULL,
    first_seen  TEXT NOT NULL,
    PRIMARY KEY (kind, key, app_version)
);
