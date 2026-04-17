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

## Crash Reporting

Crash reports are sent to [Sentry](https://sentry.io) when the `SENTRY_DSN`
environment variable is set at build time. In CI the DSN is supplied from the
`SENTRY_DSN` GitHub Actions variable (not a secret — the DSN is a public
client-side identifier per Sentry's guidance). Local builds without the env
var produce APKs where Sentry auto-init is a no-op, so there is no
configuration required for development.

ProGuard mapping and native symbol uploads are currently disabled; stack
traces in Sentry will be obfuscated until those are wired up in a follow-up
change.

## License

GPLv3 - See [LICENSE](LICENSE)
