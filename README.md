# PocketSecureShell

Android SSH client tuned for terminal-heavy workflows like Claude Code's TUI.

## Features

- ECDSA P-256 key pair generated in the Android Keystore (hardware-backed when available)
- Biometric-gated SSH public key authentication via [sshlib](https://github.com/connectbot/sshlib):
  the private key never leaves the TEE; one biometric authentication authorizes signing for
  30 minutes (the lock-screen unlock counts, so active use stays seamless)
- Terminal emulation via [Termux terminal-emulator/terminal-view](https://github.com/termux/termux-app)
- xterm-256color compatible rendering
- Configurable shortcut bars (always-on and per-foreground-command)

## Build

### Requirements

- JDK 17
- Android SDK with `compileSdk` 36 (`minSdk` 34, `targetSdk` 36)
- Android NDK 27.0.12077973
- Git (for fetching submodules)

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
