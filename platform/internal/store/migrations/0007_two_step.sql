-- Two-step sign-in of administrators and the SMTP settings (design §6.8).

ALTER TABLE admins ADD COLUMN email TEXT NOT NULL DEFAULT '';
ALTER TABLE admins ADD COLUMN email_confirmed INTEGER NOT NULL DEFAULT 0;
ALTER TABLE admins ADD COLUMN two_step_email INTEGER NOT NULL DEFAULT 0;

-- '' = a complete session (every session before this migration), 'second' = waiting
-- for the second step.
ALTER TABLE sessions ADD COLUMN stage TEXT NOT NULL DEFAULT '';

-- secret_enc is sealed with the secret box; confirmed = 0 while the set-up waits for
-- the first code. last_step is the last time step used (replay protection).
CREATE TABLE admin_totp (
    admin_id   INTEGER PRIMARY KEY REFERENCES admins(id) ON DELETE CASCADE,
    secret_enc TEXT NOT NULL,
    confirmed  INTEGER NOT NULL DEFAULT 0,
    last_step  INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

-- credential_json is the WebAuthn credential record (public key, sign count, flags).
CREATE TABLE admin_passkeys (
    id              INTEGER PRIMARY KEY,
    admin_id        INTEGER NOT NULL REFERENCES admins(id) ON DELETE CASCADE,
    name            TEXT NOT NULL,
    credential_id   TEXT NOT NULL UNIQUE,
    credential_json TEXT NOT NULL,
    last_used_at    TEXT,
    created_at      TEXT NOT NULL,
    updated_at      TEXT NOT NULL
);
CREATE INDEX admin_passkeys_admin ON admin_passkeys(admin_id);

CREATE TABLE admin_recovery_codes (
    id         INTEGER PRIMARY KEY,
    admin_id   INTEGER NOT NULL REFERENCES admins(id) ON DELETE CASCADE,
    code_hash  TEXT NOT NULL,
    used_at    TEXT,
    created_at TEXT NOT NULL
);
CREATE INDEX admin_recovery_codes_admin ON admin_recovery_codes(admin_id, code_hash);

-- One live code per administrator and purpose ('login', 'confirm').
CREATE TABLE admin_email_codes (
    admin_id   INTEGER NOT NULL REFERENCES admins(id) ON DELETE CASCADE,
    purpose    TEXT NOT NULL CHECK (purpose IN ('login', 'confirm')),
    code_hash  TEXT NOT NULL,
    address    TEXT NOT NULL,
    expires_at TEXT NOT NULL,
    tries      INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL,
    PRIMARY KEY (admin_id, purpose)
);

-- password_enc is sealed with the secret box ('' = none).
CREATE TABLE mail_settings (
    id           INTEGER PRIMARY KEY CHECK (id = 1),
    host         TEXT NOT NULL DEFAULT '',
    port         INTEGER NOT NULL DEFAULT 587,
    security     TEXT NOT NULL DEFAULT 'starttls' CHECK (security IN ('starttls', 'tls', 'none')),
    username     TEXT NOT NULL DEFAULT '',
    password_enc TEXT NOT NULL DEFAULT '',
    from_address TEXT NOT NULL DEFAULT '',
    from_name    TEXT NOT NULL DEFAULT '',
    updated_at   TEXT NOT NULL
);
