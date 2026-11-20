-- The fast server timeout of a line in milliseconds: with a backup server, a server that
-- does not answer within it is taken as out of reach. 0 = off, the app's default.
ALTER TABLE account_profiles ADD COLUMN server_timeout_ms INTEGER NOT NULL DEFAULT 0;
