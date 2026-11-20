<p align="right"><sub><a href="zh-CN/user-guide.md">中文</a> | English</sub></p>

# User guide

## Contents

- [Interface](#interface)
- [Accounts](#accounts)
- [Dialing and calls](#dialing-and-calls)
- [Incoming call handling](#incoming-call-handling)
- [Conference room](#conference-room)
- [Video calls](#video-calls)
- [Feature codes](#feature-codes)
- [Shortcut keys](#shortcut-keys)
- [LDAP directories](#ldap-directories)
- [Call history](#call-history)
- [Call recording](#call-recording)
- [Settings](#settings)
- [Battery](#battery)
- [Updates](#updates)

When the configuration is provisioned by the management platform, most settings are read-only on the phone, see [Remote configuration](remote-configuration.md).

## Interface

The navigation has four tabs: **Dialer**, **History**, **Directory**, **Settings**, at the bottom in portrait and on the left in landscape (a window narrower than 600 dp, as in split screen, keeps them at the bottom). Shortcut keys open from the shortcut keys button on the dialer, and from the call screen during a call.

During a call, the other pages show a banner that returns to the call; its position is set in **Settings → Appearance → Call banner position** (Top / Bottom, default Bottom).

## Accounts

**Settings → SIP accounts**. Up to 100 accounts. The list shows how many are registered, failed and disabled, and can be searched.

### Fields

| Field | Description |
| --- | --- |
| Account name (optional) | Used only on the phone, never sent to the server; up to 30 characters, must differ from the other account names (case-insensitive). Shortcut keys can refer to a line by this name |
| User name | Required |
| Port | Empty: the transport's default port, or the port in the domain's DNS records |
| Domain / server | Required; host name, IPv4 or `[IPv6]` |
| Password | When editing an account, leave empty to keep the current password |
| Display name (optional) | |
| Transport | UDP / TCP / TLS. An empty or default port follows the transport (5060 for UDP and TCP, 5061 for TLS) |

**Advanced settings** (collapsed):

| Field | Description |
| --- | --- |
| Auth user name (optional) | Fill in when it differs from the user name |
| Proxy (optional) | `host[:port]`; registrations and calls are sent here; when empty, to the domain |
| Backup proxy (optional) | `host[:port]`, must differ from the primary server. When the primary server cannot be reached, the account switches to the backup; it switches back when the primary recovers and there is no call. Accounts using the backup are marked "Backup server" in the list |
| DNS server (optional) | `IP[:port]` (IPv4, default port 53). This account's server addresses are resolved here instead of through the system DNS |
| Registration expiry (seconds) | 60–86400, default 3600 |
| Subscribe to voicemail | Off by default. When on, the phone subscribes to message notifications from the PBX (SUBSCRIBE); not needed when the PBX sends them unasked |
| Enable this account | Disabled accounts do not register and are not used for outgoing calls |

While typing: a `sip:` / `sips:` prefix is removed; `user@domain` typed as the user name is split when the domain is empty; a `:port` written after the domain moves to the port field; a full-width colon counts as a normal colon. Two accounts cannot have the same user name and domain.

The account editor offers **Set as default** and **Delete account**. After an account is deleted, shortcut keys set to that line ask for a line when used.

**Line diagnosis** in the account editor (also available when the configuration is managed remotely) checks the line on the current network: network type, local and public address, NAT type, battery optimization; what the server name resolves to, the UDP response time, large outgoing packets (about the size of an INVITE with ICE candidates, more than one packet; "分片丢失" when only its fragments are dropped), TCP and TLS connections, a suspected SIP ALG, and whether the PBX gave an address of its own internal network as the address of its last call (shown as "通话地址 内网": the ACK and BYE of its calls then never reach the PBX, which hangs up after about half a minute; the PBX needs its external address set, the phone cannot repair this); the registration; the direction of the sound of the last call, its loss, jitter and round trip; and what [automatic network adaptation](#advanced-settings) currently changes. **Restore defaults** clears the account's adaptations. The diagnosis only sends check requests and changes no settings; for an account set to TLS it sends no request carrying the account over UDP: the UDP row shows "未测" and the NAT type is told by the STUN server alone.

### TLS and SRTP

With TLS as the transport, the server certificate must be issued by a trusted CA. The root certificate of a private CA is imported in **Settings → TLS and CA certificates**, see [Settings](#tls-and-ca-certificates). Media encryption (SRTP) is set on the same page.

### Bulk import

The import button at the top right of the account list. Paste text or choose a CSV file (only the first 512 KB of a file is read), one account per line:

```text
user name,password,domain[,transport[,display name[,proxy[,account name]]]]
```

- Transport: `udp` / `tcp` / `tls`; empty means `udp`
- Empty lines and lines starting with `#` are ignored; a BOM at the start of the file is ignored
- Put a field that contains a comma in double quotes, and write a double quote inside a field as `""`
- Each line is checked by the same rules as manual entry; faulty lines are reported one by one (at most 8 are listed) and the other lines are imported; lines beyond the 100-account limit are skipped

Example:

```text
# user name,password,domain,transport,display name,proxy,account name
1001,"pass,word",pbx.example.com,tls,Reception,,Reception
1002,pw1002,pbx.example.com
```

## Dialing and calls

### Dialer

- Choose the outgoing account at the top, or "Use default account"
- Enter the number with the dial pad or the keyboard; extensions and SIP addresses are accepted
- Next to the call button are the video call button (when video calls are on) and the shortcut keys button
- The call features button opens the call features panel, see [Feature codes](#feature-codes)
- With do not disturb on, "Do not disturb is on" is shown; tap it to turn it off. With new voicemail, "N new voicemails" is shown; tap it to dial the voicemail feature code
- In landscape, the account, reminders, number and call button are on one side and the dial keys on the other

### Call screen

| Control | Description |
| --- | --- |
| Mute | |
| Keypad | Sends DTMF |
| Audio route | Earpiece, speaker, Bluetooth, wired headset |
| Hold / Resume | |
| Transfer | Enter the target, then choose **Blind transfer** or **Attended transfer** |
| Park | Blind-transfers the call to the park feature code; the PBX announces the parking slot, then the call ends. Not available with a second call |
| Swap | Switches between the current call and the held call |
| Merge | Opens a conference room with the current call and the held call, see [Conference room](#conference-room) |
| Shortcut keys | Opens the shortcut keys panel |
| Video | Adds or removes video, see [Video calls](#video-calls) |
| Conference room (top left) | Opens a conference room, see [Conference room](#conference-room) |
| Record (top right) | Starts / stops recording and shows the recorded time while recording; in video calls it stands in the top row with the camera buttons. See [Call recording](#call-recording) |

**Attended transfer**: the current call is put on hold and the target is called first; once connected, tap "Complete transfer" to transfer the held party to the target.

**Listen / whisper / barge**: a supervisor call started with a feature code can switch between the three modes on the call screen; switching sends DTMF `4` / `5` / `6` to the PBX, to that call only.

The interface shows at most two calls at a time (the current call plus one held or waiting call), except in a conference room.

In landscape, the in-call keypad takes the side of the controls and covers the hang-up button until it is closed (as in portrait).

### Call waiting

A call that arrives during a call shows "New call", with **Decline new call** or **Hold and answer**. With call waiting turned off in **Advanced settings**, such calls are rejected as busy (or forwarded, when forward when busy is set).

### Intercom

An incoming call that asks to be answered automatically is treated as an intercom call. Recognized SIP headers:

- `Call-Info` containing `answer-after=`
- `Alert-Info` containing `alert-autoanswer`, `auto answer` or `intercom`
- `Answer-Mode: Auto`

With **Settings → Intercom and auto answer → Auto answer intercom calls** on (off by default), intercom calls are answered at once; the setting applies to intercom calls only. When the app is not in the foreground, it shows the call screen first and then answers (Android does not let background apps use the microphone). With a call (held calls included) or a conference room up, intercom calls are not answered automatically either. **Use speaker for intercom** (on by default) decides whether intercom calls use the speaker. To start an intercom call, use the feature code, see [Feature codes](#feature-codes).

### Auto answer

**Settings → Intercom and auto answer → Regular calls → Auto answer** (off by default) applies to calls that are not intercom calls and is independent of the intercom switch. When on, choose the **Auto answer delay**: Immediately / 3 / 5 / 10 / 20 / 30 seconds (default 3 seconds), counted from the start of ringing; the call is answered with audio.

- Only calls that arrive when there is no other call are answered; a second call arriving during a call is not answered automatically, not even after the first call ends
- Do not disturb, rejecting anonymous calls and forwarding still apply before ringing; when forward on no answer and auto answer are both set, whichever time comes first applies
- When the app is not in the foreground, it shows the call screen first and answers only once the screen is shown and the time is up

## Incoming call handling

Incoming calls are handled in this order; the first match applies:

| Order | Condition | Result |
| --- | --- | --- |
| 1 | Do not disturb on, mode "Software" | Rejected by the phone |
| 2 | The caller hides the number and "Reject anonymous calls" is on | Rejected with SIP 433 Anonymity Disallowed |
| 3 | Forward always is set | Forwarded |
| 4 | Already in a call (held calls included) or a conference room | Forwarded if forward when busy is set; otherwise a waiting call if call waiting is on and there is no second call; otherwise rejected as busy |
| 5 | Otherwise | Rings; with forward on no answer set, forwarded after the wait time |

A waiting call is never answered automatically (intercom calls included); with a conference room open, the only choices are **Join room** and **Decline**.

Forwarding is done by the phone with a SIP redirect and works only while the app runs and is registered. A forwarding target that is only a number gets the domain of the receiving account.

**DND mode** (Advanced settings):

- **Software** (default): the phone rejects all incoming calls
- **Server**: turning do not disturb on or off dials the PBX's "DND on" / "DND off" feature code, and the PBX handles incoming calls. Available only when both feature codes are set; changing the mode turns do not disturb off

## Conference room

A conference room brings several calls together on the phone: the phone mixes the audio, and on the PBX each member is an ordinary call (signaling always goes through the PBX; media does too, unless the PBX uses direct media). Conference rooms are voice only.

- **Open**: once a call is connected, tap **Conference room** at the top left of the call screen; the current call (and the held call) go into the room. **Merge** opens a room as well. In a video call, the app first confirms "The conference room is voice only; video will stop."
- **Size**: up to 30 members by default (not counting the phone), set to 2–30 in **Settings → Advanced settings → Conference room → Maximum members**; read-only when the configuration is managed remotely. The title shows the count and the limit, such as `3/30`; when the room is full, **Add members** is grayed out
- **Add members**: choose from "Recent calls", "Directory" (the first enabled LDAP directory) or "Shortcut keys" (speed dial keys), or type a number and add it; several can be picked, then tap **Call**. Numbers already in the room are not called again
- **Calling**: the phone calls at most 3 members at a time; the others show "Waiting" and are called in turn
- **Members**: each row shows the state (Waiting, Calling, Ringing, Connected, On hold), and connected members also three call quality bars; each member can be hung up on their own
- **End**: **End conference** hangs up all members, after a confirmation when more than one member is in the room (also when hanging up from the notification). The room ends when the last member leaves
- **New call**: the only choices are **Join room** and **Decline**
- **Another app takes the audio** (for example a mobile network call): the phone leaves the room's audio and shows "Away", while the other members keep talking; tap **Return to room** to go back
- When the PBX ends all calls or the network is lost, the room ends cleanly; if the system ends the app's process during a room, the room ends with it
- **Limits**: a room has no keypad, transfer, park, recording or video; a running recording ends when a room opens, and video requests from members are declined. Listen, whisper and barge calls cannot open or join a room, and their feature codes and the force hang-up code cannot be called as members. No room can be opened while a transfer or park is in progress

### Failed calls

When the PBX refuses a call, the member's row shows the reason. On 503, or after 3 failures in a row (with no answer in between), the remaining members are not called and show the same reason. Nothing is retried automatically: each row has **Call again** and **Remove**. When the PBX gives `Retry-After`, calling again before that time shows "Try again later"; the wait counts as one hour at most.

| Message | Cause |
| --- | --- |
| Line full | 503; with members already in the room, a run of busy or refused calls, or two members in a row within 20 seconds hung up by the other side within 8 seconds of answering (FreePBX does this when the extension's concurrent call limit is reached; a single one only pauses the calling for 5 seconds), see [FAQ](faq.md#conference-room-members-show-line-full) |
| Refused by PBX | 403 |
| Busy | 486, 600 |
| Unavailable | 480 |
| Media negotiation failed | 488 |

### Quality warnings

During a room, the app watches the phone (CPU, temperature, memory) and every member's line (packet loss, jitter, round-trip time), and when the room may suffer it shows a warning next to the title: "Device too hot", "Device overloaded", "Low memory" or "Poor network"; with several at once, the first of these is shown. Tap the warning to see the measured values: CPU, busiest thread, device temperature, memory, and for up to 5 members with a poor line the upload / download packet loss, jitter and round-trip time. When a member's line has been poor for a while, its quality bars turn amber.

## Video calls

**Settings → Video calls** (on by default). When off, the app makes audio calls only and declines requests from the other party to add video.

- Start: the video call button on the dialer; answer: "Answer with video" or "Answer with audio" on the incoming call screen
- In a call: the "Video" button adds or removes video; when the other party asks to turn on video, a prompt offers **Decline** or **Turn on**; the camera stays off until then
- Camera permission is requested the first time video is used; without it, the phone only receives the other party's video
- The camera works only while the call screen is in the foreground; when you leave the screen, a still picture is sent instead
- Switch between front and back cameras, turn the camera off or on; the other party's video keeps its aspect ratio and can be switched between "Show whole picture" and "Fill screen". These buttons stand in the top row with the record button
- Once the other party's video arrives, the controls hide after about 4 seconds without a touch (or after the longer time set in the system's accessibility settings); a tap on the picture shows or hides them. They stay while the keypad or a panel is open, a notice or a new call is shown, or a screen reader is on
- Your own video is a small window: drag it, and on release it docks at the left or right edge (a fling picks the side it was flung towards); near the top or bottom it moves up and down as the controls show and hide; a tap on it swaps the two pictures. Its place is remembered across turns of the phone and later calls. Screen reader actions: "Swap pictures", "Move left", "Move right", "Move up", "Move down"
- When video starts while the earpiece is in use and no headset or Bluetooth device is connected, audio switches to the speaker
- The call screen supports landscape
- Video is not available in a conference room

Resolution and frame rate are set in **Settings → Codecs**:

| Item | Options | Default |
| --- | --- | --- |
| Resolution | 240p / 480p / 720p / 1080p | 480p |
| Frame rate (fps) | Auto / 15 / 24 / 30 | Auto |

With a fixed frame rate, the upload bit rate is capped by resolution and frame rate (at 30 fps: 256 kbit/s for 240p, 768 for 480p, 1536 for 720p, 3072 for 1080p, plus 100 kbit/s headroom for audio; lower frame rates get proportionally less); with "Auto" the SIP stack chooses the bit rate. What is actually sent still depends on the camera, the codec and the network.

## Feature codes

**Settings → Feature codes**. Feature codes are dial strings from the PBX dial plan and must match the PBX. Only `0-9`, `*` and `#` are allowed. **Restore defaults** restores the defaults below (they match the sample Asterisk dial plan in `testenv/`).

| Feature | Default | Dialing | Purpose |
| --- | --- | --- | --- |
| Intercom | `*80` | Code + extension | Calls the extension and asks it to answer automatically |
| Listen | `*91` | Code + extension | Listens in on the extension's call |
| Whisper | `*92` | Code + extension | Speaks to the monitored extension only |
| Barge | `*93` | Code + extension | Joins the extension's call |
| Force hang-up | `*94` | Code + extension | Forces the extension's call to end; tap twice to confirm |
| Directed pickup | `**` | Code + extension | Answers a call ringing at the given extension |
| Group pickup | `*8` | Dialed as is | Answers a call ringing in the group |
| Park | `700` | Blind transfer to this number during a call | The "Park" button on the call screen |
| Voicemail | `*97` | Dialed as is | |
| DND on | `*78` | Dialed as is | Used when the DND mode is "Server" |
| DND off | `*79` | Dialed as is | Same |

An empty feature code means the feature is not used: its entries are grayed out, and tapping one shows "No feature code set for {feature}".

The call features panel (the call features button on the dialer, or the menu of an entry in the directory or the call history) lists intercom, listen, whisper, barge, force hang-up, directed pickup, group pickup and voicemail; when it is opened from the dialer, enter the target extension.

## Shortcut keys

Like the function keys of a desk phone; open them with the shortcut keys button on the dialer or from the call screen. Up to 2000 keys.

### Types

| Type | CSV value | When idle | During a call |
| --- | --- | --- | --- |
| Speed dial | `speed` | Calls the number | Holds the current call and calls the number |
| Blind transfer | `bxfer` | Calls the number | Transfers the other party straight to the number |
| Attended transfer | `axfer` | Calls the number | Holds the current call and calls the number, then completes the transfer |
| Send DTMF | `dtmf` | — | Sends keys to the current call, `0-9 * #` only |
| Call forwarding | `forward` | Turns "forward always to this number" on or off | Same |
| Do not disturb | `dnd` | Turns do not disturb on or off (dials the feature code when the DND mode is "Server") | Same |

PBX features reached through feature codes (intercom, paging, park, pickup, voicemail and so on) use the "Speed dial" type with the feature code as the number, for example `*801005`.

### Fields and limits

| Field | Limits |
| --- | --- |
| Name | Up to 24 characters; when empty, the number (or the type name) is shown |
| Number | Up to 128 characters, no spaces; the do not disturb type has no number |
| Type | See the table above |
| Line | "Choose when calling" or "Specific line" (account name or `user@domain`). If that account is deleted or disabled, pressing the key asks for a line |
| Icon | Follows the type by default; 32 to choose from |
| Category | Up to 16 characters, case-insensitive; "All", "Uncategorized" and "New category" cannot be used as category names |

When an account is renamed or deleted, the shortcut keys set to that account are updated with it.

### Editing and ordering

- **Edit** at the top right enters edit mode; tap a key to edit it; **Done** leaves edit mode
- In edit mode, with the search box empty, long-press a key and drag it to change the order
- To delete a key: tap "Delete" in its editor, then "Confirm delete"
- "Edit categories" in the menu renames or deletes categories; the keys of a deleted category stay, uncategorized
- Search at the top by name, number, category or type, and filter by category

### CSV import and export

**Bulk import** and **Export as CSV** in the menu. One key per line:

```text
name,number[,type[,category[,line[,icon]]]]
```

- Type: `speed` / `bxfer` / `axfer` / `dtmf` / `forward` / `dnd`; the Chinese type names are accepted too; empty means `speed`
- Line: account name or `user@domain`; empty means choose when calling
- Icon: `phone` `person` `group` `star` `home` `office` `store` `reception` `support` `speaker` `meeting` `door` `lock` `key` `garage` `car` `elevator` `security` `camera` `bell` `alarm` `medical` `fire` `food` `hotel` `warehouse` `factory` `school` `repair` `computer` `delivery` `light`; the Chinese icon names are accepted too; empty follows the type
- Quoting, comment lines, file size and the other rules are the same as for [account bulk import](#bulk-import); lines beyond the 2000-key limit are skipped

Example:

```text
Reception,1001,speed,Favorites,,reception
Warehouse intercom,*801005,speed,Intercom,,warehouse
Open door,#9,dtmf,Door access,,door
To operator,1000,bxfer,Transfer
```

For shortcut keys under remote configuration, see [Remote configuration](remote-configuration.md#what-the-user-keeps).

## LDAP directories

**Settings → Call features → LDAP directories**, or the top right of the Directory tab. Up to 50 directories. The Directory tab needs at least one SIP account.

| Field | Description |
| --- | --- |
| Name (optional) | Up to 40 characters, unique; when empty, the server address is shown |
| Server address | `host[:port]`, default port 389; may be written as `ldap://host:port`. `ldaps://` is not supported |
| Base DN | Required |
| Encryption | None / StartTLS. StartTLS needs a domain name as the server address, not an IP address; the certificate must be issued by a trusted CA |
| Sign-in | Account (the user name is the Bind DN, plus a password) or Anonymous |
| Line | All lines, or one account: calls on that account look up names in this directory first, and calls placed from this directory use that account |
| Name for incoming calls / Name for outgoing calls | On by default |
| Name attributes | Default `cn sn`, separated by spaces or commas; `sn+givenName` means a name made of several attributes |
| Number attributes | Default `telephoneNumber mobile` |
| Max results | 1–1000, default 50 |
| Filter | Auto (a "contains" match built from the attributes) or Custom. In a custom filter `%` stands for the search text and matches as a prefix, for example `(cn=%)` matches names that start with the search text and `(cn=*%)` names that contain it; it must contain `%` |
| Enable this directory | |

- Search text made only of digits, `+*#-()` and spaces searches numbers; any other text searches names
- For the names of incoming and outgoing calls, the directories of the call's line are queried first, then those for "All lines"
- Entries returned by servers are kept in a local cache, and a search shows the cached matches first; while the server has not answered or returns nothing, these matches stay and are marked "From local cache". The cache is limited to 100 MB; entries a server has not returned for 90 days are deleted. "Local cache" on the LDAP list page shows the usage and clears it
- When the results reach the maximum, "Showing the first N only" is displayed

## Call history

The History tab shows the last 500 calls: outgoing, incoming, missed, and unanswered. Each entry has **Call back**; its menu has **Add to shortcut keys**, **Call features…** and **Delete entry**. The top right clears the whole history (cannot be undone). Numbers that have a name in a directory are shown with the name.

## Call recording

The record button at the top right of the call screen starts or stops recording; while recording it shows a red dot and the recorded time. No tone is played and the other party is not notified when recording starts or stops. Starting and stopping several times in one call gives one file (nothing is recorded while stopped). Hold, resume and the screen turning off do not affect recording; recording ends with the call.

- Both sides are recorded (mixed to mono); video calls record audio only
- Each call is recorded separately; the record button acts on the current call
- Conference rooms cannot be recorded: a running recording ends when a room opens, and a room has no record button
- Calls transferred in by the PBX (REFER, Replaces) cannot be recorded

**Settings → Call features → Recording**:

| Item | Description | Default |
| --- | --- | --- |
| Call recording | When off, the call screen has no record button | On |
| Record by default | Every call starts recording when connected; the button stops and resumes it | Off |
| Recordings to keep | 100 / 200 / 500 / 1000 | 500 |
| Recordings | Opens the list of recordings | — |

Recordings are stored in the app's private directory: at most "Recordings to keep" files and 2 GB in total; beyond that, the oldest recordings are deleted first (checked when a recording ends and when the app starts). If the app exits abnormally, unfinished recordings are tidied up and saved at the next start, and files without sound are deleted.

The format is AAC-LC mono `.m4a`; the sample rate follows the call's codec (for example 48 kHz for Opus, 8 kHz for G.711), at 32 kbit/s (24 kbit/s at 8 kHz), about 240 KB per minute. During the call the audio is written as WAV and converted when the call ends; if the conversion fails, the WAV is kept.

The **Recordings** page lists recordings from newest to oldest, with the other party, incoming / outgoing, start time, duration and size:

- Time filter: All, Today, 7 days, 30 days, custom date range; the count and total size are shown above the list
- Tap to play / stop
- Menu: **Export** (choose where to save), **Delete**
- Long-press to select several; **Export** in the top bar exports the selected recordings (the current filter result when none is selected) to a chosen folder, **Delete** deletes the selected recordings (after confirmation)
- Exported files are named `{start-time}_{number}.m4a`, for example `20261117-143005_1001.m4a`

Under remote configuration, the Recordings page and "Recordings to keep" stay with the user; "Call recording" and "Record by default" are provisioned by the platform.

## Settings

### Main settings page

| Group | Item | Default |
| --- | --- | --- |
| Accounts | SIP accounts | — |
| Call features | Do not disturb | Off |
| | Video calls | On |
| | Recording, Intercom and auto answer, LDAP directories, Feature codes, Codecs, Advanced settings | — |
| Security | TLS and CA certificates | — |
| Battery | Stay online in background | On |
| | Battery optimization, Show calls on lock screen (Android 14 and later) | — |
| Appearance | Theme: Follow system / Light / Dark | Follow system |
| | Call banner position: Top / Bottom | Bottom |
| | Dynamic color (Android 12 and later) | Off |
| — | Remote configuration, About | — |

### Recording

See [Call recording](#call-recording).

### Intercom and auto answer

| Group | Item | Default |
| --- | --- | --- |
| Intercom | Auto answer intercom calls | Off |
| | Use speaker for intercom | On |
| Regular calls | Auto answer | Off |
| | Auto answer delay: Immediately / 3 / 5 / 10 / 20 / 30 seconds (shown when auto answer is on) | 3 seconds |

See [Intercom](#intercom) and [Auto answer](#auto-answer).

### About

**About**, the last row of Settings, shows the current version, and "Update available" when a newer version that has not been skipped is found. The About page contains:

| Item | Default |
| --- | --- |
| Version (tap to check for updates) | — |
| Check for updates automatically | On |
| Linphone SDK version | — |
| Licenses: SipLink is GNU AGPL-3.0; the SIP stack is the Linphone SDK by Belledonne Communications, GNU AGPLv3 | — |
| GitHub icon: opens the code repository in the browser | — |

### Advanced settings

| Group | Item | Default | Notes |
| --- | --- | --- | --- |
| Incoming calls | DND mode | Software | See [Incoming call handling](#incoming-call-handling) |
| | Call waiting | On | |
| | Reject anonymous calls | Off | |
| | DTMF mode | RFC 2833 | The other option, SIP INFO, is for PBXs or trunks that require SIP INFO |
| Call forwarding | Forward always, Forward when busy, Forward on no answer | Empty | Empty means no forwarding |
| | No-answer wait (seconds) | 20 | 5–120 |
| Conference room | Maximum members | 30 | 2–30, see [Conference room](#conference-room) |
| Media and network | Adaptive rate control | Off | See below |
| | Echo cancellation | On | The SIP stack's echo cancellation |
| | Allow IPv6 | On | |
| | Adapt to network automatically | On | See below |
| | STUN server | Empty | `host[:port]`, port 3478 by default; the line diagnosis uses it to tell the NAT type and the public address, and automatic network adaptation uses it for ICE; empty for none |
| Client identity | Client name (User-Agent) | Empty | See below |

**Adaptive rate control** is off by default. When on, the SIP stack adapts the bit rate and packet time to the network. PBXs usually do not offer RTCP feedback (AVPF); the SDK then uses its basic controller, which lengthens the audio packet time step by step up to 100 ms when it detects packet loss or a doubled round-trip time, and behind Asterisk the other side has been reported to go silent at that point. See [FAQ](faq.md#no-sound-after-a-while-in-a-call).

**Adapt to network automatically**: when a line (an account on one network) runs into trouble, it is adjusted, and the adjustment is remembered for that network (up to 20 networks, forgotten after 30 days unused). The account's own settings stay as they are.
- When UDP registrations get no answer, or UDP packets larger than one packet, sent in fragments, are dropped (calls out get no answer), the line moves to TCP, and to TLS when TCP does not get through either and the current certificate settings accept the server. The account list shows "TCP（自动）". When the server refuses the line on the transport moved to before it ever registered there, the line moves on at once, or back to the account's own transport, and the refused transport is not tried again on that network for a day, then 2, 4 and 7 days each time it is refused again (at once after the account's user name, server or password is changed). A 401 or 407 counts as such a refusal only when the same login registered before, on any network and transport, since it last changed; otherwise it is taken as a wrong password: the line stays on the transport it moved to and shows the authentication error. Once the line registered there, a refusal of the server (such as a password changed on the PBX) no longer changes its transport, nor does a passing server error (such as 503) while that transport still connects. The line is retested regularly (every 7 days when it moved to TLS) and goes back to UDP when idle once UDP works again
- When the NAT forgets the line between registrations, the registration interval is shortened to 300 seconds; if that does not help, the line moves to TCP. Once a retest puts it back on UDP, it registers every 300 seconds from the start, and the registrations of the following day judge it: when it moves to TCP again, the next retest waits twice as long
- When a call's sound goes one way, a STUN server is set and the NAT is not symmetric, the line turns on ICE: its outgoing calls offer the address found by STUN as the default address, and a PBX that supports ICE runs connectivity checks. It is kept only if the next call ICE takes part in has sound both ways and is not set up 2 seconds or more slower because of it; when the sound still goes one way, the set-up is slower, a call out fails while being set up (no answer, 400, 415, 488, 513, 606), or 5 calls with ICE tell nothing (too short, or failed otherwise; busy, cancelled and declined calls do not count), it is undone and not tried again on that network for 7 days. Once kept, it is taken off after a day to retest, and kept for 2, 4 and then 7 days each time it is needed again. Calls from a PBX without ICE do not use ICE. Intercom and paging calls, and calls with one-way media, are not judged
- Networks that require a web login are not adjusted until the login
Turning it off puts all lines back on their own settings and clears what was remembered.

**Client name (User-Agent)**: up to 120 printable ASCII characters, with no space at the start or end. When empty, `LinphoneSDK/{sdk-version} SipLink/{app-version}` is sent: the SDK name comes first because some services accept only User-Agents that start with a known client name. Accounts re-register after a change.

### TLS and CA certificates

- **Import CA certificate**: PEM (may contain several certificates), DER or PKCS#7, files up to 1 MB. Accounts re-register after an import. The certificate list shows the issuer and the validity period; non-CA and expired certificates are marked
- Imported CAs are used together with the root certificates that come with the SIP stack, for both SIP over TLS and LDAP StartTLS. If LDAP has already made an encrypted connection, a CA change takes effect for LDAP only after the app restarts (the app says so)
- **Verify server certificate** and **Verify hostname**: on by default. Turning verification off exposes calls to eavesdropping
- **Media encryption (SRTP)**: Off (default) / Preferred / Mandatory

### Codecs

Audio and video codecs are listed separately; arrows change the priority and switches enable them. At least one audio codec must stay enabled, and at least one video codec while video calls are on. **Restore defaults** restores the SDK's default codec settings. For video resolution and frame rate, see [Video calls](#video-calls).

## Battery

The app does not use push notifications; incoming calls depend on the app staying registered in the background.

- **Stay online in background** (on by default): runs a foreground service; its notification shows "SipLink is running" and the number of registered / enabled accounts. It starts automatically after boot and after app updates. When off, no calls arrive once the system ends the app's process
- **Battery optimization**: opens the system's battery optimization settings; turning battery optimization off for SipLink is recommended
- **Show calls on lock screen**: from Android 14, showing the call screen on the lock screen for an incoming call needs the "full-screen notifications" permission; without it, calls on the lock screen show only a notification. Without the permission, the app reminds you when it starts, with a "Don't remind me again" option

During a call, the screen turns off when held to the ear with the earpiece in use (not in video calls).

## Updates

With "Check for updates automatically" on, the app checks for the latest GitHub release in the background each time it starts or returns to the foreground, without getting in the way; the check is skipped if the last one was less than 1 minute ago. Tap the "Version" row in **Settings → About** to check by hand at any time.

A failed check shows no pop-up, only "Update check failed" or "Too many requests" in the version row. After GitHub returns a rate limit, the app makes no more requests until the reset time GitHub gives, see [FAQ](faq.md#update-check-fails).

When a new version is found, the Settings tab shows a dot and the About row shows "Update available"; tap the version row on the About page to see the release notes and choose **Skip this version** or **Download and install**:

1. The app downloads the APK that matches the phone's ABI and shows the progress; **Cancel download** stops it; leaving the page does not
2. After the download, the size, the SHA-256 digest (when the release provides one) and the package name are checked; on a mismatch, "Download failed" is shown
3. Tap **Install** to hand the APK to the system installer, where you confirm. The first time, allow SipLink to "Install unknown apps" in the system settings; returning to the app continues the installation

If the release has no APK for the phone's ABI, the button is **Open download page**, which opens the release page in the browser. Downloaded files are deleted at the next start. For network details, see [Privacy and permissions](privacy.md).
