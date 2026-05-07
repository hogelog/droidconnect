# <img src="docs/assets/app-icon.svg" width="32" align="top"> PocketSecureShell

Android SSH client tuned for terminal-heavy workflows like Claude Code's TUI.

<img src="docs/assets/screenshot-claude-code.png" alt="Running Claude Code over SSH on PocketSecureShell" width="320">

## Features

- ECDSA P-256 key pair generated inside the Android Keystore (hardware-backed when available); the private key is non-exportable — it cannot be retrieved off the device by anyone, including the user
- Biometric-gated SSH public key authentication via [sshlib](https://github.com/connectbot/sshlib):
  the private key never leaves the TEE; one biometric authentication authorizes signing for
  30 minutes (the lock-screen unlock counts, so active use stays seamless)
- Terminal emulation via [Termux terminal-emulator/terminal-view](https://github.com/termux/termux-app), xterm-256color compatible
- tmux-aware UI: the active pane's OSC window title (foreground command) drives per-context input surfaces, with a configurable tmux prefix letter
- Customizable input surfaces grouped by context: shortcut bar, left/right swipe payloads, and a FAB speed-dial menu
- Learned suggestions: a bigram model over past stdin per foreground command surfaces candidate next tokens in a dynamic row
- Image upload: pick an image and the app SCPs it to the remote, then inserts the uploaded path at the cursor — built for handing images to Claude Code
- Japanese IME input
- Crash reporting via Sentry — only stack traces and device metadata; SSH credentials, hostnames, and terminal contents are filtered out (see [Privacy Policy](docs/privacy.md))

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

## License

GPLv3 - See [LICENSE](LICENSE)
