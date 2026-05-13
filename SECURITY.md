# Security Policy

## Reporting a Vulnerability

Please report security issues privately. **Do not open a public GitHub issue**
for security problems.

- **Preferred**: [Security → Report a vulnerability](https://github.com/hogelog/pocket-secure-shell/security/advisories/new)
  (private GitHub Security Advisory)
- **Email**: <hogelog.developer@gmail.com>

This is a hobby project maintained in spare time. I'll acknowledge receipt
within a few days; turnaround on a fix depends on severity and complexity.
Reporters will be credited in the release notes unless you ask otherwise.

## In Scope

Issues affecting released builds (Google Play, GitHub Releases) that could
lead to:

- Disclosure or unauthorized use of the on-device SSH private key
- Bypass of the biometric gate that authorizes key signing
- Bypass of TOFU host-key verification once a host key has been pinned
- Leakage of terminal contents, hostnames, usernames, or other connection
  metadata outside the SSH channel
- Code execution triggered by a malicious SSH server or crafted host key

## Out of Scope

- **Compromised-device scenarios** (root, other apps with full storage access,
  unlocked bootloader giving physical attackers control) — these break a
  stronger trust boundary than the App can defend.
- **First-connection trust under TOFU** — accepting an unknown host key on
  the very first connection is the user's choice by design. Verification
  applies on subsequent connections.
- **Debug builds** built locally from source — these include the Sentry SDK
  and are not what end users install.
- **Bugs in upstream dependencies** (sshlib, Termux terminal-emulator/
  terminal-view, AndroidX, etc.) — please report to those projects directly;
  I'm happy to coordinate.
