# <img src="docs/assets/app-icon.svg" width="32" align="top"> PocketSecureShell

Android SSH client tuned for terminal-heavy workflows like Claude Code's TUI.

<img src="docs/assets/screenshot-claude-code.png" alt="Running Claude Code over SSH on PocketSecureShell" width="320">

## Features

- ECDSA P-256 key pair generated inside the Android Keystore (hardware-backed when available); the private key is non-exportable — it cannot be retrieved off the device by anyone, including the user
- Biometric-gated SSH public key authentication via [sshlib](https://github.com/connectbot/sshlib):
  the private key never leaves the TEE; one biometric authentication authorizes signing for
  30 minutes (the lock-screen unlock counts, so active use stays seamless)
- Terminal emulation via [Termux terminal-emulator/terminal-view](https://github.com/termux/termux-app)
- xterm-256color compatible rendering
- Configurable shortcut bars (always-on and per-foreground-command)
- Japanese IME input

## Build

### Requirements

- JDK 17
- Android SDK with `compileSdk` 36 (`minSdk` 34, `targetSdk` 36)
- Android NDK 27.0.12077973

### Steps

```bash
git submodule update --init --recursive
./gradlew assembleDebug
```

The debug APK will be produced at `app/build/outputs/apk/debug/app-debug.apk`.

## Release

Pushing a `vMAJOR.MINOR.PATCH` tag triggers the release workflow, which builds a
signed APK and AAB and attaches both to a GitHub release. The AAB
(`pocketsecureshell-vX.Y.Z.aab`) is the artifact uploaded to Google Play.

## License

GPLv3 - See [LICENSE](LICENSE)
