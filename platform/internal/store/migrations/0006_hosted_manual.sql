-- A hosted address book of a configuration template may bind with an LDAP service
-- account instead of each client's derived login (NULL = derived, the default).
ALTER TABLE template_directories ADD COLUMN ldap_account_id INTEGER REFERENCES ldap_accounts(id) ON DELETE RESTRICT;
CREATE INDEX template_directories_account ON template_directories(ldap_account_id);
