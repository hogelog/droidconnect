# DroidConnect

Android SSH client designed for Claude Code TUI interaction.

## Requirements

- Android SDK with compileSdk 36
- NDK (for terminal-emulator native code)
- JDK 17+

## Project Structure

- `app/` - Main application module (Kotlin)
- `vendor/termux-app/` - Git submodule (Termux terminal-emulator and terminal-view)

### Key Packages

- `org.hogel.droidconnect` - Main activity (connection UI)
- `org.hogel.droidconnect.ui` - Terminal UI (TerminalActivity)
- `org.hogel.droidconnect.ssh` - SSH session management and key generation

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

## License

GPLv3 (due to Termux terminal-emulator/terminal-view dependency)
