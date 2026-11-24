<p align="right"><sub><a href="zh-CN/platform.md">中文</a> | English</sub></p>

# Management platform

[Home](../README.md) · [Features](features.md) · [Remote configuration](remote-configuration.md)

The SipLink management platform provisions configuration to the SipLink Android client from one place: accounts, call settings, codecs, CA certificates, directories and shortcut keys. A phone binds by scanning a QR code in SipLink → Settings → Remote configuration and syncs regularly afterwards. The platform also provides an LDAP directory service.

The platform is a Go program with a web console; it keeps its data in SQLite and is published as a Docker image. The source code is in the [`platform/`](../platform) directory of this repository; the design is in [platform/docs/DESIGN.md](../platform/docs/DESIGN.md).

**Back up the key file first.** On its first start, the platform generates a 64-byte key file (`SIPLINK_KEY_FILE`; `/run/secrets/siplink-key` in a Compose deployment). The SIP passwords, LDAP passwords and the private key of the built-in CA in the database are encrypted with it. Database backups do **not** contain the key file; without the key file, a backup cannot be used. Copy the key file to a place separate from both the server and the database backups, readable by administrators only.

## Deployment (Docker)

Image: `ghcr.io/tom231efa/siplink` (linux/amd64, linux/arm64), tagged with the version number and `latest`.

1. Create a directory, download [`platform/compose.yaml`](../platform/compose.yaml) into it, and prepare the data directory and the key file (keep the key file outside that directory):

   ```sh
   mkdir -p data
   head -c 64 /dev/urandom > {key-file-path}
   chmod 400 {key-file-path}
   sudo chown 10001:10001 data {key-file-path}
   ```

   Replace `{key-file-path}` with the real path, for example `/srv/siplink/siplink-key`. The user inside the container is uid 10001.

2. Write `.env` next to `compose.yaml`:

   ```sh
   SIPLINK_PUBLIC_URL=https://{platform-domain}
   SIPLINK_KEY_PATH={key-file-path}
   # Optional: pin the image version (default latest)
   # SIPLINK_TAG={version}
   # Optional: the name phones use for LDAP (default: the host name of SIPLINK_PUBLIC_URL)
   # SIPLINK_LDAP_PUBLIC_HOST={platform-domain}
   ```

   Replace `{platform-domain}` with a domain name the phones can reach, and `{version}` with the image version to use.

3. Start: `docker compose up -d`. HTTPS is on port 443, LDAP on port 389.

4. Create the first super administrator (the one-time password is printed only in the terminal and must be changed at the first sign-in; the command prints English unless a language is set, see [Languages](#languages)):

   ```sh
   docker compose exec siplink siplink admin create --super {user-name}
   ```

The platform has no web setup page: until an administrator exists, there is nothing on the web that someone else could claim first.

### Upgrading

```sh
docker compose pull
docker compose up -d
```

Back up before upgrading (see [Backup and restore](#backup-and-restore)).

### Building the image from source

```sh
docker build -t ghcr.io/tom231efa/siplink:dev platform
```

Run it in the repository root, then set `SIPLINK_TAG=dev` in `.env`.

### Configuration options

Common environment variables (for the full list, see [platform/docs/DESIGN.md](../platform/docs/DESIGN.md) §7.4):

| Variable | Default | Purpose |
|---|---|---|
| `SIPLINK_PUBLIC_URL` | Required | Public address used in QR codes and sync addresses |
| `SIPLINK_TLS` | `auto` | `auto`: the platform serves HTTPS itself; `off`: a reverse proxy serves HTTPS (see `SIPLINK_TRUSTED_PROXIES`) |
| `SIPLINK_TLS_CERT_FILE` / `SIPLINK_TLS_KEY_FILE` | Empty | Use own certificate files (they take precedence over a certificate uploaded on the "System" page and over the built-in CA) |
| `SIPLINK_TLS_EXTRA_NAMES` | Empty | Extra names in the server certificate issued by the built-in CA (comma-separated) |
| `SIPLINK_LDAP_PUBLIC_HOST` / `_PORT` | Host name of the public address / `389` | LDAP address provisioned to the phones |
| `SIPLINK_LDAP_REQUIRE_TLS` | `true` | LDAP sign-in (bind) requires StartTLS first |
| `SIPLINK_ENROLL_DAYS` | `30` | Days a QR code stays valid |
| `SIPLINK_BACKUP_TIME` / `SIPLINK_BACKUP_KEEP` | `03:30` / `14` | Time of the daily automatic backup and number of backups kept |

## Certificates

Each service (HTTPS, LDAP) picks its certificate in this order: the certificate files named by environment variables → an own certificate uploaded on the "System" page (it must contain the service's name: for HTTPS the host name of `SIPLINK_PUBLIC_URL`, for LDAP `SIPLINK_LDAP_PUBLIC_HOST`) → a certificate issued by the built-in CA. When no service uses a certificate of the built-in CA, automatic issuing pauses; the "System" page shows the current mode.

### Built-in CA and browsers

Without configured certificates, the platform issues its own HTTPS and LDAP certificates with the built-in CA. The CA is generated at the first start, is valid for 20 years and is never generated again (otherwise every phone would have to scan again); server certificates are renewed automatically when fewer than 30 days remain or the names change.

- **Phones** do not need to import the CA: the QR code carries the fingerprint of the CA's public key (`pin=`), and the phone trusts the platform by it at the first scan.
- **Browsers** warn that the connection is not secure until the CA is imported. The CA certificate can be downloaded at "System → Certificates → Download CA certificate", or exported with a command:

  ```sh
  docker compose exec siplink siplink pki export-ca /tmp/siplink-ca.pem
  docker compose cp siplink:/tmp/siplink-ca.pem .
  ```

  How to import it:
  - Windows (Edge, Chrome): double-click the certificate → Install Certificate → choose "Trusted Root Certification Authorities".
  - macOS: import it into the "System" keychain with Keychain Access, open the certificate and set "When using this certificate" to "Always Trust".
  - Firefox: Settings → Privacy & Security → Certificates → View Certificates → Authorities → Import, and tick "Trust this CA to identify websites".
  - Debian / Ubuntu: copy it to `/usr/local/share/ca-certificates/siplink-ca.crt` and run `sudo update-ca-certificates`.

  Check the SHA-256 fingerprint shown on the "System" page before importing.
- "Reissue server certificate" on the "System" page replaces only the server certificate; the CA stays the same and phones do not need to scan again. Command line: `siplink pki show`, `siplink pki renew`.

### Using an own certificate

A super administrator switches to "Own certificate" in "System → Certificates", then uploads the certificate chain file (PEM, server certificate first, intermediate certificates after it; for a private CA, append the root certificate at the end) and the **unencrypted** private key file (PEM) under "Upload own certificate". The upload takes effect at once, without a restart; the private key is stored encrypted in the database (and goes into backups) and can never be viewed or downloaded again. Command line:

```sh
docker compose exec -T siplink sh -c 'cat > /tmp/chain.pem' < chain.pem
docker compose exec -T siplink sh -c 'umask 077; cat > /tmp/key.pem' < key.pem
docker compose exec siplink siplink pki import /tmp/chain.pem /tmp/key.pem
docker compose exec siplink rm /tmp/key.pem /tmp/chain.pem
```

(The container's root file system is read-only and `/tmp` is a memory disk, so the files are written through standard input. When asked for confirmation, run `pki import` again with `--yes` added; a running platform starts using it within a minute.)

- **Checks**: the certificates must parse, the private key must match the server certificate, each certificate must be issued by the next one, all must be valid now and allowed for server authentication, and the host name of the public address must be included. Otherwise the page gives the reason and the certificate is not used.
- **How phones trust it**: when the chain is trusted by public CAs, the QR code carries no `pin` and the phone verifies against Android's CAs; when the chain ends in a private root certificate, the QR code carries that root's `pin` and the configuration document provisions it, as with the built-in CA. A chain that is neither trusted by public CAs nor ends in its root certificate is refused.
- **Phones already bound** trust only the CA remembered at the first scan and Android's CAs. After switching to a certificate that was neither issued by that CA nor is trusted by public CAs (or from such a certificate back to the built-in CA), these phones cannot sync and need a new QR code and a new scan; the platform first states how many phones are affected and applies the change only after confirmation. When the `pin` in the QR code changes, printed QR codes must be printed again too.
- **LDAP**: when the certificate does not contain the LDAP address, LDAP keeps using the certificate of the built-in CA, and automatic issuing does not pause. The platform warns when the chain contains a certificate with an empty Authority Key Identifier (AuthorityKeyIdentifier): the phone's SIP/LDAP TLS library (mbedTLS 3.6.0) rejects such certificates.
- **Expiry warnings**: "Days of warning before expiry" defaults to 30 (1–365). Once an own certificate (including certificate files) enters the warning period, the "System" page and the super administrators' "Overview" show a warning, and the log records a warning every day; after expiry they show "Certificate expired; phones cannot sync". `siplink health` (the Docker health check) stays healthy during the warning period and becomes **unhealthy after expiry**. Certificates of the built-in CA are renewed automatically and have no warnings.
- **Back to the built-in CA**: "Use built-in CA" in "System → Certificates → Own certificate", or `siplink pki use-builtin`, returns to the built-in CA, and automatic issuing resumes at once; the number of phones that need to scan again is shown first, and the change applies only after confirmation.
- "Built-in CA / Own certificate" at the top of the "Certificates" card only switches what is displayed and does not change the certificate in use; the side in use carries a mark, and the page opens on it.

## Hosted directories and the LDAP address

The LDAP library on the phone can verify server certificates only by **domain name**, so:

| `SIPLINK_LDAP_PUBLIC_HOST` | `SIPLINK_LDAP_REQUIRE_TLS` | Hosted directories |
|---|---|---|
| Domain name | Any | Provisioned with StartTLS |
| IP address | `true` (default) | Cannot be added to configuration templates; the page and the start-up log give the reason |
| IP address | `false` | Provisioned unencrypted; the configuration template page marks them "Unencrypted" |

For encrypted hosted directories, give the platform a DNS name and set `SIPLINK_LDAP_PUBLIC_HOST`. External LDAP directories are not limited by this, but they follow the same phone rule: StartTLS needs a domain name.

## Directories

The "Directories" page manages both kinds of directory in one list, searchable by name, identifier or server address, and filterable by kind:

- **Internal**: a directory hosted by the platform and served by the platform's LDAP service; its contacts are maintained, imported and exported on the page.
- **External**: connection details of another LDAP server: server, port, encryption, sign-in name (Bind DN), password mode, Base DN, max results, attributes and filters.

The kind is chosen in "New directory" and cannot be changed later. Password modes of an external directory:

- **Anonymous**: no sign-in.
- **Shared**: the password is stored on the platform (encrypted) and is the same for every client; it is not shown again after saving, and leaving it empty keeps it unchanged.
- **Per client**: entered client by client on the client page or in the client CSV import.

In the "Directories" part of a configuration template, "Add directory" picks a global directory or one of the template's region, and sets the name shown on the phone (empty = the directory's name), the line, lookup for incoming / outgoing calls and whether it is enabled; internal directories also have max results and credentials (automatic or an LDAP account). Once chosen, the directory cannot be swapped for another; to change it, remove it and add the other one. Changes to an external directory reach the configuration templates that use it at the clients' next sync. A directory used by a configuration template cannot be deleted; the page lists the templates that use it.

Upgrade note: external LDAP entered directly in configuration templates in older versions becomes external directories after the upgrade (entries in the same region with identical connection details and passwords are merged into one; on a name clash a suffix such as `（2）` is added); the configuration the phones receive does not change. If a template stored an external LDAP password, the upgrade needs the original key file; otherwise the database is not upgraded and the platform does not start.

## Groups and tags

The page "Devices → Groups and tags" manages the groups and tags of clients. They only organize clients in the console and do not affect the configuration provisioned to phones.

- **Groups**: a group belongs to one region; a client is in at most one group of its region. Names are unique within a region (in any case), at most 100 per region. Region administrators see and manage the groups of their own regions only. A client moved to another region leaves its group. Deleting a group leaves its clients without a group.
- **Tags**: one list shared by all regions; names are unique (in any case) and cannot contain semicolons; the color is one of a fixed few. At most 500 tags, and 10 per client. Every administrator can create tags and put them on or take them off clients they may edit; once a tag is on clients of other regions, a region administrator can no longer change or delete it.
- **Client list**: filter by group (including "No group") and by tags; with several tags chosen it shows the clients that carry all of them. The filters are kept in the address. With clients ticked, the bulk bar sets a group, adds a tag or removes one; if any of the chosen clients or the group is not one the administrator may use, the whole action is refused and nothing changes.
- **CSV import**: optional columns `group` and `tags` (Chinese `分组`, `标签`). The group must exist in the line's region; several tags are separated by `;` or `；`, and unknown tags are created (gray) when the import is confirmed. Older files without these columns import as before.
- **Audit log**: records the creation, change and deletion of groups and tags, and every change of a client's group or tags (one entry per client in bulk actions).

## Two-step verification

Administrators turn on two-step verification themselves in "My account": a second check after the password. Available methods:

- **Authenticator app** (TOTP, 30 seconds, 6 digits, for example Google Authenticator or Microsoft Authenticator): scan the QR code or enter the secret, and enter one code to turn it on.
- **Passkeys** (WebAuthn): several can be added. They need a secure address: `SIPLINK_PUBLIC_URL` with `https://` and a domain name, or `http://localhost`; with an IP address or another `http://` address they are not offered. The browser must reach the platform at exactly `SIPLINK_PUBLIC_URL` and trust the platform certificate (with the built-in CA, import the CA first, see above).
- **E-mail code**: needs an e-mail address entered and confirmed in "My account" and a configured mail service. A code is valid for 10 minutes and allows at most 5 wrong attempts; at most 1 code is sent per minute and 5 per hour.

Turning on the first method generates 10 **recovery codes**, which can be viewed for 5 minutes or until "Done" is clicked and are not shown again; each works once and can replace any method. Turning on the first method also ends all other sessions of that administrator. Turning a method off, deleting the last passkey, generating new recovery codes and changing the e-mail address all ask for the password again. After 5 failed verifications in a row (including wrong passwords when re-entering it and when viewing client passwords), the administrator cannot verify for 15 minutes.

Order at the first sign-in: password → two-step verification (when on) → change the one-time password → turn on two-step verification (when required).

**Requiring it for region administrators**: a super administrator turns on "Region administrators must use two-step verification" in "System → Two-step verification". From then on, region administrators without it can only open "My account" after signing in, can use the other pages only after turning on a method, and cannot turn off their last method. Super administrators are not affected.

**Super administrators**: a super administrator's e-mail address can only be changed by that administrator in "My account"; the role of a super administrator with two-step verification on cannot be changed. Another super administrator can still "Reset password" or disable the account, but with two-step verification on, the new password alone does not sign in.

**When the only method is unusable** (for example, passkeys stop working after the address changes to an IP, or the mail service is not configured), sign-in still requires two-step verification; use a recovery code or the reset below.

**When an administrator cannot sign in**:

- Region administrators: a super administrator uses "Reset two-step verification" in "Administrators → Edit".
- Any administrator (super administrators included): run on the server

  ```sh
  docker compose exec siplink siplink admin reset-2fa {user-name}
  ```

  Replace `{user-name}` with the administrator's user name. All methods and recovery codes are deleted and all of the administrator's sessions end; the e-mail address stays but must be confirmed again.

## Administrator passwords

When "System → Security → Password strength check" is on (changeable by super administrators, on by default), new passwords that administrators set in "My account" must be 10–256 characters long and differ from the user name; when it is off, they only have to be non-empty and at most 256 characters. One-time passwords generated by the platform are not affected. The same switch sets the minimum length of the encryption passphrase for downloading encrypted backups.

## Mail

Super administrators configure SMTP on the "Mail" page: server, port, encryption (STARTTLS / TLS / None), user name, password, sender address and sender name. The password is stored encrypted and not shown again after saving; leaving it empty keeps it unchanged, and clearing the user name deletes the password as well; changing the server, port or encryption requires entering the password again (unless no user name is set). When "Send test e-mail" fails, the server's reply is shown; on other pages a failure shows only "Sending e-mail failed.", with the reason in the log.

- The server certificate is verified against the system root certificates, and the host name must match the certificate; with STARTTLS, the server must support STARTTLS.
- With "None", the user name and password cannot be used to sign in (except for the local `localhost`).
- Mail is currently used only for two-step verification codes.

## Backup and restore

- Every day at `SIPLINK_BACKUP_TIME` (container time zone), an automatic backup is written to `data/backups/siplink-{date}.db`, keeping `SIPLINK_BACKUP_KEEP` copies. The "System" page has "Back up now".
- Command line: `docker compose exec siplink siplink backup /data/backups/{file-name}.db`.
- Restore: stop the container, copy the backup file to `data/siplink.db` (delete the old `siplink.db-wal` and `siplink.db-shm`), and start with **the same key file**. Without the original key file, the encrypted passwords cannot be used, and recovery codes and e-mail codes for two-step verification cannot be verified either (`reset-2fa` is needed).
- The key file is not in the backups, see the first part of this page.

### Downloading backups

A super administrator clicks "Download" next to a backup in the "System → Backups" list, enters the current password in the dialog that opens, and downloads.

- **Encrypted** (on by default): enter the encryption passphrase twice (at least 10 characters when "Password strength check" is on, at most 256 characters) and download `{backup-name}.db.age`, in [age](https://github.com/FiloSottile/age) format (passphrase encryption). The platform does not keep the passphrase; without it, the file cannot be opened.
- **Unencrypted**: downloads `{backup-name}.db`, the same as on the server. The file contains the whole configuration, administrator password hashes and the encrypted passwords; keep it safe.
- The downloaded file holds only the database backup, not the key file; restoring still needs the original key file. Every download is recorded in the audit log (file name, encrypted or not); the passphrase is not recorded.

To open an encrypted backup (needs age 1.0 or later; on Debian / Ubuntu `sudo apt install age`, for other systems see age's Releases page):

```sh
age -d -o {backup-name}.db {backup-name}.db.age
```

Replace `{backup-name}` with the downloaded file name (without the extension); the command asks for the encryption passphrase. Without age, the same program can run in the official Go image:

```sh
docker run --rm -it -v "$PWD":/w -w /w golang:1.27.4-trixie go run filippo.io/age/cmd/age@v1.3.2 -d -o {backup-name}.db {backup-name}.db.age
```

To restore from a downloaded backup:

```sh
docker compose stop siplink
sudo cp {backup-name}.db data/siplink.db
sudo chown 10001:10001 data/siplink.db
sudo chmod 600 data/siplink.db
sudo rm -f data/siplink.db-wal data/siplink.db-shm
docker compose start siplink
```

Replace `data/` with the directory mounted at `/data` in the container in Compose; the key file must be the same file used by the platform that made the backup.

## Daily use

- Order: region → directories (optional, internal or external) → configuration template (accounts, settings, chosen directories) → key template (optional) → client template → clients → print or show the QR codes.
- Clients can be imported in bulk: "Clients → Bulk import" offers the CSV header of each client template for download, checks first and then asks for confirmation. A file holds at most 2,000 clients and 4 MB.
- Names are sorted by Unicode code point, **not by pinyin**; homophones and common characters may sort differently from what you expect.
- The client page shows the SipLink version, version code and User-Agent reported by the phone. The platform provisions the same configuration to all versions; older versions ignore fields they do not know.
- Administrators must re-enter their own password before viewing a password stored for a client, and every view is recorded in the audit log.
- The "Advanced settings" of a configuration template set the maximum participants of the conference room (2–30, default 30), whether the lines adapt to the network automatically (on by default) and a STUN server (a host name or IP address, optionally with a port; empty for none). A template that keeps the defaults provisions exactly the configuration it did before; older app versions ignore these settings.
- An account of a configuration template can set a "Fast timeout (ms)": empty for off (the default), otherwise 200–10000 milliseconds. When the account has a backup server, a server that does not answer within this time is taken as out of reach and the account moves to the other server at once; without a backup server the value is stored and provisioned but has no effect. An account without a fast timeout provisions exactly the configuration it did before.

## Languages

The web console and the command line come in eight languages, each listed by its own name: 中文（简体）, English, 日本語, العربية (right to left), Français, 中文（繁體）, Русский, Español.

- **Switching**: "Language" under "Appearance" in the user menu; a drop-down at the foot of the sign-in page and the two-step verification page.
- **Order**: the administrator's saved language → the browser's earlier choice (cookie) → the browser's language (`Accept-Language`) → English when none matches. A choice made in the menu is saved for the administrator and follows them to other browsers; a language chosen before signing in becomes the administrator's language at sign-in if they have none saved.
- **Mail**: code mails use the language of the administrator they go to; the test mail uses the sender's language.
- **Client CSV import**: still accepts the Chinese column names, or instead the English ones `name`, `region`, `client_template`, `note`, `group`, `tags`, `{profile} username`, `{profile} password`, `{profile} auth user`, `{profile} display name`, `{directory} LDAP password` (`{profile}` and `{directory}` are the accounts and directories of the configuration template; the English names in any case). A file uses one language: with a `名称` column it is read by the Chinese names and English names in it are extra columns that are ignored; otherwise it is read by the English names. When a column fits two fields (an account "X LDAP" and a directory "X" both give `X LDAP password`) or a field has two columns, the whole file is refused and the column named; the header of such a client template cannot be downloaded either, until the account or directory is renamed. The downloaded template has Chinese column names when the console is in Simplified or Traditional Chinese, English ones otherwise; the same applies to the CSV exported from an internal directory.
- **Command line** (`siplink` inside the container): English by default; the language comes from `LC_ALL`, then `LC_MESSAGES`, then `LANG`. For Chinese output:

  ```sh
  docker compose exec -e LANG=zh_CN.UTF-8 siplink siplink admin create --super {user-name}
  ```

- **Audit log**: entries written before this version keep their original Chinese text; new entries are shown in the viewer's language.
- **Right-to-left pages** (العربية): names of clients, groups, tags, templates and the like are shown in their own direction; a Chinese or Latin name runs left to right and, when too long, fades out at its end.
- The configuration documents provisioned to phones and the SipLink app are not affected by the console language.
