# DroidConnect

Android SSH client designed for interacting with Claude Code's TUI.

## Features

- Ed25519 key pair generation
- SSH public key authentication via [sshlib](https://github.com/connectbot/sshlib)
- Terminal emulation via [Termux terminal-emulator/terminal-view](https://github.com/termux/termux-app)
- xterm-256color compatible rendering

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

### Release build (signed AAB for Play Console)

1. Generate a release keystore once (keep the file outside the repo):

   ```bash
   keytool -genkeypair -v \
     -keystore ~/.android/droidconnect-release.jks \
     -keyalg RSA -keysize 2048 -validity 10000 \
     -alias droidconnect
   ```

2. Provide the credentials via environment variables (or the equivalent
   `RELEASE_KEYSTORE_FILE`/`RELEASE_KEYSTORE_PASSWORD`/`RELEASE_KEY_ALIAS`/`RELEASE_KEY_PASSWORD`
   entries in `~/.gradle/gradle.properties`):

   ```bash
   export RELEASE_KEYSTORE_FILE=$HOME/.android/droidconnect-release.jks
   export RELEASE_KEYSTORE_PASSWORD=...
   export RELEASE_KEY_ALIAS=droidconnect
   export RELEASE_KEY_PASSWORD=...
   ```

3. Build the signed app bundle:

   ```bash
   ./gradlew bundleRelease
   ```

   The AAB lands at `app/build/outputs/bundle/release/app-release.aab` and can
   be uploaded to the Play Console.

## License

GPLv3 - See [LICENSE](LICENSE)
