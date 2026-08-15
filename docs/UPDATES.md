# Manual update protocol

暖刻 does not register periodic work, alarms, or background jobs for updates.

1. The user taps “检查更新”.
2. The app requests the latest public release metadata from `api.github.com`.
3. If a newer version exists, the app shows version and release notes.
4. Only after the user taps “下载更新” does it download the APK and its `.sha256` asset.
5. The app verifies the exact SHA-256 digest and checks that the APK signing certificate matches the currently installed app.
6. Android's package installer is opened; installation requires visible user confirmation.

If any integrity check fails, the file is deleted and installation is not offered. A release must never replace assets after publishing; use immutable GitHub Releases when available.

## Release secrets

The public repository contains no signing key. Maintainers configure `NUANKE_KEYSTORE_BASE64`, `NUANKE_KEYSTORE_PASSWORD`, `NUANKE_KEY_ALIAS`, and `NUANKE_KEY_PASSWORD` as GitHub Actions secrets. A `v*` tag runs tests and lint, builds the signed APK, generates its `.sha256`, and publishes both assets together.
