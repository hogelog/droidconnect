# PocketSecureShell

Android SSH client designed for Claude Code TUI interaction.

## Requirements

- Android SDK with compileSdk 36
- NDK (for terminal-emulator native code)
- JDK 17+

## Project Structure

- `app/` - Main application module (Kotlin)
- `vendor/termux-app/` - Git submodule (Termux terminal-emulator and terminal-view)

### Key Packages

- `org.hogel.pocketssh` - Main activity (connection UI)
- `org.hogel.pocketssh.ui` - Terminal UI (`TerminalActivity`, `ShortcutsSettingsActivity`)
- `org.hogel.pocketssh.ssh` - SSH session management and key generation
- `org.hogel.pocketssh.shortcuts` - Configurable shortcut bars (`ShortcutStore`, payload parser)

## Architecture

Uses Termux's `TerminalView` + `TerminalEmulator` for terminal rendering, with a bridge
pattern to connect SSH I/O (via sshlib) to the terminal emulator. A dummy local process
is created for `TerminalSession`, while actual I/O flows through the SSH channel.

## Submodule

After cloning, initialize the submodule:

```bash
git submodule update --init --recursive
```

The submodule is pinned to termux-app v0.118.3.

## Telemetry

Sentry is wired up only for crash reporting (auto-init via manifest meta-data). User-controlled
values must never reach Sentry events or breadcrumbs:

- Connection target (host, port, username) and SSH key material (public key, fingerprints, host
  key bytes, signatures) are off-limits — do not pass them to `Sentry.captureException` /
  `captureMessage` / `addBreadcrumb` / `setUser` / `setContext` / `setTag` / `setExtra`, and do
  not interpolate them into exception messages or `Log.*` calls that may propagate uncaught.
- Catch and log SSH-layer exceptions where they originate so they never reach the global
  `UncaughtExceptionHandler` (where their messages would be captured verbatim — e.g.
  `UnknownHostException` carries the hostname).
- Do not enable `attachViewHierarchy`, `attachScreenshot`, or session-replay; the connection
  screen's `EditText`s and the terminal output would otherwise leak.

## License

GPLv3 (due to Termux terminal-emulator/terminal-view dependency)
