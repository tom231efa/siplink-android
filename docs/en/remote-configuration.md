<p align="right"><sub><a href="../remote-configuration.md">中文</a> | English</sub></p>

# Remote configuration

A management platform can provision accounts, directories, certificates, codecs, settings and shortcut keys to SipLink. The source code of the management platform is in the `platform/` directory of this repository; for deployment and use, see [Management platform](platform.md). The second half of this page gives the API and document format the app accepts, which can also be used to write a compatible configuration service.

## Loading

**Settings → Remote configuration**:

- **Scan QR code**: asks for camera permission the first time; QR codes that are not remote configuration addresses are not used
- **Enter address**: paste the address contained in the QR code

A confirmation dialog shows the source host; after **Load**, the app binds the phone to the platform and applies the configuration. A QR code address can be used only once; after binding, the device key the platform gives the phone keeps the later syncs going.

### Address format

```text
https://{host}[:{port}][/{prefix}]/p/{token}[?pin={fingerprint}]
```

| Part | Description |
| --- | --- |
| `{host}`, `{port}`, `{prefix}` | Platform address; the sync address defaults to `https://{host}[:{port}][/{prefix}]/api/device/v1/sync` |
| `{token}` | One-time binding token, characters limited to `A-Z a-z 0-9 _ -` |
| `{fingerprint}` | SHA-256 of the SubjectPublicKeyInfo of the platform CA certificate, base64url without padding (43 characters) |

Only `https://` is accepted (debug builds also accept `http://`), and the address must not contain user information.

### Trust at first scan (pin)

- With `pin` in the address: the server's certificate chain must contain a certificate whose public key fingerprint equals `pin`; every signature from the server certificate up to that certificate must be valid and every certificate within its validity period, and the subject alternative names (SAN) of the server certificate must include the host name or IP being accessed. That certificate is saved as the platform CA
- Without `pin`: the platform certificate must be issued by a CA built into Android
- Every later sync trusts the CAs built into Android and the platform CA saved at the first scan; CAs installed by the user are not trusted

On the remote configuration page, "Platform certificate" under "Details" shows "Platform CA trusted at scan" or "Verified by system CAs".

## After loading

Remote configuration replaces the whole configuration; the following existing content on the phone is deleted or overwritten:

| Content | What happens |
| --- | --- |
| SIP accounts | All replaced by the provisioned accounts |
| LDAP directories | All replaced |
| Imported CA certificates | All replaced |
| Codecs | Set to the provisioned order and switches; when no codecs are provisioned, the SDK defaults are restored |
| Settings | All set to the provisioned values except the ones the user keeps; fields that are not provisioned get the app defaults |
| Shortcut keys | Existing shortcut keys are replaced by the provisioned ones |

To keep the existing shortcut keys, use **Export as CSV** in the shortcut keys menu before loading. Call history is not affected.

Provisioned content is read-only on the phone: the top bar of each page shows a lock icon with "Managed remotely", and fields can only be viewed; trying to change one shows "Managed remotely; cannot be changed on this device". Bulk import of shortcut keys is not available, and shortcut keys of the "Call forwarding" type cannot be switched.

### What the user keeps

| Content | Description |
| --- | --- |
| Do not disturb switch | Still controlled by the user; the DND mode is provisioned by the platform |
| Appearance | Theme, dynamic color, call banner position |
| Lock screen call reminder | The "Don't remind me again" choice |
| Call history | |
| Call recording | The Recordings page (play, export, delete) and the number of recordings to keep |
| The user's own shortcut keys | Up to 50, placed before the provisioned shortcut keys; can be created, edited and deleted; in edit mode, only the user's own keys can be dragged, among themselves; only categories made entirely of the user's own keys can be renamed |
| Remote configuration page | Sync now, remove remote configuration |

Provisioned shortcut keys can still be used and exported.

### Configuration password

The platform can set a password for the configuration. When it is set, the passwords stored in accounts and directories are not shown directly: tapping the show button of a password field asks for the configuration password. When it is not set, they can be viewed like any other field.

## Sync

Syncs happen when the app starts; when the app returns to the foreground (at least 1 minute after the last attempt); when the network comes back after a failed sync; when **Sync now** is tapped; and at the interval provisioned by the platform (default 60 minutes, range 5–1440 minutes), shown on the page as "Syncs every N minutes".

- When a sync fails, the configuration stays as it is, and the sync is retried after 1, 2, 4… minutes, never longer than the sync interval; when the platform returns `Retry-After`, the app waits that long (also never longer than the sync interval)
- A faulty configuration is not applied at all, and the page shows "Invalid remote configuration: …"; when the configuration format version is higher than the app supports, it shows "Please update SipLink"
- When some content could not be applied, the page shows "Some settings could not be applied: …", and the next sync fetches the whole configuration again
- The page shows the platform, client, region, last sync time, configuration update time and sync result; "Details" shows the address, the first 12 characters of the configuration version and the source of the platform certificate

| Message | Meaning |
| --- | --- |
| Cannot reach the platform | Network or server unreachable |
| Platform certificate does not match the QR code | At the first scan, the certificate chain did not match `pin` |
| Platform certificate not trusted | The certificate failed verification against the system CAs or the saved platform CA |
| Remote configuration authentication failed | The platform returned 401 |
| QR code invalid, used or expired | The platform returned 404 to the bind request |
| Too many requests, try again later | The platform returned 429 |

## Revocation and removal

- **Revoked by the platform** (the platform returns 410): all provisioned content and the user's own shortcut keys are cleared, settings return to their defaults (what the user keeps stays), and the page shows "Remote configuration revoked by the platform"
- **Remove remote configuration**: tells the platform to unbind and clears the same content as a revocation; call history and appearance settings are kept. When the platform cannot be reached, the configuration is still removed on the phone, with the message "Remote configuration removed (the platform could not be notified)"

After clearing, the configuration belongs to the phone again and can be edited as usual.

## API and document format

The following is a reference for implementing a configuration service, taken from `core/logic/RemoteAddress.kt`, `RemoteSync.kt`, `RemoteDocument.kt`, `RemoteChecks.kt` and `core/RemoteConfigClient.kt`.

### Requests

All requests are `POST` with a JSON body:

| Item | Value |
| --- | --- |
| `Content-Type` | `application/json; charset=utf-8` |
| `Accept` | `application/json` |
| `User-Agent` | `SipLink/{app-version}` |
| `Authorization` | `Bearer {deviceKey}` (not sent with the bind request) |
| Timeouts | 10 seconds each for connect and read |
| Redirects | Not followed |
| Response size | At most 4 MiB |

| Request | Address | Body |
| --- | --- | --- |
| Bind | The QR code address without the query part | `{"installId": "...", "device": {...}}` |
| Sync | The sync address | `{"have": "{applied version, empty if none}", "device": {...}}` |
| Unbind | The sync address with the trailing `/sync` replaced by `/leave`; `/api/device/v1/leave` when the sync address does not end in `/sync` | `{}` |

`installId` is a UUID generated once per installation. `device` is the phone's state, without numbers, contacts, calls or location:

```json
{
  "appVersion": "0.1.2",
  "appVersionCode": 3,
  "androidRelease": "16",
  "androidSdk": 36,
  "model": "{manufacturer} {model}",
  "applied": { "version": "...", "at": "2026-11-16T08:00:00Z", "error": "" },
  "registrations": [ { "account": "{account-key}", "state": "OK", "error": "" } ],
  "codecs": { "audio": ["opus/48000/2"], "video": ["H264/90000/1"] }
}
```

`applied` is the document version, time (RFC 3339, UTC) and rejection reason (empty on success) of the latest attempt. `registrations[].state` is one of `DISABLED`, `NONE`, `PROGRESS`, `OK`, `FAILED`.

### Responses

| Status | Bind | Sync |
| --- | --- | --- |
| 200 | Returns the configuration document, which must contain `deviceKey` | Returns the configuration document |
| 204 | Failure | No change |
| 401 | Failure | Authentication failed; the current configuration is kept |
| 404 | Token invalid, used or expired | Failure, retried later |
| 410 | Failure | Configuration revoked |
| 429 | Failure | Failure; the retry is delayed by `Retry-After` (seconds) |
| Other | Failure | Failure, retried later |

The unbind request counts as received by the platform when it returns 2xx, 401 or 410.

As soon as the bind response contains `deviceKey`, the phone counts as bound, even if the document itself fails validation (later syncs fetch it again).

### Document

A missing or `null` field gets the app default (not the phone's current value); a wrong type, a value outside the enumeration or a failed check rejects the whole document, and all errors are reported at once. Fields the app does not know are ignored, at any level. For a full example, see `app/src/test/resources/platform-provision-testdata-full.json`; for a minimal one, `platform-provision-testdata-minimal.json`.

**Top level**

| Field | Type | Description |
| --- | --- | --- |
| `schema` | Integer | Must be `1`. When higher, the app shows "Please update SipLink"; when lower or missing, "Unsupported configuration format" |
| `version` | String | Required. When equal to the applied version, the document is not applied again |
| `generatedAt` | String | RFC 3339 time, shown as "Configuration updated" |
| `deviceKey` | String | Required in the bind response |
| `platform.name` | String | Platform name |
| `platform.syncUrl` | String | The sync address from now on; must be `https://` (`http://` allowed in debug builds) without user information, otherwise the current address stays |
| `client.name`, `client.region` | String | Client name and region, display only |
| `refreshMinutes` | Integer | Sync interval, default 60; values outside 5–1440 are clamped |
| `configPasswordHash` | String | SHA-256 of the UTF-8 configuration password, 64 hex digits, either case; empty for no password |
| `settings` | Object | See below |
| `accounts` | Array | See below |
| `defaultAccount` | String | `key` of the default account |
| `codecs` | Object | `{"audio": [...], "video": [...]}`, each item `{"key": "{mime}/{clock-rate}/{channels}", "enabled": true}`; when omitted, the SDK defaults are restored |
| `caCertificates` | Array of strings | CA certificates in PEM format; they replace the CAs imported on the phone |
| `directories` | Array | See below |
| `shortcuts` | Array | See below, at most 2000 |

**`settings`** (defaults as in the [user guide](user-guide.md#settings))

| Field | Type / values | Default |
| --- | --- | --- |
| `keepAlive` | Boolean | `true` |
| `intercomAutoAnswer` | Boolean | `false` |
| `intercomSpeaker` | Boolean | `true` |
| `autoAnswer` | Boolean: auto answer calls that are not intercom calls | `false` |
| `autoAnswerSeconds` | Integer: `0` / `3` / `5` / `10` / `20` / `30`; other values take the nearest one (the longer one on a tie) | `3` |
| `verifyTlsCertificate` | Boolean | `true` |
| `verifyTlsHostname` | Boolean | `true` |
| `mediaSecurity` | `NONE` / `SRTP_OPTIONAL` / `SRTP_MANDATORY` | `NONE` |
| `dndMode` | `SOFTWARE` / `SERVER` | `SOFTWARE` |
| `callWaiting` | Boolean | `true` |
| `rejectAnonymous` | Boolean | `false` |
| `dtmfMode` | `RFC2833` / `SIP_INFO` | `RFC2833` |
| `forwardAlways`, `forwardBusy`, `forwardNoAnswer` | String, empty for no forwarding | `""` |
| `forwardNoAnswerSeconds` | Integer, 5–120 | `20` |
| `features` | Object: `park`, `listen`, `whisper`, `barge`, `forceHangUp`, `pickup`, `groupPickup`, `voicemail`, `intercom`, `dndOn`, `dndOff` | See [Feature codes](user-guide.md#feature-codes) |
| `videoCalls` | Boolean | `true` |
| `videoSize` | `QVGA` / `VGA` / `HD` / `FHD` (240p / 480p / 720p / 1080p) | `VGA` |
| `videoFrameRate` | `0` (auto) / `15` / `24` / `30` | `0` |
| `autoUpdateCheck` | Boolean | `true` |
| `userAgent` | String, rules as for the [client name](user-guide.md#advanced-settings) | `""` |
| `adaptiveRateControl` | Boolean | `false` |
| `echoCancellation` | Boolean | `true` |
| `ipv6` | Boolean | `true` |
| `callRecording` | Boolean: whether calls may be recorded; when `false`, the record button is hidden, a recording in progress at the sync ends, and existing files are kept | `true` |
| `recordByDefault` | Boolean: every call is recorded once connected | `false` |

The do not disturb switch, theme, dynamic color, call banner position and number of recordings to keep are not in the document; they are always up to the user.

**`accounts[]`**

| Field | Type | Description |
| --- | --- | --- |
| `key` | String | Required, unique in the document, stable across versions; the phone uses it to keep the same account |
| `name`, `username`, `password`, `authUser`, `displayName`, `domain` | String | As in the [account fields](user-guide.md#fields) |
| `port` | String | Empty for the default |
| `server`, `backupServer`, `dnsServer` | String | Proxy, backup proxy, DNS server |
| `transport` | `UDP` / `TCP` / `TLS` | Default `UDP` |
| `expires` | Integer | 60–86400, default 3600 |
| `subscribeMwi` | Boolean | Default `false` |
| `enabled` | Boolean | Default `true` |

Accounts are checked by the same rules as manual entry: at most 100, and neither names nor user name + domain pairs may repeat.

**`directories[]`**

| Field | Type | Description |
| --- | --- | --- |
| `key` | String | Required, unique in the document |
| `line` | String | `key` of the account it belongs to; when empty or pointing to an account that does not exist, the directory is used for all lines |
| `name`, `host`, `bindDn`, `password`, `baseDn` | String | As in the [directory fields](user-guide.md#ldap-directories) |
| `port` | Integer | Default 389 |
| `security` | `NONE` / `START_TLS` | Default `NONE` |
| `enabled`, `lookupIncoming`, `lookupOutgoing` | Boolean | Default `true` |
| `maxResults` | Integer | 1–1000, default 50 |
| `nameAttributes`, `numberAttributes` | String | Defaults `cn sn` and `telephoneNumber mobile` |
| `nameFilter`, `numberFilter` | String | Empty for auto; when not empty, must contain `%` (the SIP stack's `%s` form is accepted too) |

At most 50, and names must not repeat.

**`shortcuts[]`**

| Field | Description |
| --- | --- |
| `label`, `number`, `category` | As in the [shortcut key fields](user-guide.md#fields-and-limits) |
| `action` | The type as written in CSV: `speed` / `bxfer` / `axfer` / `dtmf` / `forward` / `dnd`; empty for `speed` |
| `line` | Account name or `user@domain` (not the account `key`); empty for choosing when calling |
| `icon` | The icon as written in CSV; empty to follow the type |

### Version compatibility

- Documents only gain fields. The app ignores fields it does not know and uses defaults for missing ones, so a configuration service sends the same document to clients of every version and does not need to tell versions apart
- The meaning and value range of existing fields do not change; when one has to change, `schema` is raised, and older versions of the app show "Please update SipLink"
- The app reports its version in `device.appVersion`, `device.appVersionCode` and `User-Agent` on every request, which a configuration service can record and display

### Applying the document

- Accounts, directories, CAs and provisioned shortcut keys that are not in the document are deleted; accounts and directories with the same `key` keep their identity on the phone
- Codecs: codecs that the document lists and the phone has come first, in the document's order and with its switches; the phone's other codecs follow, disabled; codecs the phone does not have are ignored. When the result has no enabled audio codec, the SDK default codecs are restored and "Some settings could not be applied" is reported
- Accounts are unregistered before the rest is applied, so that TLS accounts unregister with their existing certificates
