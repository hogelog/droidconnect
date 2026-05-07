---
title: Privacy Policy
---

# Privacy Policy

_Last updated: 2026-05-07_

PocketSecureShell ("the App") is an open-source Android SSH client maintained by
hogelog. This page describes how the App handles user data.

## Data stored on your device

- **SSH connection profiles** — hostname, port, username, and identity-key alias
  are stored in the App's private storage. The App does not transmit these to
  any third party.
- **App preferences** — shortcut bar configuration and terminal settings are
  stored locally.
- **SSH authentication key** — an ECDSA P-256 private key is generated and held
  in the Android Keystore (hardware-backed when available). The private key
  never leaves your device.

## Data sent to remote services

- **SSH traffic** — When you start a session, terminal input and output are
  exchanged directly with the SSH server you specify. The App does not relay
  traffic through any intermediary.
- **Crash and error reports** — The App sends crash and error reports to
  [Sentry](https://sentry.io). Reports include the exception details, device
  model, OS version, and app version. They do **not** include SSH credentials,
  private keys, terminal contents, hostnames, or usernames. See
  [Sentry's privacy policy](https://sentry.io/privacy/) for how Sentry handles
  this data.

## Permissions

- `INTERNET` — required to connect to the SSH servers you specify.
- `USE_BIOMETRIC` — required to authorize use of the on-device SSH private key.

## Children

The App is not directed to children under 13.

## Open source

PocketSecureShell is open source (GPL-3.0). Source code:
[github.com/hogelog/pocket-secure-shell](https://github.com/hogelog/pocket-secure-shell).

## Contact

For questions about this policy, contact
[hogelog.developer@gmail.com](mailto:hogelog.developer@gmail.com) or open an
issue on the repository above.
