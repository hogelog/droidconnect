# DroidConnect

Android SSH client designed for interacting with Claude Code's TUI.

## Features (Phase 1)

- Ed25519 key pair generation
- SSH public key authentication via [sshlib](https://github.com/connectbot/sshlib)
- Terminal emulation via [Termux terminal-emulator/terminal-view](https://github.com/termux/termux-app)
- xterm-256color compatible rendering

## Build

```bash
git submodule update --init --recursive
./gradlew assembleDebug
```

Requires Android SDK with compileSdk 36 and NDK.

## License

GPLv3 - See [LICENSE](LICENSE)
