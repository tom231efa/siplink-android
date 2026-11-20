-- The client lists are ordered by name, then id (ListClients). clients_name starts with
-- the region and serves one region only; this index has the order of every list, so that
-- a page of all regions reads its rows instead of sorting every client.
CREATE INDEX clients_name_order ON clients(name COLLATE NOCASE, id);
