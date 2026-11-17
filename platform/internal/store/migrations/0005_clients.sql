-- Clients, their per-profile credentials, reports and revoked device keys (design §2.2).

CREATE TABLE clients (
    id                 INTEGER PRIMARY KEY,
    region_id          INTEGER NOT NULL REFERENCES regions(id) ON DELETE RESTRICT,
    client_template_id INTEGER NOT NULL REFERENCES client_templates(id) ON DELETE RESTRICT,
    name               TEXT NOT NULL,
    note               TEXT NOT NULL DEFAULT '',
    public_id          TEXT NOT NULL UNIQUE,
    status             TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'active')),
    enroll_token_hash  TEXT UNIQUE,
    enroll_token_enc   TEXT,
    enroll_expires_at  TEXT,
    device_key_hash    TEXT UNIQUE,
    bound_at           TEXT,
    install_id         TEXT NOT NULL DEFAULT '',
    ldap_generation    INTEGER NOT NULL DEFAULT 1,
    forward_always     TEXT,
    forward_busy       TEXT,
    forward_no_answer  TEXT,
    revision           INTEGER NOT NULL DEFAULT 1,
    created_at         TEXT NOT NULL,
    updated_at         TEXT NOT NULL
);
CREATE UNIQUE INDEX clients_name ON clients(region_id, name COLLATE NOCASE);
CREATE INDEX clients_template ON clients(client_template_id);

CREATE TABLE client_credentials (
    client_id    INTEGER NOT NULL REFERENCES clients(id) ON DELETE CASCADE,
    profile_id   INTEGER NOT NULL REFERENCES account_profiles(id) ON DELETE CASCADE,
    username     TEXT NOT NULL,
    auth_user    TEXT NOT NULL DEFAULT '',
    password_enc TEXT NOT NULL DEFAULT '',
    display_name TEXT NOT NULL DEFAULT '',
    account_name TEXT,
    PRIMARY KEY (client_id, profile_id)
);
CREATE INDEX client_credentials_profile ON client_credentials(profile_id);

CREATE TABLE client_directory_passwords (
    client_id    INTEGER NOT NULL REFERENCES clients(id) ON DELETE CASCADE,
    directory_id INTEGER NOT NULL REFERENCES template_directories(id) ON DELETE CASCADE,
    password_enc TEXT NOT NULL,
    PRIMARY KEY (client_id, directory_id)
);
CREATE INDEX client_directory_passwords_directory ON client_directory_passwords(directory_id);

CREATE TABLE client_reports (
    client_id          INTEGER PRIMARY KEY REFERENCES clients(id) ON DELETE CASCADE,
    last_seen_at       TEXT NOT NULL DEFAULT '',
    last_ip            TEXT NOT NULL DEFAULT '',
    app_version        TEXT NOT NULL DEFAULT '',
    app_version_code   INTEGER NOT NULL DEFAULT 0,
    android_release    TEXT NOT NULL DEFAULT '',
    android_sdk        INTEGER NOT NULL DEFAULT 0,
    device_model       TEXT NOT NULL DEFAULT '',
    sent_version       TEXT NOT NULL DEFAULT '',
    revision_key       TEXT NOT NULL DEFAULT '',
    last_doc_enc       TEXT NOT NULL DEFAULT '',  -- the last valid document sent, sealed (§3.5.8)
    applied_version    TEXT NOT NULL DEFAULT '',
    applied_at         TEXT NOT NULL DEFAULT '',
    apply_error        TEXT NOT NULL DEFAULT '',
    registrations_json TEXT NOT NULL DEFAULT '[]',
    build_error        TEXT NOT NULL DEFAULT ''   -- why the current document does not validate
);

CREATE TABLE revoked_devices (
    device_key_hash TEXT PRIMARY KEY,
    client_name     TEXT NOT NULL,
    reason          TEXT NOT NULL CHECK (reason IN ('deleted', 'unbound')),
    revoked_at      TEXT NOT NULL
);
CREATE INDEX revoked_devices_at ON revoked_devices(revoked_at);
