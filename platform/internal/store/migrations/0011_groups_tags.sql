-- Groups and tags of clients (design §2.6): management data of the console only; nothing of
-- them reaches the provisioning document. name_key is the name folded for comparison
-- (Unicode case folding in Go), so names are unique ignoring case in every script.

CREATE TABLE client_groups (
    id         INTEGER PRIMARY KEY,
    region_id  INTEGER NOT NULL REFERENCES regions(id) ON DELETE CASCADE,
    name       TEXT NOT NULL,
    name_key   TEXT NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);
CREATE UNIQUE INDEX client_groups_name ON client_groups(region_id, name_key);

CREATE TABLE tags (
    id         INTEGER PRIMARY KEY,
    name       TEXT NOT NULL,
    name_key   TEXT NOT NULL UNIQUE,
    color      TEXT NOT NULL DEFAULT 'gray',
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE client_tags (
    client_id INTEGER NOT NULL REFERENCES clients(id) ON DELETE CASCADE,
    tag_id    INTEGER NOT NULL REFERENCES tags(id) ON DELETE CASCADE,
    PRIMARY KEY (client_id, tag_id)
) WITHOUT ROWID;
CREATE INDEX client_tags_tag ON client_tags(tag_id, client_id);

-- At most one group per client; deleting the group leaves the client without one.
ALTER TABLE clients ADD COLUMN group_id INTEGER REFERENCES client_groups(id) ON DELETE SET NULL;
CREATE INDEX clients_group ON clients(group_id);

-- A client's group belongs to the client's region: a second line behind the checks in Go.
CREATE TRIGGER clients_group_region_insert BEFORE INSERT ON clients
WHEN NEW.group_id IS NOT NULL AND NEW.region_id IS NOT (SELECT region_id FROM client_groups WHERE id = NEW.group_id)
BEGIN
    SELECT RAISE(ABORT, 'client group of another region');
END;
CREATE TRIGGER clients_group_region_update BEFORE UPDATE OF group_id, region_id ON clients
WHEN NEW.group_id IS NOT NULL AND NEW.region_id IS NOT (SELECT region_id FROM client_groups WHERE id = NEW.group_id)
BEGIN
    SELECT RAISE(ABORT, 'client group of another region');
END;
