---
title: Privacy Policy
---

# Privacy Policy

_Last updated: 2026-05-10_

PocketSecureShell ("the App") is an open-source Android SSH client maintained by
hogelog. This page describes how the App handles user data.

## Data stored on your device

- **Locally stored data** — SSH connection profiles (hostname, port, username,
  identity-key alias), shortcut bar configuration, and terminal settings are
  stored in the App's private storage. The App does not transmit them to any
  third party.
- **SSH authentication key** — an ECDSA P-256 private key is generated inside
  the Android Keystore (hardware-backed when available). The Keystore API does
  not expose the raw key material; the private key cannot be exported off the
  device by anyone, including the user.
- **Key signing authorization** — Signing an SSH authentication handshake with
  the on-device private key requires biometric authentication (or a device
  lock-screen unlock). The authorization is valid for 30 minutes; new sessions
  opened within that window reuse it without re-prompting. Lock the device if
  you want every connection to require a fresh authentication.
- **Learned input suggestions** — to drive the dynamic suggestion row, the App
  records frequency counts of tokens you have typed at the SSH prompt
  (per-foreground-command bigrams) in a local SQLite database. This corpus is
  effectively a partial history of shell commands you have entered. It stays in
  the App's private storage and is never transmitted. Settings export omits this
  corpus by default; tick "Include learned suggestions" in the export dialog if
  you want to carry it over to another device, and treat the exported JSON as
  sensitive when you do. You can remove individual entries via long-press on a
  suggestion button.

## Data sent to remote services

- **SSH traffic** — When you start a session, terminal input and output are
  exchanged directly with the SSH server you specify. The App does not relay
  traffic through any intermediary.
- **No app telemetry** — The App itself does not send crash reports,
  analytics, or any other telemetry to remote services.
- **Android-level crash reporting (Google Play Console)** — Independent of
  the App, Android's OS-level crash and ANR reporting via Google Play services
  may collect data on devices where the user has opted into sharing usage and
  diagnostics. This happens at the operating-system level and applies to both
  Play-installed and sideloaded versions whenever Play services are present
  and that setting is enabled; devices without Google Play services (e.g.
  de-Googled ROMs) are not covered. The App does not control this channel.
- **Debug builds** — Debug APKs built locally from source include
  [Sentry](https://sentry.io) for crash reporting. Release builds (the APK/AAB
  published on GitHub Releases and the Google Play Store) do not. Crash
  payloads from debug builds are scrubbed to drop SSH credentials, hostnames,
  usernames, and terminal contents before being sent.

## Permissions

- `INTERNET` — required to connect to the SSH servers you specify.
- `USE_BIOMETRIC` — required to authorize use of the on-device SSH private key.

## Open source

PocketSecureShell is open source (MIT). Source code:
[github.com/hogelog/pocket-secure-shell](https://github.com/hogelog/pocket-secure-shell).

## Contact

For questions about this policy, contact
[hogelog.developer@gmail.com](mailto:hogelog.developer@gmail.com) or open an
issue on the repository above.
