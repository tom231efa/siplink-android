-- Core tables: instance metadata, regions, administrators, sessions, audit trail, TLS material.

CREATE TABLE meta (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);

CREATE TABLE regions (
    id         INTEGER PRIMARY KEY,
    name       TEXT NOT NULL UNIQUE COLLATE NOCASE,
    note       TEXT NOT NULL DEFAULT '',
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE admins (
    id                   INTEGER PRIMARY KEY,
    username             TEXT NOT NULL UNIQUE COLLATE NOCASE,
    display_name         TEXT NOT NULL DEFAULT '',
    password_hash        TEXT NOT NULL,
    role                 TEXT NOT NULL CHECK (role IN ('super', 'region')),
    disabled             INTEGER NOT NULL DEFAULT 0,
    must_change_password INTEGER NOT NULL DEFAULT 0,
    last_login_at        TEXT,
    created_at           TEXT NOT NULL,
    updated_at           TEXT NOT NULL
);

CREATE TABLE admin_regions (
    admin_id  INTEGER NOT NULL REFERENCES admins(id) ON DELETE CASCADE,
    region_id INTEGER NOT NULL REFERENCES regions(id) ON DELETE RESTRICT,
    PRIMARY KEY (admin_id, region_id)
);
CREATE INDEX admin_regions_region ON admin_regions(region_id);

CREATE TABLE sessions (
    token_hash   TEXT PRIMARY KEY,
    admin_id     INTEGER NOT NULL REFERENCES admins(id) ON DELETE CASCADE,
    created_at   TEXT NOT NULL,
    expires_at   TEXT NOT NULL,
    last_seen_at TEXT NOT NULL,
    ip           TEXT NOT NULL DEFAULT '',
    user_agent   TEXT NOT NULL DEFAULT ''
);
CREATE INDEX sessions_admin ON sessions(admin_id);

CREATE TABLE audit_log (
    id          INTEGER PRIMARY KEY,
    at          TEXT NOT NULL,
    admin_id    INTEGER,
    admin_name  TEXT NOT NULL DEFAULT '',
    region_id   INTEGER,
    action      TEXT NOT NULL,
    object_type TEXT NOT NULL DEFAULT '',
    object_id   INTEGER,
    object_name TEXT NOT NULL DEFAULT '',
    detail_json TEXT NOT NULL DEFAULT '{}',
    ip          TEXT NOT NULL DEFAULT ''
);
CREATE INDEX audit_log_at ON audit_log(at);
CREATE INDEX audit_log_region ON audit_log(region_id, at);
CREATE INDEX audit_log_admin ON audit_log(admin_id, at);

CREATE TRIGGER audit_log_no_update BEFORE UPDATE ON audit_log
BEGIN SELECT RAISE(ABORT, 'audit_log is append-only'); END;
CREATE TRIGGER audit_log_no_delete BEFORE DELETE ON audit_log
BEGIN SELECT RAISE(ABORT, 'audit_log is append-only'); END;

-- Certificates of the built-in CA (addendum 2, B.3). name is 'ca' or 'server';
-- key_enc is the private key (PKCS#8 DER) sealed with the secret box.
CREATE TABLE tls_material (
    name       TEXT PRIMARY KEY,
    cert_pem   TEXT NOT NULL,
    key_enc    TEXT NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);
