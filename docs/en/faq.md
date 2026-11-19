<p align="right"><sub><a href="../faq.md">中文</a> | English</sub></p>

# FAQ

## No incoming calls while the app is in the background

SipLink does not use push notifications; incoming calls depend on the app staying registered in the background. Check in this order:

1. **Settings → Battery → Stay online in background** is on, the "SipLink is running" notification is shown, and the number of registered accounts in it is right
2. **Settings → Battery → Battery optimization**: turn battery optimization off for SipLink on the system page
3. The notification permission is granted (Android 13 and later); otherwise the background service and incoming call notifications cannot be shown
4. Only a notification and no call screen on the lock screen: allow full-screen notifications in **Show calls on lock screen** (Android 14 and later)

After the phone restarts or the app is updated, the background service starts automatically.

## No sound after a while in a call

Check whether **Settings → Advanced settings → Adaptive rate control** has been turned on (it is off by default). PBXs generally do not offer RTCP feedback (AVPF); the SDK then uses its basic rate controller, which lengthens the audio packet time step by step up to 100 ms when it detects packet loss or a doubled round-trip time; behind Asterisk, the other side has been reported to go silent when this happens. Keeping it off avoids the problem. Under remote configuration, the platform's `adaptiveRateControl` decides.

## TLS account fails to register

- When the server certificate is issued by a private CA, import that CA's root certificate in **Settings → TLS and CA certificates** (PEM, DER or PKCS#7, up to 1 MB); accounts re-register automatically after the import
- The certificate must contain the host name or IP the account connects to (when "Verify hostname" is on). When connecting by IP address, the certificate's subject alternative names must include that IP
- With TLS as the transport, an empty port means 5061
- Turning off "Verify server certificate" or "Verify hostname" is only for troubleshooting; with it off, calls can be eavesdropped

## LDAP directory cannot use StartTLS

- StartTLS needs a domain name as the "Server address", not an IP address: the SIP stack's LDAP library refuses the TLS handshake without a host name. The phone must be able to resolve the name, and the server certificate must contain it
- `ldaps://` is not supported; use StartTLS instead (usually still on port 389)
- Import the CA of the server certificate in **Settings → TLS and CA certificates**. If LDAP has already made an encrypted connection in this run, changing the CA needs an app restart (the app shows "Takes effect for LDAP after restart")
- The Directory tab shows "Add a SIP account first": the SIP stack completes directory numbers with the domain of the default account and returns no entries without an account

## The app shows "Managed remotely"

The configuration is provisioned by the management platform; it can be viewed but not changed on the phone. For what can still be changed, see [Remote configuration](remote-configuration.md#what-the-user-keeps). To get the local configuration back, remove the remote configuration in **Settings → Remote configuration**; this clears the provisioned accounts, directories, certificates and shortcut keys.

## Remote configuration does not load

For the messages on the page, see [Remote configuration → Sync](remote-configuration.md#sync). Common causes: the QR code was already used or has expired (generate a new one); the address is not `https://`; the platform certificate does not match the `pin` in the QR code, or, without `pin`, the certificate is not trusted by the system CAs.

## Update check fails

A failed check shows no pop-up; only the version row in **Settings → About** shows:

| Message | Cause |
| --- | --- |
| Too many requests | GitHub's rate limit for unauthenticated requests (60 per hour per public IP address) is used up. The app makes no requests until the reset time GitHub gives, then resumes automatically |
| Update check failed | Anything else: network unreachable, `api.github.com` not reachable, the response is not valid release information, and so on |

Devices behind the same public IP address share the rate limit. You can also download from [GitHub Releases](https://github.com/tom231efa/siplink-android/releases) directly.

## An update downloaded in the app does not install

- At the first installation, the app opens the system's "Install unknown apps" setting; after allowing it, return to the app and the installation continues
- When the download fails or does not match (size, SHA-256 digest or package name), "Download failed" is shown; tap **Download again**
- The system reports a signature conflict: the new APK is signed differently from the installed one (for example a build of your own); uninstall first, then install

## 32-bit devices

The published APK is for 64-bit ARM (arm64-v8a) devices. 32-bit ARM devices need an APK built with `armeabi-v7a`, see [Building](build.md#abi). When the update check finds no APK for the phone's ABI in the release, the button in the release notes is **Open download page**, which opens the release page.

## The server rejects registration (403), but other softphones work

Some services accept only requests whose User-Agent starts with a known client name. That is why the default User-Agent starts with `LinphoneSDK/`. If you changed the name in **Advanced settings → Client name (User-Agent)**, clear it to restore the default.

## A feature is grayed out

Its feature code is empty, which means the feature is not used. Fill it in under **Settings → Feature codes** according to the PBX dial plan, see [Feature codes](user-guide.md#feature-codes). Choosing "Server" as the "DND mode" needs both the "DND on" and "DND off" feature codes.

## When the backup server is used

When the primary server does not respond, or returns 408 or 5xx, the account switches to the backup proxy; on rejections such as 401, 403 or 404 it does not switch (the backup server would reject too). It switches back when the primary server recovers and there is no call.

## The record button is missing

**Settings → Call features → Recording → Call recording** is off, or remote configuration turned it off. A call that was set up while "Call recording" was off cannot be recorded, even if it is turned on later (the recording file has to be set when the call is set up). Three-way calls and calls transferred in by the PBX (REFER, Replaces) cannot be recorded.
