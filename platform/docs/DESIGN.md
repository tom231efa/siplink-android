# SipLink management platform — design (phase 1)

Status: accepted design, including addendum 2 (name, built-in certificate authority, TLS by
default), 2026-11-16. Names of the app that appear in this document are quoted from the app
code in this repository (`android/`, branch `dev`, including uncommitted work seen on 2026-11-16). Names of the platform (tables, JSON fields, environment variables, URL paths)
are defined here; they are new.

Names (final, decided by the owner, see §11): Go module, repository and binary `siplink`,
command in `cmd/siplink/`, CLI examples `siplink admin create --super`, Docker image
`{user}/siplink:{tag}`, compose service `siplink`. Unchanged: the `SIPLINK_` prefix of the
environment variables, the cookie `siplink_session`, the default LDAP suffix `dc=siplink`, the
backup file names, the page title "SipLink 管理平台". The repository keeps no absolute path of its
own project directory.

---

## 1. Scope and non-goals

### In scope
- One Go program, `siplink`, that contains:
  - a web UI for administrators in eight languages (Simplified Chinese as the source text, §5.5),
    server-rendered;
  - the provisioning endpoint the SipLink app fetches its managed configuration from;
  - a read-only LDAPv3 server that serves the hosted address books;
  - a built-in certificate authority that signs the certificates of its own listeners (§6.7);
  - a small CLI for operations (`admin create`, `admin reset-password`, `admin reset-2fa`, `backup`, `migrate`,
    `version`, `pki show`, `pki export-ca {path}`, `pki renew`).
- Regions, clients, configuration templates, key templates, client templates, address books,
  LDAP service accounts, administrators with two roles, audit trail, backups.
- Docker image and compose file. Tests that run in the official `golang` images.
- A precise contract (the provisioning document) for the app's loader, and the list of app work
  the loader needs (§9). The loader itself is built later, in the app repository.

### Not in scope (now)
- Changing the app (this phase only reads it).
- Pushing changes to phones instantly (phones poll; "sync now" exists on the phone).
- Remote control of a phone (wipe on demand, call logs upload, remote logs, screenshots).
- Writing to LDAP from outside (no LDAP add/modify/delete; the web UI is the only editor).
- Syncing address books from another LDAP/AD server, multi-tenant hosting, SSO/OIDC,
  high availability / clustering (one process, one SQLite file). (Two-step sign-in for
  administrators was added on 2026-11-17, §6.8.)
- Provisioning other phone brands (Yealink etc.). The hosted LDAP books can still be read by any
  LDAP-capable phone through an LDAP service account.
- App updates through the platform (the app has its own GitHub update check, see §3.6).

---

## 2. Data model and permissions

### 2.1 Concepts

```
region ──< client >── client template ──> configuration template ──< account profile
   │                                 └──> key template ──< key          ──< directory ──> address book (内部) | external directory (外部)
   ├──< configuration / key / client templates (or global: region = NULL)
   ├──< address books (or global) ──< entry
   ├──< external directories (or global)
   └──< LDAP service accounts (or global)
client ──< client credentials (one per account profile), client directory passwords
region ──< client group ──< client (at most one group, of the client's region)
tag (shared) >──< client (at most ten tags per client)
```

- A **directory** (通讯录) is either an **address book** hosted by the platform (内部, table
  `address_books`, served by the LDAP service of §4) or an **external directory** (外部, table
  `external_directories`): how to reach another LDAP server (host, port, security, bind DN, the
  password handling, base DN, maximum results, attributes, filters). Both are made and kept on
  one page, 通讯录 (§5.2); the type is chosen when one is made and never changes (they are two
  tables, so nothing can turn one into the other; the forms refuse a `kind` that differs).
- A **configuration template** (配置模板) holds everything handed out except shortcut keys:
  settings, codecs, CA certificates, the configuration password, the refresh interval, a list
  of **account profiles** (the server side of an account: domain, transport, servers, expiry, …)
  and a list of **directory uses** (`template_directories`): each refers to one address book or
  one external directory and adds only what belongs to its use in this template — the name
  shown on the phone (empty = the directory's name), enabled, the line, lookup for incoming
  and outgoing calls, the order, and for address books the maximum results and the credentials
  choice (§3.5.5). A template never holds how to reach an external server.
- A **key template** (功能键模板) holds shortcut keys.
- A **client template** (客户端模板) = one configuration template + at most one key template.
- A **client** (客户端) = one installed SipLink, in one region, using one client template, plus
  its own values: for every account profile of the template its credentials (user name,
  password, authorization user, display name, optional account name), its own forwarding
  targets if any, and its own passwords for external directories whose password is not in the
  template.

Why account profiles: one template describes "line 1 on pbx-a over TLS, line 2 on pbx-b",
every client only fills in who it is on each line. This also makes CSV bulk creation of clients
simple (one column pair per profile).

### 2.2 Tables (SQLite)

All tables have `id INTEGER PRIMARY KEY`, `created_at`, `updated_at` (UTC, RFC 3339 text)
unless noted. Foreign keys on (`PRAGMA foreign_keys=ON`), WAL mode, `busy_timeout=5000`.
`*_enc` columns hold AES-256-GCM ciphertext (§6.1). `revision` columns are integers bumped on
every change and feed the document version.

| Table | Columns (besides id/timestamps) | Notes |
|---|---|---|
| `admins` | `username` UNIQUE (case-insensitive), `display_name`, `password_hash` (argon2id PHC string), `role` (`super`/`region`), `disabled`, `must_change_password`, `last_login_at`, `email` (''= none), `email_confirmed`, `two_step_email` (migration 0007) | |
| `admin_regions` | `admin_id`, `region_id` (PK both) | only for role `region` |
| `sessions` | `token_hash` PK (SHA-256 hex), `admin_id`, `expires_at`, `last_seen_at`, `ip`, `user_agent`, `stage` (`''` complete, `second` waiting for the second step; 0007) | |
| `admin_totp` | `admin_id` PK, `secret_enc`, `confirmed`, `last_step` | 0007; §6.8 |
| `admin_passkeys` | `admin_id`, `name`, `credential_id` UNIQUE (base64url), `credential_json`, `last_used_at` | 0007; public data only |
| `admin_recovery_codes` | `admin_id`, `code_hash`, `used_at` NULL | 0007; no `updated_at` |
| `admin_email_codes` | `admin_id`, `purpose` (`login`/`confirm`) (PK both), `code_hash`, `address`, `expires_at`, `tries` | 0007; no `updated_at` |
| `mail_settings` | `id` = 1 only, `host`, `port`, `security`, `username`, `password_enc`, `from_address`, `from_name` | 0007; §6.8 |
| `regions` | `name` UNIQUE, `note` | |
| `config_templates` | `region_id` NULL=global, `name`, `note`, `settings_json`, `codecs_json`, `ca_pems` (text, PEM bundle), `config_password_hash` (SHA-256 hex or ''), `refresh_minutes`, `revision` | UNIQUE(`region_id`,`name`) |
| `account_profiles` | `config_template_id`, `position`, `name` (≤30, unique per template, case-insensitive), `domain`, `port` (text), `server`, `backup_server`, `server_timeout_ms` (0 = off, else 200–10000; migration 0013), `dns_server`, `transport` (`UDP`/`TCP`/`TLS`), `expires`, `subscribe_mwi`, `enabled`, `is_default` | ≤100 per template |
| `template_directories` | `config_template_id`, `position`, `kind` (`external`/`hosted`), `book_id` NULL (hosted), `external_id` NULL (external, migration 0008), `name` ('' = the directory's name), `enabled`, `password_mode` (`none`/`template`/`client`) and `password_enc` NULL (hosted bound by hand only), `line_profile_id` NULL, `ldap_account_id` NULL (hosted only, migration 0006), `lookup_incoming`, `lookup_outgoing`, `max_results` (hosted only) | ≤50 per template; the connection columns it had up to schema 7 moved to `external_directories` (migrations 0008/0009, §2.5); a trigger refuses an `external` row without `external_id` and a `hosted` row without `book_id` |
| `external_directories` | `region_id` NULL, `name` (≤40, unique per region case-insensitive), `note`, `host`, `port`, `security` (`NONE`/`START_TLS`), `bind_dn`, `password_mode` (`none`/`template`/`client`), `password_enc` NULL, `base_dn`, `max_results`, `name_attributes`, `number_attributes`, `name_filter`, `number_filter` | 0008; `password_mode=template` = one password kept by the platform for every client (the word is historic: it was kept in the template) |
| `key_templates` | `region_id` NULL, `name`, `note`, `revision` | UNIQUE(`region_id`,`name`) |
| `template_keys` | `key_template_id`, `position`, `label`, `number`, `action`, `category`, `line`, `icon` | ≤2000 per template |
| `client_templates` | `region_id` NULL, `name`, `note`, `config_template_id`, `key_template_id` NULL | UNIQUE(`region_id`,`name`) |
| `clients` | `region_id`, `client_template_id`, `name`, `note`, `public_id` UNIQUE (10 chars base32, used in LDAP DN), `status` (`pending`/`active`), `enroll_token_hash` UNIQUE, `enroll_token_enc`, `enroll_expires_at`, `device_key_hash` UNIQUE NULL, `bound_at`, `install_id`, `ldap_generation` (int), `forward_always` NULL, `forward_busy` NULL, `forward_no_answer` NULL, `group_id` NULL (0011, `ON DELETE SET NULL`, INDEX), `revision` | NULL forward fields = template value; triggers refuse a group of another region (§2.6) |
| `client_groups` | `region_id` (`ON DELETE CASCADE`), `name` (≤40), `name_key` (case-folded name) | 0011; UNIQUE(`region_id`,`name_key`); ≤100 per region |
| `tags` | `name` (≤24, no `;` or `；`), `name_key` UNIQUE (case-folded), `color` (palette name, default `gray`) | 0011; ≤500 in total |
| `client_tags` | `client_id`, `tag_id` (PK both, both `ON DELETE CASCADE`) | 0011; WITHOUT ROWID; INDEX(`tag_id`,`client_id`); ≤10 per client; no timestamps |
| `client_reports` | `client_id` PK, `last_seen_at`, `last_ip`, `app_version`, `app_version_code`, `user_agent` (0008), `android_release`, `android_sdk`, `device_model`, `sent_version`, `applied_version`, `applied_at`, `apply_error`, `registrations_json` | overwritten on every sync |
| `client_credentials` | `client_id`, `profile_id` (PK both), `username`, `auth_user`, `password_enc`, `display_name`, `account_name` NULL | |
| `client_directory_passwords` | `client_id`, `directory_id` (PK both; `directory_id` = `template_directories.id`), `password_enc` | only for `password_mode=client` (of the external directory, or of a hosted book bound by hand); per use in a template, so one external directory used by two templates asks twice |
| `revoked_devices` | `device_key_hash` PK, `client_name`, `reason` (`deleted`/`unbound`), `revoked_at` | kept `SIPLINK_REVOKED_KEEP_DAYS` (default 365) so the phone gets 410 |
| `address_books` | `region_id` NULL, `slug` UNIQUE (`[a-z0-9-]{2,32}`), `name`, `note`, `allow_anonymous`, `entry_count`, `revision` | ≤500 rows in total |
| `book_entries` | `book_id`, `name`, `surname`, `given_name`, `phones` (newline-separated, ≤4), `mobiles` (newline-separated, ≤4), `organization`, `department`, `title`, `email`, `sort_key` | ≤10,000 per book; INDEX(`book_id`,`sort_key`) |
| `ldap_accounts` | `region_id` NULL, `name` UNIQUE (`[a-z0-9-]{2,32}`), `password_hash` (SHA-256 of a 32-char random secret), `note`, `disabled` | service accounts for other phones and tools |
| `ldap_account_books` | `account_id`, `book_id` (PK both) | |
| `audit_log` | `at`, `admin_id`, `admin_name`, `region_id` NULL, `action`, `object_type`, `object_id`, `object_name`, `detail_json`, `ip` | append-only; no `updated_at` |
| `meta` | `key` PK, `value` | schema version, install id, instance name, `two_step_required` |

Limits enforced in Go (inside the same transaction as the write) and, for the two LDAP limits,
also by a `CHECK`-style trigger as a second line:
- address books: 500 in total (`SIPLINK_MAX_BOOKS`, default 500);
- entries per book: 10,000 (`SIPLINK_MAX_BOOK_ENTRIES`, default 10,000);
- per configuration template: 100 account profiles (`AccountRules.MAX_ACCOUNTS`), 50
  directories (`LdapRules.MAX_DIRECTORIES`); per key template 2,000 keys
  (`ShortcutRules.MAX_SHORTCUTS`). These come from the app and must not be raised on the platform.
- No limit on regions, clients, templates; the target is 5,000 clients and stays tested at that
  size (§8).

Deleting: a region only when empty; a template only when nothing uses it; an address book only
when no template directory and no LDAP account refers to it, an external directory only when no
template directory refers to it (the UI lists the users). A client is deleted for real (its rows
go) and its device key moves to `revoked_devices`.

### 2.3 Ownership rule

Templates (all three kinds), address books, external directories and LDAP service accounts
belong to one region or are **global** (`region_id IS NULL`). Global objects are made and changed
by super administrators only; region administrators can see and **use** them (pick them in their
own templates and clients) but not change or delete them. A client template may only combine a
configuration template and a key template that are global or of its own region; a configuration
template may only refer to address books and external directories that are global or of its own
region; a client may only use a client template that is global or of its own region. Moving an
object between regions is a super-administrator action and is refused when it would break one
of these rules (for a directory: a template of another region, or a global one, uses it).

### 2.4 Permission matrix

"Own" = objects whose region is one of the administrator's regions. "Global" = region NULL.

| Action | Super admin | Region admin |
|---|---|---|
| Regions: list | all | own only |
| Regions: create / rename / delete | yes | no |
| Administrators: list / create / disable / reset password / assign regions | yes | no (sees only own profile) |
| Own password change | yes | yes |
| Own e-mail address and two-step sign-in (§6.8) | yes | yes |
| Reset another administrator's two-step sign-in | region administrators only (super ones by CLI) | no |
| Mail settings, two-step policy | yes | no |
| Clients: list / view / create / edit / delete / regenerate QR / unbind / bulk import / print QR | all regions | own regions |
| Client groups: list / create / rename / delete / put clients in them | all regions | own regions |
| Tags: list / create / put on and take off clients (that one may edit) | yes | yes |
| Tags: rename / recolour / delete | yes | only when no client outside own regions carries the tag |
| Client: move to another region | yes | only between own regions |
| Client: view stored passwords (SIP, directory) | yes (re-enter own password first) | own regions (re-enter own password first) |
| Configuration / key / client templates: view | all | own + global |
| … create / edit / delete | all (incl. global) | own only |
| … use (reference) | all | own + global |
| Directories (通讯录, internal and external): list, view entries, export CSV, view an external one's connection | all | own + global |
| … create / edit (entries, import, connection, stored password) / delete | all | own only |
| … use in a configuration template | all | own + global (of the template's region) |
| LDAP service accounts | all | own regions; may grant only own + global books |
| Audit log | all | entries whose `region_id` is one of own regions, plus own actions |
| System page (backup, instance settings) | yes | no |

The last super administrator cannot be disabled, demoted or deleted. An administrator cannot
change their own role.

### 2.5 External directories moved out of templates (migrations 0008, 0009; 2026-11-18)

Up to schema 7 an external LDAP server was typed into each configuration template. Migration
0008 turns every such row into an `external_directories` entry and points the row at it:

- region = the template's region; the entry's name = the row's name, made unique within the
  region by a suffix `（2）`, `（3）` … (cut so the whole stays ≤40 characters) when another
  entry of the region already has it; the row keeps its own `name`, so the phone sees the same
  name as before.
- Rows of the same region whose connection is the same in every field — host, port, security,
  bind DN, password mode, the password itself (compared after opening), base DN, maximum
  results, attributes and filters — share one entry; any difference makes its own entry.
- The row's `password_mode` and `password_enc` become `none` / NULL; `max_results` stays but no
  longer counts for external rows. `client_directory_passwords` refer to the row, whose id does
  not change, so per-client passwords keep working untouched, and so does the document key
  `"d" + id`.
- A sealed password's associated data names its row (§6.1), and the row changes from
  `template_directories` to `external_directories`. A plain SQL migration does not have the key,
  so 0008 has a Go step that runs in the same transaction as its SQL (`store.Open` takes the
  secret box for it): it opens each password with the old associated data and seals it again
  with the new one. A database that holds such passwords cannot be migrated without its key
  file; the migration then fails as a whole and the database stays at schema 7. No code reads
  a password under the old associated data afterwards.
- Migration 0009 drops the connection columns from `template_directories`.

The document a phone gets is the same before and after (same fields, keys and values, so the same
`version`); the template revisions are not bumped by the migration.

### 2.6 Client groups and tags (migration 0011; 2026-11-20)

Both are for managing clients in the console only: the provisioning document, the LDAP service
and the client's `revision` do not change when they do (so a phone's sync stays 204).

- A **group** (分组) belongs to one region; a client is in at most one group, of its own region.
  Names (≤40) are unique per region ignoring case in any script (`name_key` = Unicode case
  folding, `golang.org/x/text/cases`), with the name rules of §5.5 (no control or bidi
  characters). At most 100 groups per region. Two triggers (`clients_group_region_insert`,
  `clients_group_region_update`) refuse a client whose group is of another region, as a second
  line behind the handlers. Moving a client to another region: the client form of the new region
  offers that region's groups only, so the client is saved without a group; a request that keeps
  the old group is refused. Deleting a group leaves its clients without one (the confirmation
  names how many); deleting a region (only when empty of clients) deletes its groups.
- A **tag** (标签) is one shared list for all regions: names ≤24, unique ignoring case, without
  `;` or `；` (they separate tags in the CSV); a colour from a fixed palette of muted tints that
  read in light and dark mode (`gray` default, `sand`, `clay`, `olive`, `sage`, `teal`, `steel`;
  stylesheet `.chip.c-*`). At most 500 tags and 10 per client.
- Permissions (§2.4): region administrators manage the groups of their regions only (another
  region's group answers 404 and is not listed or accepted anywhere); everyone creates tags and
  puts them on clients they may edit; a region administrator may rename, recolour or delete a tag
  only when no client outside their regions carries it (the page shows its buttons disabled; the
  store checks it again inside the write's transaction, `ErrInUse`).
- The client list filters by group (one, or 无分组) and by tags (every chosen tag), in the URL
  (`group=ID|none`, repeated `tag=ID`); unknown or invisible ids are ignored. The bulk bar sets a
  group (one of the clients' region, or none), adds a tag or removes one. A bulk request is
  refused whole, with nothing applied, when any selected id is not a client the administrator
  may edit, the group is not theirs or not of every selected client's region, the tag does not
  exist, or a client would carry more than 10 tags.
- Audit: `group.create`/`update`/`delete` and `tag.create`/`update`/`delete` (the delete entries
  carry the number of clients affected); a change of a client's group or tags — in the form, by
  CSV import (`client.create` with `group` and `tags`) or in bulk — is a `client.update` entry
  with the fields `group` / `tags` (old and new names), **one entry per client whose value
  actually changed**, like the bulk change of template. The removal of a group or tag from its
  clients by deleting it is recorded only in the delete entry.

---

## 3. Provisioning protocol and document

### 3.1 URLs and the QR code

| Purpose | Method and path | Auth |
|---|---|---|
| Enrollment (first fetch) | `POST /p/{enrollToken}` | the token in the path |
| Info page for a browser | `GET /p/{enrollToken}` | none; returns a static HTML page "请在 SipLink → 设置 → 远程配置 中扫描此二维码", identical for valid and invalid tokens, never any client data |
| Sync (every later fetch) | `POST /api/device/v1/sync` | `Authorization: Bearer {deviceKey}` |
| Leave (user deletes the remote configuration on the phone) | `POST /api/device/v1/leave` | `Authorization: Bearer {deviceKey}` |

- `enrollToken`: 32 random bytes, base64url without padding (43 characters). Stored as
  SHA-256 for lookup and AES-GCM-encrypted so that the QR can be shown again.
- QR content: exactly the enrollment URL. It is `{SIPLINK_PUBLIC_URL}/p/{enrollToken}?pin={pin}`
  exactly when the HTTPS listener presents a chain that ends in a private root: a certificate of
  the built-in CA, or an operator's certificate (uploaded or files) whose chain ends in a
  self-signed certificate that the system root store does not know (§6.7). `pin` = base64url
  without padding of SHA-256 over the DER SubjectPublicKeyInfo of that **root certificate** (43
  characters), so it stays the same when the server certificate is renewed under the same root.
  With a publicly trusted operator chain or with `SIPLINK_TLS=off` the URL is
  `{SIPLINK_PUBLIC_URL}/p/{enrollToken}` without `pin` (the phone then relies on Android's system
  CAs). The URL is built when the page is shown, so a change of certificate changes the QR codes
  on screen; printed ones keep the old pin (§6.7 warns). See §3.7. A plain https URL keeps the QR scannable by any reader; typing it by hand is
  possible but not expected.
- Error correction level M, rendered as SVG for screen and print, PNG for download (§5.4).

### 3.2 Enrollment (binding a phone)

Request `POST /p/{enrollToken}`, `Content-Type: application/json`:

```json
{ "installId": "uuid made once by the app", "device": { …report, §3.4… } }
```

Responses:
- `200` + provisioning document (§3.5) whose top level additionally carries
  `"deviceKey": "<43 chars>"`. The platform stores SHA-256(deviceKey), sets `status=active`,
  `bound_at`, `install_id`, and **disables the enrollment token** (`enroll_token_hash` cleared):
  the QR is single-use. Later fetches use only the device key.
- `404 {"error":"invalid_token"}` unknown token, already used, or expired
  (`enroll_expires_at`, default 30 days after it was made; `SIPLINK_ENROLL_DAYS`).
- `429` rate limited (§6.5). `503` maintenance/database busy.

To move a client to another phone the administrator presses "重新生成二维码" (unbind): the
current device key goes to `revoked_devices` with reason `unbound`, a new enrollment token is
made. One phone per client.

### 3.3 Sync

Request `POST /api/device/v1/sync`, headers `Authorization: Bearer {deviceKey}`,
`Content-Type: application/json`, body:

```json
{
  "have": "<version of the document the phone has applied, or empty>",
  "device": { …report, §3.4… }
}
```

Responses:
- `200` + document: the computed version differs from `have`.
- `204` (no body): unchanged. Cheap path: the server computes the version from revisions
  (§3.5.1) and compares, without building the document.
- `401 {"error":"unauthorized"}` unknown key that is not in `revoked_devices` (e.g. corrupted
  storage). The phone keeps its configuration, shows "远程配置认证失败", retries later.
- `410 {"error":"revoked","reason":"deleted"|"unbound"}`: this phone is no longer the client.
  The phone removes the managed configuration (§9, default of open question Q1).
- `429` with `Retry-After`; `5xx`: the phone keeps everything and retries with backoff.

Refresh: the phone syncs at app start, when the network comes back, when the user presses
"立即同步", and every `refreshMinutes` (from the document; default 60, allowed 5–1440) while the
app or its service runs. On failure it retries after 1, 2, 4 … up to `refreshMinutes` minutes.
A missed refresh never unlocks anything.

`POST /api/device/v1/leave` (same auth): the user removed the remote configuration on the
phone. The platform moves the key to `revoked_devices` (reason `unbound`), sets the client back
to `pending` with a fresh enrollment token, and writes an audit entry (actor "device").

### 3.4 What the phone reports about itself

Sent in every enrollment and sync request (`device` object). Shown on the client page; never
used for authorization.

| Field | App source |
|---|---|
| `appVersion`, `appVersionCode` | `BuildConfig.VERSION_NAME` / `VERSION_CODE` (`android/app/build.gradle.kts`: `versionName`, `versionCode`) |
| `androidRelease`, `androidSdk` | `android.os.Build.VERSION.RELEASE`, `SDK_INT` |
| `model` | `Build.MANUFACTURER + " " + Build.MODEL` |
| `applied` | `{ "version": "...", "at": "RFC3339", "error": "" }` — the last document the loader tried, and the reason it rejected it (validation messages are the app's own `…Error.full` texts) |
| `registrations` | `[{ "account": "<account key>", "state": "OK" }]` — `RegState` names from `core/model/Models.kt` (`DISABLED`, `NONE`, `PROGRESS`, `OK`, `FAILED`) plus `error` (`SipAccount.error`) |
| `codecs` | `{ "audio": ["opus/48000/2", …], "video": [...] }` — `Codec.key` of `CodecManager.codecs` / `videoCodecs`; the platform keeps the union per app version to offer in the codec editor |

No phone number, contacts, call history or location is sent. Besides the `device` object the
platform keeps the `User-Agent` header of the request (`client_reports.user_agent`, at most 200
characters, 0008). The client list shows `appVersion`; the client page shows `appVersion` with
`appVersionCode` next to it and the User-Agent, so that a later need can tell versions apart.

### 3.5 The provisioning document

`Content-Type: application/json; charset=utf-8`, `Cache-Control: no-store`. Unknown fields are
ignored by the app; a missing field means the app's default (the value in `Settings()` /
`FeatureCodes()` / `LdapDirectory()` / `AccountDraft()`), **not** the phone's current value — the
document always describes the whole handed-out configuration.

#### 3.5.1 Version

`version` = lowercase hex SHA-256 of the canonical JSON of the document without `version`,
`generatedAt` and `deviceKey` (keys sorted, no whitespace). For the cheap 204 path the server
first compares a "revision key" (template revisions + client revision + book revisions of the
hosted books it refers to + `ldap_generation` + server settings that appear in the document); the
full hash is computed only when that key changed since the last document sent to this client
(`client_reports.sent_version` plus a cached revision key).

Versions of the app: the platform sends one document to every app version and has no
per-version branch anywhere. The app ignores fields it does not know and takes its own default
for a field that is missing, so a new field reaches new versions and older ones go on without
it. The version each phone reports is stored (§3.4) for a later need, never used to shape the
document.

#### 3.5.2 Top level

| Field | Type | Becomes in the app |
|---|---|---|
| `schema` | int, `1` | loader refuses documents with a higher major schema ("请升级 SipLink") |
| `version` | string | stored by the loader, sent back as `have` |
| `generatedAt` | RFC 3339 | shown as "上次更新" |
| `deviceKey` | string, enrollment only | stored by the loader in private storage (§9) |
| `platform` | `{ "name": "...", "syncUrl": "https://…/api/device/v1/sync" }` | shown on the remote-configuration screen; `syncUrl` lets the server name move |
| `client` | `{ "name": "...", "region": "..." }` | shown on the remote-configuration screen |
| `refreshMinutes` | int 5–1440 | loader's poll interval |
| `configPasswordHash` | string, 64 hex or `""` | `ConfigLock.setManaged(true, passwordHash)` (`core/ConfigLock.kt`); checked with `ManagedConfig.isPassword` (`core/logic/ManagedConfig.kt`) |
| `settings` | object, §3.5.3 | `SettingsStore` via `SipEngine.updateSettings` inside `ConfigLock.fromRemote { }` |
| `accounts` | array, §3.5.4 | `AccountManager.add` / `update` / `remove` inside `fromRemote` |
| `defaultAccount` | account key or `""` | `AccountManager.setDefault(id)` |
| `codecs` | object or absent, §3.5.6 | `CodecManager` (new apply function, §9) |
| `caCertificates` | array of PEM strings, the platform's CA first (below) | `TlsManager` (new replace function, §9) |
| `directories` | array, §3.5.5 | `DirectoryManager` (new replace function, §9) |
| `shortcuts` | array, §3.5.7 | `ShortcutManager` (new replace-handed-out function keeping `Shortcut.own` keys, §9) |

`caCertificates` (milestone P8) starts with the platform's own CAs (§6.7): the private root of
the HTTPS listener's chain, then that of the LDAP service's chain — the built-in CA when it
issued that listener's certificate, the self-signed root at the end of an operator's chain when
no public CA vouches for it, nothing for a publicly trusted chain; the template's own CAs follow;
identical certificates appear once. The template's 安全 tab shows them as fixed first rows
"平台 CA（自动下发）" that cannot be removed.

#### 3.5.3 `settings`

Field names are the names of `data class Settings` in `core/model/Models.kt`, so the loader can
map them one to one. Only the locked fields are sent; the personal ones of
`ManagedConfig.localUpdate` (`doNotDisturb`, `themeMode`, `dynamicColor`,
`callBannerPosition`, `lockScreenPromptDeclined`) are never sent, and the loader keeps the
phone's values for them.

| JSON field | Type / allowed | `Settings` field | Platform UI (配置模板) |
|---|---|---|---|
| `keepAlive` | bool | `keepAlive` | 接听与保活 → 保活 → 后台保活 |
| `intercomAutoAnswer` | bool | `intercomAutoAnswer` | 接听与保活 → 对讲 → 对讲自动应答 |
| `intercomSpeaker` | bool | `intercomSpeaker` | 接听与保活 → 对讲 → 对讲使用免提 |
| `verifyTlsCertificate` | bool | `verifyTlsCertificate` | 安全 |
| `verifyTlsHostname` | bool | `verifyTlsHostname` | 安全 |
| `mediaSecurity` | `NONE` / `SRTP_OPTIONAL` / `SRTP_MANDATORY` | `mediaSecurity` (`enum MediaSecurity`) | 安全 |
| `dndMode` | `SOFTWARE` / `SERVER` | `dndMode` (`enum DndMode`) | 呼叫处理 → 免打扰方式 |
| `callWaiting` | bool | `callWaiting` | 呼叫处理 |
| `rejectAnonymous` | bool | `rejectAnonymous` | 呼叫处理 |
| `dtmfMode` | `RFC2833` / `SIP_INFO` | `dtmfMode` (`enum DtmfMode`) | 呼叫处理 |
| `forwardAlways` | string, `""` = off | `forwardAlways` | 呼叫处理; per-client override possible |
| `forwardBusy` | string | `forwardBusy` | idem |
| `forwardNoAnswer` | string | `forwardNoAnswer` | idem |
| `forwardNoAnswerSeconds` | int 5–120 (`IncomingPolicy.MIN_NO_ANSWER_SECONDS`..`MAX_NO_ANSWER_SECONDS`) | `forwardNoAnswerSeconds` | 呼叫处理 |
| `features` | object, below | `features` (`data class FeatureCodes`) | 功能码 |
| `videoCalls` | bool | `videoCalls` | 视频 |
| `videoSize` | `QVGA` / `VGA` / `HD` / `FHD` | `videoSize` (`enum VideoSize`) | 视频 |
| `videoFrameRate` | 0, 15, 24, 30 (`VideoRules.FRAME_RATES`) | `videoFrameRate` | 视频 |
| `autoUpdateCheck` | bool | `autoUpdateCheck` | 通用 → 自动检查更新 |
| `userAgent` | string, `""` = the app's own name; ≤120 printable ASCII characters (0x20–0x7E), no leading or trailing space | `userAgent` (to be added to `Settings`; SIP User-Agent) | 高级设置 → 客户端名称（User-Agent） |
| `adaptiveRateControl` | bool, default `false` | `adaptiveRateControl` (to be added) | 高级设置 → 自适应速率控制 |
| `echoCancellation` | bool, default `true` | `echoCancellation` (to be added) | 高级设置 → 回声消除 |
| `ipv6` | bool, default `true` | `ipv6` (to be added) | 高级设置 → 允许 IPv6 |
| `callRecording` | bool, default `true` | `callRecording` (to be added) | 音视频 → 录音 → 通话录音 |
| `recordByDefault` | bool, default `false`; always `false` while `callRecording` is `false` | `recordByDefault` (to be added) | 音视频 → 录音 → 默认录音 |
| `autoAnswer` | bool, default `false` | `autoAnswer` (to be added) | 接听与保活 → 常规呼叫 → 自动接听 |
| `autoAnswerSeconds` | int, default `3`; one of 0, 3, 5, 10, 20, 30 (0 = at once) | `autoAnswerSeconds` (to be added) | 接听与保活 → 常规呼叫 → 自动接听时间 |
| `roomLimit` | int 2–30 (`RoomRules.MIN_LIMIT`..`LIMIT`), default `30`; **absent at the default** | `roomLimit` | 高级设置 → 会议室 → 会议室人数上限 |
| `autoNetwork` | bool, default `true`; **absent at the default** | `autoNetwork` | 高级设置 → 客户端与媒体 → 自动适应网络 |
| `stunServer` | string `host[:port]`, `""` = none; **absent when empty** | `stunServer` | 高级设置 → 客户端与媒体 → STUN 服务器 |

The last four fields (added 2026-11-16) are options of the SIP library that the app's former
"呼叫处理" page shows under "高级设置"; the platform's template tab of the same name carries them.
Templates stored before they existed read absent values as the defaults above, and every document
carries all four.

`callRecording` and `recordByDefault` (added 2026-11-17, names agreed with the app) say whether the
user may record calls (off: the app shows no record button) and whether every call is recorded
from the moment it is connected. They are set on the template's 音视频 tab; 默认录音 is disabled
and saved as `false` while 通话录音 is off. Templates stored before them read the defaults
(`true`, `false`), and every document carries both.

`autoAnswer` and `autoAnswerSeconds` (added 2026-11-17, names agreed with the app) make a regular
incoming call answer itself after the chosen wait; they are apart from `intercomAutoAnswer`. They
are set in the 常规呼叫 card of the template's 接听与保活 tab (formerly 对讲与保活); 自动接听时间 is
disabled while 自动接听 is off and keeps its stored value. A wait other than the six allowed ones
is refused on input ("自动接听时间无效"); a stored one is read as the nearest allowed value (of
two equally near, the longer; `rules.AutoAnswerDelay`). Templates stored before them read the
defaults (`false`, `3`), and every document carries both.

`roomLimit`, `autoNetwork` and `stunServer` (added 2026-11-20) are the size of the app's
conference room (members besides the user), whether the lines adapt to the network by themselves,
and the STUN server the app's line checks ask and its lines' ICE uses (a line whose sound went one
way tries ICE, only with a STUN server set). Unlike every field before them, each is **left out
of the document while it keeps the app's default** (`provision.Settings` holds the first two as
pointers and all three with `omitempty`; `SetRoomLimit`/`SetAutoNetwork` drop a default, and
`catalog.ParseSettings` drops a stored one): the document of a template that does not set them is
byte for byte what it was, with the same `version`, so no phone downloads it again, and the app
reads an absent field as its default. Only a template that sets a value carries the field
(golden case `internal/provision/testdata/room-network.json`).

- `roomLimit`: the app clamps a value outside 2–30 into that range (`RoomRules.limitOf`, used by
  `RemoteDocument`) instead of refusing the document. The platform still **refuses** a value
  outside 2–30 in the template editor and in `provision.SettingsProblems` ("会议室人数上限应为
  2–30"), so it never provisions one; the port of the clamp (`rules.RoomLimitOf`) only reads a
  stored value the way the app would.
- `stunServer`: the app and the platform take the same rule, that of an account's server address
  (`MediaAdapt.isValidStunServer` in the app, `rules.IsValidStunServer` here, both on
  `AccountRules.isValidHostPort` / `rules.IsValidHostPort`): empty for none, else a host name or
  IPv4 address, an IPv6 address in brackets, an optional port 1–65535 (3478 when none is given),
  no whitespace. The platform trims the value on input; the app trims it before use, and
  `RemoteDocument` **refuses** a document whose value breaks the rule
  ("settings.stunServer 格式不正确"), as it does other invalid fields.
- The three are on the template's 高级设置 tab like the app's own screen: 会议室人数上限 in a card
  会议室 after 呼叫转移 (the app's 会议室 section), 自动适应网络 and STUN 服务器 in 客户端与媒体
  (the app's 媒体与网络). The audit entry names them with their defaults when they go back to one.

`features` fields = `FeatureCodes` fields: `park`, `listen`, `whisper`, `barge`,
`forceHangUp`, `pickup`, `groupPickup`, `voicemail`, `intercom`, `dndOn`, `dndOff` (strings). An
empty string means "not in use" (the app greys the entry). The platform's defaults for a new
template are the app's defaults (`FeatureCodes()`), shown as placeholders.

#### 3.5.4 `accounts`

One element per account profile of the configuration template that the client has credentials
for (profiles without credentials are left out and flagged on the client page).

| JSON field | Comes from | `AccountDraft` field (`core/model/Models.kt`) |
|---|---|---|
| `key` | `"p" + profile id` (stable across edits) | — (loader maps key → local account id, §9) |
| `name` | client `account_name`, else profile `name` | `name` (≤30, unique case-insensitive: `AccountRules.MAX_NAME_LENGTH`, `isNameTaken`) |
| `username` | client | `username` |
| `password` | client (decrypted) | `password` |
| `authUser` | client | `authUser` |
| `displayName` | client | `displayName` |
| `domain` | profile | `domain` |
| `port` | profile, string, `""` = default | `port` (a `String` in `AccountDraft`) |
| `server` | profile | `server` |
| `backupServer` | profile | `backupServer` |
| `serverTimeoutMs` | profile `server_timeout_ms`, int ms; **absent when 0** (off) | `serverTimeoutMs` |
| `dnsServer` | profile | `dnsServer` |
| `transport` | profile, `UDP`/`TCP`/`TLS` | `transport` (`enum SipTransport`) |
| `expires` | profile, 60–86400 | `expires` (`AccountRules.MIN_EXPIRES`/`MAX_EXPIRES`) |
| `subscribeMwi` | profile | `subscribeMwi` |
| `enabled` | profile | `enabled` |

`defaultAccount` = key of the profile marked `is_default` (or the first one).

The platform validates every account with the rules of `AccountRules.normalize` /
`validate` (user name pattern, host[:port], port 1–65535, backup ≠ primary, DNS server
`IP[:port]`, expires range, duplicate `user@domain`), ported to Go in
`internal/rules/accounts.go`, with tests taken from `android/app/src/test/.../AccountRulesTest.kt`.

`serverTimeoutMs` (added 2026-11-20) is the fast server timeout of a line: when the account has a
backup server, a server that does not answer within this many milliseconds is taken as out of
reach and the account moves to the other server at once. 0 is off, the app's default, and is
**left out of the document** (`omitempty`), so the document of a line without it is byte for byte
what it was, with the same `version`; otherwise it is 200–10000 (`rules.MinServerTimeoutMs`,
`rules.MaxServerTimeoutMs`, `rules.IsValidServerTimeoutMs`). The platform refuses anything else in
the account form (`catalog.ProfileProblems`) and in `provision.Build` ("快速超时应为 200–10000
毫秒"); the check is the platform's own, outside the ported `ValidateAccount`. A timeout without
a backup server is **stored and sent**, not refused: as with 自动接听时间 while 自动接听 is off or
the STUN server while 自动适应网络 is off, a dependent value keeps its own state, and the app
uses it only with a backup server. In the console it is the number field 快速超时（毫秒） below
备用服务器 (empty = off, any whole number in range, so a stored value survives an edit). Golden
case `internal/provision/testdata/server-timeout.json`; databases before migration 0013 read 0.

#### 3.5.5 `directories`

Order = template order. Up to 50 (`LdapRules.MAX_DIRECTORIES`).

"Use" = the template's row (`template_directories`); "entry" = the external directory it refers
to (`external_directories`).

| JSON field | External directory | Hosted address book | `LdapDirectory` field |
|---|---|---|---|
| `key` | `"d" + id of the use` | same | — (loader maps key → local id) |
| `name` | use, default = entry name (≤40) | use, default = book name | `name` |
| `enabled` | use | use | `enabled` |
| `host` | entry | `SIPLINK_LDAP_PUBLIC_HOST` | `host` |
| `port` | entry | `SIPLINK_LDAP_PUBLIC_PORT` (default 389) | `port` |
| `security` | entry, `NONE` / `START_TLS`; the form starts with `START_TLS` | `START_TLS` by default (there is always a certificate, §4.8); see the note on literal addresses below | `security` (`enum LdapSecurity`) |
| `bindDn` | entry | `uid={client public_id},ou=clients,{suffix}` | `bindDn` |
| `password` | entry's `password_mode`: `template` → the entry's; `client` → the client's for this use; `none` → `""` | derived per client (§4.3) | `password` |
| `baseDn` | entry | `ou={book slug},ou=books,{suffix}` | `baseDn` |
| `line` | account key of the use's `line_profile_id`, or `""` | same | `line` (loader maps account key → local account id) |
| `lookupIncoming`, `lookupOutgoing` | use | use | same names |
| `maxResults` | entry, 1–1000 | use, 1–1000, default 50 | `maxResults` (`LdapRules.MAX_RESULTS`) |
| `nameAttributes` | entry, default `cn sn` | `cn` | `nameAttributes` |
| `numberAttributes` | entry, default `telephoneNumber mobile` | `telephoneNumber mobile` | `numberAttributes` |
| `nameFilter`, `numberFilter` | entry, `""` = made by the app; must contain `%` | `""` | same names |

Validation mirrors `LdapRules.validate`: no `ldaps://` (`HOST_LDAPS`), StartTLS needs a host
name, not an address (`STARTTLS_NEEDS_HOST_NAME`), password needs a bind DN, base DN required,
filters contain `%`. The connection is checked when the entry is saved (its own name ≤40 and
required); the names shown on the phone are checked unique case-insensitive within the template
when a use is saved. The platform refuses to save a hosted directory with `START_TLS` while
`SIPLINK_LDAP_PUBLIC_HOST` is an IP address and says why. A change of an entry bumps the
revision of every configuration template that uses it, so phones get it at their next sync.

Adding a directory to a template = choosing one of the address books and external directories
that are global or of the template's region (and that the administrator may use, §2.4). The
choice is made once: editing a use changes only its own fields; to point at another directory
the use is removed and another added.

Hosted directories and literal addresses: the app today refuses StartTLS to a literal address
(`LdapRules.Error.STARTTLS_NEEDS_HOST_NAME`: its LDAP library wants a host name). The briefing
session tests on a phone whether that can be lifted for certificates of the built-in CA and
decides before P6. Until then the rule is ported in P5 as it is in the app, and the choice of a
hosted directory's security is kept in one function. External directories in a configuration
template: the form starts with `START_TLS` (P6).

The LDAP bind password of an **external** directory (set on its entry):
- `password_mode=template` (统一设置): stored (encrypted) with the entry, same for all clients of
  every template that uses it; write-only on the page ("已设置，留空保持不变"), never shown, never
  in the audit log (only "已修改");
- `password_mode=client` (按客户端设置, when the bind DN is per person): entered per client and
  per use on the client page or in the client CSV import; a client without it gets the
  directory with an empty password and a warning on the client page;
- `password_mode=none`: anonymous bind (bind DN empty).
Hosted books never need a password from anyone: each client gets its own derived LDAP
credentials automatically (§4.3).

Hosted books bound by hand (2026-11-16): the directory form offers "凭据": "自动（每个客户端独立，推荐）"
(the derivation above, the default; `ldap_account_id` NULL) or "手动指定": one LDAP service account
(global or of the template's region, enabled, granted that book — checked when saving, with the
reason shown) and its password, `password_mode` `template` (sealed in the template) or `client`
(typed per client). Typed passwords are checked against the account's stored SHA-256. In the
document such an entry keeps all fields; `bindDn` = `cn={account},ou=accounts,{suffix}` and
`password` come from the account instead of the derivation. If the account is later disabled,
loses the grant or gets a new password, the client's document stops validating and the client
page says why. An account in use by a template cannot be deleted.

#### 3.5.6 `codecs`

```json
"codecs": { "audio": [ {"key": "opus/48000/2", "enabled": true}, … ], "video": [ … ] }
```

`key` = `Codec.key` (`"$mime/$clockRate/$channels"`, `core/model/Models.kt`). Order = order of
preference. Codecs on the phone that the list does not name keep their relative order behind
the named ones and are switched off; named codecs the phone does not have are ignored; if this
would leave no audio codec on, the loader keeps the SDK default selection and reports an error.
`codecs` absent = "SDK defaults" (`CodecManager.restoreDefaults()`). The codec editor offers the
keys reported by phones (§3.4); the initial list must be captured from a real device running the
current app, not typed from memory.

#### 3.5.7 `shortcuts`

| JSON field | `Shortcut` field (`core/model/Shortcut.kt`) |
|---|---|
| `label` (≤24) | `label` |
| `number` (≤128) | `number` |
| `action`: `speed`/`bxfer`/`axfer`/`dtmf`/`forward`/`dnd` (`ShortcutRules.typeWord`) | `action` (`enum ShortcutAction`) |
| `category` (≤16, not `全部`/`未分类`/`新建分类`) | `category` |
| `line`: account name (as the CSV format: account name or `user@domain`) or `""` | `line` |
| `icon`: lowercase `ShortcutIcon` name or `""` (`ShortcutRules.iconWords`) | `icon` |

The words are those of the app's CSV format (`ShortcutRules.FORMAT_HINT`
`名称,号码[,类型[,分类[,线路[,图标]]]]`), so a key template can be imported from and exported to
the same CSV the app reads; the platform reuses the rules of `ShortcutRules.normalize` /
`validate`. `own` is never sent: handed-out keys are never the user's own. `line` must name one
of the template's account profiles (checked when the client template is saved). A profile
rename is followed in the keys of the key templates paired with the configuration template
only where the administrator may edit the key template (one `key_template.update` audit entry
each); in a key template they may not edit, such as a global one paired by a region
administrator, the keys keep the old name until someone who may edit it changes them.

#### 3.5.8 Merge order

1. Configuration template: settings, codecs, CAs, config password hash, refresh interval,
   account profiles, directory uses with the external directories they refer to.
2. Client values: credentials per profile (+ optional account name); forwarding targets that are
   not NULL replace the template's; directory passwords of `password_mode=client`.
3. Derived values: hosted directories (host, port, security, bind DN, password, base DN).
4. Key template: shortcuts (none when the client template has no key template).
5. Validation of the whole result with the ported app rules; a client whose document does not
   validate is shown in red with the messages, and a sync returns the last valid document the
   platform built for it (kept in memory and in `client_reports.sent_version`) — never a broken one.

### 3.6 What the phone keeps personal

As decided by the owner and as coded in `ManagedConfig.localUpdate`: the do-not-disturb switch
(`doNotDisturb`), theme (`themeMode`, `dynamicColor`), `callBannerPosition`,
`lockScreenPromptDeclined`, the account picked on the dial pad (that is `selectedId` in
`DialerScreen`, UI state only, not the default account), call history, and up to 50 own
shortcut keys (`ShortcutRules.MAX_OWN`, `Shortcut.own`).

### 3.7 Transport security of provisioning

The document contains SIP and LDAP passwords in clear, so:
- The platform serves provisioning only over HTTPS. `SIPLINK_TLS=auto` (default): the HTTP
  listener speaks HTTPS with the operator's files (`SIPLINK_TLS_CERT_FILE`,
  `SIPLINK_TLS_KEY_FILE`) when they are set, else with the certificate a super administrator
  uploaded on the 系统 page when it names the public host, otherwise with a certificate of the
  built-in CA (§6.7); nobody has to supply certificates. `SIPLINK_TLS=off`: plain HTTP, meant for a
  reverse proxy that terminates TLS (`SIPLINK_TRUSTED_PROXIES` names it; then
  `X-Forwarded-Proto: https` is required); without such a proxy provisioning and logins are
  refused (`403`) unless `SIPLINK_ALLOW_HTTP_PROVISIONING=true` (LAN tests). The admin UI follows
  the same rule (session cookies are `Secure`).
- The app accepts `https://` URLs; `http://` only in debug builds. Trust on the first scan: the
  QR URL carries `pin=` (the private root's SPKI hash, §3.1) when the HTTPS listener presents a
  chain that ends in a private root. The app's side (`core/logic/CertificatePin.kt`,
  `core/RemoteConfigClient.kt`, read 2026-11-16): at the scan it accepts a chain *as the server
  sent it* that contains a certificate whose SPKI hash equals the pin — any position, the leaf
  included — checks the validity of every certificate up to that one, that each is issued (issuer
  name, `basicConstraints` CA, signature) by the next one, that the leaf's extended key usage is
  absent or lists `serverAuth`, and that the leaf names the host or address in its SANs; it keeps
  that certificate and from then on trusts Android's system CAs plus that certificate for every
  sync (`Trust.Kept`; user-installed CAs are not trusted). The kept certificate is never replaced
  by a later document. Without `pin` (public chain or `SIPLINK_TLS=off`) the phone relies on
  Android's system CAs (`Trust.System`). This bootstraps a private CA without asking users to
  install it.
- HSTS on the admin UI when TLS is on.
- The HTTPS listener offers HTTP/2 by ALPN (`h2`, then `http/1.1`); with `SIPLINK_TLS=off` the
  platform speaks HTTP/1.1 to its proxy.
- Compression (gzip, standard library): pages of the admin UI and the text files under
  `/static` (CSS, JS, SVG; not the font) when the browser accepts gzip, with
  `Vary: Accept-Encoding`; a static file's compressed form has its own ETag (`"<hash>-gzip"`).
  Never compressed, because the length of a compressed page that holds a secret and text an
  attacker chose tells whether the two share bytes (BREACH): pages that show a one-time or LDAP
  account password, the TOTP secret, recovery codes, an enrollment link or QR code (client page
  while pending, print page), revealed stored passwords or the document preview, and every
  answer of the device API.
- The enrollment token is single-use and expires; it appears in reverse-proxy access logs only
  until the first scan. Device keys travel in a header, which proxies do not log by default.

---

## 4. LDAP service

### 4.1 What the phone actually sends

Read from the app (`DirectoryManager.pointServerAt`, `LdapRules.coreFilter`) and from the
Linphone SDK source (`src/ldap/ldap-contact-search.cpp`, `ldap-contact-provider.cpp` of
liblinphone on the official GitHub mirror, master; the filter code is identical in tag 5.4.0;
the exact 5.5.39 tag could not be fetched from gitlab.linphone.org from this host — to be
re-checked against a packet capture in milestone P4):

- Connection: `ldap://host:port`; optional StartTLS extended operation (`ldap_start_tls`,
  OID `1.3.6.1.4.1.1466.20037`). The app refuses `ldaps://` (`LdapRules.Error.HOST_LDAPS`).
- Bind: simple bind (`ldap_sasl_bind` with no mechanism = simple), DN + password, or anonymous
  (empty DN, empty password). LDAPv3.
- Search: base = `baseDn`, scope **subtree**, attributes **all** (NULL list), `typesOnly` false,
  size limit = `maxResults + 1`, time limit = 5 s (`TIMEOUT_SEC`), no controls.
- Filter: the directory's filter with `%s` replaced by the typed text; the SDK escapes
  `\ * ( ) /` as `\5c \2a \28 \29 \2f`, turns spaces into `*`, collapses `**` to `*`, and wraps
  the whole in parentheses if needed. With the app's defaults this gives
  `(|(cn=*{text}*)(sn=*{text}*))` for names and `(|(telephoneNumber=*{digits}*)(mobile=*{digits}*))`
  for numbers (`LdapRules.filterOver`); name lookups for calls try the full number, then its last
  8 digits (`NameLookup.queries`).
- Result use: attribute names are lowered; the name is taken from the first of
  `nameAttributes` the entry has (`a+b` joins several), numbers from `numberAttributes`. The
  SDK's LDAP provider does not fill a contact's organization, so `o`/`ou` are served for other
  clients but not shown by SipLink today.

### 4.2 DIT

Suffix `SIPLINK_LDAP_SUFFIX`, default `dc=siplink`.

```
dc=siplink
├── ou=books
│   └── ou={book slug}                       objectClass: organizationalUnit; description: book name
│       └── uid={entry id}                   objectClass: inetOrgPerson (+ person, organizationalPerson, top)
├── ou=clients                               bind identities of provisioned phones (not searchable)
│   └── uid={client public_id}
└── ou=accounts                              LDAP service accounts (not searchable)
    └── cn={account name}
```

Entry attributes (from `book_entries`): `uid`, `cn` (name), `sn` (surname, or name when empty —
`sn` is mandatory in `person`), `givenName`, `displayName` (= name), `telephoneNumber` (0–4
values: office / extension), `mobile` (0–4), `o`, `ou` (department), `title`, `mail`,
`objectClass`. Book slugs are ASCII so DNs stay simple for phones and administrators to type;
names may be Chinese.

Root DSE (`base ""`, scope base) answers `namingContexts`, `supportedLDAPVersion: 3`,
`supportedExtension` (always StartTLS, OID `1.3.6.1.4.1.1466.20037`: there is always a
certificate, §4.8), `vendorName`.

### 4.3 Bind model and access

| Bind | DN | Password | May read |
|---|---|---|---|
| Provisioned phone | `uid={public_id},ou=clients,{suffix}` | `base64url(HMAC-SHA256(ldapKey, public_id ‖ ldap_generation))[:32]` — derived, not stored | the hosted books its current document refers to |
| Service account | `cn={name},ou=accounts,{suffix}` | 32 random chars shown once; SHA-256 stored (high-entropy secret, so a fast hash is right and binds stay cheap) | the books granted to it |
| Anonymous | empty | empty | books with `allow_anonymous` (default off) |

`ldapKey` is a 32-byte key in the key file (§6.1). Rotating a client's LDAP password = bump
`ldap_generation` (button "重置 LDAP 凭据"), which also changes the document version so the phone
gets the new one at its next sync. A deleted or unbound client cannot bind any more (lookup by
public_id fails or the client is not `active`); an unbind (new QR code, or the phone leaving)
also bumps `ldap_generation`, so the old phone's password stays invalid once a new phone has
enrolled.

A search whose base is outside the permitted books returns `noSuchObject` (32) — not
`insufficientAccessRights` — so DNs of other books do not leak. A base of `ou=books` or the
suffix with scope subtree returns entries of all permitted books. Bind failures answer
`invalidCredentials` (49) after a constant delay.

### 4.4 Protocol coverage

Implemented operations: BindRequest (simple only; SASL → `authMethodNotSupported` 7),
UnbindRequest, SearchRequest, AbandonRequest, ExtendedRequest StartTLS. Everything else
(add, modify, delete, modDN, compare, other extended ops) → `unwillingToPerform` (53).
Critical controls → `unavailableCriticalExtension` (12); non-critical controls are ignored
(paged results is ignored; results are capped anyway).

Filters (all of RFC 4511): `and`, `or`, `not`, `equalityMatch`, `substrings`
(initial/any/final), `present`, `greaterOrEqual`, `lessOrEqual` (string order),
`approxMatch` (treated as equality). `extensibleMatch` → evaluates to Undefined (no match).
Matching rules per attribute:
- `cn`, `sn`, `givenName`, `displayName`, `o`, `ou`, `title`, `mail`, `uid`: caseIgnore — Unicode
  simple case folding, insignificant spaces collapsed;
- `telephoneNumber`, `mobile`: telephoneNumberMatch — spaces and `-` removed on both sides before
  comparing (so `138 0000 1001` is found by `*13800001001*`);
- `objectClass`: caseIgnore; unknown attributes: Undefined.
Attribute selection: empty list or `*` = all user attributes; `1.1` = none; named attributes
case-insensitive; `+` = none (no operational attributes).

Search scope `base`, `one`, `sub` are all honoured; size limit = min(request, 1000,
`SIPLINK_LDAP_MAX_RESULTS`); exceeding it sends the entries up to the limit then
`sizeLimitExceeded` (4), which the SDK reports as "limit reached". Results are in `sort_key`
order (the name, so lists look sorted on the phone).

### 4.5 Limits and robustness

- Max message size 256 KiB; max connections 2,000 in total and 64 per IP; idle timeout 5 min;
  per-operation deadline 5 s; one goroutine per connection, requests on one connection processed
  in order (the SDK sends one search at a time).
- Failed binds: 20 per IP per minute, then every bind from that IP is delayed 2 s and answered
  `invalidCredentials` until the window passes.
- `SIPLINK_LDAP_REQUIRE_TLS` (default `true`): before TLS is established on a connection only
  these are answered: the StartTLS extended request, unbind, an anonymous bind, and a base-scope
  search of the root DSE. A simple bind with a DN and every other search answer
  `confidentialityRequired` (13); the password of such a bind is not checked. With `false` the
  old behaviour: every operation is answered without TLS.
- Limits of the books themselves: 500 books, 10,000 entries per book (§2.2).

### 4.6 Performance approach

SQLite is the source of truth; searches run against an in-memory snapshot per book:
- Snapshot = slice of entries with pre-folded strings (case-folded names, digit-only numbers),
  built on first access from one indexed query (`book_id`, ordered by `sort_key`), tagged with the
  book's `revision`. A write bumps the revision; the next search builds a new snapshot and swaps
  it atomically (searches in flight keep the old one).
- Evaluation is a linear scan of the book with the compiled filter. A 10,000-entry book is a few
  hundred kilobytes to a few megabytes; a scan with substring tests takes well under a
  millisecond. Target, measured by a benchmark in P4: p99 < 5 ms for a substring search over a
  full book from cache, < 150 ms for a cold load.
- Cache bound `SIPLINK_LDAP_CACHE_ENTRIES` (default 1,000,000 entries ≈ a few hundred MB);
  least recently used books are dropped beyond it. 500 × 10,000 = 5,000,000 entries all hot would
  need more memory than a small server has; dropping cold books keeps memory bounded while hot
  books stay fast.
- Searches over several books (suffix base) scan each permitted book in turn and stop at the
  size limit.
- Why not SQL `LIKE`: substring matching with leading wildcard cannot use an index anyway, and
  the in-memory scan gives exact LDAP matching semantics (folding, phone-number rule) without a
  second implementation in SQL. SQLite FTS5 trigram is the fallback if measurements disappoint.

### 4.7 Import and export of people

CSV, one person per line, header line required (Chinese or English column names accepted):

```
姓名,办公电话,手机,单位,部门,职务,邮箱,姓,名
name,phone,mobile,organization,department,title,email,surname,given_name
```

- `姓名` required (≤64). `办公电话`/`手机`: several numbers separated by `;` (≤4 each, each ≤32,
  digits and `+*#-() ` only). Other columns optional, ≤64 (email ≤128).
- Encoding: UTF-8 (with or without BOM); if the file is not valid UTF-8 it is decoded as GB18030,
  because Excel on Chinese Windows saves CSV that way. 10 MB max.
- Modes: "追加" (append) or "替换全部" (replace, one transaction). Always a dry run first: the page
  shows how many lines are fine, the errors per line (line number + reason), and whether the
  10,000 limit would be exceeded; the administrator confirms; the import is atomic.
- Export: UTF-8 with BOM (opens correctly in Excel), same columns, numbers joined with `;`.
- Also LDIF export of a book (for moving it to another LDAP server). No LDIF import (CSV is what
  administrators have).

### 4.8 TLS

**StartTLS on one port (389 in front, 1389 inside the container)**, because the app only does
StartTLS (`LdapSecurity.START_TLS`) and refuses `ldaps://`. There is always a certificate, so
StartTLS is always offered (§4.2) and TLS is required by default (`SIPLINK_LDAP_REQUIRE_TLS`,
§4.5). The certificate is `SIPLINK_LDAP_TLS_CERT_FILE`/`_KEY_FILE`, else the HTTPS files when
those are set, else the uploaded certificate when it names `SIPLINK_LDAP_PUBLIC_HOST`, else a
certificate of the built-in CA (§6.7) — also with `SIPLINK_TLS=off`. The built-in CA's server
certificate carries `SIPLINK_LDAP_PUBLIC_HOST` among its names. The app's LDAP library needs a
host name for StartTLS (see §3.5.5 for literal addresses). Phones trust it through the CA list
of their document (`caCertificates`, which starts with the platform's CAs, §3.5.2, and which
`TlsManager` puts into the bundle the SDK uses for SIP and LDAP, after the SDK's own root
bundle; a publicly trusted chain is checked against those roots); a change of the LDAP
certificate reaches a phone's trust with its next sync, so StartTLS may fail until then; the SDK
loads CAs once per process (`DirectoryManager.tlsChangeNeedsRestart`), so the loader must report
"restart needed" when the CA list changes after a StartTLS search ran.

Optional `SIPLINK_LDAPS_LISTEN` (default empty = off): a second listener with implicit TLS, same
certificate and same handler, for desk phones that only know LDAPS. The SipLink app does not use
it.

---

## 5. Web UI

### 5.1 Navigation

Left sidebar (collapses to a top bar on narrow screens; on the right side in a right-to-left
language, §5.5), items shown by role:

概览 · 客户端 · 分组与标签 · 客户端模板 · 配置模板 · 功能键模板 · 通讯录 · LDAP 账号 · 区域 (super) ·
管理员 (super) · 邮件 (super) · 审计日志 · 系统 (super). Top right: region filter (for administrators with several
regions; "全部区域" for super), the administrator's name → 外观 / 语言 / 我的账号 / 退出.

Names of pages, fields and buttons in this document are the Simplified Chinese texts; the other
languages show their translations of them (§5.5).

### 5.2 Pages

| Page | What it does |
|---|---|
| 登录 `/login` | user name + password; rate-limited; then 两步验证 when the administrator has a method; after `must_change_password` goes to 我的账号; the language choice at its foot (§5.5) |
| 两步验证 `/login/step` | the administrator's methods (passkey, TOTP code, e-mail code, recovery code), §6.8; the language choice at its foot |
| 概览 `/` | counts (clients active / pending / 离线 = bound and not seen for more than 3× the refresh interval, the same rule as the 离线 status and filter of the client list), clients with an invalid configuration, apply errors or failed registrations, and offline clients (the first 10 of each by name; the rest as "另有 N 个", for offline clients a link to `/clients?status=offline`), for super administrators the expiry notice of an operator's certificate (§6.7), books near the limit, last 8 audit entries of visible regions |
| 客户端 `/clients` | table: name (with the client's tags as small chips), region, group, client template, status, app version, last seen, registration summary; one filter row: search by name or SIP user, template, group (全部分组 / 无分组 / a group, by region when several), tags (a picker; every chosen tag), status — all in the URL; region from the top bar; bulk actions: change template, set group, add tag, remove tag, print QR, delete (§2.6) |
| 分组与标签 `/groups`, `/tags` | two tabs. 分组: name, region, number of clients (a link to the filtered list); the top bar's region filter applies. 标签: the chip, number of clients in the administrator's regions; edit and delete disabled for a region administrator when clients outside their regions carry it. Create and edit in dialogs (`?new=1`, `?edit={id}` open them without JavaScript), delete with "确认删除…？" naming the clients affected |
| 新建客户端 `/clients/new` | region (first load: the region filter of the top bar, else the administrator's first region), client template (only those usable in the selected region; a template in the query that does not fit the region is dropped, so the template shown, the form below and its hidden fields always agree; the same for 编辑客户端), name, note, group (only groups of the selected region), tags (≤10), credentials for each account profile of the template, directory passwords where the template asks for them, optional forwarding overrides |
| 客户端详情 `/clients/{id}` | group (a link to the list filtered by it) and tags under the name; QR + URL (copy) while pending; device report (§3.4), registration states, applied version vs. current, apply error; buttons: 编辑, 重新生成二维码 (unbind), 重置 LDAP 凭据, 删除; "预览下发内容" shows the document with passwords masked |
| 批量导入客户端 `/clients/import` | CSV: `名称,区域,客户端模板,备注,分组,标签,{profile}用户名,{profile}密码,…` (分组 and 标签 optional, §5.5); template download per client template; dry run → confirm |
| 打印二维码 `/clients/print?ids=…` | print-only page: grid of cards (name, region, QR, short instruction), A4, no navigation |
| 客户端模板 | list; edit = name, region/global, configuration template, key template; shows how many clients use it |
| 配置模板 `/config-templates/{id}` | tabs: 账号 (profiles, order, default), 呼叫处理 (DND mode, call waiting, anonymous, DTMF, forwarding), 功能码, 接听与保活 (regular call auto answer and its wait, intercom, keep-alive), 音视频 (codecs order/on-off, video on, size, frame rate), 安全 (TLS verification, SRTP, CA certificates upload/list with subject and expiry; fixed first row "平台 CA（自动下发）" that cannot be removed, §3.5.2), 通讯录 (the directories used: one "添加通讯录" whose page first picks one of the usable directories, then the use's own fields; §3.5.5), 高级 (configuration password set/clear, refresh interval, update check); "已被 N 个客户端使用" with a confirmation when saving a template in use |
| 功能键模板 `/key-templates/{id}` | table editor (label, number, type, category, line, icon) in a list that scrolls inside its card (search as you type, which turns reordering off; height adjustable by a bar, remembered per browser) with reorder (drag a row by its grip, or arrow keys on the grip; one `POST …/keys/order` with every id in the new order, as for profiles and directories); CSV import/export in the app's format; line picker offers the profile names of the configuration templates that use it |
| 通讯录 `/books` | one list of internal and external directories: name, type (内部 · 外部), region, entries/10,000 or the server address, how many configuration templates use it; search (name, slug, server address) and type filter in one row, 50 per page; "新建通讯录" |
| 新建通讯录 `/books/new?kind=hosted\|external` | the type (内部 / 外部) chosen at the top, then the fields of that type; the type is fixed after creation |
| 通讯录详情 `/books/{id}` (internal) | entries table with search and paging (50 per page), add/edit/delete entry, import (dry run), export CSV/LDIF; "连接信息" box: host, port, base DN, StartTLS yes/no; 设置 shows the type as a fixed value |
| 外部通讯录 `/directories/{id}` | the entry's connection (§3.5.5) as a form (read-only for who may not edit it), the type as a fixed value, the stored password write-only; "使用情况": the templates that use it; delete only while unused |
| LDAP 账号 `/ldap-accounts` | service accounts: name, region, books granted, disabled; password shown once on create/reset |
| 区域 `/regions` | list, create, rename, delete (empty only), counts |
| 管理员 `/admins` | list, create (role, regions), disable, reset password (one-time password shown once) |
| 审计日志 `/audit` | filter by admin, region, object, date; detail shows changed field names, never secret values |
| 系统 `/system` | version, public URL and LDAP host as configured; card "安全" (the switches of §6.1 and §6.8); card "证书" (§6.7) with a switch 内置 CA · 自有证书 at its head (a GET form, `?cert=builtin\|own`; it opens on the source in use and marks each source in use with a dot, and the view shows "使用中"; looking at the other view changes nothing): the mode and the source of each listener in both views; 内置 CA: the CA's subject, validity and SHA-256 fingerprint (written the way the app shows fingerprints in its CA list; the form is read from the app's `CertificateTools`, not guessed), the names and expiry of the built-in server certificate, "下载 CA 证书" (PEM, for the administrators' browsers and for desk phones) and "重新签发服务器证书" unless issuing is paused; 自有证书: the operator's certificates (subject, issuer, names, expiry with days left, how phones trust it, which listeners use it), "到期前提醒天数", the upload (two file fields side by side, "上传" in the form's foot) and "改用内置 CA" while an uploaded certificate exists (all POST with CSRF and an audit entry); card "备份": the backups with "下载" each, which opens the download dialog (§6.6), "立即备份", database size |
| 我的账号 `/me` | own e-mail address (confirmed by code), own password, two-step sign-in (TOTP set-up `/me/totp`, passkeys, e-mail code, recovery codes shown once at `/me/recovery`), §6.8 |
| 邮件 `/mail` | SMTP settings and "发送测试邮件" (super), §6.8 |

The 系统 page's card "安全" carries the switches "区域管理员必须启用两步验证" (§6.8) and "密码强度检测"
(§6.1); an administrator's page (super)
carries the e-mail address and "重置两步验证" for region administrators; the 管理员 list shows
whether two-step sign-in is on.

### 5.3 Visual direction

- Neutral greys on white; dark mode by `prefers-color-scheme` with a soft black (#16171a-ish
  background, #1f2024 surfaces), not pure black. One restrained blue accent (e.g. #2f6fde in light,
  #6b9bf0 in dark) for primary buttons, links and focus rings only. No gradients, no purple.
- Danger actions are neutral buttons with red text plus a confirmation dialog — no large
  saturated red/green buttons. Status shown as small dots + text (grey/blue/amber/red at low
  saturation).
- Inter for Latin and Cyrillic, then a system font stack per language (§5.5: `"PingFang SC",
  "Microsoft YaHei"` for Simplified Chinese, the Traditional Chinese, Japanese and Arabic system
  fonts for those); 14 px base; 8 px spacing grid;
  1 px borders, 6 px radii, no shadows except dialogs; tables dense but readable.
- Plain `html/template` pages, one CSS file (~600 lines) and one JS file (~300 lines: confirm
  dialogs, copy buttons, row reordering, password reveal, tab state), embedded with `embed.FS`.
  Works without JS except reordering. No external fonts or CDNs (LAN deployments without
  internet).
- Choices between modes inside a form row are drop-downs (`<select>`); the segmented control is
  kept for compact choices at the right of a settings row, in card heads and for the theme.
- Hints of a field (format, limits, what an empty value means, "留空保持不变") are not
  placeholders: a "?" button after the field's name (outside its `<label>`) shows them as a
  tooltip on hover, focus and tap; the input refers to it with `aria-describedby`. Placeholders
  stay for example values, search prompts and stored states ("已设置", "已保存", "未设置").
- Spacing: a card body's padding is the only space at its edges (no first or last child adds a
  margin); inside a form every row, group and stacked grid row is 16 px apart, and a switch row
  among fields has 16 px on both sides of its line and ends at the body's padding.
- Entry animations fill backwards only, so no finished animation leaves a stacking context that
  would cover a popover. While a dialog or the narrow-screen drawer is open the page behind does
  not scroll (`html:has(dialog[open])`, `overflow: hidden`, the root keeps its scroll bar gutter);
  scrolling areas in overlays do not chain to the page and have thin scroll bars.

### 5.4 QR codes

Produced on the server by `github.com/skip2/go-qrcode` (§7): its bitmap is turned into an SVG
by our own few lines (crisp at any size, prints well); PNG download via the library's PNG
encoder. Level M. The page never puts the token into a URL other than the QR target itself (no
`?token=` image URLs: the QR image is inlined in the page).

### 5.5 Languages (added 2026-11-19)

Request of the owner (2026-11-19): "web和app加入几种主流语言(中文，日文，英文，阿拉伯文，法文，繁体字，俄文，西班牙文)".
Package `internal/i18n`, web file `internal/web/lang.go`, migration `0010_admin_language.sql`,
glossary `docs/GLOSSARY.md`.

**Languages.** Eight, each with a code (a BCP 47 tag: the catalog's directory, the value stored
and the page's `lang`) and named in itself, in this order in every picker: `zh-Hans` 中文（简体）,
`en` English, `ja` 日本語, `ar` العربية (right to left), `fr` Français, `zh-Hant` 中文（繁體）,
`ru` Русский, `es` Español. Simplified Chinese is the source: its catalog holds the console's
texts as they were before; the others translate it, with the terms of the glossary (one column
per language), so that a term reads the same everywhere. Standard technical terms (SIP, TLS,
LDAP, StartTLS/STARTTLS, SRTP, DTMF, CSV, LDIF, QR, CA, DNS, URL, PEM, TOTP, User-Agent) are not
translated.

**Catalogs.** `internal/i18n/locales/{code}/{area}.json`, embedded in the binary: flat JSON
objects, key → text, the same files and keys in every language. Keys are lower case,
dot-separated, named after where the text is used (`clients.title`, `rules.account.name_taken`).
Arguments are named, `{name}`; every translation of a key has the same arguments as the
Simplified Chinese text. A key that a catalog lacks falls back to English, then shows as
`⟦key⟧`; an argument the caller did not pass shows as `⟦name⟧`. The tests (§8.1) keep both
from happening.

**Translating.** `i18n.Tr` is one language. `Tr.T(key, name, value, …)` gives the text with its
arguments. Values: strings as they are (names, addresses and values people typed are never
translated), integers as plain ASCII digits, an `i18n.Msg` translated in the same language, a
`[]string` or `[]int` joined with the language's separator (`format.list_sep`), an
`i18n.Names` (what uses an object, a list that may grow long) as its first five names and how
many more there are (`format.list_more`: "a, b, c, d, e and 15 more"). Every string argument
loses the embedding, override and isolate characters it may carry (U+202A–U+202E,
U+2066–U+2069), in every language; in a right-to-left language it is then wrapped in U+2068
FSI … U+2069 PDI, so a name or an address in Latin letters keeps its order inside Arabic text
(also in flash messages, confirmations and mail, where no markup can do it) and cannot reorder
the text around it. New names (clients, regions, configuration, key and client templates,
account profiles, directories, internal directories, administrators' display names, client
groups, tags) may not
contain those characters (`names.bidi_control`); names stored before stay as they are. LDAP
account names already allow only `a-z`, `0-9` and `-`. A flash message is at most 500 bytes;
a longer one gives way to a short message of the same outcome (`flash.done`,
`flash.failed`).

**Messages from other packages.** Packages outside `internal/web` that produce text a person
reads (`rules`, `catalog`, `provision`, `auth`, `books`, `pki`, `mail`, the CLI) return
`i18n.Msg` (a key and its arguments) or `[]i18n.Msg` in place of Chinese strings, and errors
that carry one (`i18n.E(key, …)`, `i18n.Wrap(err, key, …)`); the web layer translates them in the
request's language (`errText`), the CLI in its own (below). An error's `Error()` is the English
text, for logs and for code that only wraps errors; a printed `Msg` shows `⟦key⟧`, so a message
that skipped translation is caught by the tests.

**Counts.** No plural forms: every text with a number is phrased so that the number does not
change the words — a label and the number ("客户端：15", "Clients: 15", "Клиенты: 15"), or the
number alone in a column, chip or field. Chinese and Japanese keep their sentences (they have
no grammatical number). Relative times in the browser ("3 minutes ago") come from the
browser's `Intl.RelativeTimeFormat`, which knows each language's plural rules.

**Which language.** For a request with a complete session: the administrator's stored language
(`admins.language`, migration 0010, `''` = none); then the cookie `siplink_lang`; then the
browser's `Accept-Language`, matched with `golang.org/x/text/language` (`NewMatcher` over the
eight tags: `zh-CN`, `zh-SG`, `zh` → `zh-Hans`; `zh-TW`, `zh-HK`, `zh-MO` → `zh-Hant`; `ar-EG`
→ `ar`; …); when nothing matches (confidence `No`), English. Pages before sign-in (login, the
second step, the `/p/{token}` page for browsers, error pages of public routes) use the cookie,
then `Accept-Language`.

**Choosing.** `POST /language` (`lang`, `next`; a public route with CSRF protection, so it works
before sign-in) sets the cookie (one year, `HttpOnly`, `SameSite=Lax`, `Secure` like the session
cookie) and, for a complete session, stores the language for the administrator; it is a personal
display setting like the theme, so no audit entry. The control is a compact drop-down with the
Lucide `languages` icon: in the user menu under 外观, and at the foot of the login and the
second-step page; it submits on change (a button without JavaScript). When a sign-in completes,
a stored language refreshes the cookie; without one, the cookie's language (if any) becomes the
administrator's stored language, so a choice made on the login page follows them to other
browsers.

**Pages.** `<html lang="{code}" dir="ltr|rtl">`. The templates are parsed once per language with
that language's functions (`t`, `msg`, `msgs` and the label helpers of the audit log and the
shell), so `{{t "key" "name" .Value}}` works at any depth; handlers use `tr(r)`. The few texts
the scripts need travel in the layout as JSON (`<script type="application/json" id="i18n">`:
not executed, so the CSP of §6.3 is unchanged).

**Right to left.** The stylesheets use logical properties wherever a side matters (margins,
paddings, insets, borders, text alignment, radii of grouped controls), so in Arabic the sidebar
is on the right, the narrow-screen drawer comes in from the right, popovers and tooltips anchor
at the mirrored side and tables align to the start. Icons that point a way (arrows, chevrons, the
breadcrumb separator, the dashboard's stacked bar) are mirrored by `[dir=rtl]` rules; switches
and the select's arrow move to the other side. What must read left to right stays so: SIP
addresses, host names, URLs, code, keys, fingerprints, CSV and LDIF samples are `.mono`, `<code>`
or `<kbd>` elements, which the stylesheet sets left to right and isolates (aligned to the right in
an Arabic page), and inputs for such values carry `dir="ltr"` (their placeholder follows the page
while they are empty). Names people typed that run inside a sentence are isolated by the FSI/PDI
of the arguments (above).

Names on their own (2026-11-20): every element that prints one value people or phones gave —
the name of a client, group, tag, region, template, directory, entry, account or administrator,
in tables, chips, the breadcrumb, headings, the dashboard's lists and the print cards, and the
technical `.fade` values — carries `dir="auto"`, so a Chinese or Latin name in an Arabic page is
laid out, cut and faded left to right (its start stays visible) while the element itself still
sits at the start of the cell. Sub-titles that mix the page's words with names keep the page's
direction and isolate each name with `<bdi>` or a `dir="auto"` link. The fade of `.fade` follows
the element's own direction (`.fade.overflowing:dir(rtl)`, not `[dir=rtl] .fade`); its end can
only be the line's end in such a mixed sub-title.

**Formats.** Times use the language's numeric pattern (`format.date`, `format.datetime`, tokens
`YYYY MM DD HH mm`), always with ASCII digits; the server writes them in its zone with the zone
named, and `app.js` rewrites them with the same pattern in the viewer's zone, and the relative
ones with `Intl.RelativeTimeFormat` (numbering system `latn`; "yesterday 08:05" and "just now"
from the catalog, keys `js.*`, which `app.js` offers the other scripts as
`window.siplinkText`). Numbers stay plain (no grouping, no Arabic-Indic digits). Lists use
`format.list_sep`; each language quotes names its own way (「」, “”, « »).

**Fonts.** Inter stays first (Latin, Cyrillic); then per `:lang()`: Simplified Chinese PingFang
SC / Microsoft YaHei (as before), Traditional Chinese PingFang TC / Microsoft JhengHei, Japanese
Hiragino Sans / Yu Gothic / Meiryo, Arabic the system's Arabic faces (Geeza Pro, Segoe UI, Noto
Sans Arabic). No font is added to the image.

**Mail.** The code mails (§6.8) are written in the language stored for the administrator they
go to, else in the language of the request that sends them; the test mail in the language of the
administrator who sends it.

**What does not change.** The provisioning document (§3.5; `platform.name` stays "SipLink
管理平台", the golden files stay byte for byte), the device API, LDAP, backups, the key
template CSV (the app's format, §3.5.7), the audit action codes, stored audit entries and log
lines. Audit entries written from now on store codes, not text (`"changed"` in place of
"已修改" for a secret); the audit page translates actions, object types and field names, and
shows what older entries stored as it is.

**CSV.** The client import (§5.2) reads either the Chinese column names, as before, or the
English ones: `name`, `region`, `client_template`, `note`, `group`, `tags`, and `{profile} username`, `{profile}
password`, `{profile} auth user`, `{profile} display name`, `{directory} LDAP password` (in any
case). One file uses one language: a header with a `名称` column is a Chinese one, whose
English names are extra columns and ignored (an older file with its own "Name" column imports
as before); otherwise, with a `name` column, only the English names count. In that language
a header with a field in two columns, or a column that fits two fields (a profile "X LDAP" and
a directory "X" both give "X LDAP password"; "X LDAP" and "X" both give "XLDAP密码"), is
refused with the column named, rather than a password going to the wrong place. The header
download refuses such a template the same way, so that the profile or directory is renamed
first. The address book import already reads both languages (§4.7). The client template
download and the address book export write the Chinese names in `zh-Hans` and `zh-Hant`, the
English ones in every other language.

The columns 分组 / `group` and 标签 / `tags` (2026-11-20, §2.6) are optional; a file without
them imports as before. 分组 names a group of the line's region (ignoring case), else the line
is refused naming it. 标签 holds tag names separated by `;` or `；`; a known tag is reused
(ignoring case), an unknown one is made at the confirmation with the default colour; a name the
tag rules refuse, or more than 10 tags, refuses the line, and a file that would take the tags
beyond 500 is refused as a whole; a group deleted between the dry run and the confirmation
voids the dry run (`clients.import.expired`) before anything is written. There is no export of clients; the header download is the
starting point of a file.

**CLI.** `siplink admin …`, `pki …`, `backup`, `migrate` speak the language of the POSIX locale
(`LC_ALL`, else `LC_MESSAGES`, else `LANG`; `zh_CN.UTF-8` → `zh-Hans`, `zh_TW.UTF-8` →
`zh-Hant`), matched like `Accept-Language`; unset, `C`, `POSIX` or a locale that names a
codeset other than UTF-8 (`zh_CN.GB18030`: the output is UTF-8) → English. Values people copy
from the terminal (one-time passwords, pins, fingerprints, paths, certificate names) follow
their translated label as they are, never as arguments, so no isolates stick to them. In the
container: `docker compose exec -e LANG=zh_CN.UTF-8 siplink siplink admin create --super
{用户名}`.

---

## 6. Security

### 6.1 Secrets at rest

| Secret | Stored as | Why |
|---|---|---|
| Admin passwords | argon2id (`golang.org/x/crypto/argon2`, m=64 MiB, t=3, p=2, 16-byte salt, PHC string); at most 4 computations at once in the process (`auth.MaxHashing`), a login waits at most 3 s for one and otherwise gets the too-many-attempts page (`429`), other callers wait | only verified |
| Session tokens | SHA-256 | only looked up |
| Enrollment token | SHA-256 (lookup) + AES-GCM (to show the QR again) | single-use, expires |
| Device key | SHA-256 | only looked up |
| SIP passwords, external LDAP passwords (of the external directory or per client), hosted-book account passwords | AES-256-GCM, random 12-byte nonce, AAD = table + row id + column (`external_directories`/`password_enc` for a directory's own password since 0008, §2.5) | the phone needs them in clear |
| Hosted-book client LDAP passwords | not stored; HMAC-derived (§4.3) | |
| Service account LDAP passwords | SHA-256 of a 32-char random secret | only verified, cheap per bind |
| Configuration password | the SHA-256 hex the app expects (`ManagedConfig.isPassword`) | the app's format; it is unsalted, so the UI asks for at least 8 characters and says it protects only against casual viewing |
| TOTP secrets, SMTP password (§6.8) | AES-256-GCM like SIP passwords | needed in clear to compute codes / to log in to the mail server |
| E-mail codes, recovery codes (§6.8) | HMAC-SHA256 under the code key (below) of `{admin id}:{purpose}:{code}` | only verified; a database backup alone cannot test guesses |
| Private keys of the built-in CA and of its server certificate (§6.7) | in the database, AES-256-GCM with the data-encryption key (the secret box above) | a database backup carries them but is useless without the key file; never written to the log |

Password strength (owner's request of 2026-11-18): the switch "密码强度检测" on the 系统 page
(super administrators, meta key `password_check`, absent = on, audit `password.policy`) decides
what a new password of an administrator's own (我的账号) must be. On (default): 10 to 256
characters and not the user name. Off: 1 to 256 characters. The rule is the pure function
`auth.PasswordProblem(password, username, strict)`; the 我的账号 form shows "至少 10 个字符" only
while the check is on. One-time passwords made by the platform are not affected. The same switch
sets the minimum of the passphrase of an encrypted backup download (§6.6).

Key file: `SIPLINK_KEY_FILE` (default `/run/secrets/siplink-key`, else `{data}/key`), 64 random
bytes made at first start with mode 0600: 32 bytes data-encryption key, 32 bytes `ldapKey`. It is
deliberately separate from the database so that a leaked database backup alone reveals no
password. The key file is backed up separately (§6.6). The code key of §6.8 is derived from the
data-encryption key (HMAC-SHA256 with the label `siplink two-step codes v1`); it is not stored.
Key rotation: there is no command for it (no `rotate-key` subcommand exists); a new key file
makes every sealed value and every stored code unreadable.

### 6.2 Sessions

Cookie `siplink_session`: 32 random bytes, `HttpOnly`, `Secure` (unless HTTP is explicitly
allowed), `SameSite=Lax`, path `/`. Idle timeout 2 h, absolute 12 h. New token at login and at
password change; all sessions of an administrator end when their password is reset, they are
disabled, or their regions change.

### 6.3 CSRF

`http.CrossOriginProtection` from the standard library (Go ≥1.25; `Sec-Fetch-Site`/`Origin`
based) on every non-GET admin route, plus `SameSite=Lax` cookies. No state change on GET. The
device API is not cookie-authenticated, so it is outside CSRF concerns.

### 6.4 Viewing stored passwords

Passwords of clients are masked by default; "显示" requires the administrator to re-enter their
own password (valid 5 minutes) and writes an audit entry `secret.view`.

### 6.5 Rate limits (in memory, per process)

- Login: 5 failures per user name per 15 min, then 15 min lockout of that name for that IP;
  30 attempts per IP per minute overall. A name no administrator can have (`auth.ValidUsername`: 2–32
  of `A–Z a–z 0–9 . _ -`) counts only per IP and is not kept.
- Request bodies: every route caps its body when it is matched — 64 KiB without a complete
  session (login, language, second step) and for the device API, 1 MiB for signed-in pages, the
  upload routes their own limit (directory CSV 10 MiB, client CSV 4 MiB, key CSV 2 MiB, CA
  certificates 1 MiB, own certificate 2 × 64 KiB, each plus the form). A larger body is refused
  (`413`, or the upload's own message) before anything changes. Forms are parsed with the whole
  cap in memory, so no multipart part goes to a temporary file.
- Enrollment: 10 failed tokens per IP per 10 min → `429`; device sync: 120 requests per device
  key per hour, 600 per IP per minute (NAT of a whole office).
- LDAP: §4.5.
- Second step and password re-entries (我的账号, 显示 of a client password): 5 failures per
  administrator per 15 min, then 15 min refused; e-mail codes: one a minute, 5 an hour per
  administrator (§6.8).
- Every limit that counts failures takes its slot before the check (`auth.Window.Take`, one
  lock: refuse at the limit, else count) and gives it back on success (`Release`), so parallel
  requests cannot check more than the limit allows. The login lockout does the same.
Client IP = remote address, or `X-Forwarded-For` only when the peer is in
`SIPLINK_TRUSTED_PROXIES`.

### 6.6 Logging, audit, backups

Logging (`log/slog`, JSON to stdout): method, route **pattern** (e.g. `/p/{token}`, never the
actual path of `/p/…`), status, duration, client IP, admin id. Never logged: request or response
bodies of the device API, `Authorization` headers, cookies, tokens, passwords, LDAP bind
passwords, LDAP filters and results (they contain names and numbers; only at `debug` level, and
then with values masked), CSV contents. Panics are recovered and logged without request data.

Audit trail (`audit_log`): every create/update/delete of every object, logins (success/failure),
password resets, secret views, QR regeneration, unbinds, device enroll/leave, imports (counts),
backups, certificate re-issue, upload and removal of an own certificate (subject, issuer, names,
expiry, fingerprint — never the key) and the reminder setting. `detail_json` lists changed field names and old/new values **except** secrets (stored as
`"changed"`; entries written before the languages of §5.5 hold "已修改"). Groups and tags:
their own create/update/delete entries, and one `client.update` entry per client whose group or
tags changed (§2.6).

Backups: daily at `SIPLINK_BACKUP_TIME` (default 03:30 local) `VACUUM INTO
{data}/backups/siplink-{date}.db`, keep `SIPLINK_BACKUP_KEEP` (default 14); "立即备份" on the
system page; CLI `siplink backup {path}`. Restore = stop, copy file, start. The key file
must be backed up separately and kept elsewhere; the README says so in the first paragraph.

Download (owner's request of 2026-11-18: "备份可以在web下载，下载的时候可以选择是不是需要加密"):
- Super administrators only (region administrators get 404; a session waiting for the second
  step reaches nothing, §6.8). Each listed backup has "下载", a link to `/system?backup={name}`.
  The form is a modal dialog (the console's `.dialog`): with JavaScript the link opens it in
  place (focus on 当前密码; 取消, Esc or a click on the backdrop close it, focus returns to the
  link and the address is reset to `/system`); without JavaScript, and after a refused download,
  the server renders the page with the dialog open (`<dialog open>` over a scrim link back to
  `/system#backups`) for a name its own listing contains, with the refusal inside it and 加密 as
  it was, secrets never kept. A download answers with a file and the page stays, so the dialog
  closes once its form is sent (after the confirmation when unencrypted); a refusal answers with
  a new page that has the dialog open again.
- The form: 当前密码 (checked by the limited re-entry of §6.8, `recheckPassword`), the switch
  加密 (on by default) and, shown only while it is on, 加密口令 typed twice. `POST
  /system/backups/download` (CSRF as every form) with the name, which is looked up in the
  listing of `{data}/backups` (`backup.Open`): no path from the client is ever opened; anything
  not listed is 404.
- Off: the backup file as it is on disk, `attachment; filename="{name}"`. With JavaScript the
  form first asks "未加密的备份文件含全部配置、密码哈希与加密保存的密码，确认下载？".
- On: the file encrypted while it is sent, in the age format (v1, scrypt passphrase recipient,
  `filippo.io/age`, §7.2), `attachment; filename="{name}.age"`; `age -d` opens it. Passphrase:
  both entries equal, 1 to 256 characters, at least 10 while "密码强度检测" (§6.1) is on
  (`auth.PassphraseProblem`). It is never logged, audited or kept.
- Nothing is written to disk for a download, encrypted or not; the response is streamed.
- What is sent is the database backup only, unchanged: the key file never leaves the server
  through the console (owner's decision); the card keeps its warning that it is needed and kept
  separately.
- Audit `backup.download` with the backup's name and `encrypted` true/false.

### 6.7 Built-in certificate authority (package `internal/pki`)

The platform signs its own certificates. Nobody has to supply certificate files for it to speak
TLS on every listener; using a certificate of one's own stays possible, as files named in the
environment or uploaded on the 系统 page, and while no listener needs the built-in certificate
the built-in CA stops issuing.

**The CA**
- Made at the first start when none is stored, reused for ever after. No function in the web UI
  re-makes it (every phone would have to scan again).
- ECDSA P-256 key. Subject `CN=SipLink CA {first 8 hex digits of the key identifier}, O=SipLink`.
  Valid from one hour before its creation for 20 years. Random serial of 128 bits (positive).
- Extensions: BasicConstraints critical `CA:TRUE, pathlen:0`; KeyUsage critical
  `keyCertSign, cRLSign`; SubjectKeyIdentifier. **No AuthorityKeyIdentifier in the root.** The
  Linphone SDK's TLS library (mbedTLS 3.6.0) aborts the handshake on a chain certificate whose
  AuthorityKeyIdentifier is an empty SEQUENCE; that happened on this site with a root from another
  tool. A test must parse the DER and assert: extension 2.5.29.35 is absent in the root, present
  in every leaf, and the leaf's keyIdentifier equals the root's SubjectKeyIdentifier.
- Key generation sits behind one function (one place to change the key type should the phone's
  LDAP library turn out not to accept ECDSA; that is checked in the end-to-end test).

**Server certificates**
- Issued by the CA: new ECDSA P-256 key at every issue, valid from one hour before issue for 397
  days, random 128-bit serial, KeyUsage critical `digitalSignature`, ExtendedKeyUsage `serverAuth`,
  BasicConstraints `CA:FALSE`, SubjectKeyIdentifier, AuthorityKeyIdentifier = the CA's key identifier.
- Names (SubjectAltName; CN = the first name): the host of `SIPLINK_PUBLIC_URL`,
  `SIPLINK_LDAP_PUBLIC_HOST`, every entry of the optional `SIPLINK_TLS_EXTRA_NAMES`
  (comma separated), `localhost`, `127.0.0.1`, `::1`. A literal address goes in as an iPAddress
  entry, everything else as a dNSName entry.
- Issued again by itself when the name set differs from the stored certificate's or when fewer
  than 30 days of validity are left; checked at start and once a day; swapped in without a restart
  (`tls.Config.GetCertificate`). A clock is injected so that tests can move it.
- **Issuing pauses** while no listener uses the built-in certificate (every listener that speaks
  TLS has files or the uploaded certificate): the check of the start and of every day does
  nothing, "重新签发服务器证书" is not offered and `siplink pki renew` refuses. The CA and the last
  server certificate stay stored. When a listener needs the built-in certificate again (the
  uploaded one is removed, or the configuration changes) the check runs at once and issues when
  needed.
- Listeners send the leaf followed by the CA certificate (the phone needs the CA in the chain to
  check the pin, §3.1, §3.7). TLS 1.2 is the minimum.

**Storage and choice of certificate**
- CA certificate, CA key, server certificate and server key live in the database (`tls_material`,
  names `ca` and `server`); the two private keys are encrypted with the secret box of §6.1. An
  uploaded certificate is the row `own`: the chain as PEM, the key sealed the same way (AAD
  `tls_material/own/key_enc`). One backup file carries them, useless without the key file.
  Nothing of it is written to the log; the key is never shown or offered for download again.
- Which certificate a listener presents, first match wins:

| Listener | 1. files from the environment | 2. uploaded certificate | 3. otherwise |
|---|---|---|---|
| HTTPS (admin UI and provisioning) | `SIPLINK_TLS_CERT_FILE` / `SIPLINK_TLS_KEY_FILE` | when it names the host of `SIPLINK_PUBLIC_URL` | certificate of the built-in CA |
| LDAP StartTLS and LDAPS | `SIPLINK_LDAP_TLS_CERT_FILE` / `_KEY_FILE`, else the HTTPS files when those are set | when it names `SIPLINK_LDAP_PUBLIC_HOST` | certificate of the built-in CA |

- The choice is made per TLS handshake (`GetCertificate`), so an upload or a removal takes effect
  at once, without a restart. A certificate stored by the CLI reaches a running platform within a
  minute (it looks for a changed row once a minute).
- `SIPLINK_TLS` = `auto` (default: the HTTP listener speaks HTTPS) or `off` (plain HTTP, meant
  for a reverse proxy named in `SIPLINK_TRUSTED_PROXIES` that sends `X-Forwarded-Proto: https`;
  without such a proxy provisioning and logins are refused unless
  `SIPLINK_ALLOW_HTTP_PROVISIONING=true`). With `off` the LDAP service still gets the uploaded
  certificate or one of the built-in CA unless files are configured.
- Mode shown on the 系统 page and by `siplink pki show`: 内置 CA 自动签发 / 使用自有证书（自动签发已
  暂停） / 使用环境变量指定的证书文件（自动签发已暂停） / both of the last two together (paused) /
  混合 (one listener on the built-in CA, issuing continues).

**A certificate of one's own** (owner's request of 2026-11-16: "证书可以用自己的，这样的话签发就
暂停并且设置过期提示")
- Uploaded by a super administrator on the 系统 page (two file inputs, 64 KB each, multipart,
  CSRF like every form; kept in memory, never in a temporary file) or with `siplink pki import`:
  a chain as PEM, the server's certificate first, and its private key as PEM.
- Refused, each with a Chinese reason: no PEM certificate, a certificate that does not parse, a
  private key in the chain file, more than 8 certificates; no key, an encrypted key (PKCS#8
  `ENCRYPTED PRIVATE KEY` or a legacy `Proc-Type: 4,ENCRYPTED` header), a key that is neither
  RSA nor ECDSA, a key that does not belong to the first certificate; a self-signed certificate
  before the end of the chain, a certificate not signed by the next one (the order is server,
  intermediates, root); any certificate of the chain not yet valid or expired; a server
  certificate whose extended key usage exists without `serverAuth`, or without SANs (the app
  checks only SANs); a certificate that does not name the public host while the HTTPS listener
  would use it (neither files nor `SIPLINK_TLS=off`), or that no listener would use; a chain that
  is neither trusted by the system root store nor ends in a self-signed root (no phone could
  trust it: the app has no way to learn such a CA).
- Warnings, shown before the change takes effect and confirmed explicitly ("确认启用此证书";
  `--yes` on the command line; the pending upload waits in memory for 10 minutes, for the same
  administrator only):
  - the certificate does not name the LDAP host: LDAP keeps the built-in certificate and issuing
    goes on;
  - a chain certificate whose AuthorityKeyIdentifier is an empty SEQUENCE while the chain serves
    LDAP or its root goes into `caCertificates`: mbedTLS 3.6.0 in the phones' SDK aborts the
    handshake on it (see "The CA" above);
  - a self-signed server certificate that is not a CA: phones pin it directly, and every later
    change of certificate means scanning again;
  - **bound phones would be locked out**: a phone keeps what it trusted at its first scan (§3.7).
    The platform does not record what each phone kept; it assumes the anchor of the HTTPS chain
    served now (the built-in CA, the private root of the operator's chain, or the system CAs for
    a public chain). A new HTTPS chain that is not publicly trusted and does not lead to that
    anchor would leave every bound client unable to sync: the warning names the number of bound
    clients and says they must be given a new QR code and scan again. A renewal under the same
    private root, or a public chain, raises no warning. The same check runs before
    "停用自有证书" (returning to the built-in CA from a private root or from a public chain);
  - the pin of QR codes would change while clients wait for their first scan: QR codes printed or
    sent before carry the old pin;
  - the certificate expires inside the reminder window.
- Public or private is decided with the system root store of the machine (`x509.SystemCertPool`).
  The runtime image (`alpine:3.24` + `ca-certificates`, checked 2026-11-16 in `siplink:dev`:
  package `ca-certificates-20261020-r0`, 118 roots in `/etc/ssl/certs/ca-certificates.crt`) has
  one. It is Mozilla's list, close to but not the same as Android's; a CA that only one of the
  two knows is judged wrongly. Without a root store every chain counts as private.
- "停用自有证书" (or `siplink pki use-builtin`) deletes the row and returns to the built-in CA;
  issuing resumes at once.
- Files from the environment are not held to the upload's checks (they worked before them; only
  the key match is checked), but they get the same trust rules (pin, `caCertificates`) and the
  same reminder.

**Trust on the first scan**
- `pin` = base64url without padding of SHA-256 over the DER SubjectPublicKeyInfo of the root the
  phones are to keep (43 characters): the built-in **CA certificate** when it issued the HTTPS
  certificate; the self-signed certificate at the end of an operator's chain when the system
  root store does not vouch for the chain (for a lone self-signed server certificate that is the
  server certificate itself, which the app's check accepts); none for a publicly trusted chain.
  It stays the same when the server certificate is renewed under the same root. It is appended
  to the enrollment URL exactly when the HTTPS listener presents such a chain (§3.1); the app's
  handling is described in §3.7. Listeners send the whole chain, so the root is in it.
- Documents start `caCertificates` with the same roots, HTTPS first, then LDAP (§3.5.2).

**Expiry reminder**
- Setting "到期前提醒天数" on the certificate card, 1–365, default 30 (meta key
  `cert_reminder_days`). It applies to the operator's certificates in use (uploaded or files);
  the built-in certificate renews itself and has none.
- Inside the window: a notice on the 系统 page, an entry for super administrators in the attention
  list of 概览, a `warn` log line at start and once a day. After expiry the notice is an error:
  "证书已过期，设备将无法同步", logged at `error`.
- `GET /healthz` answers `ok` and one line per notice (`warn …`, `error …`), always with status
  200 so that a reverse proxy does not take the platform out of service. `siplink health` prints
  the lines; it stays healthy inside the window and **fails after expiry** (Docker then shows the
  container as unhealthy; plain Docker does not restart it for that). `siplink pki show` lists
  the notices too.

**Web UI and CLI**
- 系统 page, card "证书" (§5.2, super administrators only): mode and source per listener, the
  operator's certificates, notices, the CA's subject, validity and SHA-256 fingerprint, the names
  and expiry of the built-in server certificate, "下载 CA 证书" (PEM, any administrator),
  "重新签发服务器证书" unless issuing is paused, upload, "改用内置 CA" (the web form of
  `siplink pki use-builtin`: the same `RemovalWarnings` first, confirmed with "确认改用内置 CA，
  {n} 台设备需重新扫码"), "到期前提醒天数" (POST with CSRF, audit entries `pki.reissue`,
  `pki.own_import`, `pki.own_remove`, `pki.reminder`). The card shows one source at a time
  (内置 CA · 自有证书, §5.2); uploading switches to the own certificate, "改用内置 CA" back.
- CLI: `siplink pki show` (mode, sources, whether issuing is paused, pin, the built-in CA and
  server certificate, the operator's certificates with names, issuer, expiry and trust, the
  reminder and its notices), `siplink pki export-ca {path}`, `siplink pki renew`,
  `siplink pki import [--yes] {chain} {key}`, `siplink pki use-builtin [--yes]` (audit entries
  under the name `CLI`).
- The README explains that a browser warns until the CA is imported.

### 6.8 Two-step sign-in and mail (added 2026-11-17)

Request of the owner (2026-11-17): "管理平台加上邮箱验证码的功能，还有totp，或者passkey，可以选择开2fa，可以给区域管理员强制需要2fa，smtp配置加一个页面". Package
`internal/twostep` (TOTP, one-time and recovery codes, the policy rules), `internal/mail` (SMTP),
store file `twostep.go`, web handlers `h_signin_step.go`, `h_account.go`, `h_twostep.go`,
`h_passkeys.go`, `h_mail.go`, script `static/passkey.js`. Migration `0007_two_step.sql`.

**Methods.** Every method is a second step after the password; none replaces it.
- *TOTP* (RFC 6238: HMAC-SHA1, 30 s, 6 digits; standard library only). Secret: 20 random bytes,
  shown base32 without padding and as a QR code (`internal/qr`, the code of §5.4) of
  `otpauth://totp/SipLink:{user}?secret=…&issuer=SipLink&algorithm=SHA1&digits=6&period=30`.
  "启用" stores a new secret (sealed, AAD `admin_totp`/admin id/`secret_enc`) as *pending*; it is on
  only after one valid code. Accepted: the steps now−1, now, now+1; a step is used once
  (`last_step`, updated with `WHERE last_step < ?`, so two parallel requests cannot both pass).
- *Passkeys* (WebAuthn, `github.com/go-webauthn/webauthn`, §7.2). Several per administrator, each
  with a name (≤40), creation time and last use; added and deleted on 我的账号 (no rename: delete
  and add again). RP ID = host of `SIPLINK_PUBLIC_URL` in lower case, origin = the public URL with scheme and host in lower case (default port
  left out); user handle = the administrator id as 8 bytes; resident key "preferred", user
  verification "preferred", attestation "none". The ceremony state (`webauthn.SessionData`) lives
  in memory for 5 minutes, bound to the administrator and to a random token the page sends back.
  A sign count that goes backwards (clone warning) refuses the sign-in. The credential is stored
  as the library's JSON (public data only). WebAuthn needs a secure context and a domain name as
  relying party ID: when the public URL is `http://` to a host other than `localhost` or
  `*.localhost`, or its host is an IP address, passkeys are neither offered nor accepted
  (`twostep.PasskeysUsable`). The browser must reach the platform at exactly the public URL
  and trust its certificate (the built-in CA must be imported; WebAuthn refuses pages with
  certificate errors).
- *E-mail code*: needs an e-mail address on the administrator that was confirmed once by a code
  sent to it, and SMTP to be configured (host and sender address set); otherwise not offered.
  Codes: 6 digits from `crypto/rand`, valid 10 minutes, at most 5 wrong tries per code (then the
  code is void), one live code per administrator and purpose (`login`, `confirm`); a new code at
  most once a minute and 5 per hour per administrator (in memory, §6.5).
- *Recovery codes*: when the first method is switched on, ten codes of 10 characters (alphabet
  without look-alikes, shown `xxxxx-xxxxx`) are made and shown once (`/me/recovery`, through a
  token kept in memory for 5 minutes). The same administrator can reload the page with the token
  until the 5 minutes are over or "完成" (a POST) drops the codes. "重新生成恢复码" voids the old set. A recovery
  code is accepted in place of any method and is then used up (audit `two_step.recovery_used`).
  Switching off the last method deletes the codes.
- Hashes: HMAC-SHA256 with the code key (§6.1, derived from the key file, not the sealing key)
  of `{admin id}:{purpose}:{code}`, purpose `recovery` for recovery codes (normalised code) and
  `login` / `confirm` for e-mail codes. A database backup alone does not let anyone test guesses;
  a restore without the key file leaves the codes unverifiable as well (the administrator then
  needs `reset-2fa`). Nothing of this is logged or audited in clear.

**Sign-in flow.** Order: password → second step (if the administrator has a method) → forced
password change (`must_change_password`) → forced set-up (policy). The second step comes first
because it proves the identity; the password change comes before the set-up because a new or
reset administrator knows a one-time password that another administrator has seen.
- A correct password for an administrator with at least one method starts a *pending* session
  (`sessions.stage = 'second'`, absolute lifetime 10 minutes). It reaches only `/login/step…`
  (the step page, its forms and the passkey endpoints) and `/logout`; every other route sends it
  to `/login/step`, and an unknown path answers the plain 404 (as it does for an administrator held by a forced
  password change or a forced set-up). Completing the step deletes the
  pending session and starts a normal one with a new token (§6.2); `next` is carried through.
- The step page offers the administrator's usable methods, passkey first, then TOTP, then e-mail
  ("发送验证码" is a POST), and "使用恢复码" folded.
- Failed second steps: 5 per administrator in 15 minutes (`auth.Window` with hold, as the login
  lockout of §6.5), then 15 minutes refused (429) for that administrator from any address; each
  failure is audited `login.second_failure` with the method. Password re-entries (我的账号,
  "当前密码" of a password change, 显示 of a client password) and the first code of a TOTP set-up
  count against the same limit (§6.5: slot taken before the check).
- `next` is kept only when it is a path-only reference: it starts with one `/`, has no backslash
  and no control character, and parses to a path without scheme, host or user that does not start
  with `//`; otherwise `/`. The passkey step's JSON redirect is such a value.
- Existing sessions stay valid across the upgrade (`stage` defaults to `''` = complete).

**Own settings (我的账号 `/me`).** Account card (user name, e-mail address with its confirmation
by code), password card (as before), card "两步验证": TOTP (启用 / 关闭), passkeys (list, 添加,
删除), e-mail code (启用 / 关闭), recovery codes (remaining count, 重新生成). Switching a method
off, deleting the last passkey, making new recovery codes and changing the e-mail address ask for
the password again. Changing the e-mail address makes it unconfirmed and switches the e-mail
method off. A super administrator edits a region administrator's address on that
administrator's page (unconfirmed afterwards); the form carries the address it was shown with
(`email_was`) and the address is written only when the form changed it. A super administrator's
address (another's or one's own) cannot be changed there ("超级管理员的邮箱只能由本人在「我的账号」中修改。"),
and the role of a super administrator with any method cannot be changed ("已启用两步验证的超级管理员不可修改角色。"),
which closes demote → reset → promote.
- Switching on the first method ends the administrator's other sessions; the current one stays.
- TOTP set-up: the confirmation names the sealed secret whose code it checked (`UPDATE … WHERE
  secret_enc = ?`), so a code for a secret replaced in another tab confirms nothing. "取消" on the
  set-up page (a POST) drops the pending secret.
- Removing a method (TOTP off, e-mail off, passkey delete, an address change that switches the
  e-mail method off) reads the state, applies the rule (`twostep.MayRemove`, and "the last passkey
  needs the password") and deletes in one store transaction (`RemoveMethod`, `SetAdminEmail`);
  the store returns `ErrLastMethod` and the handler shows the usual message.

**Policy.** Meta key `two_step_required` (`1`/absent), switch "区域管理员必须启用两步验证" on the
系统 page (super administrators; audit `two_step.policy`). While it is on, a region administrator
without a method reaches only `/me`, `/me/…` and `/logout` after the password (and after a forced
password change) until one method is on, and cannot switch off or delete their last method (also
not by changing the e-mail address). It does not bind super administrators. The rules are pure
functions in `twostep` (`MustSetUp`, `MayRemove`).

**Getting back in.** `siplink admin reset-2fa {用户名}` removes every method, the recovery codes and
pending codes of that administrator and ends their sessions (audit `two_step.reset` by `CLI`); the
e-mail address stays (unconfirmed). A super administrator can do the same for a region
administrator from that administrator's page ("重置两步验证", confirmation, audit); a region
administrator cannot reset anyone, and super administrators are reset only through the CLI.

**Super administrators against each other.** What protects a super administrator's account from
another super administrator: the second step (a reset password alone does not sign in when a
method is on), the address rule and the role rule above, and `reset-2fa` for super
administrators only on the CLI. What does not: "重置密码" (`adminResetPassword`) still works on
another super administrator and ends their sessions; disabling the account; anyone with the CLI
or the database and key file; and a super administrator without any method, whose account is
taken over by a password reset.

**Known limit.** An administrator whose only method has become unusable (passkeys after the
public URL changed to an IP address, the e-mail method while SMTP is not configured) still gets the
second step and needs a recovery code or `reset-2fa`; the policy does not ask them for another
method.

**SMTP (邮件 `/mail`, super administrators).** Single row `mail_settings`: host, port (1–65535;
default 587/465/25 by security), security `starttls` / `tls` / `none` (STARTTLS / TLS / 不加密),
user name, password (sealed, AAD `mail_settings`/1/`password_enc`; write-only: the page shows
已设置 / 未设置, an empty field keeps it, an empty user name drops it), sender address and sender
name. "发送测试邮件" to an address typed in; on failure the page shows the server's answer. Sending
uses `net/smtp` + `crypto/tls` (system roots, server name = host): dial 10 s, the whole exchange
30 s; with STARTTLS the upgrade is required (a server without it fails); authentication is
PLAIN, which `net/smtp` refuses on an unencrypted connection except to localhost. Mail: plain
text, UTF-8, base64 body, subject "SipLink 管理平台验证码", body with the code and "10 分钟内有效" (in
Simplified Chinese; in the recipient's language, §5.5).
Once the server accepted DATA, an error on QUIT still counts as sent. Outside the 邮件 page a
failed send shows only "邮件发送失败。"; the cause goes to the log. Changing host, port or
security without typing the password again is refused ("修改服务器、端口或加密方式后，须重新输入密码。")
unless no user name is set, so the stored password is never sent to a new server.
Audit `mail.update` (field names, password as "已修改") and `mail.test`.

**Audit actions.** `login.second_failure`, `two_step.enable` / `two_step.disable` (detail
`method`: `totp`, `email`), `passkey.create`, `passkey.delete`, `two_step.recovery_renew`,
`two_step.recovery_used`, `two_step.reset`, `two_step.policy`, `admin.email`,
`admin.email_confirm`, `mail.update`, `mail.test`.

---

## 7. Tech stack, dependencies, layout, build

### 7.1 Stack

Go 1.27.4 (images `golang:1.27.4-alpine3.24` and `golang:1.27.4-trixie`; 1.27.4 fixes GO-2026-6859
in `net/http` — use the newest 1.27.x image available at build time), `CGO_ENABLED=0`, one static
binary. Standard library for HTTP (`net/http` with method+pattern `ServeMux`), templates
(`html/template`), embedding, logging (`log/slog`), crypto (AES-GCM, HMAC, SHA-256), TLS, CSRF
(`http.CrossOriginProtection`). Migrations are numbered `.sql` files embedded and applied in
order at start.

### 7.2 Third-party modules (all evidence fetched 2026-11-16 from GitHub/GitLab APIs and
proxy.golang.org)

| Module | Use | Evidence it is the genuine, maintained one |
|---|---|---|
| `modernc.org/sqlite` v1.62.0 | SQLite without CGO | Vanity path resolves to `gitlab.com/cznic/sqlite` (proxy.golang.org `Origin.URL`); repo since 2018-11, last tag v1.62.0 on 2026-11-07, tags every few weeks; by Jan Mercl (cznic), the long-standing author of the modernc.org tool chain; the most used pure-Go SQLite driver. Pulls in `modernc.org/libc`, `mathutil`, `memory`, `fileutil` and a few small indirect modules of the same author plus `golang.org/x/sys`. |
| `github.com/go-asn1-ber/asn1-ber` v1.5.8 | BER encoding/decoding of LDAP messages | Organization `go-asn1-ber`, the same maintainers as `go-ldap/ldap`; repo since 2014-10; last release 2026-07-16; recent commits "Harden and modernize the BER decoder" (2026-09). MIT. No dependencies. |
| `golang.org/x/crypto` v0.59.0 | argon2id | Official Go sub-repository (go.googlesource.com/crypto, mirror github.com/golang/crypto since 2014), released 2026-11-11. BSD-3. |
| `golang.org/x/text` v0.44.0 | GB18030 decoding of CSV (`encoding/simplifiedchinese`); matching `Accept-Language` and the POSIX locale against the console's languages (`language`, §5.5) | Official Go sub-repository. Already a dependency; the languages add no module. |
| `github.com/skip2/go-qrcode` (v0.0.0-20200617195104-da1b6568686e) | QR matrix and PNG | Repo since 2015-03, ~3,000 stars, MIT, **zero dependencies** (its go.mod has no requires). Last code change 2020-06: the QR standard does not change, the library is feature-complete; risk accepted because it is tiny and dependency-free. Alternative if the owner prefers an active project: `github.com/yeqown/go-qrcode/v2` v2.3.0 (2026-08, active, but adds `yeqown/reedsolomon` and `x/text`). |
| `github.com/go-webauthn/webauthn` v0.18.2 (added 2026-11-17, evidence fetched that day from the GitHub API and proxy.golang.org) | WebAuthn ceremonies for passkeys (§6.8) | Organization `go-webauthn`, repo since 2021-12-15, ~1,365 stars, BSD-3, not archived, last push 2026-11-17, release v0.18.2 on 2026-09-19 (v0.18.0 2026-08-27, v0.18.1 2026-09-10). It is the maintained successor of `duo-labs/webauthn`: that repository (since 2017-10, ~1,028 stars) is archived since 2022-12 and its README opens with "THIS LIBRARY IS DEPRECATED" and points to `github.com/go-webauthn/webauthn` and its migration guide. Pulls in (linked or in `go.sum`): `fxamacker/cbor/v2` (since 2019, ~1,100 stars, MIT), `go-viper/mapstructure/v2` (org, MIT), `go-webauthn/x` (same org), `golang-jwt/jwt/v5` (org, ~9,300 stars, MIT), `google/go-tpm` (Google org, Apache-2.0), `google/uuid` (already present), `tinylib/msgp` (org, ~1,960 stars, MIT) with `philhofer/fwd`, `x448/float16` (MIT); `testify` and `go.uber.org/mock` are its test dependencies only. TOTP and SMTP use the standard library (no dependency). No virtual-authenticator library: the Go tests build authenticator responses themselves (P-256 key, CBOR and COSE through the library's own `protocol/webauthncbor` and `protocol/webauthncose`). |
| `filippo.io/age` v1.3.2 (added 2026-11-18, evidence fetched that day from proxy.golang.org, the vanity page and the GitHub API) | encryption of a backup download with a passphrase (§6.6) | The vanity path's `go-import` points to `github.com/FiloSottile/age`; proxy.golang.org `@latest` = v1.3.2 of 2026-08-29 with `Origin.URL` that repository (tags v1.3.0 2025-12-27, v1.3.1 2025-12-28, v1.3.2 2026-08-29); repository since 2019-05-18 by Filippo Valsorda (former Go security lead, the format's author), ~24,000 stars, BSD-3, not archived; pkg.go.dev lists 432 importers. Implements the age v1 format (C2SP spec), so `age -d` of the official CLI opens the files. Its go.mod requires `filippo.io/hpke`, `filippo.io/edwards25519`, `filippo.io/nistec` (same author), `golang.org/x/crypto`, `x/sys`, `x/term` (official); of these only `filippo.io/hpke` is new in our `go.mod` (indirect). |
| Test only: `github.com/go-ldap/ldap/v3` v3.4.15 | LDAP client in Go integration tests | Organization `go-ldap`, repo since 2014-10, ~2,500 stars, release 2026-10-07. Not linked into the binary. |

Not used, and why: `github.com/jimlambrt/gldap` (an LDAP server library; last release v0.1.14 on
2024-08-30, two years without a release, and it pulls in `hclog`, `backoff`, `testify`,
`x/exp`); `vjeantet/ldapserver` (small, user-owned, sporadic). The LDAP server we need is small
(bind, search, StartTLS, abandon, unbind — §4.4), and writing it on top of `asn1-ber` keeps the
dependency list short and the behaviour under our control. No web framework, no ORM, no
session or CSRF library, no JS build.

### 7.3 Project layout

```
siplink/
  cmd/siplink/main.go     flags/subcommands: serve (default), admin, pki, seed, backup, health, migrate, version
  internal/config/        config.go           environment variables → Config, validation
  internal/pki/           built-in CA (§6.7): CA, server certificates, renewal, choice of certificate per listener, pin
  internal/store/         db.go, migrate.go, migrations/0001_init.sql …,
                          admins.go, regions.go, clients.go, groups.go, templates.go, keys.go, books.go,
                          ldapaccounts.go, audit.go           (one file per area, plain SQL)
  internal/secret/        keyfile.go, aead.go, derive.go       key file, AES-GCM, HMAC LDAP passwords
  internal/auth/          password.go (argon2id), session.go, ratelimit.go, perm.go (the §2.4 matrix in one place)
  internal/rules/         accounts.go, ldap.go, shortcuts.go, settings.go   ports of AccountRules, LdapRules, ShortcutRules, value ranges
  internal/provision/     document.go (types = the JSON contract), build.go (merge §3.5.8),
                          version.go, handler.go (enroll/sync/leave/info page)
  internal/ldapd/         server.go (listener, conns, limits), message.go (BER ↔ ops),
                          filter.go (parse + evaluate), match.go (matching rules),
                          dit.go (DNs, root DSE, entries), acl.go, cache.go (book snapshots), starttls.go
  internal/books/         csv.go (import/export, GB18030), ldif.go
  internal/qr/            qr.go (SVG/PNG)
  internal/twostep/       totp.go, codes.go, policy.go   two-step sign-in rules (§6.8)
  internal/mail/          mail.go                        SMTP settings and sending (§6.8)
  internal/i18n/          i18n.go, catalog.go, locales/{code}/*.json   languages, catalogs, Msg (§5.5)
  internal/web/           server.go (routes, middleware), render.go, flash.go,
                          h_login.go, h_dashboard.go, h_clients.go, h_client_bulk.go, h_client_import.go, h_groups.go, h_templates_config.go,
                          h_templates_key.go, h_templates_client.go, h_books.go, h_ldapaccounts.go,
                          h_regions.go, h_admins.go, h_audit.go, h_system.go
                          templates/*.html, static/app.css, static/app.js
  internal/backup/        backup.go (VACUUM INTO, schedule, retention)
  testdata/               golden documents, CSV samples
  Dockerfile, compose.yaml, .dockerignore, .gitignore, README.md
```

Every file stays under 800 lines (user rule); handlers split per area as above.

### 7.4 Configuration (environment variables; optional file `SIPLINK_CONFIG_FILE` with the same keys as `KEY=value` lines)

| Variable | Default | Meaning |
|---|---|---|
| `SIPLINK_PUBLIC_URL` | (required) | `https://platform.example.com`, used in QR codes and `syncUrl` |
| `SIPLINK_HTTP_LISTEN` | `:8080` | HTTP or HTTPS listener |
| `SIPLINK_TLS` | `auto` | `auto`: the HTTP listener speaks HTTPS; `off`: plain HTTP behind a reverse proxy (§3.7, §6.7) |
| `SIPLINK_TLS_CERT_FILE` / `SIPLINK_TLS_KEY_FILE` | empty | operator's HTTPS certificate (and StartTLS for LDAP); empty = the certificate uploaded on the 系统 page, else the built-in CA's (§6.7) |
| `SIPLINK_TLS_EXTRA_NAMES` | empty | further names (comma separated) for the built-in CA's server certificate (§6.7) |
| `SIPLINK_TRUSTED_PROXIES` | empty | CIDRs whose `X-Forwarded-*` are believed |
| `SIPLINK_ALLOW_HTTP_PROVISIONING` | `false` | LAN tests only |
| `SIPLINK_DATA_DIR` | `/data` | database, backups |
| `SIPLINK_KEY_FILE` | `/run/secrets/siplink-key` if present, else `{data}/key` | §6.1 |
| `SIPLINK_LDAP_LISTEN` | `:1389` | LDAP listener (empty = LDAP off) |
| `SIPLINK_LDAP_PUBLIC_HOST` / `_PORT` | host of public URL / `389` | what hosted directories tell the phones |
| `SIPLINK_LDAP_SUFFIX` | `dc=siplink` | DIT root |
| `SIPLINK_LDAP_TLS_CERT_FILE` / `_KEY_FILE` | the HTTPS ones, else the uploaded one, else the built-in CA's | StartTLS and LDAPS certificate (§4.8) |
| `SIPLINK_LDAPS_LISTEN` | empty | LDAPS listener with implicit TLS (empty = off; §4.8) |
| `SIPLINK_LDAP_REQUIRE_TLS` | `true` | §4.5 |
| `SIPLINK_LDAP_MAX_RESULTS` | `1000` | server cap |
| `SIPLINK_LDAP_CACHE_ENTRIES` | `1000000` | §4.6 |
| `SIPLINK_MAX_BOOKS` / `SIPLINK_MAX_BOOK_ENTRIES` | `500` / `10000` | §2.2 (may be lowered, not raised beyond the app's needs) |
| `SIPLINK_ENROLL_DAYS` | `30` | enrollment token lifetime |
| `SIPLINK_REVOKED_KEEP_DAYS` | `365` | §2.2 |
| `SIPLINK_BACKUP_TIME` / `SIPLINK_BACKUP_KEEP` | `03:30` / `14` | §6.6 |
| `SIPLINK_LOG_LEVEL` | `info` | |
| `TZ` | `Asia/Shanghai` in compose | display times |

Two-step sign-in and mail (§6.8) add no variables: the SMTP settings and the policy are kept in
the database and set on the web pages; passkeys follow `SIPLINK_PUBLIC_URL`.

First administrator: `docker compose exec siplink siplink admin create --super
{username}` prints a one-time password to the terminal (not to the log) and sets
`must_change_password`. The web UI has no setup page (nothing to hijack before an admin exists).

### 7.5 Docker

```dockerfile
FROM golang:1.27.4-alpine3.24 AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 go build -trimpath -ldflags "-s -w -X main.version=${VERSION}" -o /out/siplink ./cmd/siplink

FROM alpine:3.24
RUN apk add --no-cache ca-certificates tzdata && adduser -D -u 10001 siplink && mkdir /data && chown siplink /data
COPY --from=build /out/siplink /usr/local/bin/
USER siplink
VOLUME /data
EXPOSE 8080 1389
HEALTHCHECK CMD ["siplink", "health"]
ENTRYPOINT ["siplink"]
```

(`alpine:3.24` is the base of the local golang image; to be confirmed present as a standalone
official image at build time.) `.dockerignore`: `.git`, `.env*`, `*.key`, `*.pem`, `*.db`,
`data/`, `*.log`, `*.pcap*`, `.agent/`. `compose.yaml`: one service `siplink` (image `{user}/siplink:{tag}`), ports `443:8080` (or behind a
proxy) and `389:1389`, volume `./data:/data`, secret `siplink-key` from a file outside the
project, `restart: unless-stopped`, `read_only: true` root filesystem with `/data` writable.

### 7.6 Build and test commands (no local Go)

```
docker run --rm -v "$PWD":/src -w /src -v siplink-gomod:/go/pkg/mod golang:1.27.4-trixie go test ./...
docker run --rm -v "$PWD":/src -w /src -v siplink-gomod:/go/pkg/mod golang:1.27.4-trixie go vet ./...
docker run --rm ... golang:1.27.4-trixie sh -c 'apt-get update && apt-get install -y ldap-utils && go test -tags interop ./internal/ldapd/...'
docker build -t ghcr.io/tom231efa/siplink:dev .
```
`gofmt -l` must be empty; `staticcheck` only if the owner agrees to fetching it (`honnef.co/go/tools`, official).

---

## 8. Test plan

### 8.1 Unit tests (Go)
- `twostep` (§6.8): the SHA-1 vectors of RFC 6238 appendix B, the ±1 window and replay, the
  `otpauth://` URI, codes and recovery codes (format, hashing bound to administrator and purpose),
  the policy rules, when passkeys are usable. `mail`: sending over STARTTLS and implicit TLS to an
  in-process server, refused recipient and wrong password with the server's answer, unknown CA,
  a silent server (timeout), validation of the settings.
- `rules`: every case of the app's `AccountRulesTest`, `LdapRulesTest`, `ShortcutRulesTest`
  (ported, same inputs and expected errors), forward seconds range, frame rates, video sizes.
- `provision`: golden documents in `testdata/` for: minimal template; two profiles with default;
  external directory in each password mode; hosted directory with and without TLS; client
  forwarding overrides; key template with lines; codecs absent/present. Version stability (same
  input → same version; any field change → new version; `generatedAt` excluded).
- `auth`: argon2id verify, session expiry, permission matrix table test (each row of §2.4 ×
  each role × own/other/global object).
- `secret`: AES-GCM round trip, AAD mismatch rejected, key rotation, HMAC derivation stable.
- `pki` (§6.7): the CA is made once and reused over restarts; the extension assertions of §6.7
  (2.5.29.35 absent in the root, present in every leaf, leaf keyIdentifier = root
  SubjectKeyIdentifier); names from the configuration with an address as iPAddress; re-issue on a
  changed name set and near expiry (injected clock); `x509.Verify` of leaf + CA for a DNS name and
  for an address; the pin against an independent calculation. Own certificates (made at test
  time, injected clock and root store): every refusal; precedence per listener with files,
  upload and built-in; the pause of the issue check and its resumption; pin and `caCertificates`
  for built-in, private root, public-style chain and a lone self-signed certificate; the empty
  AuthorityKeyIdentifier warning; the reminder window and expiry; the rescan and reprint warnings;
  a removal made by a second process reaching the first.
- `ldapd/filter`: BER → filter tree → evaluation, for the exact filters the SDK builds (escaped
  `\2a`, spaces turned into `*`, `**`), Chinese names, phone-number matching with spaces/dashes,
  `not`, presence, unknown attribute = Undefined.
- `books/csv`: UTF-8, UTF-8 BOM, GB18030, quotes, too many numbers, 10,001 lines, replace mode
  atomicity.
- `store` migration 0008/0009 (§2.5): a database built at schema 7 with inline external
  directories in each password mode (two identical rows sharing an entry, a name taken in the
  region, a global template) is migrated with the key; entries, names, re-sealed passwords,
  per-client passwords and the document are checked; without the key it refuses and stays at 7.
- `store` groups and tags (§2.6, `groups_test.go`): a database at schema 10 with a client
  migrates to 11 (no group, no tags, same revision); names unique ignoring case (Cyrillic too),
  the limits, the triggers refusing a group of another region on insert and on a move, deletes
  (group, tag, region) and their counts, the filters (group, none, every tag), the rule of tags
  used outside a region administrator's regions, a refused bulk addition writing nothing.
- `store` migration 0013: a line stored at schema 12 reads a fast server timeout of 0 with its
  other fields and the template's revision unchanged, and keeps one saved afterwards.
  `BenchmarkClientList`: a page of 5,000 clients with 100 groups and 200 tags, unfiltered and
  filtered.

- `i18n` (§5.5): every catalog has the same files and keys as `zh-Hans`, no key twice, the same
  arguments in every translation, no empty text, the patterns of `format.*` valid; matching of
  `Accept-Language` and of the POSIX locale (regional and script variants, codesets other than
  UTF-8, no match → English); arguments (strings isolated in Arabic only and without bidi
  controls in every language, numbers, nested messages, lists, long name lists cut at five,
  missing ones marked); every call with a literal key, in Go and in the templates, passes
  exactly the arguments of its text. A source scan fails when a template, a script or a non-test Go file outside the
  catalogs still holds Chinese text, except an allow-list of what is data (language names, the
  Chinese CSV column names, the app's reserved shortcut categories, `platform.name`, sample data
  of `seed`).

### 8.2 HTTP integration tests (`httptest`, real SQLite in a temp dir)
- Languages (§5.5): every page, signed in as a super and as a region administrator, rendered in
  every language without a template error, a `⟦…⟧` marker or a leftover `{argument}`; `lang`
  and `dir` of the page; the choice by cookie, by `Accept-Language` and stored per administrator
  (it follows them to a new browser, a cookie is adopted at sign-in); mail in the recipient's
  language; the client CSV in one language per file (an older Chinese file with an extra
  "Name" column, a mixed header) and its refusals of clashing or doubled columns, in the import
  and in the header download; audit object names translated only for the type `system`; names
  with bidi controls refused, stored ones shown without them; a flash naming 20 users in Arabic
  and one too long for the cookie. The existing tests send `Accept-Language: zh-CN`, so they read
  the texts they always did.
- Login, lockout, session idle/absolute expiry, CSRF rejection of cross-site POST.
- Own certificate: upload as super only, CSRF, each refusal shown with its reason, the
  confirmation when a bound phone would have to scan again, removal with confirmation, the
  reminder setting, the notices on 系统, 概览 and `/healthz`.
- Region admin cannot read/modify another region's clients/templates/books (404, not 403, to
  avoid leaking existence), can use but not modify global templates.
- Groups and tags (`web_groups_test.go`, `web_groups_csv_test.go`): a region administrator's
  groups only (list, dialogs, 404 for another region's, the client form and the filter); a group
  of another region refused in the form and in bulk; a move leaving the group behind; every bulk
  refusal (foreign or unparseable ids, another region's group, clients of two regions, unknown
  tag, more than 10 tags) changing nothing; tag rules (names, colours, the lock for region
  administrators, 500 tags); the filters in the URL and through paging; the audit entries; the
  document unchanged (sync 204) after group and tag changes; the CSV with 分组/标签 in Chinese and
  `group`/`tags` in English, its line refusals and doubled columns; 5,000 clients with 100 groups
  and 200 tags answering each list, filter and page within a second.
- Directories (`web_directories_test.go`): the list with search, type filter and paging; creating
  each type; the type staying fixed; the external page and its validation; the stored password
  write-only and never in the audit log or the log; region rules for use and moves; delete
  refused while in use; the template's directory part; the client's reported version code and
  User-Agent.
- Enrollment: 200 + deviceKey; second enrollment with same token → 404; expired token → 404;
  GET info page identical for valid and invalid tokens.
- Sync: 200 then 204 with `have`; template edit → 200 with new version; unbind → 410 `unbound`;
  delete → 410 `deleted`; unknown key → 401; plain HTTP refused unless allowed.
- Logging test: capture slog output during a full enrollment + sync and assert that no token,
  device key or password substring appears.
- Two-step sign-in (§6.8): password then TOTP (replay, window), e-mail code against an
  in-process SMTP server (STARTTLS and implicit TLS, throwaway certificate; once a minute, five an
  hour, five tries, expiry), recovery codes (once, renewal voids), the pending session reaching
  nothing else, the policy (region administrators forced into set-up after the password change,
  super administrators not bound, last method kept), reset by a super administrator and by the
  CLI, the 邮件 page (super only, write-only password, the server's answer on failure), the
  per-administrator limit. Passkeys with a software authenticator built in the test (P-256,
  "none" attestation, signed assertions; counter going back and a foreign origin refused; an
  upper-case public host). Parallel requests over warmed-up connections against every limit
  (second step, password re-entries, login, TOTP set-up, sending codes) and against removal
  under the policy (`web_twostep_race_test.go`); the rules of super administrators, `next`,
  keyed code hashes, the mail error text, the SMTP password guard, sessions ended by the first
  method, the plain 404 while held, the TOTP set-up (`web_twostep_rules_test.go`); a QUIT error
  after DATA (`mail`).
  Outside `go test`: a real registration and sign-in in headless Chrome with a CDP virtual
  authenticator against `http://localhost`.

### 8.3 LDAP integration tests
- Go client (`go-ldap/ldap/v3`, test-only): binds (client derived, service account, anonymous
  on/off, wrong password, disabled client), StartTLS with a test CA, `REQUIRE_TLS`, scope
  base/one/sub, size limit → result code 4, access to a foreign book → 32.
- TLS by default: a bind before StartTLS answers 13 and the same bind after StartTLS succeeds;
  the test client checks the certificate against the platform's CA and the server name; with
  `SIPLINK_LDAP_REQUIRE_TLS=false` the old behaviour; the LDAPS listener when switched on.
- Interop with OpenLDAP's client library — the library the Linphone SDK uses — through
  `ldapsearch` from Debian `ldap-utils` in the trixie container, with the exact filters of
  §4.1, `-z {maxResults+1}`, `-ZZ` for StartTLS with `LDAPTLS_CACERT` set to the exported CA.
- Certificate interoperability inside the trixie container against the running server:
  `openssl verify -CAfile`, `openssl s_client -verify_return_error -CAfile` for HTTPS and with
  `-starttls ldap`, the pin recomputed with `openssl x509 -pubkey | openssl pkey -pubin -outform
  der | openssl dgst -sha256 -binary`. The same with an uploaded chain (leaf, intermediate, private
  root): `s_client -verify_return_error -CAfile {root}` on HTTPS and StartTLS, refused with the
  built-in CA, pin recomputed from the root. Certificates and keys of tests are made at test time,
  never committed.
- Benchmark: 10,000-entry book, substring name search and number search; 50 concurrent
  connections; report p50/p99.

### 8.4 End-to-end with the phone (app repository `testenv/`)
- Add an overlay `testenv/docker-compose.platform.yml` (later, in the app repository, by the app
  agent with the owner's agreement): service `platform` at `172.31.250.14` on the `testnet`
  network, image built from the platform repository, `SIPLINK_PUBLIC_URL=https://platform.siplink.test`,
  `SIPLINK_LDAP_PUBLIC_HOST=platform.siplink.test`; `dns` gains
  `--address=/platform.siplink.test/172.31.250.14`; `gen-certs.sh` adds
  `DNS:platform.siplink.test,IP:172.31.250.14` to the server certificate (the platform then trusts
  the same test CA as Asterisk and OpenLDAP). For a real phone on the LAN, the LAN overlay
  publishes `LAN_ADDRESS:8443→8080` and `LAN_ADDRESS:1389→1389` (389 is already taken there by
  OpenLDAP); without a DNS name the hosted book's security follows the decision on literal
  addresses (§3.5.5: the app refuses StartTLS to an IP today), consistent with the "no DNS changes
  for testing" rule. The end-to-end test also checks that the phone's LDAP library accepts the
  built-in CA's ECDSA certificates (§6.7).
- A seeding script creates through the CLI/HTTP: a region, a configuration template pointing at
  the test Asterisk (`pbx.siplink.test`, TLS, the account `1001` with the password from
  `testenv/.env`), the test CA, one hosted book (the five people of `testenv/ldap/people.ldif`
  as CSV), a key template, a client; it prints the enrollment URL.
- New androidTest class (app side, §9): enroll from the URL → accounts register → hosted
  directory search finds "张伟" by name and by `1001` → shortcut keys present and read-only →
  own key can be added → edit template on the platform → "立即同步" → change visible → delete the
  client on the platform → next sync wipes and unlocks. Plus: wrong certificate pin refused;
  config password hides passwords.

---

## 9. App-side work for the loader (for the agent who builds it later)

New dependencies (evidence fetched 2026-11-16):
- `androidx.camera:camera-camera2`, `camera-lifecycle`, `camera-view` 1.6.2 (Google Maven,
  AndroidX, official) — camera preview for scanning.
- `com.google.zxing:core` 3.5.4 (Maven Central; github.com/zxing/zxing since 2011, ~34k stars,
  Apache-2.0, last commit 2026-11-16; the project says it is in maintenance mode, the core decoder
  is stable). Chosen over ML Kit `com.google.mlkit:barcode-scanning` 17.3.0 (last release
  2024-08) because many phones in China have no Google Play services and ZXing core is plain Java
  with no Google services or telemetry dependencies; ML Kit's bundled model is the alternative if
  the owner prefers it (it works offline, +2.4 MB; behaviour without Play services must then be
  verified on a phone without them).
- No HTTP library: `HttpURLConnection` (or `HttpsURLConnection` with a custom
  `X509TrustManager` for `pin=`) is enough for one JSON POST. JSON with `org.json` (in Android).

Work items:
1. **Remote configuration screen** in Settings ("远程配置"): empty state with "扫描二维码" and
   "输入地址"; when managed: platform name, client name, region, last sync time and result,
   "立即同步", "删除远程配置" (confirmation; sends `leave`, then wipes). A camera permission
   request (`android.permission.CAMERA` is already in the manifest).
2. **Scanner**: CameraX `PreviewView` + `ImageAnalysis` → ZXing `QRCodeReader`; accepts only
   `https://` URLs (http in debug builds) with path `/p/{token}`.
3. **RemoteConfigClient**: enrollment POST, sync POST, leave POST, device report (§3.4), pin
   check, timeouts (10 s), backoff; never logs URLs with tokens, keys or bodies.
4. **RemoteConfigStore**: device key, install id, sync URL, current version, last result, key
   maps (account key → local account id, directory key → local directory id) in private storage
   (the app's section of the SIP core config file, as `ConfigLock` does, or a private file);
   wiped with the configuration.
5. **Loader / applier** (`core/RemoteConfig.kt` + pure `core/logic/RemoteDocument.kt` for parsing
   and validation with the existing rules): parse → validate everything first with
   `AccountRules`, `LdapRules`, `ShortcutRules`, `VideoRules`, `IncomingPolicy` ranges → if any
   error, apply nothing and report it → else inside `ConfigLock.fromRemote { }`:
   `setManaged(true, configPasswordHash)`; settings via `SipEngine.updateSettings` (personal
   fields kept from `settings.current`); accounts add/update/remove by key map
   (`AccountManager.add`/`update`/`remove` — `add` needs to return the new id, or a variant that
   creates with a given `idkey`); `setDefault`; CAs; codecs; directories; shortcuts.
6. **Manager additions** (each refuses unless `fromRemote`, i.e. `lock.refuses` is false):
   `TlsManager.replaceAll(pems)` (reconnect only when changed); `CodecManager.apply(audio, video)`;
   `DirectoryManager.replaceAll(list, passwords)` keeping local ids from the key map so the local
   copy (`DirectoryCache`) survives unchanged directories; `ShortcutManager.replaceHandedOut(keys)`
   that keeps every `Shortcut.own` key and the id counter (today `import` is refused while
   managed and appends instead of replacing).
7. **Wipe** (on 410 or user delete): inside `fromRemote` remove all handed-out accounts,
   directories, CAs, handed-out keys; restore codec defaults and `Settings()` defaults for the
   locked fields; then `setManaged(false)` (which calls `onReleased` → `ShortcutManager.dropOwn`);
   clear `RemoteConfigStore`. Keep history and personal settings. Note: `setManaged(false)` alone
   keeps the handed-out configuration by design, so the wipe has to be explicit.
8. **Scheduling**: sync at start (`SipEngine` init), on network back (`NetworkWatch`), every
   `refreshMinutes` while `SipService` runs (Handler; no WorkManager needed), and on demand.
9. **Restart notice**: when the CA list changed and `DirectoryManager.tlsChangeNeedsRestart` is
   true, show the existing "restart needed" path (`restartApp`).
10. **Settings screen**: the update-check switch (`autoUpdateCheck`) becomes locked like the
    others when managed.
11. Unit tests for `RemoteDocument` (golden JSON from the platform's `testdata/`), androidTest for
    the end-to-end flow (§8.4).

---

## 10. Milestones (platform)

Each ends with tests green in the container, `gofmt`/`go vet` clean, a short review note.

| # | Content | Reviewable result |
|---|---|---|
| P1 | Skeleton: config, SQLite + migrations, key file, slog, CLI `admin create`/`version`, built-in CA and HTTPS by default (`internal/pki`, §6.7; CLI `pki`, 系统 card "证书"), login/logout, sessions, CSRF, rate limit, base layout + CSS, audit writer | log in over HTTPS, see empty dashboard |
| P2 | Regions, administrators, permission matrix (`auth/perm.go`) with table tests | super/region admin separation |
| P3 | Address books: CRUD, entries, CSV import (dry run, GB18030)/export, LDIF export, limits | books with 10,000 entries |
| P4 | LDAP server: BER, bind (service accounts + anonymous), search, filters, matching rules, ACL, cache, StartTLS (always offered, required by default), optional LDAPS listener, limits; go-ldap + `ldapsearch` + `openssl` interop; benchmark; packet capture of the SDK's real search compared with §4.1 | `ldapsearch` against a hosted book |
| P5 | Ported app rules (`internal/rules`) with the app's test cases (`STARTTLS_NEEDS_HOST_NAME` as it is in the app, §3.5.5) | rules test suite |
| P6 | Configuration templates (all tabs, profiles, directories starting with `START_TLS`, CA upload, codecs; decision on StartTLS to literal addresses taken before, §3.5.5), key templates (editor, app CSV), client templates | templates complete |
| P7 | Clients: CRUD, credentials, CSV bulk import, QR (SVG/PNG, with `pin=` per §3.1), print page, LDAP client binds | QR on screen |
| P8 | Provisioning: document builder, version, enroll/sync/leave, revocation, reports on the client page, platform CA first in `caCertificates` and hosted directories with `START_TLS` by default (§3.5.2, §3.5.5), golden tests, logging test | `curl` enrollment and sync |
| P9 | Dockerfile, compose, backups, `health`, README (deployment, TLS, importing the CA into browsers, key file backup), testenv overlay proposal | `docker compose up` |
| A1–A4 | App side (§9): screen + scanner; client + store; loader + manager additions; wipe + e2e test | phone provisioned end to end |

---

## 11. Open questions for the owner (with the default used if there is no answer)

1. **When a client is deleted or moved to another phone on the platform, what happens on the old
   phone?** Default: at its next sync it removes everything that was handed out (accounts with
   passwords, directories, CA certificates, handed-out and own shortcut keys) and is unlocked;
   call history and appearance stay.
2. **External LDAP password not in the configuration template — where from?** Default: entered
   per client on the platform (or in the client CSV). Hosted address books need no password from
   anyone: every client gets its own LDAP login automatically.
3. **One phone per client, QR usable once?** Default: yes; moving to a new phone = "重新生成二维码"
   on the platform.
4. **Per-client values beyond SIP credentials?** Default: only the account name, display name and
   the three forwarding targets can differ per client; everything else comes from the template.
5. **Repository, binary and Docker image name.** Decided by the owner (2026-11-16, "平台名称就是
   siplink"): Go module, repository and binary `siplink`, Docker image `{user}/siplink:{tag}`,
   compose service `siplink`.

---

## Appendix A — findings in the app code relevant to the request

- `ShortcutManager.import` is refused while managed and only appends; there is no way yet to
  replace the handed-out keys while keeping `own` ones — needed (§9 item 6).
- `TlsManager` has only `import`/`remove`, `CodecManager` only per-codec calls,
  `DirectoryManager.save` makes a new UUID per directory, `AccountManager.add` does not return
  the new id: the loader needs replace/apply variants and key maps (§9).
- `ConfigLock.setManaged(false)` deliberately keeps what was handed out; "deleted on the
  platform" therefore needs an explicit wipe before unlocking (§9 item 7).
- The default account is locked (`AccountManager.setDefault` refuses while managed), which is
  consistent with the owner's list: what stays free is the dial pad's pick, which is UI state
  (`DialerScreen` `selectedId`, `rememberSaveable`) and is not kept across app restarts.
- The app does LDAP StartTLS only (`ldaps://` refused) and only to a host name
  (`STARTTLS_NEEDS_HOST_NAME`): the platform's LDAP must offer StartTLS (it always does, §4.8);
  whether StartTLS to a literal address can be allowed for certificates of the built-in CA is
  decided before P6 (§3.5.5).
- A configuration template can carry at most 50 directories (`LdapRules.MAX_DIRECTORIES`), so a
  phone can see at most 50 of the 500 hosted books; `maxResults` ≤ 1000.
- The Linphone SDK's LDAP provider does not fill a contact's organization, so the
  `organization` shown by `DirectoryContact` stays empty for LDAP results whatever the platform
  serves.
- The update-check setting is `Settings.autoUpdateCheck`; the document uses the same name
  (`settings.autoUpdateCheck`, §3.5.3).
