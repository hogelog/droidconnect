# DroidConnect

Android SSH client designed for interacting with Claude Code's TUI.

## Features

- Ed25519 key pair generation
- SSH public key authentication via [sshlib](https://github.com/connectbot/sshlib)
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

## License

GPLv3 - See [LICENSE](LICENSE)
