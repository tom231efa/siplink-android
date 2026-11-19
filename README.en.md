<p align="right"><sub><a href="README.md">中文</a> | English</sub></p>

<p align="center"><img src="docs/images/hero.svg" width="100%" alt="SipLink: phone, IP PBX, desk phones and remote configuration"></p>

<h1 align="center">SipLink</h1>

<p align="center">Android SIP softphone for offices with an IP PBX</p>

<p align="center"><a href="https://github.com/tom231efa/siplink-android/releases/latest"><img src="https://img.shields.io/github/v/release/tom231efa/siplink-android?style=flat&label=release&color=3E7FE3" alt="Release"></a> <a href="LICENSE"><img src="https://img.shields.io/badge/license-AGPL--3.0-6E7781?style=flat" alt="License: AGPL-3.0"></a> <img src="https://img.shields.io/badge/Android-9%2B-6E7781?style=flat" alt="Android 9+"> <a href="https://github.com/tom231efa/siplink-android/releases"><img src="https://img.shields.io/github/downloads/tom231efa/siplink-android/total?style=flat&color=6E7781" alt="Downloads"></a></p>

<p align="center"><a href="https://github.com/tom231efa/siplink-android/releases/latest"><img src="docs/images/btn-download-en.svg" width="148" height="44" alt="Download"></a> <a href="docs/en/features.md"><img src="docs/images/btn-features-en.svg" width="148" height="44" alt="Features"></a> <a href="docs/en/screenshots.md"><img src="docs/images/btn-screenshots-en.svg" width="148" height="44" alt="Screenshots"></a> <a href="docs/en/README.md"><img src="docs/images/btn-docs-en.svg" width="148" height="44" alt="Documentation"></a></p>

<p align="center"><img src="docs/images/divider.svg" width="100%" alt=""></p>

SipLink is a SIP softphone based on the [Linphone SDK](https://www.linphone.org/en/liblinphone-voip-sdk), with a user interface written in Jetpack Compose. It is mainly for voice calls, supports one-to-one video calls, and has no instant messaging.

This repository also contains the [management platform](docs/en/platform.md) (`platform/`): a server that provisions accounts, settings, directories and shortcut keys to many phones from one place, published as a Docker image.

<p align="center"><a href="docs/en/features.md#calls-and-call-features"><img src="docs/images/feature-calls.svg" width="19%" alt="Calls and call features"></a> <a href="docs/en/features.md#shortcut-keys"><img src="docs/images/feature-shortcuts.svg" width="19%" alt="Shortcut keys"></a> <a href="docs/en/features.md#video-calls"><img src="docs/images/feature-video.svg" width="19%" alt="Video calls"></a> <a href="docs/en/features.md#remote-configuration"><img src="docs/images/feature-remote.svg" width="19%" alt="Remote configuration"></a> <a href="docs/en/features.md#recording-and-security"><img src="docs/images/feature-recording.svg" width="19%" alt="Recording and security"></a></p>

<p align="center"><a href="docs/en/features.md#calls-and-call-features">Calls and call features</a> · <a href="docs/en/features.md#shortcut-keys">Shortcut keys</a> · <a href="docs/en/features.md#video-calls">Video calls</a> · <a href="docs/en/features.md#remote-configuration">Remote configuration</a> · <a href="docs/en/features.md#recording-and-security">Recording and security</a></p>

<p align="center"><img src="docs/images/divider.svg" width="100%" alt=""></p>

## Download

Download the APK from [GitHub Releases](https://github.com/tom231efa/siplink-android/releases) and install it. The app checks GitHub for new versions, which can be downloaded in the app and handed to the system installer.

- Android 9 or later
- The published APK is for 64-bit ARM (arm64-v8a) devices
- So far tested on Android 16 only

The update check contacts `api.github.com` and can be turned off in Settings, see [Privacy and permissions](docs/en/privacy.md).

## Documentation

- [Features](docs/en/features.md)
- [Screenshots](docs/en/screenshots.md)
- [User guide](docs/en/user-guide.md): accounts, calls, conference room, video, feature codes, shortcut keys, directories, recording, settings
- [Remote configuration](docs/en/remote-configuration.md): loading and sync, what is locked, configuration document format
- [Management platform](docs/en/platform.md): the server that provisions configuration, Docker deployment
- [FAQ](docs/en/faq.md): no incoming calls, no sound in calls, certificates, LDAP, updates
- [Privacy and permissions](docs/en/privacy.md): what each permission is for, what the app connects to, what is stored on the phone
- [Building](docs/en/build.md) and [testing](docs/en/testing.md)

## License

[GNU AGPL-3.0](LICENSE). This project uses the Linphone SDK, which is available under AGPLv3 or a commercial license.
